#!/usr/bin/env python3
"""FastDrop LAN protocol test tool (stdlib only). See subcommands with -h.

  fdtest.py hello <ip>
  fdtest.py send <ip> <file>... [--streams N] [--name NAME]
  fdtest.py recv [--dir DIR] [--name NAME] [--auto]
  fdtest.py make <path> <sizeMB>
  fdtest.py sha <file>...
"""
import argparse, hashlib, json, os, queue, re, select, socket, struct, subprocess
import sys, threading, time, uuid

PORT, DISC_PORT, MCAST = 53530, 53531, "224.0.0.168"
M_CTRL, M_DATA, M_HELLO = 0x46444331, 0x46444431, 0x46444831
CHUNK, SOCK_BUF, MAX_JSON = 8 << 20, 4 << 20, 32 << 20
CTRL_TIMEOUT = 15.0
stop = threading.Event()


def fmt_bytes(n):
    v = float(n)
    for u in ("B", "KB", "MB", "GB", "TB"):
        if v < 1024 or u == "TB":
            return f"{int(v)} B" if u == "B" else f"{v:.1f} {u}"
        v /= 1024


def mbs(b, s):
    return b / (1 << 20) / s if s > 0 else 0.0


def read_exact(sock, n, abort=None):
    buf = bytearray(n)
    mv, got = memoryview(buf), 0
    while got < n:
        try:
            r = sock.recv_into(mv[got:], n - got)
        except socket.timeout:
            if abort is not None and abort():
                raise ConnectionError("aborted")
            raise
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
    d = json.dumps(obj).encode("utf-8")
    sock.sendall(struct.pack(">i", len(d)) + d)


# ---------------------------------------------------------------- hello
def cmd_hello(a):
    s = socket.create_connection((a.ip, PORT), timeout=CTRL_TIMEOUT)
    s.sendall(struct.pack(">i", M_HELLO))
    print(json.dumps(read_json(s), indent=2))
    s.close()


# ---------------------------------------------------------------- send
def cmd_send(a):
    files = []
    for p in a.files:
        if not os.path.isfile(p):
            sys.exit("not a file: " + p)
        files.append((p, os.path.basename(p), os.path.getsize(p)))
    total = sum(f[2] for f in files)
    sid = str(uuid.uuid4())
    name = a.name or "Mac test"
    offer = {"id": sid, "name": name, "type": "phone",
             "files": [{"name": n, "size": sz} for _, n, sz in files]}
    ctrl = socket.socket()
    ctrl.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    ctrl.settimeout(CTRL_TIMEOUT)
    ctrl.connect((a.ip, PORT))
    ctrl.sendall(struct.pack(">i", M_CTRL))
    send_json(ctrl, offer)
    print(f"Offered {len(files)} file(s), {fmt_bytes(total)} to {a.ip}; waiting for accept (up to 120s)...")
    ctrl.settimeout(1.0)
    deadline, resp = time.time() + 120, None
    while resp is None:
        try:
            resp = read_json(ctrl)
        except socket.timeout:
            if time.time() > deadline:
                sys.exit("timed out waiting for accept")
    if not resp.get("accept"):
        sys.exit("DECLINED: " + (resp.get("reason") or "(no reason)"))
    print("ACCEPTED")

    q = queue.Queue()
    for i, (_, _, sz) in enumerate(files):
        off = 0
        while off < sz:
            q.put((i, off, min(CHUNK, sz - off)))
            off += CHUNK
    sent = [0]
    lock = threading.Lock()
    errors = []
    idb = sid.encode()

    def worker():
        handles = {}
        try:
            s = socket.socket()
            s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, SOCK_BUF)
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            s.connect((a.ip, PORT))
            s.settimeout(30)
            s.sendall(struct.pack(">i", M_DATA) + struct.pack(">i", len(idb)) + idb)
            while not stop.is_set() and not errors:
                try:
                    fi, off, ln = q.get_nowait()
                except queue.Empty:
                    break
                s.sendall(struct.pack(">iqi", fi, off, ln))
                f = handles.get(fi)
                if f is None:
                    f = handles[fi] = open(files[fi][0], "rb")
                # sendfile in slices so progress is live
                pos, rem = off, ln
                while rem > 0:
                    n = min(rem, 2 << 20)
                    s.sendfile(f, pos, n)
                    pos += n; rem -= n
                    with lock:
                        sent[0] += n
            s.sendall(struct.pack(">iqi", -1, 0, 0))
            s.close()
        except Exception as e:
            errors.append(repr(e))
        finally:
            for f in handles.values():
                f.close()

    t0 = time.time()
    ths = [threading.Thread(target=worker, daemon=True) for _ in range(a.streams)]
    for t in ths:
        t.start()
    last_b, last_t = 0, t0
    try:
        while any(t.is_alive() for t in ths):
            time.sleep(0.5)
            now = time.time()
            with lock:
                b = sent[0]
            print(f"\r  {100.0*b/total if total else 100:5.1f}%  {mbs(b-last_b, now-last_t):7.1f} MB/s  ({fmt_bytes(b)}/{fmt_bytes(total)})   ",
                  end="", flush=True)
            last_b, last_t = b, now
    except KeyboardInterrupt:
        stop.set(); print("\ninterrupted"); ctrl.close(); sys.exit(130)
    for t in ths:
        t.join()
    el = time.time() - t0
    print()
    if errors:
        sys.exit("send errors: " + "; ".join(errors))
    print(f"Sent {fmt_bytes(total)} in {el:.2f}s, avg {mbs(total, el):.1f} MB/s ({a.streams} streams)")
    ctrl.settimeout(1.0)
    wait_until = time.time() + 120
    while True:
        try:
            print("Final:", json.dumps(read_json(ctrl)))
            break
        except socket.timeout:
            if time.time() > wait_until:
                sys.exit("timed out waiting for final ok")
        except Exception as e:
            sys.exit("no final ok: %r" % e)
    print(f"Total incl. receiver finalize: {time.time()-t0:.2f}s")
    ctrl.close()


# ---------------------------------------------------------------- recv
def clean_name(n):
    parts = [p for p in n.replace("\\", "/").split("/") if p not in ("", ".", "..")]
    parts = [p.replace("..", "_") for p in parts]
    return "/".join(parts) or "file"


class Session:
    def __init__(self, sid, files, base):
        self.id, self.files = sid, files
        self.fds, self.got = [], [0] * len(files)
        self.lock = threading.Lock()
        self.total = sum(sz for _, sz in files)
        self.received = 0
        self.failed = None
        self.done = threading.Event()
        self.t0 = time.time()
        self.conns = 0
        self.paths = []
        for n, sz in files:
            p = os.path.join(base, clean_name(n))
            os.makedirs(os.path.dirname(p) or ".", exist_ok=True)
            root, ext = os.path.splitext(p); k = 1
            while os.path.exists(p):
                p = f"{root} ({k}){ext}"; k += 1
            fd = os.open(p, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o644)
            if sz:
                os.ftruncate(fd, sz)
            self.fds.append(fd); self.paths.append(p)
        if self.total == 0:
            self.done.set()

    def add(self, fi, n):
        with self.lock:
            self.received += n
            self.got[fi] += n
            if self.received >= self.total:
                self.done.set()

    def close(self):
        for fd in self.fds:
            try: os.close(fd)
            except OSError: pass
        self.fds = []


class Receiver:
    def __init__(self, a):
        self.dir, self.auto = a.dir, a.auto
        self.name, self.id = a.name, uuid.uuid4().hex[:16]
        self.sessions = {}
        self.slock = threading.Lock()
        self.busy = False
        self.stdin_lock = threading.Lock()
        os.makedirs(self.dir, exist_ok=True)

    def info(self):
        return {"app": "fastdrop", "v": 1, "id": self.id, "name": self.name,
                "type": "phone", "port": PORT, "receivable": True}

    def serve(self):
        srv = socket.socket()
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, SOCK_BUF)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("0.0.0.0", PORT)); srv.listen(128); srv.settimeout(0.5)
        print(f"Listening on TCP {PORT} as '{self.name}' (id {self.id}); saving to {os.path.abspath(self.dir)}")
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
                print(f"[conn {addr[0]}] {e!r}")
            try: c.close()
            except OSError: pass

    def offer(self, c, addr):
        o = read_json(c)
        sid = o["id"]
        files = [(clean_name(f["name"]), int(f["size"])) for f in o["files"]]
        sender = str(o.get("name", "Unknown"))[:60]
        if not files or any(sz < 0 for _, sz in files):
            send_json(c, {"accept": False, "reason": "Invalid offer"}); c.close(); return
        total = sum(sz for _, sz in files)
        with self.slock:
            if self.busy:
                send_json(c, {"accept": False, "reason": "busy"}); c.close(); return
            self.busy = True
        try:
            print(f"\nOffer from '{sender}' ({addr[0]}): {len(files)} file(s), {fmt_bytes(total)}")
            for n, sz in files[:10]:
                print(f"   {n}  {fmt_bytes(sz)}")
            ok = True
            if not self.auto:
                with self.stdin_lock:
                    print("Accept? [y/N] ", end="", flush=True)
                    r, _, _ = select.select([sys.stdin], [], [], 60)
                    ok = bool(r) and sys.stdin.readline().strip().lower().startswith("y")
            if not ok:
                send_json(c, {"accept": False, "reason": "declined"}); c.close(); return
            s = Session(sid, files, self.dir)
            with self.slock:
                self.sessions[sid] = s
            send_json(c, {"accept": True, "reason": ""})
            print("Accepted, receiving...")
            last_b, last_t = 0, time.time()
            c.settimeout(None)
            while not s.done.wait(0.5) and not stop.is_set() and not s.failed:
                now = time.time()
                with s.lock: b = s.received
                print(f"\r  {100.0*b/s.total:5.1f}%  {mbs(b-last_b, now-last_t):7.1f} MB/s   ", end="", flush=True)
                last_b, last_t = b, now
                # sender vanished with no data conns left?
                if s.conns == 0 and now - s.t0 > 60 and b == 0:
                    s.failed = "no data connections"
            el = time.time() - s.t0
            if s.done.is_set() and not s.failed:
                s.close()
                send_json(c, {"ok": True})
                print(f"\nReceived {fmt_bytes(s.total)} in {el:.2f}s, avg {mbs(s.total, el):.1f} MB/s")
                for p in s.paths: print("   ->", p)
            else:
                print("\nTransfer failed:", s.failed or "aborted")
                for p in s.paths:
                    try: os.unlink(p)
                    except OSError: pass
            s.close()
            c.close()
        finally:
            with self.slock:
                self.sessions.pop(sid, None)
                self.busy = False

    def data(self, c):
        idl = read_int(c)
        if not 1 <= idl <= 200:
            raise IOError("bad id")
        sid = read_exact(c, idl).decode()
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
            while not s.done.is_set() and not stop.is_set():
                fi, off, ln = struct.unpack(">iqi", read_exact(c, 16))
                if fi < 0:
                    break
                if fi >= len(s.files) or off < 0 or ln < 0 or off + ln > s.files[fi][1]:
                    raise IOError("bad frame")
                pos, rem = off, ln
                while rem > 0:
                    n = c.recv_into(mv, min(rem, len(buf)))
                    if n == 0:
                        raise EOFError("sender disconnected")
                    w = 0
                    while w < n:
                        w += os.pwrite(s.fds[fi], mv[w:n], pos + w)
                    pos += n; rem -= n
                    s.add(fi, n)
        except Exception as e:
            if not s.done.is_set():
                s.failed = f"data stream error: {e!r}"
        finally:
            with s.lock: s.conns -= 1
            c.close()


# discovery
def local_ips_bcasts():
    ips, bc = [], {"255.255.255.255"}
    try:
        out = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=5).stdout
        for m in re.finditer(r"inet (\d+\.\d+\.\d+\.\d+) netmask (0x[0-9a-f]+)(?: broadcast (\d+\.\d+\.\d+\.\d+))?", out):
            ip = m.group(1)
            if ip.startswith("127.") or ip.startswith("169.254."):
                continue
            ips.append(ip)
            mask = int(m.group(2), 16); ipn = struct.unpack(">I", socket.inet_aton(ip))[0]
            bc.add(socket.inet_ntoa(struct.pack(">I", (ipn | (~mask & 0xFFFFFFFF)) & 0xFFFFFFFF)))
    except Exception:
        pass
    return ips, sorted(bc)


def discovery(rx):
    def msg(kind):
        d = rx.info(); d["kind"] = kind
        return json.dumps(d).encode()

    rs = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rs.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "SO_REUSEPORT"):
        rs.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    rs.bind(("", DISC_PORT)); rs.settimeout(0.5)
    ips, bcs = local_ips_bcasts()
    for ip in (ips or ["0.0.0.0"]):
        try:
            rs.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                          socket.inet_aton(MCAST) + socket.inet_aton(ip))
        except OSError:
            pass
    print(f"Discovery on UDP {DISC_PORT}; local IPs {ips}; broadcast {bcs}")

    tx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    tx.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    tx.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)

    def announce(kind):
        data = msg(kind)
        for ip in (ips or [None]):
            if ip:
                try: tx.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
                except OSError: pass
            for dst in [MCAST] + bcs:
                try: tx.sendto(data, (dst, DISC_PORT))
                except OSError: pass

    def loop_tx():
        while not stop.is_set():
            announce("announce")
            stop.wait(2.5)
        announce("bye")

    t = threading.Thread(target=loop_tx, daemon=True); t.start()
    seen = set()
    while not stop.is_set():
        try:
            data, addr = rs.recvfrom(65535)
            d = json.loads(data.decode())
        except socket.timeout:
            continue
        except Exception:
            continue
        if d.get("app") != "fastdrop" or d.get("id") == rx.id:
            continue
        if d.get("kind") == "announce":
            if d["id"] not in seen:
                seen.add(d["id"])
                print(f"[discovery] saw '{d.get('name')}' at {addr[0]}")
            try: tx.sendto(msg("reply"), (addr[0], DISC_PORT))
            except OSError: pass
    t.join(2)


def cmd_recv(a):
    import signal
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    rx = Receiver(a)
    threading.Thread(target=discovery, args=(rx,), daemon=True).start()
    th = threading.Thread(target=rx.serve)
    th.start()
    try:
        while th.is_alive():
            th.join(0.5)
    except KeyboardInterrupt:
        print("\nshutting down...")
        stop.set()
        th.join(3)
        time.sleep(0.5)  # let bye go out


# ---------------------------------------------------------------- misc
def cmd_make(a):
    total, written = int(a.sizeMB * (1 << 20)), 0
    t0 = time.time()
    with open(a.path, "wb") as f:
        while written < total:
            n = min(8 << 20, total - written)
            f.write(os.urandom(n)); written += n
    print(f"wrote {a.path} ({fmt_bytes(total)}) in {time.time()-t0:.2f}s")


def cmd_sha(a):
    for p in a.files:
        h = hashlib.sha256()
        with open(p, "rb") as f:
            while True:
                b = f.read(8 << 20)
                if not b: break
                h.update(b)
        print(h.hexdigest(), p)


def main():
    ap = argparse.ArgumentParser(description="FastDrop protocol test tool")
    sp = ap.add_subparsers(dest="cmd", required=True)
    p = sp.add_parser("hello"); p.add_argument("ip"); p.set_defaults(f=cmd_hello)
    p = sp.add_parser("send"); p.add_argument("ip"); p.add_argument("files", nargs="+")
    p.add_argument("--streams", type=int, default=4); p.add_argument("--name"); p.set_defaults(f=cmd_send)
    p = sp.add_parser("recv"); p.add_argument("--dir", default="./fd_received")
    p.add_argument("--name", default="Mac test"); p.add_argument("--auto", action="store_true"); p.set_defaults(f=cmd_recv)
    p = sp.add_parser("make"); p.add_argument("path"); p.add_argument("sizeMB", type=float); p.set_defaults(f=cmd_make)
    p = sp.add_parser("sha"); p.add_argument("files", nargs="+"); p.set_defaults(f=cmd_sha)
    a = ap.parse_args()
    try:
        a.f(a)
    except KeyboardInterrupt:
        stop.set(); sys.exit(130)


if __name__ == "__main__":
    main()
