#!/usr/bin/env python3
"""Tiny Source-RCON client used to drive the Fettle dev server (benchmarks / injection checks).

Usage:
    python tools/fettle_rcon.py "<command>" [more commands...]
Env: FETTLE_RCON_HOST (default 127.0.0.1), FETTLE_RCON_PORT (25575), FETTLE_RCON_PASSWORD (fettle)
"""
import os
import socket
import struct
import sys

TERMINATOR = bytes(2)


def connect(host, port, password, timeout=15):
    sock = socket.create_connection((host, port), timeout=timeout)

    def send(req_id, req_type, body):
        payload = struct.pack("<ii", req_id, req_type) + body.encode("utf8") + TERMINATOR
        sock.sendall(struct.pack("<i", len(payload)) + payload)

    def recv():
        raw_len = sock.recv(4)
        if len(raw_len) < 4:
            raise RuntimeError("connection closed")
        length = struct.unpack("<i", raw_len)[0]
        data = b""
        while len(data) < length:
            chunk = sock.recv(length - len(data))
            if not chunk:
                raise RuntimeError("connection closed mid-packet")
            data += chunk
        req_id, req_type = struct.unpack("<ii", data[:8])
        return req_id, req_type, data[8:-2].decode("utf8", "ignore")

    send(1, 3, password)
    req_id, _, _ = recv()
    if req_id == -1:
        raise RuntimeError("RCON auth failed")
    return sock, send, recv


def main():
    host = os.environ.get("FETTLE_RCON_HOST", "127.0.0.1")
    port = int(os.environ.get("FETTLE_RCON_PORT", "25575"))
    password = os.environ.get("FETTLE_RCON_PASSWORD", "fettle")
    commands = sys.argv[1:]
    if not commands:
        print(__doc__)
        return 2
    sock, send, recv = connect(host, port, password)
    try:
        for i, command in enumerate(commands, start=2):
            send(i, 2, command)
            _, _, body = recv()
            print("> %s" % command)
            print(body)
    finally:
        sock.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
