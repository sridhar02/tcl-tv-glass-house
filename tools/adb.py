#!/usr/bin/env python3
"""Minimal ADB client for the TV, built on the adb-shell package.

Usage:
  adb.py shell '<command>'          run one shell command, print output
  adb.py push <local> <remote>      push a file (base64 chunks, md5 verified)
  adb.py pull <remote> <local>      pull a file
  adb.py screencap <local.png>      screenshot from the TV
  adb.py key <KEYCODE> [...]        inject remote key presses

TV address: $TV_IP if set, else the first line of tools/tv.local (an IP or a
hostname such as the TV's <uuid>.local mDNS name). Run tools/find_tv.sh to create it.
Port 5555. The ADB key is created on first use as tools/adbkey (keep it private).
"""
import base64
import hashlib
import os
import sys
import time

from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HERE = os.path.dirname(os.path.abspath(__file__))
KEY = os.path.join(HERE, "adbkey")
LOCAL = os.path.join(HERE, "tv.local")


def _tv_host():
    if os.environ.get("TV_IP"):
        return os.environ["TV_IP"]
    if os.path.exists(LOCAL):
        with open(LOCAL) as f:
            name = f.readline().strip()
        if name:
            import socket
            try:
                # A DHCP address can change across reboots; an mDNS name does not.
                return socket.gethostbyname(name)
            except OSError:
                sys.exit(f"Cannot resolve {name}: is the TV on? Try tools/find_tv.sh")
    sys.exit("TV address unknown: run tools/find_tv.sh, or set TV_IP=<address>")


HOST = _tv_host()


def connect():
    if not os.path.exists(KEY):
        old = os.umask(0o077)  # create the key owner-only from the start
        try:
            keygen(KEY)
        finally:
            os.umask(old)
    # The TV grants a shell to whoever holds this key: keep it readable by you only,
    # including keys made before this check existed.
    if os.stat(KEY).st_mode & 0o077:
        os.chmod(KEY, 0o600)
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    dev = AdbDeviceTcp(HOST, 5555, default_transport_timeout_s=30.0)
    # Long auth timeout: the first connection waits for "Allow USB debugging?" on the TV.
    dev.connect(rsa_keys=[PythonRSASigner(pub, priv)], auth_timeout_s=120.0)
    return dev


def sh(dev, cmd, timeout=120):
    return dev.shell(cmd, timeout_s=timeout, read_timeout_s=timeout)


def push(dev, local, remote):
    data = open(local, "rb").read()
    want = hashlib.md5(data).hexdigest()
    b64 = base64.b64encode(data).decode()
    tmp = remote + ".b64"
    sh(dev, f"rm -f '{tmp}'")
    step = 48000
    for i in range(0, len(b64), step):
        sh(dev, f"printf '%s' '{b64[i:i + step]}' >> '{tmp}'")
    sh(dev, f"base64 -d '{tmp}' > '{remote}' && rm -f '{tmp}'")
    got = sh(dev, f"md5sum '{remote}'").split()[0]
    if got != want:
        sys.exit(f"md5 mismatch: {got} != {want}")
    print(f"pushed {len(data)} bytes, md5 ok")


def pull(dev, remote, local):
    out = dev.shell(f"base64 '{remote}'", timeout_s=120, read_timeout_s=120, decode=False)
    data = base64.b64decode(b"".join(out.split()))
    with open(local, "wb") as f:
        f.write(data)
    print(f"pulled {len(data)} bytes -> {local}")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    op, args = sys.argv[1], sys.argv[2:]
    dev = connect()
    try:
        if op == "shell":
            print(sh(dev, " ".join(args)), end="")
        elif op == "push":
            push(dev, args[0], args[1])
        elif op == "pull":
            pull(dev, args[0], args[1])
        elif op == "screencap":
            sh(dev, "screencap -p /data/local/tmp/s.png")
            pull(dev, "/data/local/tmp/s.png", args[0])
        elif op == "key":
            for k in args:
                sh(dev, f"input keyevent {k}")
                time.sleep(0.35)
        else:
            sys.exit(__doc__)
    finally:
        dev.close()


if __name__ == "__main__":
    main()
