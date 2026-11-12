#!/usr/bin/env python3
"""FastDrop command-line client for Windows, Linux and macOS (Python 3.8+, stdlib only).

Sends and receives files, folders and text with FastDrop phones, TVs and Macs over the
local network (protocol 1 + protocol 2, see docs/PROTOCOL.md).

  fastdrop.py scan
  fastdrop.py hello <ip>
  fastdrop.py send <ip|name> <file|folder>... [--streams N] [--crc] [--name NAME] [--port P]
  fastdrop.py send <ip|name> --text "hello"        (or --clipboard)
  fastdrop.py recv [--dir DIR] [--accept ask|trusted|everyone] [--crc] [--port P]
  fastdrop.py trust list | trust remove <id>
  fastdrop.py selftest
  fastdrop.py make <path> <sizeMB> | sha <file>...
"""
import argparse
import hashlib
import hmac
import json
import os
import queue
import re
import select
import socket
import struct
import subprocess
import sys
import threading
import time
import uuid
import zlib

PORT, DISC_PORT, MCAST = 53530, 53531, "224.0.0.168"
M_CTRL, M_DATA, M_HELLO = 0x46444331, 0x46444431, 0x46444831
CHUNK, SOCK_BUF, MAX_JSON = 8 << 20, 4 << 20, 32 << 20
CTRL_TIMEOUT = 15.0
MAX_TEXT = 100000
DAY_MS = 24 * 3600 * 1000
RECORD_TTL_MS = 7 * DAY_MS
REPLAY_MAX = 2000
CAPS = ["text", "resume", "trust", "crc"]
O_BINARY = getattr(os, "O_BINARY", 0)
stop = threading.Event()


# ------------------------------------------------------------------ small helpers
def fmt_bytes(n):
    v = float(n)
    for u in ("B", "KB", "MB", "GB", "TB"):
        if v < 1024 or u == "TB":
            return "%d B" % v if u == "B" else "%.1f %s" % (v, u)
        v /= 1024


def mbs(b, s):
    return b / (1 << 20) / s if s > 0 else 0.0


def now_ms():
    return int(time.time() * 1000)


def read_exact(sock, n):
    buf = bytearray(n)
    mv, got = memoryview(buf), 0
    while got < n:
        r = sock.recv_into(mv[got:], n - got)
        if r == 0:
            raise EOFError("connection closed")
        got += r
    return bytes(buf)


def read_int(sock):
    return struct.unpack(">i", read_exact(sock, 4))[0]


def read_json(sock):
    n = read_int(sock)
    if n < 0 or n > MAX_JSON:
        raise IOError("bad message length %d" % n)
    return json.loads(read_exact(sock, n).decode("utf-8"))


def send_json(sock, obj):
    d = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    sock.sendall(struct.pack(">i", len(d)) + d)


# ------------------------------------------------------------------ protocol-2 pure functions
def confirmation_code(session_id):
    h = hashlib.sha256(session_id.encode("utf-8")).digest()
    return "%04d" % (int.from_bytes(h[:4], "big") % 10000)


def files_digest(files, text=None):
    """files: iterable of (name, size). Returns lowercase hex SHA-256."""
    s = "".join("%s\n%d\n" % (n, sz) for n, sz in files)
    if text is not None:
        s += "T\n" + text
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def canonical_string(sid, dev, ts, digest):
    return "fastdrop-auth-v1\n%s\n%s\n%s\n%s" % (sid, dev, ts, digest)


def auth_hmac(key, canonical):
    return hmac.new(key, canonical.encode("utf-8"), hashlib.sha256).hexdigest()


def resume_key(dev, name, size, mtime=None):
    s = "%s\n%s\n%d\n%s" % (dev, name, size, "" if mtime is None else mtime)
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def _mtime(v):
    """A usable mtime is a positive integer; anything else means unknown (no resume for that file)."""
    return v if isinstance(v, int) and not isinstance(v, bool) and v > 0 else None


def crc32(data, value=0):
    return zlib.crc32(data, value) & 0xFFFFFFFF


def merge_ranges(ranges):
    """Merge half-open [start, end) intervals (overlapping or touching) into sorted form."""
    out = []
    for s, e in sorted((int(a), int(b)) for a, b in ranges if b > a):
        if out and s <= out[-1][1]:
            out[-1][1] = max(out[-1][1], e)
        else:
            out.append([s, e])
    return out


def chunks_to_ranges(chunk_set):
    return merge_ranges([[i, i + 1] for i in chunk_set])


def chunk_len(size, ci):
    return max(0, min(CHUNK, size - ci * CHUNK))


# ------------------------------------------------------------------ path safety
_RESERVED = {"CON", "PRN", "AUX", "NUL"} | {"COM%d" % i for i in range(1, 10)} | {"LPT%d" % i for i in range(1, 10)}


def sanitize_component(s):
    s = re.sub(r'[\x00-\x1f\x7f<>:"|?*]', "_", s)
    s = s.rstrip(" .")
    if not s:
        return "_"
    if s.split(".")[0].upper() in _RESERVED:
        s = "_" + s
    if len(s) > 180:
        root, ext = os.path.splitext(s)
        s = root[: 180 - len(ext)] + ext
    return s


def safe_components(name):
    """Offered name -> list of safe path components (no '..', no absolute/drive parts)."""
    parts = [p for p in str(name).replace("\\", "/").split("/") if p.strip() not in ("", ".", "..")]
    parts = [sanitize_component(p) for p in parts]
    return parts or ["file"]


def inside(base, path):
    b = os.path.realpath(base)
    p = os.path.realpath(path)
    try:
        return os.path.commonpath([b, p]) == b
    except ValueError:
        return False


# ------------------------------------------------------------------ state dir
def default_state_dir():
    if os.name == "nt":
        return os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "FastDrop")
    return os.path.join(os.path.expanduser("~"), ".fastdrop")


class State:
    """Persistent identity, trusted senders, keys held for receivers and resume records."""

    def __init__(self, path=None, name=None):
        self.dir = os.path.abspath(path or default_state_dir())
        os.makedirs(self.dir, mode=0o700, exist_ok=True)
        if os.name != "nt":
            try: os.chmod(self.dir, 0o700)
            except OSError: pass
        self.lock = threading.RLock()
        ident = self._load("identity.json", {})
        changed = False
        if not ident.get("id"):
            ident["id"] = str(uuid.uuid4()); changed = True
        if not ident.get("name"):
            ident["name"] = (socket.gethostname().split(".")[0] or "PC")[:40]; changed = True
        if changed:
            self._save("identity.json", ident)
        self.id = ident["id"]
        self.name = name or ident["name"]
        self.trusted = self._load("trusted.json", {})   # sender dev -> {name,key(hex),added}
        self.keys = self._load("keys.json", {})         # receiver dev -> {name,key(hex)}
        self.records = self._load("resume.json", {})    # resumeKey -> record

    def _load(self, fn, default):
        try:
            p = os.path.join(self.dir, fn)
            if os.name != "nt":
                try: os.chmod(p, 0o600)     # best effort: tighten files made by older versions
                except OSError: pass
            with open(p, "r", encoding="utf-8") as f:
                v = json.load(f)
            return v if isinstance(v, type(default)) else default
        except (OSError, ValueError):
            return default

    def _save(self, fn, obj):
        p = os.path.join(self.dir, fn)
        tmp = p + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | O_BINARY, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(obj, f, indent=1)
        os.replace(tmp, p)

    def save_trusted(self):
        with self.lock:
            self._save("trusted.json", self.trusted)

    def save_keys(self):
        with self.lock:
            self._save("keys.json", self.keys)

    def save_records(self):
        with self.lock:
            self._save("resume.json", self.records)

    def seen_session(self, sid):
        """Persistent replay cache. True if this authenticated session id was already seen; else records it."""
        with self.lock:
            t = now_ms()
            d = self._load("replay.json", {})
            d = {k: v for k, v in d.items() if isinstance(v, int) and 0 <= t - v <= DAY_MS}
            if sid in d:
                return True
            d[sid] = t
            if len(d) > REPLAY_MAX:
                d = dict(sorted(d.items(), key=lambda kv: kv[1])[-REPLAY_MAX:])
            try: self._save("replay.json", d)
            except OSError: pass
            return False

    def info(self, port=PORT, receivable=True):
        return {"app": "fastdrop", "v": 2, "caps": CAPS, "id": self.id, "name": self.name,
                "type": "laptop", "port": port, "receivable": receivable}


# ------------------------------------------------------------------ network interfaces + discovery
def _mask_to_int(m):
    m = m.strip()
    if m.lower().startswith("0x"):
        return int(m, 16)
    if "." in m:
        return struct.unpack(">I", socket.inet_aton(m))[0]
    return (0xFFFFFFFF << (32 - int(m))) & 0xFFFFFFFF


def local_addrs():
    """Returns [(ip, broadcast)] for the machine's IPv4 LAN addresses, with fallbacks per OS."""
    found = {}

    def add(ip, mask=0xFFFFFF00):
        if ip.startswith("127.") or ip.startswith("169.254.") or ip == "0.0.0.0":
            return
        ipn = struct.unpack(">I", socket.inet_aton(ip))[0]
        found[ip] = socket.inet_ntoa(struct.pack(">I", (ipn | (~mask & 0xFFFFFFFF)) & 0xFFFFFFFF))

    def run(cmd):
        try:
            return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  universal_newlines=True, timeout=5).stdout
        except Exception:
            return ""

    if os.name != "nt":
        out = run(["ip", "-4", "-o", "addr"])
        for m in re.finditer(r"inet (\d+\.\d+\.\d+\.\d+)/(\d+)", out):
            try: add(m.group(1), _mask_to_int(m.group(2)))
            except Exception: pass
        if not found:
            out = run(["ifconfig"])
            for m in re.finditer(r"inet (?:addr:)?(\d+\.\d+\.\d+\.\d+)\s+(?:netmask |Mask:)(0x[0-9a-fA-F]+|\d+\.\d+\.\d+\.\d+)", out):
                try: add(m.group(1), _mask_to_int(m.group(2)))
                except Exception: pass
    # portable fallbacks (assume /24)
    try:
        for r in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            if r[4][0] not in found:
                add(r[4][0])
    except OSError:
        pass
    try:
        t = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        t.connect(("10.255.255.255", 1))   # UDP connect sends nothing; just picks the route
        ip = t.getsockname()[0]
        t.close()
        if ip not in found:
            add(ip)
    except OSError:
        pass
    return sorted(found.items())


class Discovery:
    """UDP multicast + broadcast discovery (same packet as Discovery.kt / Discovery.swift)."""

    def __init__(self, info_fn, my_id, disc_port=DISC_PORT, announce=True, on_peer=None, on_bye=None):
        self.info_fn, self.my_id, self.port = info_fn, my_id, disc_port
        self.announce_on, self.on_peer, self.on_bye = announce, on_peer, on_bye
        self.rs = None
        self.tx = None
        self.ev = threading.Event()
        self.threads = []

    def _packet(self, kind):
        d = dict(self.info_fn()); d["kind"] = kind
        return json.dumps(d).encode("utf-8")

    def start(self):
        rs = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rs.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if hasattr(socket, "SO_REUSEPORT"):
            try: rs.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            except OSError: pass
        rs.bind(("", self.port))
        rs.settimeout(0.5)
        self.rs = rs
        self.tx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.tx.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.tx.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
        self._join()
        for fn in ([self._rx_loop, self._tx_loop] if self.announce_on else [self._rx_loop]):
            t = threading.Thread(target=fn, daemon=True); t.start(); self.threads.append(t)

    def _join(self):
        for ip, _ in local_addrs():
            try:
                self.rs.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                                   socket.inet_aton(MCAST) + socket.inet_aton(ip))
            except OSError:
                pass

    def _announce(self, kind):
        data = self._packet(kind)
        addrs = local_addrs()
        bcs = {"255.255.255.255"} | {b for _, b in addrs}
        for ip, _ in (addrs or [(None, None)]):
            if ip:
                try: self.tx.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
                except OSError: pass
            for dst in [MCAST] + sorted(bcs):
                try: self.tx.sendto(data, (dst, self.port))
                except OSError: pass

    def _tx_loop(self):
        n = 0
        while not self.ev.is_set() and not stop.is_set():
            if n % 2 == 0:
                self._join()
            self._announce("announce")
            n += 1
            self.ev.wait(2.5)

    def _rx_loop(self):
        while not self.ev.is_set() and not stop.is_set():
            try:
                data, addr = self.rs.recvfrom(65535)
                d = json.loads(data.decode("utf-8"))
            except socket.timeout:
                continue
            except OSError:
                break
            except ValueError:
                continue
            if not isinstance(d, dict) or d.get("app") != "fastdrop" or not d.get("id") or d.get("id") == self.my_id:
                continue
            if d.get("kind") == "bye":
                if self.on_bye: self.on_bye(d["id"])
                continue
            if d.get("receivable") is False:
                continue
            if self.on_peer: self.on_peer(d, addr[0])
            if d.get("kind") == "announce" and self.announce_on:
                try: self.tx.sendto(self._packet("reply"), (addr[0], self.port))
                except OSError: pass

    def close(self):
        if self.announce_on and self.tx:
            try: self._announce("bye")
            except Exception: pass
        self.ev.set()
        for s in (self.rs, self.tx):
            try: s.close()
            except Exception: pass


def hello_probe(ip, port, timeout=3.0):
    """TCP HELLO. Returns the device-info dict or None."""
    try:
        s = socket.create_connection((ip, port), timeout=timeout)
    except OSError:
        return None
    try:
        s.settimeout(timeout)
        s.sendall(struct.pack(">i", M_HELLO))
        d = read_json(s)
        return d if isinstance(d, dict) else None
    except Exception:
        return None
    finally:
        s.close()


def sweep(port, my_id, timeout=0.5, found=None):
    """HELLO every host of every local /24 (64 at a time). Returns {id: (info, ip)}."""
    from concurrent.futures import ThreadPoolExecutor
    addrs = local_addrs()
    mine = {ip for ip, _ in addrs}
    nets = []
    for ip, _ in addrs:
        n = ip.rsplit(".", 1)[0]
        if n not in nets:
            nets.append(n)
    targets = ["%s.%d" % (n, i) for n in nets for i in range(1, 255)]
    targets = [t for t in targets if t not in mine]
    res = {}

    def one(ip):
        d = hello_probe(ip, port, timeout)
        if d and d.get("app") == "fastdrop" and d.get("id") != my_id and d.get("id"):
            res[d["id"]] = (d, ip)
            if found: found(d, ip)

    with ThreadPoolExecutor(max_workers=64) as ex:
        list(ex.map(one, targets))
    return res


def scan_devices(state, port=PORT, disc_port=DISC_PORT, seconds=4.0, quiet=False):
    """Passive UDP listen plus TCP /24 sweep. Returns [(info, ip)] sorted by name."""
    peers = {}
    lock = threading.Lock()

    def add(d, ip):
        with lock:
            if d["id"] not in peers:
                peers[d["id"]] = (d, ip)

    disc = None
    try:
        disc = Discovery(lambda: {}, state.id, disc_port, announce=False, on_peer=add)
        disc.start()
    except OSError as e:
        if not quiet:
            print("(UDP discovery unavailable: %s; using network sweep only)" % e, file=sys.stderr)
    t0 = time.time()
    th = threading.Thread(target=lambda: [add(d, ip) for d, ip in sweep(port, state.id, found=add).values()], daemon=True)
    th.start()
    while time.time() - t0 < seconds and not stop.is_set():
        time.sleep(0.1)
    th.join(max(0.0, seconds + 3 - (time.time() - t0)))
    if disc:
        disc.close()
    return sorted(peers.values(), key=lambda p: str(p[0].get("name", "")).lower())


def resolve_target(target, state, port, disc_port=DISC_PORT):
    """IP/hostname used as-is; anything else is matched against discovered device names."""
    if re.match(r"^\d{1,3}(\.\d{1,3}){3}$", target) or ("." in target and not target.startswith(".")):
        return target
    devs = scan_devices(state, port, disc_port, seconds=4.0, quiet=True)
    low = target.lower()
    exact = [d for d in devs if str(d[0].get("name", "")).lower() == low]
    part = exact or [d for d in devs if low in str(d[0].get("name", "")).lower()]
    if not part:
        sys.exit("no device named '%s' found (try `scan`, or pass its IP)" % target)
    if len(part) > 1:
        sys.exit("'%s' is ambiguous: %s" % (target, ", ".join("%s (%s)" % (d["name"], ip) for d, ip in part)))
    print("Found '%s' at %s" % (part[0][0].get("name"), part[0][1]))
    return part[0][1]


# ------------------------------------------------------------------ clipboard
def read_clipboard():
    cmds = []
    if sys.platform == "darwin":
        cmds = [["pbpaste"]]
    elif os.name == "nt":
        cmds = [["powershell", "-NoProfile", "-Command", "Get-Clipboard -Raw"]]
    else:
        cmds = [["wl-paste", "--no-newline"], ["xclip", "-selection", "clipboard", "-o"],
                ["xsel", "--clipboard", "--output"], ["pbpaste"]]
    for c in cmds:
        try:
            r = subprocess.run(c, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
            if r.returncode == 0:
                return r.stdout.decode("utf-8", "replace")
        except Exception:
            continue
    sys.exit("could not read the clipboard (install xclip, xsel or wl-clipboard, or use --text)")


# ------------------------------------------------------------------ sender
def collect_files(paths):
    items = []
    for p in paths:
        if os.path.isdir(p):
            base = os.path.basename(os.path.abspath(p)) or "folder"
            for root, dirs, files in os.walk(p):
                dirs.sort()
                for fn in sorted(files):
                    full = os.path.join(root, fn)
                    if not os.path.isfile(full):
                        continue
                    rel = os.path.relpath(full, p).replace(os.sep, "/")
                    items.append(_item(full, base + "/" + rel))
        elif os.path.isfile(p):
            items.append(_item(p, os.path.basename(p)))
        else:
            sys.exit("not a file or folder: " + p)
    return items


def _item(path, name):
    st = os.stat(path)
    it = {"path": path, "name": name, "size": st.st_size}
    if st.st_mtime > 0:
        it["mtime"] = int(st.st_mtime * 1000)    # omitted when unknown: the receiver then never resumes this file
    return it


def build_offer(sid, name, dev, files, text=None, crc=False, key=None, ts=None, v2=True):
    """files: list of dicts (name,size,mtime). Returns the offer dict (auth added if key given)."""
    o = {"id": sid, "name": name, "type": "laptop"}
    ts = ts if ts is not None else now_ms()
    if v2:
        o.update({"v": 2, "dev": dev, "ts": ts, "chunk": CHUNK})
    o["files"] = [({"name": f["name"], "size": f["size"], "mtime": f["mtime"]} if v2 and "mtime" in f
                   else {"name": f["name"], "size": f["size"]}) for f in files]
    if text is not None:
        o["text"] = text
    if crc:
        o["crc"] = True
    if v2 and key:
        dig = files_digest([(f["name"], f["size"]) for f in files], text)
        o["auth"] = auth_hmac(key, canonical_string(sid, dev, ts, dig))
    return o


def do_send(a, state, ip, items, text):
    port = a.port
    total = sum(i["size"] for i in items)
    hello = hello_probe(ip, port, 4.0)
    peer_v = int(hello.get("v", 1)) if hello else 1
    peer_name = (hello or {}).get("name", ip)
    peer_id = (hello or {}).get("id")
    v2 = peer_v >= 2 or (hello is None and text is not None)
    if text is not None and hello and peer_v < 2:
        sys.exit("%s needs FastDrop 2.1 or newer to receive text" % peer_name)
    if a.crc and not v2:
        print("note: %s does not support CRC; sending without it" % peer_name)
    key = None
    if v2 and peer_id and peer_id in state.keys:
        try: key = bytes.fromhex(state.keys[peer_id]["key"])
        except (ValueError, KeyError): key = None
    sid = str(uuid.uuid4())
    offer = build_offer(sid, state.name, state.id, items, text, crc=bool(a.crc and v2), key=key, v2=v2)
    ctrl = socket.socket()
    ctrl.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    ctrl.settimeout(CTRL_TIMEOUT)
    try:
        ctrl.connect((ip, port))
    except OSError as e:
        sys.exit("cannot connect to %s:%d (%s)" % (ip, port, e))
    ctrl.sendall(struct.pack(">i", M_CTRL))
    send_json(ctrl, offer)
    what = ("text (%d chars)" % len(text)) if text is not None else "%d file(s), %s" % (len(items), fmt_bytes(total))
    print("Offered %s to %s (%s)%s; waiting for accept (up to 120s)..." % (
        what, peer_name, ip, "" if key else ("  Code %s" % confirmation_code(sid) if v2 else "")))
    ctrl.settimeout(1.0)
    deadline, resp = time.time() + 120, None
    while resp is None:
        try:
            resp = read_json(ctrl)
        except socket.timeout:
            if time.time() > deadline:
                sys.exit("timed out waiting for accept")
        except Exception as e:
            sys.exit("no reply from receiver: %r" % e)
    if not resp.get("accept"):
        reason = resp.get("reason") or "(no reason)"
        if reason == "invalid" and text is not None:
            sys.exit("%s needs FastDrop 2.1 or newer to receive text" % peer_name)
        sys.exit("DECLINED: " + reason)
    print("ACCEPTED")
    rdev = resp.get("dev") or peer_id
    if resp.get("trust") and rdev:
        state.keys[rdev] = {"name": peer_name, "key": resp["trust"], "added": now_ms()}
        state.save_keys()
        print("Paired: %s will now accept your sends without asking." % peer_name)
    if text is not None:
        fin = _wait_final(ctrl)
        print("Text delivered." if fin else "Text sent.")
        return 0
    use_crc = bool(resp.get("crc")) and offer.get("crc")
    if offer.get("crc") and not use_crc:
        print("note: receiver did not agree to CRC; sending without it")

    # which chunks to send
    have = {}
    if v2 and isinstance(resp.get("have"), dict):
        for k, rs in resp["have"].items():
            try: have[int(k)] = merge_ranges(rs)
            except (ValueError, TypeError): pass

    def present(fi, ci):
        return any(s <= ci < e for s, e in have.get(fi, []))

    q = queue.Queue()
    skipped = 0
    for fi, it in enumerate(items):
        ci = 0
        while ci * CHUNK < it["size"]:
            if present(fi, ci):
                skipped += chunk_len(it["size"], ci)
            else:
                q.put((fi, ci * CHUNK, chunk_len(it["size"], ci)))
            ci += 1
    to_send = total - skipped
    if skipped:
        print("Resuming at %.0f%% (%s already on receiver)" % (100.0 * skipped / total, fmt_bytes(skipped)))
    sent = [0]
    lock = threading.Lock()
    errors = []
    interrupted = threading.Event()
    limit = int(a.stop_after_mb * (1 << 20)) if a.stop_after_mb else 0
    idb = sid.encode()
    nstreams = max(1, min(a.streams, q.qsize())) if q.qsize() else 0

    def worker():
        handles = {}
        try:
            s = socket.socket()
            s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, SOCK_BUF)
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            s.connect((ip, port))
            s.settimeout(30)
            s.sendall(struct.pack(">i", M_DATA) + struct.pack(">i", len(idb)) + idb)
            while not stop.is_set() and not errors and not interrupted.is_set():
                if limit and sent[0] >= limit:
                    interrupted.set()
                    break
                try:
                    fi, off, ln = q.get_nowait()
                except queue.Empty:
                    s.sendall(struct.pack(">iqi", -1, 0, 0))
                    break
                s.sendall(struct.pack(">iqi", fi, off, ln))
                f = handles.get(fi)
                if f is None:
                    f = handles[fi] = open(items[fi]["path"], "rb")
                pos, rem, c = off, ln, 0
                if use_crc:
                    f.seek(off)
                    while rem > 0:
                        b = f.read(min(rem, 1 << 20))
                        if not b:
                            raise IOError("file shrank while sending: " + items[fi]["path"])
                        c = crc32(b, c)
                        s.sendall(b)
                        rem -= len(b)
                        with lock: sent[0] += len(b)
                    s.sendall(struct.pack(">I", c))
                else:
                    while rem > 0:
                        n = min(rem, 2 << 20)
                        s.sendfile(f, pos, n)
                        pos += n; rem -= n
                        with lock: sent[0] += n
            s.close()
        except Exception as e:
            errors.append(repr(e))
        finally:
            for f in handles.values():
                f.close()

    t0 = time.time()
    ths = [threading.Thread(target=worker, daemon=True) for _ in range(nstreams)]
    for t in ths:
        t.start()
    tty = sys.stdout.isatty()
    last_b, last_t, last_print = 0, t0, 0.0
    try:
        while any(t.is_alive() for t in ths):
            time.sleep(0.25)
            now = time.time()
            if now - last_print >= (0.5 if tty else 3.0):
                with lock: b = sent[0]
                pct = 100.0 * (skipped + b) / total if total else 100
                line = "  %5.1f%%  %7.1f MB/s  (%s/%s)" % (pct, mbs(b - last_b, now - last_t), fmt_bytes(skipped + b), fmt_bytes(total))
                print("\r" + line + "   " if tty else line, end="" if tty else "\n", flush=True)
                last_b, last_t, last_print = b, now, now
    except KeyboardInterrupt:
        stop.set(); print("\ninterrupted"); ctrl.close(); sys.exit(130)
    for t in ths:
        t.join()
    el = time.time() - t0
    if tty: print()
    if errors:
        sys.exit("send errors: " + "; ".join(errors))
    if interrupted.is_set():
        print("sent_bytes=%d skipped_bytes=%d" % (sent[0], skipped))
        print("Simulated interruption after %s (--stop-after-mb)" % fmt_bytes(sent[0]))
        ctrl.close()
        sys.exit(3)
    print("Sent %s in %.2fs, avg %.1f MB/s (%d streams)" % (fmt_bytes(to_send), el, mbs(to_send, el), nstreams))
    print("sent_bytes=%d skipped_bytes=%d" % (sent[0], skipped))
    if not _wait_final(ctrl):
        sys.exit("receiver did not confirm the transfer")
    print("Done. Total incl. receiver finalize: %.2fs" % (time.time() - t0))
    ctrl.close()
    return 0


def _wait_final(ctrl):
    ctrl.settimeout(1.0)
    until = time.time() + 120
    while True:
        try:
            r = read_json(ctrl)
            return bool(r.get("ok"))
        except socket.timeout:
            if time.time() > until:
                return False
        except Exception:
            return False


def cmd_send(a):
    state = State(a.state_dir, a.name)
    text = None
    if a.clipboard:
        text = read_clipboard()
    elif a.text is not None:
        text = a.text
    if text is not None:
        if a.paths:
            sys.exit("--text/--clipboard cannot be combined with files")
        if len(text) > MAX_TEXT:
            sys.exit("text is longer than %d characters" % MAX_TEXT)
        items = []
    else:
        if not a.paths:
            sys.exit("nothing to send: give files/folders or --text/--clipboard")
        items = collect_files(a.paths)
        if not items:
            sys.exit("nothing to send (empty folder)")
    ip = resolve_target(a.target, state, a.port, a.disc_port)
    do_send(a, state, ip, items, text)


# ------------------------------------------------------------------ receiver
class Stdin:
    q = queue.Queue()
    started = False
    eof = False
    lock = threading.Lock()

    @classmethod
    def _run(cls):
        while True:
            try:
                line = sys.stdin.readline()
            except Exception:
                line = ""
            if line == "":
                cls.eof = True
                cls.q.put("")
                return
            cls.q.put(line)

    @classmethod
    def answer(cls, timeout):
        with cls.lock:
            if not cls.started:
                cls.started = True
                threading.Thread(target=cls._run, daemon=True).start()
        if cls.eof and cls.q.empty():
            return ""
        try:
            return cls.q.get(timeout=timeout).strip().lower()
        except queue.Empty:
            return ""


class Slot:
    def __init__(self, raw_name, size, mtime, key, comps, base, v2, idx=0, sid=""):
        self.raw, self.size, self.mtime, self.key = raw_name, size, mtime, key
        self.comps = comps
        self.rel = "/".join(comps)
        d = os.path.join(base, *comps[:-1]) if len(comps) > 1 else base
        self.dir = d
        # unique per file slot: two offered files named x.bin never share a partial
        self.tag = key[:12] if key else (sid[:8] or "0")
        self.partial = os.path.join(d, self.partial_name(self.tag, idx))
        self.nchunks = (size + CHUNK - 1) // CHUNK
        self.done = set()
        self.fd = None
        self.users = 0
        self.complete = False
        self.lock = threading.Lock()      # serialises seek+write where pwrite is missing
        self.fdlock = threading.Lock()    # guards fd / users / complete
        self.v2 = v2
        self.final = None

    def partial_name(self, tag, idx):
        return ".%s.%s.%d.fdpart" % (self.comps[-1][:100], tag, idx)

    def partial_belongs(self, p):
        """True if p is a partial this slot's resume key could have produced (same dir, name, key tag)."""
        if not self.key or not isinstance(p, str):
            return False
        b = os.path.basename(p)
        return (os.path.normpath(os.path.dirname(p)) == os.path.normpath(self.dir)
                and b.startswith("." + self.comps[-1][:100] + "." + self.key[:12] + ".") and b.endswith(".fdpart"))

    def prepare(self, reuse=False):
        """Create/size the partial file on disk without holding an fd (fds open lazily on first write)."""
        os.makedirs(self.dir, exist_ok=True)
        if not inside(os.path.dirname(self.partial), self.partial) or not inside(self.dir, self.partial):
            raise IOError("unsafe path")
        flags = os.O_RDWR | os.O_CREAT | O_BINARY
        if not reuse:
            flags |= os.O_TRUNC
        fd = os.open(self.partial, flags, 0o644)
        try:
            if os.fstat(fd).st_size != self.size:
                os.ftruncate(fd, self.size)
        finally:
            os.close(fd)

    def write(self, pos, mv):
        with self.fdlock:
            if self.fd is None:
                self.fd = os.open(self.partial, os.O_RDWR | O_BINARY)
            self.users += 1
            fd = self.fd
        try:
            if hasattr(os, "pwrite"):
                w = 0
                while w < len(mv):
                    w += os.pwrite(fd, mv[w:], pos + w)
            else:
                with self.lock:
                    os.lseek(fd, pos, os.SEEK_SET)
                    w = 0
                    while w < len(mv):
                        w += os.write(fd, mv[w:])
        finally:
            with self.fdlock:
                self.users -= 1
                if self.complete and self.users == 0:
                    self._close_locked()

    def mark_complete(self):
        with self.fdlock:
            self.complete = True
            if self.users == 0:
                self._close_locked()

    def _close_locked(self):
        if self.fd is not None:
            try: os.close(self.fd)
            except OSError: pass
            self.fd = None

    def close(self):
        with self.fdlock:
            self._close_locked()


class Session:
    def __init__(self, sid, dev, v2, slots, crc):
        self.id, self.dev, self.v2, self.slots, self.crc = sid, dev, v2, slots, crc
        self.total = sum(s.size for s in slots)
        self.verified = 0       # bytes in verified frames (+ pre-existing resumed chunks)
        self.progress = 0       # live bytes incl. unverified (for display)
        self.failed = None
        self.done = threading.Event()
        self.lock = threading.Lock()
        self.conns = 0
        self.t0 = time.time()
        self.last_activity = time.time()
        if self.total == 0:
            self.done.set()


class Receiver:
    def __init__(self, a, state):
        self.a, self.state = a, state
        self.dir = os.path.abspath(a.dir)
        self.port = a.port
        self.mode = a.accept
        self.use_crc = not a.no_crc
        self.non_interactive = bool(getattr(a, "non_interactive", False))
        self.sessions = {}
        self.slock = threading.Lock()
        self.busy = False
        os.makedirs(self.dir, exist_ok=True)
        self.prune()

    def info(self):
        return self.state.info(self.port, True)

    # --- resume records
    def prune(self):
        st = self.state
        with st.lock:
            dead = [k for k, r in st.records.items()
                    if now_ms() - int(r.get("updated", 0)) > RECORD_TTL_MS or not os.path.exists(r.get("partial", ""))]
            for k in dead:
                p = st.records[k].get("partial")
                if p and now_ms() - int(st.records[k].get("updated", 0)) > RECORD_TTL_MS:
                    try: os.unlink(p)
                    except OSError: pass
                del st.records[k]
            if dead:
                st.save_records()

    def save_records(self, s, final=False):
        st = self.state
        with s.lock:    # data threads add to sl.done under s.lock: snapshot before iterating
            snaps = [set(sl.done) for sl in s.slots]
        with st.lock:
            for sl, done in zip(s.slots, snaps):
                if sl.size == 0 or not s.v2 or not sl.key:
                    continue
                if sl.final or not done or len(done) >= sl.nchunks:
                    if sl.key in st.records and (sl.final or len(done) >= sl.nchunks):
                        del st.records[sl.key]
                    continue
                st.records[sl.key] = {"key": sl.key, "partial": sl.partial, "name": sl.rel, "size": sl.size,
                                      "chunks": chunks_to_ranges(done), "updated": now_ms()}
            st.save_records()

    # --- network
    def serve(self):
        srv = socket.socket()
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, SOCK_BUF)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            srv.bind(("0.0.0.0", self.port))
        except OSError as e:
            sys.exit("cannot listen on TCP %d: %s (is another FastDrop running?)" % (self.port, e))
        srv.listen(128)
        srv.settimeout(0.5)
        print("Listening on TCP %d as '%s' (id %s); saving to %s; accept=%s%s" % (
            self.port, self.state.name, self.state.id, self.dir, self.mode, " crc=on" if self.use_crc else ""), flush=True)
        while not stop.is_set():
            try:
                c, addr = srv.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            threading.Thread(target=self.handle, args=(c, addr), daemon=True).start()
        srv.close()

    def handle(self, c, addr):
        try:
            c.settimeout(CTRL_TIMEOUT)
            c.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            m = read_int(c)
            if m == M_HELLO:
                send_json(c, self.info()); c.close()
            elif m == M_CTRL:
                self.offer(c, addr)
            elif m == M_DATA:
                self.data(c)
            else:
                c.close()
        except Exception as e:
            if not stop.is_set():
                print("[conn %s] %r" % (addr[0], e), flush=True)
            try: c.close()
            except OSError: pass

    def check_trust(self, o, dev, names, text):
        with self.state.lock:   # `trust remove` runs in another process, so re-read the file
            self.state.trusted = self.state._load("trusted.json", {})
        rec = self.state.trusted.get(dev) if dev else None
        auth, ts = o.get("auth"), o.get("ts")
        if not rec or not isinstance(auth, str) or not isinstance(ts, int) or isinstance(ts, bool):
            return False
        if abs(now_ms() - ts) > DAY_MS:
            return False
        try: key = bytes.fromhex(rec["key"])
        except (ValueError, KeyError): return False
        exp = auth_hmac(key, canonical_string(o["id"], dev, ts, files_digest(names, text)))
        if not hmac.compare_digest(exp, auth.lower()):
            return False
        return not self.state.seen_session(str(o["id"]))

    def prompt(self, sender, ip, names, total, code, text, trusted, allow_always=True):
        print("\nOffer from '%s' (%s)%s" % (sender, ip, "  [trusted device]" if trusted else ""))
        if text is not None:
            shown = text if len(text) <= 300 else text[:300] + "..."
            print("   Text (%d chars): %s" % (len(text), shown))
        else:
            print("   %d file(s), %s" % (len(names), fmt_bytes(total)))
            for n, sz in names[:10]:
                print("   %s  %s" % (n, fmt_bytes(sz)))
            if len(names) > 10:
                print("   ... and %d more" % (len(names) - 10))
        print("   Confirmation code: %s  (should match the sender's screen)" % code)
        opts = "[y/N/a=accept and always trust]" if allow_always else "[y/N]"
        if not allow_always:
            print("   (this device id is already trusted but the offer was not authenticated; remove it with"
                  " 'trust remove' first to re-pair)")
        if self.non_interactive:
            print("Accept? %s (non-interactive: declined)" % opts, flush=True)
            return "n"
        print("Accept? %s " % opts, end="", flush=True)
        ans = Stdin.answer(120)
        print(ans or "(no answer)", flush=True)
        a1 = ans[:1]
        return "y" if a1 == "a" and not allow_always else a1

    def offer(self, c, addr):
        o = read_json(c)
        sid = str(o["id"])
        v2 = int(o.get("v", 1) or 1) >= 2 and bool(o.get("dev"))
        dev = str(o.get("dev", "")) if v2 else ""
        base_reply = {"v": 2, "dev": self.state.id} if v2 else {}

        def refuse(reason):
            r = {"accept": False, "reason": reason}; r.update(base_reply)
            send_json(c, r); c.close()

        text = o.get("text") if v2 else None
        try:
            raw = [(str(f["name"]), int(f["size"]), _mtime(f.get("mtime")) if v2 else None) for f in (o.get("files") or [])]
        except (KeyError, TypeError, ValueError):
            return refuse("invalid")
        if (text is not None and (not isinstance(text, str) or len(text) > MAX_TEXT)) or any(sz < 0 for _, sz, _ in raw) \
                or (not raw and text is None):
            return refuse("invalid")
        names = [(n, sz) for n, sz, _ in raw]
        total = sum(sz for _, sz in names)
        sender = str(o.get("name", "Unknown"))[:60]
        with self.slock:
            if self.busy:
                return refuse("busy")
            self.busy = True
        s = None
        try:
            trusted = v2 and self.check_trust(o, dev, names, text)
            with self.state.lock:
                known = bool(v2 and dev in self.state.trusted)   # never replace an existing key from an offer
            code = confirmation_code(sid)
            auto = self.mode == "everyone" or (self.mode == "trusted" and trusted)
            always = False
            if auto:
                print("\nOffer from '%s' (%s): auto-accepted (%s)" % (sender, addr[0], "trusted device" if trusted else "accept=everyone"))
            else:
                ans = self.prompt(sender, addr[0], names, total, code, text, trusted, allow_always=not known)
                if ans not in ("y", "a"):
                    return refuse("declined")
                always = ans == "a" and v2 and not known
            reply = {"accept": True, "reason": ""}
            reply.update(base_reply)
            if always:
                key = os.urandom(32)
                with self.state.lock:
                    if dev in self.state.trusted:
                        always = False
                    else:
                        self.state.trusted[dev] = {"name": sender, "key": key.hex(), "added": now_ms()}
                        self.state.save_trusted()
                if always:
                    reply["trust"] = key.hex()
                    print("Trusting '%s' (%s) from now on." % (sender, dev))
            if text is not None:
                print("Text from '%s':\n%s" % (sender, text), flush=True)
                try:
                    with open(os.path.join(self.state.dir, "text_history.log"), "a", encoding="utf-8") as f:
                        f.write("[%s] %s: %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), sender, text.replace("\n", "\\n")))
                except OSError:
                    pass
            if not raw:
                send_json(c, reply)
                send_json(c, {"ok": True})
                c.close()
                return
            crc = bool(v2 and o.get("crc") and self.use_crc)
            if crc:
                reply["crc"] = True
            slots = []
            seen_keys = set()
            for i, (rn, sz, mt) in enumerate(raw):
                comps = safe_components(rn)
                # resume needs a known, positive mtime: without it two versions of a same-size file could be spliced
                key = resume_key(dev, rn, sz, mt) if v2 and o.get("chunk", CHUNK) == CHUNK and mt else None
                if key in seen_keys:
                    key = None
                if key:
                    seen_keys.add(key)
                slots.append(Slot(rn, sz, mt, key, comps, self.dir, v2, i, sid))
            s = Session(sid, dev, v2, slots, crc)
            have = {}
            used = set()
            for i, sl in enumerate(slots):
                if sl.size == 0:
                    continue
                rec = self.state.records.get(sl.key) if sl.key else None
                reuse = False
                if rec and rec.get("key") == sl.key and sl.partial_belongs(rec.get("partial")) \
                        and rec["partial"] not in used and rec.get("size") == sl.size \
                        and os.path.isfile(rec["partial"]) and os.path.getsize(rec["partial"]) == sl.size:
                    ch = set()
                    for a0, b0 in merge_ranges(rec.get("chunks", [])):
                        ch.update(range(max(0, a0), min(sl.nchunks, b0)))
                    if ch:
                        sl.partial = rec["partial"]
                        sl.done = ch
                        reuse = True
                        have[str(i)] = chunks_to_ranges(ch)
                        s.verified += sum(chunk_len(sl.size, k) for k in ch)
                        s.progress = s.verified
                if not reuse and rec and sl.partial_belongs(rec.get("partial")) and rec["partial"] != sl.partial:
                    try: os.unlink(rec["partial"])      # stale partial from an earlier offer of this file
                    except OSError: pass
                used.add(sl.partial)
                sl.prepare(reuse)
            if have:
                reply["have"] = have
                print("Resuming: %s already present" % fmt_bytes(s.verified))
            if s.verified >= s.total:
                s.done.set()
            with self.slock:
                self.sessions[sid] = s
            send_json(c, reply)
            print("Accepted, receiving...", flush=True)
            self.watch(c, s)
        except Exception as e:
            print("Transfer error: %r" % (e,), flush=True)
            if s is not None:
                s.failed = s.failed or repr(e)
                self.fail(s)
            try: c.close()
            except OSError: pass
        finally:
            with self.slock:
                self.sessions.pop(sid, None)
                self.busy = False

    def watch(self, c, s):
        c.settimeout(None)
        tty = sys.stdout.isatty()
        last_b, last_t, last_ck, last_print = s.progress, time.time(), time.time(), 0.0
        while not s.done.is_set() and not s.failed and not stop.is_set():
            s.done.wait(0.4)
            now = time.time()
            try:
                r, _, _ = select.select([c], [], [], 0)
                if r and not c.recv(1, socket.MSG_PEEK):
                    s.failed = "sender disconnected"
            except (OSError, ValueError):
                s.failed = "sender disconnected"
            if now - last_ck >= 10:
                self.save_records(s); last_ck = now
            with s.lock:
                b = s.progress
                if b != last_b: s.last_activity = now
            if now - last_print >= (0.5 if tty else 3.0) and s.total:
                line = "  %5.1f%%  %7.1f MB/s" % (100.0 * b / s.total, mbs(b - last_b, now - last_t))
                print("\r" + line + "   " if tty else line, end="" if tty else "\n", flush=True)
                last_b, last_t, last_print = b, now, now
            if now - s.last_activity > 60 and not s.done.is_set():
                s.failed = "stalled"
        el = time.time() - s.t0
        if s.done.is_set() and not s.failed:
            paths = self.finalize(s)
            send_json(c, {"ok": True})
            print("\nReceived %s in %.2fs, avg %.1f MB/s" % (fmt_bytes(s.total), el, mbs(s.total, el)))
            for p in paths:
                print("   -> " + p, flush=True)
        else:
            print("\nTransfer failed: %s" % (s.failed or "aborted"), flush=True)
            self.fail(s)
        c.close()

    def finalize(self, s):
        out = []
        for sl in s.slots:
            sl.close()
            final = os.path.join(sl.dir, sl.comps[-1])
            root, ext = os.path.splitext(final); k = 1
            while os.path.exists(final):
                final = "%s (%d)%s" % (root, k, ext); k += 1
            if sl.size == 0:
                os.makedirs(sl.dir, exist_ok=True)
                open(final, "wb").close()
                try: os.unlink(sl.partial)
                except OSError: pass
            else:
                os.replace(sl.partial, final)
            sl.final = final
            out.append(final)
        self.save_records(s, final=True)
        return out

    def fail(self, s):
        t = time.time()
        while s.conns > 0 and time.time() - t < 2:
            time.sleep(0.05)
        for sl in s.slots:
            sl.close()
            if not (s.v2 and sl.key and sl.done):
                try: os.unlink(sl.partial)
                except OSError: pass
        self.save_records(s)

    def data(self, c):
        idl = read_int(c)
        if not 1 <= idl <= 200:
            raise IOError("bad id")
        sid = read_exact(c, idl).decode("utf-8", "replace")
        with self.slock:
            s = self.sessions.get(sid)
        if s is None:
            raise IOError("unknown session")
        try: c.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, SOCK_BUF)
        except OSError: pass
        c.settimeout(30)
        with s.lock: s.conns += 1
        buf = bytearray(1 << 20); mv = memoryview(buf)
        try:
            while not s.done.is_set() and not stop.is_set() and not s.failed:
                fi, off, ln = struct.unpack(">iqi", read_exact(c, 16))
                if fi < 0:
                    break
                if fi >= len(s.slots) or off < 0 or ln < 0 or off + ln > s.slots[fi].size:
                    raise IOError("bad frame")
                sl = s.slots[fi]
                aligned = off % CHUNK == 0 and ln == chunk_len(sl.size, off // CHUNK)
                ci = off // CHUNK
                skip = aligned and ci in sl.done
                pos, rem, crc = off, ln, 0
                while rem > 0:
                    n = c.recv_into(mv, min(rem, len(buf)))
                    if n == 0:
                        raise EOFError("sender disconnected")
                    if not skip:
                        sl.write(pos, mv[:n])
                        if s.crc: crc = crc32(mv[:n], crc)
                    pos += n; rem -= n
                    with s.lock: s.progress += 0 if skip else n
                if s.crc:
                    want = struct.unpack(">I", read_exact(c, 4))[0]
                    if not skip and want != crc:
                        raise IOError("Data check failed (CRC mismatch)")
                if skip:
                    continue
                with s.lock:
                    s.verified += ln
                    if aligned:
                        sl.done.add(ci)
                    fdone = len(sl.done) >= sl.nchunks
                    if s.verified >= s.total:
                        s.done.set()
                if fdone:
                    sl.mark_complete()      # release the fd as soon as the file is complete
        except Exception as e:
            if not s.done.is_set() and not s.failed:
                s.failed = "data stream error: %r" % (e,)
        finally:
            with s.lock: s.conns -= 1
            c.close()


def cmd_recv(a):
    try:
        import signal
        signal.signal(signal.SIGTERM, lambda *_: stop.set())
    except Exception:
        pass
    state = State(a.state_dir, a.name)
    rx = Receiver(a, state)
    disc = None
    if not a.no_discovery:
        try:
            seen = set()

            def on_peer(d, ip):
                if d["id"] not in seen:
                    seen.add(d["id"])
                    print("[discovery] saw '%s' (%s) at %s" % (d.get("name"), d.get("type"), ip), flush=True)

            disc = Discovery(rx.info, state.id, a.disc_port, announce=True, on_peer=on_peer)
            disc.start()
        except OSError as e:
            print("warning: UDP discovery unavailable (%s); phones can still send to this IP directly" % e, flush=True)
    th = threading.Thread(target=rx.serve)
    th.start()
    try:
        while th.is_alive():
            th.join(0.5)
    except KeyboardInterrupt:
        print("\nshutting down...")
    stop.set()
    th.join(3)
    if disc:
        disc.close()
        time.sleep(0.3)


# ------------------------------------------------------------------ other subcommands
def cmd_hello(a):
    d = hello_probe(a.ip, a.port, 5.0)
    if d is None:
        sys.exit("no FastDrop device at %s:%d" % (a.ip, a.port))
    print(json.dumps(d, indent=2))


def cmd_scan(a):
    state = State(a.state_dir)
    print("Scanning for %d seconds..." % a.seconds)
    devs = scan_devices(state, a.port, a.disc_port, a.seconds)
    if not devs:
        print("No FastDrop devices found. (Check that they are on the same Wi-Fi and the firewall allows UDP %d / TCP %d.)" % (a.disc_port, a.port))
        return
    print("%-24s %-16s %-8s %-4s %s" % ("NAME", "ADDRESS", "TYPE", "V", "ID"))
    for d, ip in devs:
        print("%-24s %-16s %-8s %-4s %s" % (str(d.get("name"))[:24], ip, d.get("type", "?"), d.get("v", 1), d.get("id")))


def cmd_trust(a):
    state = State(a.state_dir)
    if a.action == "list":
        print("Trusted senders (they may send here without asking):")
        for k, v in sorted(state.trusted.items(), key=lambda kv: kv[1].get("added", 0)):
            print("  %s  %s  added %s" % (k, v.get("name", "?"), time.strftime("%Y-%m-%d %H:%M", time.localtime(v.get("added", 0) / 1000))))
        if not state.trusted: print("  (none)")
        print("Paired receivers (we hold a key, so sends to them skip the prompt):")
        for k, v in state.keys.items():
            print("  %s  %s" % (k, v.get("name", "?")))
        if not state.keys: print("  (none)")
    else:
        if not a.id:
            sys.exit("trust remove <id>")
        m = [k for k in state.trusted if k == a.id or k.startswith(a.id)]
        mk = [k for k in state.keys if k == a.id or k.startswith(a.id)]
        if not m and not mk:
            sys.exit("no trusted device with id " + a.id)
        for k in m: del state.trusted[k]
        for k in mk: del state.keys[k]
        state.save_trusted(); state.save_keys()
        print("Removed %d entr%s." % (len(m) + len(mk), "y" if len(m) + len(mk) == 1 else "ies"))


# Reference vectors; Kotlin and Swift implementations hard-code the same values.
V_ID = "123e4567-e89b-12d3-a456-426614174000"
V_DEV = "dev-abc"
V_TS = 1731400000000
V_FILES = [("a.txt", 5), ("dir/b.bin", 9000000)]
V_TEXT = "hello"
V_KEY = bytes(range(32))
V_MTIME = 1731300000000
EXPECTED = {
    "code": "8249",
    "filesDigest (files only)": "1fb15c941126f6d16a33ee4f26296fa6338624bd8c78148b768722f203ff0184",
    "filesDigest (files + text \"hello\")": "0eb577122832125a5218ed7d05f8a968cf2bff8d7a37da1986c62aa90bd4d837",
    "canonical (files only)": "fastdrop-auth-v1\n123e4567-e89b-12d3-a456-426614174000\ndev-abc\n1731400000000\n1fb15c941126f6d16a33ee4f26296fa6338624bd8c78148b768722f203ff0184",
    "auth (files only)": "1a8208d2d36cb0a7bba5a7d93980341d48b292e3cc4d81f639fe848ec3fc2817",
    "canonical (files + text)": "fastdrop-auth-v1\n123e4567-e89b-12d3-a456-426614174000\ndev-abc\n1731400000000\n0eb577122832125a5218ed7d05f8a968cf2bff8d7a37da1986c62aa90bd4d837",
    "auth (files + text)": "c1bad27d493dbc2b2b181256033d1b2a9ec2aa272d8cea467a4403f6b4586047",
    "resumeKey dir/b.bin 9000000 no mtime": "5d7b5f5b7cbddca6cfa3bdf974764861210c4d19e9f376dc7436615f30f91459",
    "resumeKey dir/b.bin 9000000 mtime 1731300000000": "7a3cb9198e3e966d93e35370eba894f99649cbc6d4cd2135513a61e734331c92",
    "resumeKey a.txt 5 no mtime": "a732c2de87498963c7aa10ead1d2d91824263f1e5b0da3255c09abd8424cc4a2",
    "resumeKey a.txt 5 mtime 1731300000000": "48d8e971ef69f06580dff1e2fb96e783e485fcd99df3b336180e3b587bea35d7",
    "crc32(\"fastdrop\")": "8ed2d25a (2396181082)",
    "mergeRanges [[0,3],[2,5],[7,8]]": "[[0,5],[7,8]]",
}


def selftest_vectors():
    d0 = files_digest(V_FILES)
    d1 = files_digest(V_FILES, V_TEXT)
    c0 = canonical_string(V_ID, V_DEV, V_TS, d0)
    c1 = canonical_string(V_ID, V_DEV, V_TS, d1)
    return [
        ("code", confirmation_code(V_ID)),
        ("filesDigest (files only)", d0),
        ("filesDigest (files + text \"hello\")", d1),
        ("canonical (files only)", c0),
        ("auth (files only)", auth_hmac(V_KEY, c0)),
        ("canonical (files + text)", c1),
        ("auth (files + text)", auth_hmac(V_KEY, c1)),
        ("resumeKey dir/b.bin 9000000 no mtime", resume_key(V_DEV, "dir/b.bin", 9000000)),
        ("resumeKey dir/b.bin 9000000 mtime 1731300000000", resume_key(V_DEV, "dir/b.bin", 9000000, V_MTIME)),
        ("resumeKey a.txt 5 no mtime", resume_key(V_DEV, "a.txt", 5)),
        ("resumeKey a.txt 5 mtime 1731300000000", resume_key(V_DEV, "a.txt", 5, V_MTIME)),
        ("crc32(\"fastdrop\")", "%08x (%d)" % (crc32(b"fastdrop"), crc32(b"fastdrop"))),
        ("mergeRanges [[0,3],[2,5],[7,8]]", json.dumps(merge_ranges([[0, 3], [2, 5], [7, 8]]), separators=(",", ":"))),
    ]


def cmd_selftest(a):
    print("inputs: id=%r dev=%r ts=%d files=%r text=%r key=00..1f mtime=%d" % (V_ID, V_DEV, V_TS, V_FILES, V_TEXT, V_MTIME))
    vec = selftest_vectors()
    for k, v in vec:
        print("%-52s %s" % (k, v.replace("\n", "\\n")))
    got = dict(vec)
    bad = [k for k, v in EXPECTED.items() if got.get(k) != v]
    # independent cross-checks (do not rely on the helper code paths above)
    ok = True
    h = hashlib.sha256(V_ID.encode()).digest()
    ok &= got["code"] == "%04d" % (struct.unpack(">I", h[:4])[0] % 10000)
    ok &= zlib.crc32(b"fastdrop") & 0xFFFFFFFF == crc32(b"fastdrop")
    ok &= merge_ranges([[0, 3], [3, 5]]) == [[0, 5]] and merge_ranges([[5, 6], [0, 1]]) == [[0, 1], [5, 6]]
    ok &= safe_components("../../etc/passwd") == ["etc", "passwd"]
    ok &= safe_components("C:\\Windows\\System32\\x.txt") == ["C_", "Windows", "System32", "x.txt"]
    ok &= safe_components("/abs/a<b>.txt") == ["abs", "a_b_.txt"]
    ok &= safe_components("nul.txt") == ["_nul.txt"]
    ok &= safe_components("..") == ["file"]
    if bad or not ok:
        print("SELFTEST FAILED", bad)
        sys.exit(1)
    print("SELFTEST OK")


def cmd_make(a):
    total, written = int(a.sizeMB * (1 << 20)), 0
    t0 = time.time()
    with open(a.path, "wb") as f:
        while written < total:
            n = min(8 << 20, total - written)
            f.write(os.urandom(n)); written += n
    print("wrote %s (%s) in %.2fs" % (a.path, fmt_bytes(total), time.time() - t0))


def cmd_sha(a):
    for p in a.files:
        h = hashlib.sha256()
        with open(p, "rb") as f:
            while True:
                b = f.read(8 << 20)
                if not b: break
                h.update(b)
        print(h.hexdigest(), p)


def main(argv=None):
    ap = argparse.ArgumentParser(description="FastDrop command-line client (protocol 1 + 2)")
    sp = ap.add_subparsers(dest="cmd")
    sp.required = True

    def common(p, port=True):
        p.add_argument("--state-dir", help="where identity, trusted devices and resume data live (default ~/.fastdrop or %%APPDATA%%\\FastDrop)")
        if port:
            p.add_argument("--port", type=int, default=PORT, help="TCP port (default %d)" % PORT)
            p.add_argument("--disc-port", type=int, default=DISC_PORT, help=argparse.SUPPRESS)

    p = sp.add_parser("hello", help="query a device"); p.add_argument("ip"); common(p); p.set_defaults(f=cmd_hello)
    p = sp.add_parser("scan", help="list FastDrop devices on the network"); p.add_argument("--seconds", type=float, default=4.0)
    common(p); p.set_defaults(f=cmd_scan)
    p = sp.add_parser("send", help="send files, folders or text")
    p.add_argument("target", help="IP address or device name"); p.add_argument("paths", nargs="*")
    p.add_argument("--streams", type=int, default=4); p.add_argument("--crc", action="store_true", help="verify each chunk with CRC32")
    p.add_argument("--name", help="name shown to the receiver"); p.add_argument("--text", help="send this text instead of files")
    p.add_argument("--clipboard", action="store_true", help="send the clipboard text")
    p.add_argument("--stop-after-mb", type=float, default=0, help=argparse.SUPPRESS)
    common(p); p.set_defaults(f=cmd_send)
    p = sp.add_parser("recv", help="receive files")
    p.add_argument("--dir", default="./fd_received"); p.add_argument("--name")
    p.add_argument("--accept", choices=["ask", "trusted", "everyone"], default="trusted")
    p.add_argument("--no-crc", action="store_true", help="refuse CRC32 verification even when the sender asks (default: agree)")
    p.add_argument("--crc", action="store_true", help=argparse.SUPPRESS)  # old flag, now the default
    p.add_argument("--no-discovery", action="store_true")
    p.add_argument("--non-interactive", action="store_true", help=argparse.SUPPRESS)
    common(p); p.set_defaults(f=cmd_recv)
    p = sp.add_parser("trust", help="list or remove trusted devices"); p.add_argument("action", choices=["list", "remove"])
    p.add_argument("id", nargs="?"); common(p, port=False); p.set_defaults(f=cmd_trust)
    p = sp.add_parser("selftest", help="print and check the protocol-2 reference vectors"); p.set_defaults(f=cmd_selftest)
    p = sp.add_parser("make", help="write a random test file"); p.add_argument("path"); p.add_argument("sizeMB", type=float); p.set_defaults(f=cmd_make)
    p = sp.add_parser("sha", help="SHA-256 of files"); p.add_argument("files", nargs="+"); p.set_defaults(f=cmd_sha)
    a = ap.parse_args(argv)
    try:
        a.f(a)
    except KeyboardInterrupt:
        stop.set(); sys.exit(130)


if __name__ == "__main__":
    main()
