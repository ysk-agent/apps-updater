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
file wins. Downloads are verified against the release asset's
own `digest` from the GitHub API. The scan never downloads the
`update.json` asset (it is still published with each release for
other tooling): the latest version name comes from the tag, and
update judgement compares version-name tuples against the
installed app's version name (see "Scan speed").

### Scan speed (v0.2.0)

On-device measurements (2026-11-19, timing-test builds) showed the
GitHub phase was ~90% of an 8.2 s scan when repositories were
fetched one after another. v0.2.0 therefore fetches repositories
concurrently (bounded pool of 8 — every watched repository in a
single wave — results joined in discovery order), starts
optimistically from the repository list persisted by the previous
successful discovery (those fetches run while discovery is still
in flight; discovery remains authoritative), and sends
`Cache-Control: no-cache` on API requests so a refresh always
reads the current state. The scan also performs no `update.json`
requests at all: a VM benchmark put the repos phase at a
3,427 ms median with per-file `update.json` fetches vs 930 ms
releases-only, and everything the scan needs (latest version
name, size, SHA-256 digest) is already in the releases record.
Finally, the last completed scan's rows are persisted locally and
rendered immediately on open and on refresh (stale-while-
revalidate); the fresh scan replaces them when it lands, and
downloads started from a stale row stay safe because the bytes
are verified against the asset digest after download. The header keeps the last scan's timing summary
(合計 / GitHub / ローカル) and every phase is logged to logcat
under the `UpdaterTiming` tag.

### Rate limits, optional token, failed scans (v0.2.2)

The GitHub API is called unauthenticated by default, which
allows 60 requests/hour **per IP address** — on a shared
carrier-NAT address, other users burn the same quota (observed
2026-11-19: the quota was exhausted twice in one evening and
scans came back empty). The header's 「トークン設定」 button
therefore accepts an optional personal GitHub token: it is
stored only in the app's private preferences and sent only as
the `Authorization` header of `api.github.com` requests (asset
downloads and the package-probe ranges never receive it),
raising the limit to 5,000 requests/hour for the token's own
account. Each scan logs only the mode (`github auth token` /
`github auth none`) under `UpdaterTiming`, never the value.

A scan whose API requests fail records the failure (HTTP
status, and the `X-RateLimit-Reset` time on 403/429). When such
a scan comes back empty, the list is no longer wiped: the
persisted rows stay on screen under a notice such as
「更新に失敗しました（GitHub のレート制限、リセット 22:04）。前回の結果を表示しています。」,
or, with no persisted scan yet, the failure message itself is
shown instead of 「APK が見つかりませんでした」. A failed scan
never overwrites the persisted last scan or the persisted
repository list.

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

## Parallel downloads (v0.2.3)

A single HTTPS stream from the release-asset host plateaus far
below the line's capacity (observed on a real device: ~7 MB/s on
a line measured at ~250 Mbps), because one TCP flow over a
long-RTT path cannot fill the pipe. Downloads of 4 MiB or more
are therefore split into Range segments (about 2 MiB each, at
most 8 connections) fetched in parallel and reassembled by
offset; the SHA-256 is then computed over the reassembled file
and verified exactly as before. Any segment failure — or a
server that ignores Range — falls back to the original single
stream, so parallel support can only make a download faster,
never impossible. Each download logs its mode and segment count
under the `UpdaterTiming` tag, and the CI emulator test serves
its payload through a Range-capable server (`ci/range_server.py`)
and asserts the parallel path was actually used.

## How it works

- One-time setup on the device: none for listing. Allow
  「不明なアプリのインストール」 for this app if you want in-app
  installs.
- The main screen lists one section per project (repository) and
  every APK it publishes, with:
  - release asset size and update time,
  - the installed version (for known packages, and for any other
    package once the app has learned it — see "Known packages"
    below), with a 「・更新あり」 mark when the latest
    version-name tuple is strictly newer than the installed one,
  - the latest version name from the release tag and whether the
    asset carries a SHA-256 digest.
- 「ダウンロード」 downloads the APK into the app's internal
  downloads directory (the single APK storage location), verifies
  SHA-256 against the release asset's digest (a mismatch blocks
  the install), and publishes the same bytes to
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

Publishers attach an `update.json` asset to each release. Since
v0.2.0 the app itself no longer downloads it during scans (the
releases record carries everything the scan needs); the file
remains published for any other tooling that reads it. All
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

Since v0.2.1 the table is only the curated seed. For a file that
is in neither the table nor the learned map, the scan probes the
release asset itself once — a few HTTP Range requests read the
ZIP central directory and just the `AndroidManifest.xml` entry
(never the whole APK, and never update.json) — and persists the
learned file → package mapping on the device. A learned value
never overrides a table entry. Unknown APKs whose probe fails
still list, download, and install exactly as before; their
package name is then read from the downloaded APK itself.

## Build and handover

Gradle builds run in GitHub Actions on this public repository
(Gradle cannot run on the Muse VM: JVM loopback TCP is intercepted
there). Signing uses one pinned keystore held in repository secrets,
so every build overwrite-installs over the previous one. The fixed
distribution file name is `apps-updater.apk`, published as a
GitHub Release asset together with its `update.json`. CI also
runs an emulator launch test with screenshots of the main screen.

## Download transport (v0.2.4)

Downloads moved from the platform `HttpURLConnection` stack to
OkHttp. Measured on the user's moto g05 (2026-11-21): the old
stack plateaued at ~5 MB/s for release assets — single-stream
and 8-way parallel alike, app CPU idle, WiFi negotiated at
390 Mbps, storage writing at 746 MB/s — while Chrome pulled the
same asset at ~21 MB/s on the same phone and a wired Mac on the
same gateway at ~36 MB/s single-flow. The bottleneck was the
transport, not the network and not the flow count. The scan /
GitHub API path still uses `HttpURLConnection` (small JSON,
unaffected). Segmented parallel download from v0.2.3 is kept.
