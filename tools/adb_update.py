#!/usr/bin/env python3
"""VM-side orchestration for an Apps Updater driven update.

Flow:
  1. Force-stop Apps Updater, then ask it (via an `am start` intent
     extra) to download one APK from its GitHub Releases source. The
     force-stop guarantees a fresh process takes the intent through
     onCreate: an intent delivered to an already-running instance
     proved unreliable on the ZTE Libero (absorbed without effect),
     which is also why the app itself is singleTop since v0.1.1.
     The download runs on the device, at the device's own line
     speed; nothing but a few KB of commands crosses the adb link.
  2. Poll the downloaded copy in /sdcard/Download/apps-updater/ until
     its SHA-256 matches the expected value (the discriminator that
     the download is complete and intact).
  3. Install it, in one of two ways:
     - Default: `adb shell pm install -r`, which needs no user
       confirmation, and optionally verify the installed version.
       The APK is staged through /data/local/tmp first: on the
       Libero (FUSE scoped storage) system_server cannot read
       /sdcard paths for pm install (SELinux denial), while
       /data/local/tmp works. The staging copy is made on-device;
       no bytes cross the link. Note the installer of record for
       this route is the shell, not Apps Updater.
     - `--install-via-updater` (app v0.2.6+): the trigger carries
       `auto_install=true`, so Apps Updater itself installs the
       downloaded APK through its own PackageInstaller flow once
       the download verifies — Apps Updater becomes the installer
       of record, which is what later unattended updates require.
       This script then waits until the target package's
       lastUpdateTime changes (requires --package). If the platform
       demands a user confirmation, the install waits on the
       device's confirmation notification and this wait times out.

Prerequisites: adb is connected to the device (any route: USB,
wireless debugging, a relay) and Apps Updater is installed. For
--install-via-updater, Apps Updater's "install unknown apps"
permission must be allowed on the device.

Example:
  python3 tools/adb_update.py \
    --adb /path/to/adb --serial 127.0.0.1:15002 \
    --project phone-node-waker --file phone-node-waker.apk \
    --sha256 <hex of the release asset> --package dev.moriya.phonenode.waker \
    --install-via-updater
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


def shell_resilient(adb: str, serial: str, command: str, timeout: int = 60,
                    attempts: int = 3) -> str:
    """shell() with retries on transient adb/tunnel stalls.

    The tailnet relay path used to reach the Libero occasionally
    stalls a single adb call for tens of seconds while the next one
    answers instantly, so one TimeoutExpired must not abort a run.
    """
    last: Exception | None = None
    for i in range(attempts):
        try:
            return shell(adb, serial, command, timeout=timeout)
        except subprocess.TimeoutExpired as e:
            last = e
            print(f"  (adb call timed out, retry {i + 1}/{attempts}: {command[:60]}...)")
            time.sleep(5)
    raise RuntimeError(f"adb call kept timing out: {command}") from last


def package_info(adb: str, serial: str, package: str) -> dict[str, str]:
    """Selected dumpsys package fields for one package ({} if absent)."""
    out = shell(adb, serial, f"dumpsys package {package}", timeout=15)
    info: dict[str, str] = {}
    for line in out.splitlines():
        line = line.strip()
        for key in ("versionCode=", "versionName=", "lastUpdateTime="):
            if line.startswith(key):
                info[key[:-1]] = line[len(key):].strip()
    return info


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True, help="project (repo) name, e.g. phone-node-waker")
    ap.add_argument("--file", required=True, help="APK file name, e.g. phone-node-waker.apk")
    ap.add_argument("--sha256", required=True, help="expected SHA-256 of the APK (hex)")
    ap.add_argument("--package", default="", help="package name to verify after install")
    ap.add_argument("--serial", default="", help="adb serial (default: adb's default device)")
    ap.add_argument("--adb", default="adb", help="path to the adb binary")
    ap.add_argument("--timeout", type=int, default=600, help="download wait budget, seconds")
    ap.add_argument("--poll", type=int, default=3, help="poll interval, seconds")
    ap.add_argument("--no-install", action="store_true", help="download and verify only")
    ap.add_argument("--install-via-updater", action="store_true",
                    help="Apps Updater itself installs via its PackageInstaller flow "
                         "(auto_install extra, app v0.2.6+); requires --package so the "
                         "install can be verified by lastUpdateTime")
    ap.add_argument("--install-timeout", type=int, default=300,
                    help="seconds to wait for the via-updater install to land")
    ap.add_argument("--no-force-stop", action="store_true",
                    help="do not force-stop Apps Updater before triggering the download")
    args = ap.parse_args()
    sys.stdout.reconfigure(line_buffering=True)

    for value in (args.project, args.file):
        if " " in value or "/" in value or "'" in value:
            print(f"refusing unsafe name: {value!r}", file=sys.stderr)
            return 2
    if args.install_via_updater and args.no_install:
        print("--install-via-updater and --no-install cannot be combined", file=sys.stderr)
        return 2
    if args.install_via_updater and not args.package:
        print("--install-via-updater requires --package (install verification)", file=sys.stderr)
        return 2
    expected = args.sha256.lower()
    remote = f"{REMOTE_DIR}/{args.file}"

    baseline = package_info(args.adb, args.serial, args.package) if args.package else {}

    print(f"[1/3] triggering download of {args.project}/{args.file} in Apps Updater")
    if not args.no_force_stop:
        shell_resilient(args.adb, args.serial, f"am force-stop {PKG}")
    trigger = (
        f"am start -n {PKG}/.MainActivity "
        f"--es auto_project {args.project} --es auto_file {args.file}"
    )
    if args.install_via_updater:
        trigger += " --ez auto_install true"
    out = shell_resilient(args.adb, args.serial, trigger)
    print(out.strip())

    print(f"[2/3] waiting for SHA-256 match on {remote} (timeout {args.timeout}s)")
    deadline = time.time() + args.timeout
    last_seen = ""
    while time.time() < deadline:
        try:
            out = shell(args.adb, args.serial, f"sha256sum {remote}", timeout=20)
        except subprocess.TimeoutExpired:
            out = ""
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

    if args.install_via_updater:
        print(f"[3/3] waiting for Apps Updater's own install of {args.package} "
              f"(timeout {args.install_timeout}s)")
        base_update = baseline.get("lastUpdateTime", "")
        deadline = time.time() + args.install_timeout
        info: dict[str, str] = {}
        while time.time() < deadline:
            try:
                info = package_info(args.adb, args.serial, args.package)
            except Exception:
                info = {}
            landed = bool(info) and (
                not base_update or info.get("lastUpdateTime", "") != base_update
            )
            if landed:
                print(f"  installed {args.package}: versionCode={info.get('versionCode', '?')} "
                      f"versionName={info.get('versionName', '?')} "
                      f"lastUpdateTime={info.get('lastUpdateTime', '?')}")
                return 0
            time.sleep(args.poll)
        print("timed out waiting for the via-updater install; if the device shows "
              "an install-confirmation notification for Apps Updater, the install "
              "is waiting for that tap", file=sys.stderr)
        return 1

    print(f"[3/3] installing with pm install -r (staged via /data/local/tmp)")
    staged = f"/data/local/tmp/{args.file}"
    shell_resilient(args.adb, args.serial, f"cp {remote} {staged}", timeout=120)
    out = shell_resilient(args.adb, args.serial, f"pm install -r {staged}", timeout=300)
    print(out.strip())
    try:
        shell(args.adb, args.serial, f"rm -f {staged}", timeout=30)
    except Exception:
        pass
    if "Success" not in out:
        print("pm install did not report Success", file=sys.stderr)
        return 1

    if args.package:
        info = package_info(args.adb, args.serial, args.package)
        for key in ("versionCode", "versionName"):
            if key in info:
                print(f"  installed {args.package}: {key}={info[key]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
