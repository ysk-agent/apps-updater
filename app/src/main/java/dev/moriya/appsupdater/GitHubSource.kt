package dev.moriya.appsupdater

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub Releases source (pilot in v0.1.8 for moka-vpn only,
 * generalised to every known project in v0.1.9, repository
 * discovery added in v0.1.12).
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
 * Discovery (v0.1.12): the repositories to watch are no longer
 * only the hardcoded [FILE_REPO] map. The app searches GitHub for
 * the owner's repositories carrying the `android` topic
 * (search/repositories?q=user:ysk-agent+topic:android) and, for
 * each discovered repository, enumerates the `.apk` assets of its
 * qualifying releases. A new Android project then appears in the
 * list as soon as its repository carries the topic and a release —
 * with no Apps Updater release of its own in between (the failure
 * that hid yt-client in v0.1.10/v0.1.11). [FILE_REPO] remains as
 * the fallback: when discovery fails (unreachable, rate limited)
 * or returns nothing at all, the scan falls back to the hardcoded
 * map so the GitHub rows never silently vanish.
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
 * All repositories are public; no token is used or needed. The
 * unauthenticated search API allows 10 requests/minute, far above
 * this app's user-triggered refresh rate.
 */
object GitHubSource {

    /** Account whose `android`-topic repositories are watched. */
    const val OWNER = "ysk-agent"
    const val TOPIC = "android"

    /** APK file name -> "owner/repo" that publishes it as Release
     *  assets. Since v0.1.12 this map is the FALLBACK, used only
     *  when topic discovery fails or finds nothing; discovery is
     *  the primary source. A file listed here gets a GitHub row
     *  whenever a matching release is reachable, replacing its
     *  Drive row. */
    val FILE_REPO: Map<String, String> = mapOf(
        "mokavpn.apk" to "ysk-agent/moka-vpn",
        "apps-updater.apk" to "ysk-agent/apps-updater",
        "phonenode-app.apk" to "ysk-agent/phone-node",
        "phonenode-helper.apk" to "ysk-agent/phone-node",
        "phone-node-waker.apk" to "ysk-agent/phone-node-waker",
        "yt-player.apk" to "ysk-agent/yt-player",
        "findmy-mac.apk" to "ysk-agent/findmy-mac",
        "yt-client.apk" to "ysk-agent/yt-client-android"
    )

    private val VERSION_TAG = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")

    /** Parsed releases array per repo, cached for ONE scan only:
     *  several files can share a repo (phone-node), and re-listing
     *  releases per file would multiply API calls. [fetchAll]
     *  clears the cache at the start of every scan, so each scan
     *  fetches fresh release lists; only successfully parsed
     *  arrays are stored, so a failed fetch never poisons a scan. */
    private val releasesCache = mutableMapOf<String, JSONArray>()

    /** Every GitHub-sourced item for one scan: topic discovery
     *  first, the hardcoded [FILE_REPO] map as fallback when
     *  discovery fails or returns no repositories. */
    fun fetchAll(): List<ApkItem> {
        // Per-scan cache: every scan starts from fresh release lists.
        synchronized(releasesCache) { releasesCache.clear() }
        val repos = discoverRepos()
        if (repos.isNullOrEmpty()) {
            return FILE_REPO.keys.mapNotNull { fileName ->
                try {
                    fetchItem(fileName)
                } catch (e: Exception) {
                    null
                }
            }
        }
        val items = mutableListOf<ApkItem>()
        for (repo in repos) {
            try {
                items += fetchRepoItems(repo)
            } catch (e: Exception) {
                // One bad repository must not sink the whole scan.
            }
        }
        return items
    }

    /** The repositories carrying the watched topic, as
     *  "owner/repo" strings, or null when the search could not be
     *  completed or parsed (the caller then falls back). An empty
     *  list is returned as-is; the caller treats it like failure,
     *  because a healthy account always has tagged repositories. */
    private fun discoverRepos(): List<String>? {
        val query = URLEncoder.encode("user:$OWNER topic:$TOPIC", "UTF-8")
        val text = httpGetText(
            "https://api.github.com/search/repositories?q=$query&per_page=100"
        ) ?: return null
        return try {
            val items = JSONObject(text).optJSONArray("items") ?: return null
            val repos = mutableListOf<String>()
            for (i in 0 until items.length()) {
                val fullName = items.optJSONObject(i)?.optString("full_name")
                if (!fullName.isNullOrEmpty()) repos += fullName
            }
            repos
        } catch (e: Exception) {
            null
        }
    }

    /** Every APK row one repository publishes: the `.apk` asset
     *  names across its qualifying releases, each resolved to the
     *  highest qualifying version that carries it. */
    private fun fetchRepoItems(repo: String): List<ApkItem> {
        val releases = releasesFor(repo) ?: return emptyList()
        val fileNames = linkedSetOf<String>()
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (!isQualifying(release)) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val name = assets.optJSONObject(j)?.optString("name") ?: continue
                if (name.endsWith(".apk", ignoreCase = true)) fileNames += name
            }
        }
        return fileNames.mapNotNull { fileName ->
            try {
                fetchItem(fileName, repo)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun isQualifying(release: JSONObject): Boolean {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return false
        return VERSION_TAG.matches(release.optString("tag_name"))
    }

    /** The GitHub-sourced item for [fileName] published by the
     *  repository in [FILE_REPO], or null when the file is not a
     *  known release artifact. Used by the fallback path. */
    fun fetchItem(fileName: String): ApkItem? {
        val repo = FILE_REPO[fileName] ?: return null
        return fetchItem(fileName, repo)
    }

    /** The GitHub-sourced item for [fileName] in [repo], or null
     *  when no qualifying release carries it or GitHub is
     *  unreachable (callers then keep the Drive row). */
    private fun fetchItem(fileName: String, repo: String): ApkItem? {
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
        // Cache only successful parses: a failed fetch must not
        // poison the rest of this scan (or any later one).
        if (parsed != null) {
            synchronized(releasesCache) { releasesCache[repo] = parsed }
        }
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
