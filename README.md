# Apps Updater

A small, dependency-free Android app that lists the APKs published in
Moriya's Google Drive `apps` folder and updates them on the device.

## Why

Hand-carrying every new build from Drive to the phone is the slow
step in the develop–test cycle for the phone-node family of apps.
The VM-to-phone adb/Tailscale link is also slow (a few hundred KB/s),
so pushing a 108 MB APK over it is painful. This app inverts the
flow: the phone downloads the APK itself, at its own line speed,
through the Drive app's document provider; adb then only has to say
"install this file that is already here".

## How it works

- One-time setup on the device: open the app, tap 「フォルダを選ぶ」,
  and pick the Drive `apps` folder (SAF grant; the app holds no
  Google credentials — the Drive app brokers access with the phone's
  own account, so the folder stays private). Also allow
  「不明なアプリのインストール」 for this app if you want in-app
  installs.
- The main screen lists every project folder under `apps` and every
  APK inside it, with:
  - Drive size and modification time,
  - the installed version (for known packages, and for any package
    once its APK has been downloaded and parsed),
  - `update.json` metadata when the project folder carries one.
- 「ダウンロード」 copies the APK into internal storage, verifies
  SHA-256 when `update.json` provides one (a mismatch blocks the
  install), and publishes the same bytes to
  `Download/apps-updater/<file>` on shared storage, plus a
  `<file>.done.json` marker (package, version, sha256, size).
- 「インストール」 installs the downloaded copy with
  PackageInstaller, asking for no user action (Android 12+). If the
  platform still demands a confirmation, a high-importance
  notification and the in-app 「インストール確認を開く」 button carry
  it — the routes that survive background-activity-launch
  restrictions (the design phone-node's self-update settled on).

## adb-driven updates

`tools/adb_update.py` runs the whole flow from the VM:

```sh
python3 tools/adb_update.py \
  --adb /path/to/adb --serial <device> \
  --project phone-node-waker --file phone-node-waker.apk \
  --sha256 <hex of the Drive file> --package dev.moriya.phonenode.waker
```

It force-stops the app and starts it with `auto_project` /
`auto_file` intent extras (the app downloads that one item), polls
the SHA-256 of `/sdcard/Download/apps-updater/<file>` until it
matches, then installs and prints the installed version. Two
device findings from the Libero verification (2026-11-14) are baked
in: an intent delivered to the already-running app instance was
silently absorbed without triggering the auto flow (hence the
force-stop, and the app's `singleTop` launch mode since v0.1.1),
and `pm install` cannot read `/sdcard` paths there (FUSE scoped
storage; system_server is denied), so the install stages the APK
through `/data/local/tmp` with an on-device copy first.

## update.json format

Publishers place an `update.json` next to the APK in each project
folder. All fields except `file` are optional; phone-node's legacy
`{"buildId": ..., "file": ...}` form is read as-is.

```json
{
  "file": "phone-node-waker.apk",
  "versionName": "0.1.1",
  "versionCode": 2,
  "sha256": "<hex>",
  "size": 6350454,
  "buildId": 20261114000100
}
```

## Known packages

| APK file | Package |
| --- | --- |
| phonenode-app.apk | dev.moriya.phonenode |
| phonenode-helper.apk | dev.moriya.phonenode.helper |
| phone-node-waker.apk | dev.moriya.phonenode.waker |
| yt-player.apk | app.ytplayer |
| apps-updater.apk | dev.moriya.appsupdater |

Unknown APKs still list, download, and install; their package name
and version are read from the downloaded APK itself.

## Build and handover

Gradle builds run in GitHub Actions on this public repository
(Gradle cannot run on the Muse VM: JVM loopback TCP is intercepted
there). Signing uses one pinned keystore held in repository secrets,
so every build overwrite-installs over the previous one. The fixed
distribution file name is `apps-updater.apk`, published to the Drive
folder `apps/apps-updater` together with its `update.json`. CI also
runs an emulator launch test with screenshots of the main screen.
