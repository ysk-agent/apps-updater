#!/usr/bin/env python3
"""VM-side orchestration for an Apps Updater driven update.

Flow:
  1. Force-stop Apps Updater, then ask it (via an `am start` intent
     extra) to download one APK from the Drive `apps` folder. The
     force-stop guarantees a fresh process takes the intent through
     onCreate: an intent delivered to an already-running instance
     proved unreliable on the ZTE Libero (absorbed without effect),
     which is also why the app itself is singleTop since v0.1.1.
     The download runs on the device, at the device's own line
     speed; nothing but a few KB of commands crosses the adb link.
  2. Poll the downloaded copy in /sdcard/Download/apps-updater/ until
     its SHA-256 matches the expected value (the discriminator that
     the download is complete and intact).
  3. Install it with `adb shell pm install -r`, which needs no user
     confirmation, and optionally verify the installed version. The
     APK is staged through /data/local/tmp first: on the Libero
     (FUSE scoped storage) system_server cannot read /sdcard paths
     for pm install (SELinux denial), while /data/local/tmp works.
     The staging copy is made on-device; no bytes cross the link.

Prerequisites: adb is connected to the device (any route: USB,
wireless debugging, a relay), Apps Updater is installed, and its
one-time SAF grant for the Drive `apps` folder has been given.

Example:
  python3 tools/adb_update.py \
    --adb /path/to/adb --serial 127.0.0.1:15002 \
    --project phone-node-waker --file phone-node-waker.apk \
    --sha256 <hex of the Drive file> --package dev.moriya.phonenode.waker
"""
import argparse
import subprocess
import sys
import time

PKG = "dev.moriya.appsupdater"
REMOTE_DIR = "/sdcard/Download/apps-updater"


def run(adb: str, serial: str, args: list[str], timeout: int = 30) -> str:
    cmd = [adb]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return (out.stdout or "") + (out.stderr or "")


def shell(adb: str, serial: str, command: str, timeout: int = 30) -> str:
    return run(adb, serial, ["shell", command], timeout=timeout)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True, help="Drive folder name, e.g. phone-node-waker")
    ap.add_argument("--file", required=True, help="APK file name, e.g. phone-node-waker.apk")
    ap.add_argument("--sha256", required=True, help="expected SHA-256 of the APK (hex)")
    ap.add_argument("--package", default="", help="package name to verify after install")
    ap.add_argument("--serial", default="", help="adb serial (default: adb's default device)")
    ap.add_argument("--adb", default="adb", help="path to the adb binary")
    ap.add_argument("--timeout", type=int, default=600, help="download wait budget, seconds")
    ap.add_argument("--poll", type=int, default=3, help="poll interval, seconds")
    ap.add_argument("--no-install", action="store_true", help="download and verify only")
    ap.add_argument("--no-force-stop", action="store_true",
                    help="do not force-stop Apps Updater before triggering the download")
    args = ap.parse_args()
    sys.stdout.reconfigure(line_buffering=True)

    for value in (args.project, args.file):
        if " " in value or "/" in value or "'" in value:
            print(f"refusing unsafe name: {value!r}", file=sys.stderr)
            return 2
    expected = args.sha256.lower()
    remote = f"{REMOTE_DIR}/{args.file}"

    print(f"[1/3] triggering download of {args.project}/{args.file} in Apps Updater")
    if not args.no_force_stop:
        shell(args.adb, args.serial, f"am force-stop {PKG}")
    out = shell(
        args.adb, args.serial,
        f"am start -n {PKG}/.MainActivity "
        f"--es auto_project {args.project} --es auto_file {args.file}",
    )
    print(out.strip())

    print(f"[2/3] waiting for SHA-256 match on {remote} (timeout {args.timeout}s)")
    deadline = time.time() + args.timeout
    last_seen = ""
    while time.time() < deadline:
        out = shell(args.adb, args.serial, f"sha256sum {remote}", timeout=15)
        got = out.strip().split()[0].lower() if out.strip() else ""
        if got and got != last_seen:
            print(f"  observed: {got}")
            last_seen = got
        if got == expected:
            break
        time.sleep(args.poll)
    else:
        print(f"timed out; last observed sha256: {last_seen or '(file not present)'}", file=sys.stderr)
        return 1
    print("  SHA-256 matches the expected value")

    if args.no_install:
        print("[3/3] skipped install (--no-install)")
        return 0

    print(f"[3/3] installing with pm install -r (staged via /data/local/tmp)")
    staged = f"/data/local/tmp/{args.file}"
    out = shell(args.adb, args.serial, f"cp {remote} {staged}", timeout=120)
    out = shell(args.adb, args.serial, f"pm install -r {staged}", timeout=300)
    print(out.strip())
    shell(args.adb, args.serial, f"rm -f {staged}", timeout=30)
    if "Success" not in out:
        print("pm install did not report Success", file=sys.stderr)
        return 1

    if args.package:
        out = shell(args.adb, args.serial, f"dumpsys package {args.package}", timeout=15)
        for line in out.splitlines():
            line = line.strip()
            if line.startswith(("versionCode=", "versionName=")):
                print(f"  installed {args.package}: {line}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
