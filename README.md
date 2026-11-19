# Apps Updater

A small, dependency-free Android app that lists the APKs published
as GitHub Releases by Moriya's watched repositories and updates
them on the device.

## Why

Hand-carrying every new build to the phone is the slow step in the
develop–test cycle for the phone-node family of apps. The VM-to-
phone adb/Tailscale link is also slow (a few hundred KB/s), so
pushing a 108 MB APK over it is painful. This app inverts the flow:
the phone downloads the APK itself, at its own line speed, over
HTTPS from GitHub; adb then only has to say "install this file
that is already here".

## GitHub Releases source (the only source since v0.2.0)

Until v0.1.13 the app also listed a Google Drive `apps` folder
(granted via SAF). The Drive documents provider repeatedly served
the first-indexed generation of a folder (stale listing metadata
and stale APK bytes for a brand-new document) while `update.json`
read fresh, so GitHub Releases took over row by row from v0.1.8
on, and v0.2.0 removed the Drive source entirely once every
project had a GitHub Release. No folder grant is needed any more.

Repositories are discovered at scan time by searching GitHub for
the owner's repositories carrying the `android` topic
(`user:ysk-agent topic:android`); every `.apk` asset of their
qualifying releases gets a row. A new Android project appears as
soon as its repository carries the topic and a release — no Apps
Updater release in between. The hardcoded `GitHubSource.FILE_REPO`
map remains as the fallback when discovery itself fails.
Selection does not use `/releases/latest` (an unrelated AAR-cache
release holds that slot in the moka-vpn repository); only tags of
the form `vX.Y.Z` qualify, and the highest such tag carrying the
file wins. Downloads are verified against both `update.json`'s
SHA-256 (when the release carries one naming that file) and the
release asset's own `digest` from the GitHub API.

### Scan speed (v0.2.0)

On-device measurements (2026-11-19, timing-test builds) showed the
GitHub phase was ~90% of an 8.2 s scan when repositories were
fetched one after another. v0.2.0 therefore fetches repositories
concurrently (bounded pool of 4, results joined in discovery
order), starts optimistically from the repository list persisted
by the previous successful discovery (those fetches run while
discovery is still in flight; discovery remains authoritative),
and sends `Cache-Control: no-cache` on API requests so a refresh
always reads the current state. On top of that, publishers embed
the `update.json` content in the release body (a line containing
only `<!-- update-json -->`, followed by a fenced ```json block);
when the chosen release carries that block the app parses the
metadata from the body and never fetches the `update.json` asset
(a VM benchmark put the repos phase at a 3,427 ms median with
those fetches vs 930 ms releases-only). Releases without the
marker still fetch the asset. The header keeps the last scan's timing summary
(合計 / GitHub / ローカル) and every phase is logged to logcat
under the `UpdaterTiming` tag.

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

- One-time setup on the device: none for listing. Allow
  「不明なアプリのインストール」 for this app if you want in-app
  installs.
- The main screen lists one section per project (repository) and
  every APK it publishes, with:
  - release asset size and update time,
  - the installed version (for known packages, and for any package
    once its APK has been downloaded and parsed),
  - `update.json` metadata when the release carries one naming
    that file.
- 「ダウンロード」 downloads the APK into the app's internal
  downloads directory (the single APK storage location), verifies
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
  --sha256 <hex of the published APK> --package dev.moriya.phonenode.waker
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

Publishers attach an `update.json` asset to each release. All
fields except `file` are optional; phone-node's legacy
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
| mokavpn.apk | dev.moriya.vpn |
| findmy-mac.apk | dev.moriya.findmymac |
| yt-client.apk | app.ytclient |

Unknown APKs still list, download, and install; their package name
and version are read from the downloaded APK itself.

## Build and handover

Gradle builds run in GitHub Actions on this public repository
(Gradle cannot run on the Muse VM: JVM loopback TCP is intercepted
there). Signing uses one pinned keystore held in repository secrets,
so every build overwrite-installs over the previous one. The fixed
distribution file name is `apps-updater.apk`, published as a
GitHub Release asset together with its `update.json`. CI also
runs an emulator launch test with screenshots of the main screen.
