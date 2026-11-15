package dev.moriya.appsupdater

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases source (pilot in v0.1.8 for moka-vpn only,
 * generalised to every known project in v0.1.9).
 *
 * Why this exists: the on-device Drive documents provider
 * repeatedly served a stale generation of an APK while update.json
 * already described the new one (moto g05, 2026-11-14/15) — the
 * provider's folder view appears pinned to the first generation it
 * indexed, and there is no app-side way to force it current. A
 * public GitHub Release has no such layer: the asset bytes come
 * over HTTPS straight from GitHub, and the API reports a SHA-256
 * `digest` for every asset, so the download is verified twice
 * (against update.json and against the digest) before it can be
 * installed.
 *
 * Release selection deliberately does NOT use /releases/latest:
 * the moka-vpn repository carries a libtailscale AAR-cache release
 * whose tag is not a version, and that release holds "Latest".
 * Only tags of the form vX.Y.Z qualify, and among those the
 * highest version that actually carries the requested file wins.
 * A release's update.json asset is honoured only when it names
 * the requested file (a release can carry several APKs; only the
 * primary one's metadata lives in update.json for now — other
 * files fall back to asset size + digest verification).
 *
 * All repositories are public; no token is used or needed.
 */
object GitHubSource {

    /** APK file name -> "owner/repo" that publishes it as Release
     *  assets. A file listed here gets a GitHub row whenever a
     *  matching release is reachable, replacing its Drive row. */
    val FILE_REPO: Map<String, String> = mapOf(
        "mokavpn.apk" to "ysk-agent/moka-vpn",
        "apps-updater.apk" to "ysk-agent/apps-updater",
        "phonenode-app.apk" to "ysk-agent/phone-node",
        "phonenode-helper.apk" to "ysk-agent/phone-node",
        "phone-node-waker.apk" to "ysk-agent/phone-node-waker",
        "yt-player.apk" to "ysk-agent/yt-player"
    )

    private val VERSION_TAG = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")

    /** Chosen release per repo, cached for the process lifetime of
     *  one scan: several files can share a repo (phone-node), and
     *  re-listing releases per file would multiply API calls. The
     *  cache is keyed by repo and stores the parsed releases array
     *  once; selection per file happens against it. */
    private val releasesCache = mutableMapOf<String, JSONArray?>()

    /** The GitHub-sourced item for [fileName], or null when the
     *  file is not a known release artifact, no qualifying release
     *  carries it, or GitHub is unreachable (callers then keep the
     *  Drive row). */
    fun fetchItem(fileName: String): ApkItem? {
        val repo = FILE_REPO[fileName] ?: return null
        val releases = releasesFor(repo) ?: return null

        var best: JSONObject? = null
        var bestKey: LongArray? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val tag = release.optString("tag_name")
            val match = VERSION_TAG.matchEntire(tag) ?: continue
            val assets = release.optJSONArray("assets") ?: continue
            if (findAsset(assets, fileName) == null) continue
            val key = longArrayOf(
                match.groupValues[1].toLong(),
                match.groupValues[2].toLong(),
                match.groupValues[3].toLong()
            )
            if (bestKey == null || key.compareVersionTo(bestKey!!) > 0) {
                best = release
                bestKey = key
            }
        }
        val release = best ?: return null
        val assets = release.optJSONArray("assets") ?: return null
        val apkAsset = findAsset(assets, fileName) ?: return null

        // update.json, when the release carries one AND it names
        // this file, supplies the version metadata.
        var update: UpdateInfo? = null
        val updateAsset = findAsset(assets, "update.json")
        if (updateAsset != null) {
            val url = updateAsset.optString("browser_download_url")
            if (url.isNotEmpty()) {
                val text = httpGetText(url)
                if (text != null) {
                    val parsed = Store.parseUpdate(text)
                    if (parsed != null && parsed.file == fileName) update = parsed
                }
            }
        }

        val digest = apkAsset.optString("digest")
            .removePrefix("sha256:").lowercase().ifEmpty { null }
        val assetSize = apkAsset.optLong("size", -1L)
        return ApkItem(
            project = repo.substringAfter('/'),
            fileName = fileName,
            docUri = null,
            size = if (assetSize > 0) assetSize else (update?.size ?: -1L),
            modified = parseIsoTime(apkAsset.optString("updated_at")),
            update = update,
            source = "GitHub",
            downloadUrl = apkAsset.optString("browser_download_url").ifEmpty { null },
            assetDigest = digest
        )
    }

    private fun releasesFor(repo: String): JSONArray? {
        synchronized(releasesCache) {
            if (releasesCache.containsKey(repo)) return releasesCache[repo]
        }
        val text = httpGetText("https://api.github.com/repos/$repo/releases?per_page=30")
        val parsed = text?.let {
            try {
                JSONArray(it)
            } catch (e: Exception) {
                null
            }
        }
        synchronized(releasesCache) { releasesCache[repo] = parsed }
        return parsed
    }

    private fun findAsset(assets: JSONArray, name: String): JSONObject? {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == name) return asset
        }
        return null
    }

    private fun LongArray.compareVersionTo(other: LongArray): Int {
        for (i in indices) {
            if (this[i] != other[i]) return if (this[i] > other[i]) 1 else -1
        }
        return 0
    }

    private fun parseIsoTime(text: String?): Long {
        if (text.isNullOrEmpty()) return 0L
        return try {
            java.time.Instant.parse(text).toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    /** Small text GET for API/update.json payloads. Asset bytes do
     *  NOT go through here — Store streams those with progress and
     *  hashing. Returns null on any failure: GitHub is an optional
     *  source and must never break the Drive listing. */
    private fun httpGetText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AppsUpdater")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
