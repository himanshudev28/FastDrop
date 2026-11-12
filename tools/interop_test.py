#!/usr/bin/env python3
"""End-to-end tests for tools/fastdrop.py on 127.0.0.1 (stdlib only). Run: python3 tools/interop_test.py

Uses a non-default port, separate --state-dirs and a temp directory that is deleted afterwards.
Scenarios: plain/nested/empty files, text, trust pairing, tampered/replayed/stale auth, resume,
CRC, protocol-1 compatibility with fdtest.py, and (optionally) the Mac CLI when it is built.
"""
import glob
import hashlib
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import traceback
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fastdrop as fd  # noqa: E402

PY = sys.executable
FASTDROP = os.path.join(HERE, "fastdrop.py")
PORT, DPORT = 53730, 53731
LOOP = "127.0.0.1"
ENV = dict(os.environ, PYTHONUNBUFFERED="1")
ROOT = None
procs = []


# ------------------------------------------------------------------ helpers
def tmp(*parts):
    p = os.path.join(ROOT, *parts)
    os.makedirs(os.path.dirname(p) if parts and "." in os.path.basename(p) else p, exist_ok=True)
    return p


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def tree(base, include_hidden=True):
    out = {}
    for r, _, fs in os.walk(base):
        for f in fs:
            if not include_hidden and f.startswith("."):
                continue
            p = os.path.join(r, f)
            out[os.path.relpath(p, base).replace(os.sep, "/")] = sha(p)
    return out


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)


class Recv:
    def __init__(self, name, state, outdir, args=(), stdin=False, binary=None, extra_cmd=None):
        self.log_path = tmp("logs", name + "-%s.log" % uuid.uuid4().hex[:6])
        os.makedirs(os.path.dirname(self.log_path), exist_ok=True)
        self.log = open(self.log_path, "w")
        cmd = extra_cmd or [PY, FASTDROP, "recv", "--dir", outdir, "--state-dir", state, "--port", str(PORT),
                            "--no-discovery"] + list(args)
        self.p = subprocess.Popen(cmd, stdout=self.log, stderr=subprocess.STDOUT, env=ENV,
                                  stdin=subprocess.PIPE if stdin else subprocess.DEVNULL)
        procs.append(self.p)
        t = time.time()
        while time.time() - t < 15:
            if fd.hello_probe(LOOP, PORT, 0.5):
                return
            if self.p.poll() is not None:
                raise RuntimeError("receiver exited early:\n" + self.text())
            time.sleep(0.1)
        raise RuntimeError("receiver did not start:\n" + self.text())

    def text(self):
        self.log.flush()
        with open(self.log_path, errors="replace") as f:
            return f.read()

    def wait_for(self, needle, timeout=20, count=1):
        t = time.time()
        while time.time() - t < timeout:
            if self.text().count(needle) >= count:
                return True
            time.sleep(0.1)
        return False

    def say(self, line):
        self.p.stdin.write((line + "\n").encode()); self.p.stdin.flush()

    def stop(self):
        if self.p.poll() is None:
            self.p.terminate()
            try: self.p.wait(10)
            except subprocess.TimeoutExpired: self.p.kill()
        self.log.close()


def send(state, *args, timeout=120, expect=0):
    r = subprocess.run([PY, FASTDROP, "send", LOOP, *args, "--state-dir", state, "--port", str(PORT)],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=ENV, timeout=timeout,
                       universal_newlines=True)
    if expect is not None and r.returncode != expect:
        raise AssertionError("send rc=%d (expected %d):\n%s" % (r.returncode, expect, r.stdout))
    return r


def raw_offer(offer):
    s = socket.create_connection((LOOP, PORT), 10)
    s.settimeout(30)
    s.sendall(struct.pack(">i", fd.M_CTRL))
    fd.send_json(s, offer)
    r = fd.read_json(s)
    ok = None
    if r.get("accept"):
        try: ok = fd.read_json(s)
        except Exception: pass
    s.close()
    return r, ok


def make_tree(base):
    write(os.path.join(base, "top.txt"), b"top level\n")
    write(os.path.join(base, "sub", "deep", "x.bin"), os.urandom(3 * (1 << 20) + 17))
    write(os.path.join(base, "sub", "empty.dat"), b"")
    write(os.path.join(base, "sub", "uünï.txt"), "unicode name".encode())
    os.makedirs(os.path.join(base, "emptydir"), exist_ok=True)   # empty dirs are not transferred


# ------------------------------------------------------------------ scenarios
def s_a_files(S):
    src, out = tmp("a", "src"), tmp("a", "out")
    st_s, st_r = tmp("a", "ss"), tmp("a", "sr")
    make_tree(os.path.join(src, "proj"))
    write(os.path.join(src, "single.bin"), os.urandom(9 * (1 << 20) + 5))   # spans 2 chunks
    write(os.path.join(src, "zero.txt"), b"")
    rx = Recv("a", st_r, out, ["--accept", "everyone"])
    try:
        r = send(st_s, os.path.join(src, "proj"), os.path.join(src, "single.bin"), os.path.join(src, "zero.txt"))
        check("Done." in r.stdout, r.stdout)
        got = tree(out)
        want = {"proj/" + k: v for k, v in tree(os.path.join(src, "proj")).items()}
        want.update({"single.bin": sha(os.path.join(src, "single.bin")), "zero.txt": sha(os.path.join(src, "zero.txt"))})
        check(got == want, "tree mismatch\n got=%s\nwant=%s" % (sorted(got), sorted(want)))
        check(os.path.getsize(os.path.join(out, "zero.txt")) == 0, "0-byte file not empty")
        check(not [f for f in got if f.endswith(".fdpart")], "partial left behind")
    finally:
        rx.stop()


def s_b_text(S):
    out, st_s, st_r = tmp("b", "out"), tmp("b", "ss"), tmp("b", "sr")
    rx = Recv("b", st_r, out, ["--accept", "everyone"])
    try:
        txt = "Hello éè ☃\nsecond line https://example.com"
        send(st_s, "--text", txt)
        check(rx.wait_for("Text from"), "receiver did not show text:\n" + rx.text())
        check("second line https://example.com" in rx.text(), "text missing from receiver output")
        r = send(st_s, "--text", "x" * (fd.MAX_TEXT + 1), expect=1)
        check("longer than" in r.stdout, r.stdout)
    finally:
        rx.stop()


def s_c_trust(S):
    out, st_s, st_r = tmp("c", "out"), tmp("c", "ss"), tmp("c", "sr")
    f = os.path.join(tmp("c", "src"), "t.bin"); write(f, os.urandom(1 << 20))
    rx = Recv("c1", st_r, out, ["--accept", "ask"], stdin=True)
    try:
        rx.say("a")                                    # accept and always trust
        r = send(st_s, f)
        check("Paired" in r.stdout and "Code " in r.stdout, r.stdout)
        check(rx.wait_for("Confirmation code:"), rx.text())
        code = [l for l in rx.text().splitlines() if "Confirmation code" in l][0].split(":")[1].split()[0]
        check(code in r.stdout, "sender and receiver codes differ (%s)\n%s" % (code, r.stdout))
    finally:
        rx.stop()
    # restart: trusted mode and non-interactive, so any prompt would decline
    rx = Recv("c2", st_r, out, ["--accept", "trusted", "--non-interactive"])
    try:
        r = send(st_s, f)
        check("Code " not in r.stdout, "authenticated send should not show a code:\n" + r.stdout)
        check("auto-accepted (trusted device)" in rx.text(), rx.text())
        check(len(tree(out)) == 2, "expected 2 files, got %s" % sorted(tree(out)))
        # a different, untrusted sender must be declined
        r = send(tmp("c", "other"), f, expect=1)
        check("DECLINED: declined" in r.stdout, r.stdout)
        tl = subprocess.run([PY, FASTDROP, "trust", "list", "--state-dir", st_r], stdout=subprocess.PIPE,
                            universal_newlines=True).stdout
        sid = fd.State(st_s).id
        check(sid in tl, "sender id missing from trust list:\n" + tl)
        subprocess.run([PY, FASTDROP, "trust", "remove", sid, "--state-dir", st_r], check=True, stdout=subprocess.PIPE)
        r = send(st_s, f, expect=1)       # revoked: key no longer valid, so it just prompts (declined)
        check("DECLINED: declined" in r.stdout, r.stdout)
    finally:
        rx.stop()


def s_d_auth(S):
    out, st_s, st_r = tmp("d", "out"), tmp("d", "ss"), tmp("d", "sr")
    f = os.path.join(tmp("d", "src"), "t.bin"); write(f, b"hi")
    rx = Recv("d1", st_r, out, ["--accept", "ask"], stdin=True)
    try:
        rx.say("a"); send(st_s, f)
    finally:
        rx.stop()
    rx = Recv("d2", st_r, out, ["--accept", "trusted", "--non-interactive"])
    try:
        me = fd.State(st_s)
        rid = fd.hello_probe(LOOP, PORT, 2)["id"]
        key = bytes.fromhex(me.keys[rid]["key"])
        files = [{"name": "r.txt", "size": 0, "mtime": 1}]

        def offer(sid=None, k=key, ts=None, name="r.txt"):
            fl = [{"name": name, "size": 0, "mtime": 1}]
            return fd.build_offer(sid or str(uuid.uuid4()), "tester", me.id, fl, key=k, ts=ts)

        r, ok = raw_offer(offer())
        check(r["accept"] and ok == {"ok": True}, "valid auth should auto-accept: %r" % r)
        check(rx.text().count("auto-accepted (trusted device)") == 1, rx.text())
        # replay: same session id again
        sid = str(uuid.uuid4())
        r, _ = raw_offer(offer(sid, name="r1.txt"))
        check(r["accept"], "first use of id must pass")
        r, _ = raw_offer(offer(sid, name="r1.txt"))
        check(not r["accept"] and r["reason"] == "declined", "replayed id must fall back to prompt: %r" % r)
        # tampered: valid HMAC for different files
        o = offer(name="honest.txt"); o["files"][0]["name"] = "evil.txt"
        r, _ = raw_offer(o)
        check(not r["accept"], "tampered offer must not be auto-accepted: %r" % r)
        # wrong key
        r, _ = raw_offer(offer(k=os.urandom(32)))
        check(not r["accept"], "wrong key must not be auto-accepted")
        # stale timestamp (valid HMAC, 25 h old)
        r, _ = raw_offer(offer(ts=fd.now_ms() - 25 * 3600 * 1000))
        check(not r["accept"], "stale ts must not be auto-accepted")
        # garbage auth
        o = offer(); o["auth"] = "zz" + o["auth"][2:]
        r, _ = raw_offer(o)
        check(not r["accept"], "garbage auth must not be auto-accepted")
        check(rx.text().count("Accept? [y/N") == 5, "expected 5 prompts:\n" + rx.text())
        check(not os.path.exists(os.path.join(out, "evil.txt")), "evil.txt written")
        # path traversal is neutralised
        r, ok = raw_offer(offer(name="..\\..\\etc/../../evil2.txt"))
        check(r["accept"] and ok, r)
        check(os.path.exists(os.path.join(out, "etc", "evil2.txt")) or os.path.exists(os.path.join(out, "evil2.txt")), "sanitised file missing")
        escaped = [p for p in glob.glob(os.path.join(os.path.dirname(out), "**", "evil2.txt"), recursive=True)
                   if not os.path.realpath(p).startswith(os.path.realpath(out))]
        check(not escaped, "file escaped --dir: %s" % escaped)
    finally:
        rx.stop()


def s_e_resume(S):
    out, st_s, st_r = tmp("e", "out"), tmp("e", "ss"), tmp("e", "sr")
    f = os.path.join(tmp("e", "src"), "big.bin")
    size = 40 * (1 << 20)
    write(f, os.urandom(size))
    rx = Recv("e", st_r, out, ["--accept", "everyone"])
    try:
        r = send(st_s, f, "--streams", "1", "--stop-after-mb", "16", expect=3)
        check("sent_bytes=%d" % (16 << 20) in r.stdout, r.stdout)
        check(rx.wait_for("Transfer failed"), rx.text())
        t = time.time()
        while time.time() - t < 5 and not fd.State(st_r).records:
            time.sleep(0.1)
        check(glob.glob(os.path.join(out, ".big.bin.*.fdpart")), "hidden partial missing: %s" % os.listdir(out))
        check(not os.path.exists(os.path.join(out, "big.bin")), "final file must not exist yet")
        check(len(fd.State(st_r).records) == 1, "resume record not saved")
        r = send(st_s, f, "--streams", "2")
        check("Resuming at 40%" in r.stdout, r.stdout)
        check("sent_bytes=%d skipped_bytes=%d" % (size - (16 << 20), 16 << 20) in r.stdout, "only the remainder should be sent:\n" + r.stdout)
        check(sha(os.path.join(out, "big.bin")) == sha(f), "hash mismatch after resume")
        check(sorted(os.listdir(out)) == ["big.bin"], "leftovers: %s" % os.listdir(out))
        check(not fd.State(st_r).records, "record not cleared")
    finally:
        rx.stop()


def s_f_crc(S):
    out, st_s, st_r = tmp("f", "out"), tmp("f", "ss"), tmp("f", "sr")
    f = os.path.join(tmp("f", "src"), "c.bin"); write(f, os.urandom(10 * (1 << 20) + 3))
    rx = Recv("f", st_r, out, ["--accept", "everyone", "--crc"])
    try:
        send(st_s, f, "--crc")
        check(sha(os.path.join(out, "c.bin")) == sha(f), "crc transfer hash mismatch")
        # receiver with --no-crc: sender falls back to plain frames
    finally:
        rx.stop()
    out2 = tmp("f", "out2")
    rx = Recv("f2", tmp("f", "sr2"), out2, ["--accept", "everyone", "--no-crc"])
    try:
        r = send(st_s, f, "--crc")
        check("did not agree to CRC" in r.stdout and sha(os.path.join(out2, "c.bin")) == sha(f), r.stdout)
    finally:
        rx.stop()
    # corrupt frame must fail the transfer
    out3 = tmp("f", "out3")
    rx = Recv("f3", tmp("f", "sr3"), out3, ["--accept", "everyone", "--crc"])
    try:
        sid = str(uuid.uuid4())
        o = fd.build_offer(sid, "tester", "dev-x", [{"name": "bad.bin", "size": 1000, "mtime": 1}], crc=True)
        c = socket.create_connection((LOOP, PORT), 10); c.settimeout(20)
        c.sendall(struct.pack(">i", fd.M_CTRL)); fd.send_json(c, o)
        r = fd.read_json(c)
        check(r["accept"] and r.get("crc"), r)
        d = socket.create_connection((LOOP, PORT), 10)
        idb = sid.encode()
        d.sendall(struct.pack(">ii", fd.M_DATA, len(idb)) + idb + struct.pack(">iqi", 0, 0, 1000) + b"\0" * 1000 + struct.pack(">I", 0xDEADBEEF))
        check(rx.wait_for("Transfer failed"), rx.text())
        check("CRC" in rx.text() or "Data check failed" in rx.text(), rx.text())
        check(not os.path.exists(os.path.join(out3, "bad.bin")), "corrupt file was finalised")
        d.close(); c.close()
    finally:
        rx.stop()


def fdtest_cmd(*args):
    code = ("import sys; sys.path.insert(0, %r); import fdtest; fdtest.PORT=%d; fdtest.DISC_PORT=%d; "
            "sys.argv=['fdtest']+sys.argv[1:]; fdtest.main()" % (HERE, PORT, DPORT + 5))
    return [PY, "-c", code] + list(args)


def s_g_v1(S):
    src = tmp("g", "src")
    f1 = os.path.join(src, "one.bin"); write(f1, os.urandom(9 * (1 << 20) + 11))
    f2 = os.path.join(src, "two.txt"); write(f2, b"two")
    # fdtest send -> fastdrop recv (protocol 1 offer)
    out = tmp("g", "out1")
    rx = Recv("g1", tmp("g", "sr"), out, ["--accept", "everyone"])
    try:
        r = subprocess.run(fdtest_cmd("send", LOOP, f1, f2), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           env=ENV, timeout=120, universal_newlines=True)
        check(r.returncode == 0 and "Final:" in r.stdout, r.stdout)
        check(tree(out) == {"one.bin": sha(f1), "two.txt": sha(f2)}, "v1 send -> py recv mismatch: %s" % tree(out))
    finally:
        rx.stop()
    # fastdrop send -> fdtest recv
    out = tmp("g", "out2")
    rx = Recv("g2", tmp("g", "x"), out, extra_cmd=fdtest_cmd("recv", "--dir", out, "--auto", "--name", "v1peer"))
    try:
        r = send(tmp("g", "ss"), f1, f2)
        check("Done." in r.stdout, r.stdout)
        check(tree(out) == {"one.bin": sha(f1), "two.txt": sha(f2)}, "py send -> v1 recv mismatch: %s" % tree(out))
        r = send(tmp("g", "ss"), "--text", "hi", expect=1)
        check("needs FastDrop 2.1" in r.stdout, r.stdout)
    finally:
        rx.stop()


def mac_cli():
    root = os.path.dirname(HERE)
    c = sorted(glob.glob(os.path.join(root, "mac", ".build", "*", "debug", "fastdrop-cli")))
    return c[0] if c else None


def s_h_mac(S):
    cli = mac_cli()
    if not cli:
        return "SKIP (mac/.build/*/debug/fastdrop-cli not built)"
    st = subprocess.run([cli, "selftest"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True, timeout=30)
    help_txt = subprocess.run([cli], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True, timeout=30).stdout
    if st.returncode != 0 or "--state-dir" not in (help_txt + open(os.path.join(os.path.dirname(HERE), "mac", "Sources", "FastDropCLI", "main.swift")).read()):
        return "SKIP (Mac CLI lacks protocol 2 flags / selftest)"
    # selftest vectors must agree with ours
    mine = dict(fd.selftest_vectors())
    for k in ("code", "filesDigest (files only)", "auth (files only)"):
        check(mine[k] in st.stdout, "Mac selftest disagrees on %s (%s)" % (k, mine[k]))
    src = tmp("h", "src"); f = os.path.join(src, "m.bin"); write(f, os.urandom(9 * (1 << 20)))
    # py -> mac
    out = tmp("h", "out1")
    mrx = Recv("h1", None, out, extra_cmd=[cli, "recv", "--dir", out, "--state-dir", tmp("h", "msr"), "--port", str(PORT),
                                           "--no-discovery", "--accept", "everyone"])
    try:
        send(tmp("h", "ss"), f)
        check(tree(out) == {"m.bin": sha(f)}, "py -> mac mismatch")
        send(tmp("h", "ss"), "--text", "from python")
    finally:
        mrx.stop()
    # mac -> py
    out = tmp("h", "out2")
    rx = Recv("h2", tmp("h", "psr"), out, ["--accept", "everyone"])
    try:
        r = subprocess.run([cli, "send", LOOP, f, "--port", str(PORT), "--state-dir", tmp("h", "mss")],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True, timeout=120)
        check(r.returncode == 0 and tree(out) == {"m.bin": sha(f)}, "mac -> py failed:\n" + r.stdout)
    finally:
        rx.stop()


def s_i_same_name(S):
    """Two offered files with the same name (different folders) must both arrive, de-duplicated."""
    src = tmp("i", "src")
    fa = os.path.join(src, "a", "x.bin"); fb = os.path.join(src, "b", "x.bin")
    size = 3 * (1 << 20) + 5
    write(fa, os.urandom(size)); write(fb, os.urandom(size))     # same name AND same size
    want = sorted([sha(fa), sha(fb)])

    def got(out):
        check(sorted(os.listdir(out)) == ["x (1).bin", "x.bin"], "unexpected names/leftovers: %s" % os.listdir(out))
        return sorted(sha(os.path.join(out, n)) for n in os.listdir(out))

    out = tmp("i", "out1")
    rx = Recv("i1", tmp("i", "sr"), out, ["--accept", "everyone"])
    try:
        r = send(tmp("i", "ss"), fa, fb)
        check("Done." in r.stdout, r.stdout)
        check(got(out) == want, "py -> py: hashes differ")
    finally:
        rx.stop()
    cli = mac_cli()
    if not cli:
        return "SKIP py->mac, fdtest->mac (mac CLI not built); py->py passed"
    for label, sender in (("py", lambda: send(tmp("i", "ss2"), fa, fb)),
                          ("fdtest", lambda: subprocess.run(fdtest_cmd("send", LOOP, fa, fb), stdout=subprocess.PIPE,
                                                            stderr=subprocess.STDOUT, env=ENV, timeout=120,
                                                            universal_newlines=True))):
        out = tmp("i", "out_" + label)
        mrx = Recv("i_" + label, None, out, extra_cmd=[cli, "recv", "--dir", out, "--state-dir", tmp("i", "msr_" + label),
                                                       "--port", str(PORT), "--no-discovery", "--accept", "everyone"])
        try:
            r = sender()
            check(r.returncode == 0, "%s -> mac failed:\n%s" % (label, r.stdout))
            time.sleep(0.5)
            check(got(out) == want, "%s -> mac: hashes differ" % label)
        finally:
            mrx.stop()


def _accept_only(offer):
    """Send an offer, return the accept reply, then drop the connection without sending data."""
    s = socket.create_connection((LOOP, PORT), 10)
    s.settimeout(30)
    s.sendall(struct.pack(">i", fd.M_CTRL))
    fd.send_json(s, offer)
    r = fd.read_json(s)
    s.close()
    return r


def s_j_hardening(S):
    out, st_s, st_r = tmp("j", "out"), tmp("j", "ss"), tmp("j", "sr")
    f = os.path.join(tmp("j", "src"), "big.bin")
    size = 40 * (1 << 20)
    write(f, os.urandom(size))
    mt = int(os.stat(f).st_mtime * 1000)
    # resume needs a positive mtime: an offer without one never gets "have"
    rx = Recv("j1", st_r, out, ["--accept", "everyone"])
    try:
        send(st_s, f, "--streams", "1", "--stop-after-mb", "16", expect=3)
        check(rx.wait_for("Transfer failed"), rx.text())
        t = time.time()
        while time.time() - t < 5 and not fd.State(st_r).records:
            time.sleep(0.1)
        me = fd.State(st_s)
        base = {"v": 2, "dev": me.id, "ts": fd.now_ms(), "chunk": fd.CHUNK, "name": "tester", "type": "laptop"}
        r = _accept_only(dict(base, id=str(uuid.uuid4()), files=[{"name": "big.bin", "size": size}]))
        check(r["accept"] and "have" not in r, "no-mtime offer must start fresh: %r" % r)
        time.sleep(0.5)
        r = _accept_only(dict(base, id=str(uuid.uuid4()), files=[{"name": "big.bin", "size": size, "mtime": 0}]))
        check(r["accept"] and "have" not in r, "mtime 0 must start fresh: %r" % r)
        time.sleep(0.5)
        r = _accept_only(dict(base, id=str(uuid.uuid4()), files=[{"name": "big.bin", "size": size, "mtime": mt}]))
        check(r["accept"] and "have" in r, "positive mtime should resume: %r" % r)
    finally:
        rx.stop()
    # state files are private (POSIX)
    if os.name != "nt":
        check(oct(os.stat(st_r).st_mode & 0o777) == "0o700", "state dir mode")
        for n in os.listdir(st_r):
            check(oct(os.stat(os.path.join(st_r, n)).st_mode & 0o777) == "0o600", "mode of %s" % n)
    # replay cache survives a receiver restart; spoofed dev never gets "always trust"
    st_r2, out2 = tmp("j", "sr2"), tmp("j", "out2")
    g = os.path.join(tmp("j", "src"), "t.bin"); write(g, b"hi")
    rx = Recv("j2", st_r2, out2, ["--accept", "ask"], stdin=True)
    try:
        rx.say("a"); send(st_s, g)
    finally:
        rx.stop()
    me = fd.State(st_s)
    rid = [k for k in me.keys][0]
    key = bytes.fromhex(me.keys[rid]["key"])
    before = dict(fd.State(st_r2).trusted)
    sid = str(uuid.uuid4())
    fl = [{"name": "r.txt", "size": 0, "mtime": 1}]
    rx = Recv("j3", st_r2, out2, ["--accept", "trusted", "--non-interactive"])
    try:
        r, _ = raw_offer(fd.build_offer(sid, "tester", me.id, fl, key=key))
        check(r["accept"], "valid auth should auto-accept")
    finally:
        rx.stop()
    rx = Recv("j4", st_r2, out2, ["--accept", "trusted", "--non-interactive"])
    try:
        r, _ = raw_offer(fd.build_offer(sid, "tester", me.id, fl, key=key))
        check(not r["accept"], "replay after restart must fall back to prompt: %r" % r)
        r, _ = raw_offer(fd.build_offer(str(uuid.uuid4()), "tester", me.id, fl, key=None))
        check(not r["accept"], r)
        check("[y/N]" in rx.text() and "always trust" not in rx.text().split("Offer from")[-1],
              "spoofed trusted dev must not be offered 'always trust':\n" + rx.text())
        check(fd.State(st_r2).trusted == before, "trusted entry changed")
    finally:
        rx.stop()


SCENARIOS = [
    ("a  py->py files, nested folder, 0-byte, SHA-256", s_a_files),
    ("b  text offers", s_b_text),
    ("c  trust pairing then auto-accept, revoke", s_c_trust),
    ("d  tampered / replayed / stale / wrong-key auth falls back to prompt", s_d_auth),
    ("e  resume after --stop-after-mb sends only the remainder", s_e_resume),
    ("f  CRC32 on, fallback, and corrupt frame", s_f_crc),
    ("g  protocol-1 compat both ways with fdtest.py", s_g_v1),
    ("h  py<->mac CLI (optional)", s_h_mac),
    ("j  resume needs mtime, state file modes, persistent replay cache, no re-trust of known dev", s_j_hardening),
    ("i  same-name files from different folders: py->py, py->mac, fdtest->mac", s_i_same_name),
]


def main():
    global ROOT
    ROOT = tempfile.mkdtemp(prefix="fdinterop-")
    results = []
    try:
        for title, fn in SCENARIOS:
            t0 = time.time()
            try:
                r = fn(None)
                status = r if isinstance(r, str) else "PASS"
            except Exception as e:
                status = "FAIL: " + (str(e) or repr(e))
                if not isinstance(e, AssertionError):
                    status += "\n" + traceback.format_exc()
            results.append((title, status))
            print("[%s] %s  (%.1fs)" % (status.split("\n")[0].split(":")[0].split(" ")[0], title, time.time() - t0))
            if status.startswith("FAIL"):
                print(status)
            elif status.startswith("SKIP"):
                print("      " + status)
    finally:
        for p in procs:
            if p.poll() is None:
                p.kill()
        shutil.rmtree(ROOT, ignore_errors=True)
    failed = [t for t, s in results if s.startswith("FAIL")]
    print("\n%d passed, %d skipped, %d failed" % (
        sum(1 for _, s in results if s == "PASS"), sum(1 for _, s in results if s.startswith("SKIP")), len(failed)))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
