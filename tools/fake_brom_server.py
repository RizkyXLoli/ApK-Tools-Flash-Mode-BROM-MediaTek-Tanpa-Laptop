#!/usr/bin/env python3
"""TCP bridge to the BROM emulator, so the Java bypass engine can be exercised
on the JVM exactly as it will run on the phone.

The bridge plays the device side: it feeds every host write into
``FakeBromBackend`` and hands back whatever the emulator produced. Framing
between Java and this process is deliberately dumb:

    request  : op(1) + args
    response : status(1) [+ len(4) + message] [+ len(4) + payload]

    'W' write : u32 len, data
    'R' read  : u32 len
    'C' ctrl  : u32 requestType, u32 request, u32 value, u32 index,
                u32 len, u8 hasData, data?
    'D' drain
    'X' close

Run: python3 tools/fake_brom_server.py [--port 0] [--var1 0]
"""
import argparse
import os
import socket
import struct
import sys
import threading

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

# The emulator lives with the Python reference implementation, not in this repo.
_MTKAUTH = os.environ.get("MTKAUTH_DIR", "/root/mtkauth")


def _load_backend():
    import importlib.util
    path = os.path.join(_MTKAUTH, "tests", "fake_brom.py")
    if not os.path.exists(path):
        raise SystemExit("emulator tidak ditemukan: %s (set MTKAUTH_DIR)" % path)
    if _MTKAUTH not in sys.path:
        sys.path.insert(0, _MTKAUTH)
    spec = importlib.util.spec_from_file_location("fake_brom", path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["fake_brom"] = mod
    spec.loader.exec_module(mod)
    return mod.FakeBromBackend


FakeBromBackend = _load_backend()


def u32(b, off):
    return struct.unpack(">I", b[off:off + 4])[0]


def recv_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def send_error(conn, msg):
    payload = msg.encode("utf-8", "replace")
    conn.sendall(b"\x01" + struct.pack(">I", len(payload)) + payload)


def need(conn, n):
    """recv_exact that raises instead of returning None mid-frame."""
    buf = recv_exact(conn, n)
    if buf is None:
        raise ConnectionError("host menutup koneksi di tengah frame")
    return buf


def send_ok(conn, data=None):
    if data is None:
        conn.sendall(b"\x00")
    else:
        conn.sendall(b"\x00" + struct.pack(">I", len(data)) + data)


def serve(conn, backend, verbose):
    while True:
        head = recv_exact(conn, 1)
        if not head:
            return
        op = head
        try:
            if op == b"W":
                n = u32(need(conn, 4), 0)
                data = need(conn, n) if n else b""
                if verbose:
                    print("W %d %s" % (n, data.hex()), flush=True)
                backend.write(data)
                send_ok(conn)
            elif op == b"R":
                n = u32(need(conn, 4), 0)
                out = b""
                while len(out) < n:
                    out += backend.read(n - len(out))
                if verbose:
                    print("R %d -> %s" % (n, out.hex()), flush=True)
                send_ok(conn, out)
            elif op == b"C":
                args = need(conn, 20)
                rtype, request, value, index, length = struct.unpack(">IIIII", args)
                has = need(conn, 1)[0]
                data = need(conn, length) if has else None
                if verbose:
                    print("ctrl %02X %02X %04X %04X len=%d data=%s"
                          % (rtype, request, value, index, length,
                             data.hex() if data else "-"), flush=True)
                res = backend.ctrl_transfer(rtype, request, value, index,
                                            data if data is not None else length)
                if isinstance(res, int):
                    res = b""
                send_ok(conn, bytes(res or b""))
            elif op == b"D":
                backend._out.clear()
                send_ok(conn)
            elif op == b"X":
                backend.close()
                send_ok(conn)
                return
            else:
                send_error(conn, "op tidak dikenal: %r" % op)
                return
        except Exception as exc:  # transport errors are part of the contract
            send_error(conn, "%s: %s" % (type(exc).__name__, exc))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=0)
    ap.add_argument("--var1", type=lambda s: int(s, 0), default=0x0)
    ap.add_argument("--watchdog", type=lambda s: int(s, 0), default=0x10000000)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.port))
    srv.listen(1)
    print("READY %d" % srv.getsockname()[1], flush=True)

    conn, _ = srv.accept()
    backend = FakeBromBackend(var1=args.var1, verbose=args.verbose,
                              watchdog=args.watchdog)
    try:
        serve(conn, backend, args.verbose)
    finally:
        conn.close()
        srv.close()


if __name__ == "__main__":
    main()
