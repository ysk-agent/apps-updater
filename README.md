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

## GitHub Releases source

Since v0.1.8 (moka-vpn pilot) and generalised in v0.1.9, every APK
with a known publishing repository (see `GitHubSource.FILE_REPO`:
moka-vpn, apps-updater, phone-node, phone-node-waker, yt-player)
is fetched from GitHub Releases (tags of the form `vX.Y.Z`) over
plain HTTPS instead of through the Drive provider: on a real
device the provider kept serving the first-indexed generation of
a folder (stale listing metadata and stale APK bytes for a
brand-new document) while `update.json` read fresh. A GitHub row
replaces the Drive row for the same file whenever it can be
fetched; the Drive row remains as the offline fallback. Downloads
are verified against both `update.json`'s SHA-256 (when the
release carries one naming that file) and the release asset's own
`digest` from the GitHub API. Selection does not use
`/releases/latest` (an unrelated AAR-cache release holds that slot
in the moka-vpn repository); the highest `vX.Y.Z` tag carrying the
file wins.

For Drive items, a preflight guard compares the listing size with
`update.json`'s stated size before downloading: a disagreement
means the provider's view is stale, and the download is refused
before any byte moves (the stale bytes would only be discarded on
hash mismatch anyway, at metered-line cost).

## Background downloads (v0.1.9)

Downloads run in `DownloadService`, a foreground service with a
progress notification. Before v0.1.9 they ran on a bare activity
thread, and the system aborted the socket as soon as the app was
backgrounded (`SocketException: Software caused connection
abort`, observed on a real device during a 28 MB release
download). The CI emulator test exercises this path end to end:
it serves a payload from the runner host (emulator address
`10.0.2.2`, the one cleartext exception in the network security
config) through MainActivity's debug-only test extras, and checks
the done marker, the published bytes' SHA-256, and that a
mismatched download is discarded with no marker and no published
file.

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
| findmy-mac.apk | dev.moriya.findmymac |

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
