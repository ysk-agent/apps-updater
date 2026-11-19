package dev.moriya.appsupdater

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * GitHub Releases source — the ONLY source since v0.2.0 (the
 * Drive/SAF source was removed; see below). Piloted on moka-vpn
 * in v0.1.8, generalised to every known project in v0.1.9,
 * repository discovery added in v0.1.12.
 *
 * Why this exists: the on-device Drive documents provider
 * repeatedly served a stale generation of an APK while update.json
 * already described the new one (moto g05, 2026-11-14/15) — the
 * provider's folder view appears pinned to the first generation it
 * indexed, and there is no app-side way to force it current. A
 * public GitHub Release has no such layer: the asset bytes come
 * over HTTPS straight from GitHub, and the API reports a SHA-256
 * `digest` for every asset, so the downloaded bytes are verified
 * against it before they can be installed.
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
 * The scan never downloads the update.json asset (publishers
 * still attach one for other tooling): everything it needs is in
 * the releases record — versionName from the tag, size and
 * SHA-256 digest from the APK asset — plus the installed version
 * from PackageManager. Update judgement therefore compares
 * versionName tuples, not versionCodes (see Store.versionTuple);
 * all watched projects publish monotonic X.Y.Z names, so ordering
 * by name is sound. A file that update.json never named
 * (phone-node's helper APK) is unaffected: its verification was
 * always the asset digest.
 *
 * Scan speed (v0.2.0): on-device measurements (timing-test builds,
 * 2026-11-19) showed the GitHub phase was ~90% of an 8.2s scan
 * when repositories were fetched one after another. Three
 * measures, all verified on the timing-test branch:
 *  - repositories are fetched concurrently on a bounded pool
 *    (8 threads — every watched repository in a single wave),
 *    futures joined in discovery order so the item order is
 *    identical to the sequential version's;
 *  - the scan starts optimistically from the repository list the
 *    previous successful discovery persisted (see [fetchAll]):
 *    those fetches run while discovery is still in flight;
 *  - API requests carry Cache-Control: no-cache, so a refresh
 *    always reads the current state;
 *  - the scan performs NO update.json requests at all (see the
 *    selection note above): a VM benchmark put the repos phase
 *    at a 3,427ms median with per-file update.json fetches vs
 *    930ms releases-only, so this removes the dominant remaining
 *    cost and one request per published file.
 *
 * Package learning (v0.2.1): the installed-version column needs
 * each file's package name. The curated map in Store predates the
 * newest projects (findmy-mac.apk, yt-client.apk were missing, so
 * their rows showed no installed version), so after the items are
 * built, any file whose package is still unknown is probed once:
 * a few HTTP Range requests read the package name out of the
 * asset's own AndroidManifest.xml (see PackageProbe) and the
 * result is persisted. The probe fetches ranges of the APK asset
 * only — update.json stays out of the scan.
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
     *  when topic discovery fails or finds nothing AND no persisted
     *  repository list exists; discovery is the primary source. */
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

    /** One lock object per repo, so concurrent workers fetching
     *  DIFFERENT repos never block each other, while two workers
     *  asking for the SAME repo serialise and the second one reuses
     *  the first's fetch instead of issuing a duplicate request.
     *  Cleared with [releasesCache] at the start of every scan. */
    private val repoFetchLocks = mutableMapOf<String, Any>()

    private fun repoFetchLock(repo: String): Any =
        synchronized(repoFetchLocks) { repoFetchLocks.getOrPut(repo) { Any() } }

    // -- persisted repository list (optimistic start) ---------------

    private const val PREFS = "appsupdater-github"
    private const val KEY_KNOWN_REPOS = "known_repos"

    /** The repository list the last successful discovery returned,
     *  persisted across process restarts; empty on first run. */
    private fun loadKnownRepos(context: Context): List<String> =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_KNOWN_REPOS, null)
            ?.split('\n')?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun saveKnownRepos(context: Context, repos: List<String>) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_KNOWN_REPOS, repos.joinToString("\n")).apply()
    }

    // -- scan timing --------------------------------------------------

    /** Breakdown of the most recent [fetchAll] scan, written
     *  only by the thread that called fetchAll. */
    class ScanTimings {
        var discoveryMs: Long? = null
        var totalMs: Long? = null
    }

    @Volatile
    var lastScanTimings: ScanTimings = ScanTimings()
        private set

    // -- scan -----------------------------------------------------------

    /** Every GitHub-sourced item for one scan.
     *
     *  Optimistic start (v0.2.0): when a previous scan persisted a
     *  repository list, fetches for those repositories are
     *  submitted immediately and discovery runs CONCURRENTLY
     *  instead of first. Discovery remains authoritative: a repo
     *  that discovery no longer returns is dropped (its in-flight
     *  items are discarded), a newly discovered repo is fetched
     *  when discovery returns, and the list is re-persisted. A
     *  repository that was renamed since the last scan is simply a
     *  failed fetch under its old name plus a normal fetch under
     *  its new one — the user explicitly accepted that optimism.
     *  When discovery fails or returns nothing, the persisted
     *  list's results are used as-is; with no persisted list either,
     *  the hardcoded [FILE_REPO] map is the fallback. The first-ever
     *  scan (no persisted list) is discovery-first, exactly like
     *  v0.1.13 plus parallel fetching. */
    fun fetchAll(context: Context): List<ApkItem> {
        val totalStart = Timing.now()
        val timings = ScanTimings()
        lastScanTimings = timings
        // Per-scan cache: every scan starts from fresh release lists.
        synchronized(releasesCache) { releasesCache.clear() }
        synchronized(repoFetchLocks) { repoFetchLocks.clear() }

        val pool = Executors.newFixedThreadPool(8)
        try {
            val known = loadKnownRepos(context)
            Timing.logLine("github optimistic repos ${known.size}")
            // Optimistic submissions: known repos start fetching
            // NOW, before (and while) discovery runs.
            val optimistic = LinkedHashMap<String, Future<List<ApkItem>>>()
            for (repo in known) {
                optimistic[repo] = submitRepo(pool, repo)
            }

            val discoveryStart = Timing.now()
            val discovered = discoverRepos()
            timings.discoveryMs = Timing.now() - discoveryStart
            Timing.log("github discovery", timings.discoveryMs!!)

            val result: List<ApkItem> = when {
                !discovered.isNullOrEmpty() -> {
                    saveKnownRepos(context, discovered)
                    val futures = LinkedHashMap<String, Future<List<ApkItem>>>()
                    for (repo in discovered) {
                        futures[repo] = optimistic.remove(repo) ?: submitRepo(pool, repo)
                    }
                    // Whatever is left in `optimistic` belongs to
                    // repositories discovery no longer returns:
                    // their results are discarded by never joining
                    // those futures.
                    joinInOrder(futures)
                }
                known.isNotEmpty() -> joinInOrder(optimistic)
                else -> FILE_REPO.keys.mapNotNull { fileName ->
                    try {
                        fetchItem(fileName)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            // Package learning (v0.2.1): rows whose package is in
            // neither the curated map nor the learned map get one
            // range probe of their own asset (see PackageProbe),
            // on this same pool, before the scan is reported done —
            // so the rows the caller renders already know their
            // installed version and update judgement.
            learnUnknownPackages(context, result, pool)
            timings.totalMs = Timing.now() - totalStart
            Timing.log("github total", timings.totalMs!!)
            return result
        } finally {
            pool.shutdown()
        }
    }

    /** Probe the assets of every item whose package name is still
     *  unknown, in parallel on the scan pool, and persist what was
     *  learned. Files already covered by the curated map or a
     *  previous probe are never probed again. A failed probe
     *  changes nothing: the row renders exactly as in v0.2.0 and
     *  the next scan retries. */
    private fun learnUnknownPackages(
        context: Context,
        items: List<ApkItem>,
        pool: ExecutorService
    ) {
        val targets = items
            .filter { it.downloadUrl != null && it.size > 0 }
            .distinctBy { it.fileName }
            .filter { Store.packageFor(context, it.fileName) == null }
        if (targets.isEmpty()) return
        val futures = targets.map { item ->
            item to pool.submit(Callable<PackageProbe.Outcome> {
                val start = Timing.now()
                val outcome = PackageProbe.probe(item.downloadUrl!!, item.size)
                Timing.logLine(
                    "package probe ${item.fileName}: " +
                        (outcome.packageName ?: "failed") +
                        " (${outcome.requests} req, ${outcome.bytes} B, " +
                        "${Timing.now() - start}ms)"
                )
                outcome
            })
        }
        for ((item, future) in futures) {
            val outcome = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            if (outcome?.packageName != null) {
                Store.learnPackage(context, item.fileName, outcome.packageName)
            }
        }
    }

    private fun submitRepo(
        pool: ExecutorService, repo: String
    ): Future<List<ApkItem>> =
        pool.submit(Callable<List<ApkItem>> {
            try {
                fetchRepoItems(repo)
            } catch (e: Exception) {
                // One bad repository must not sink the whole scan.
                emptyList()
            }
        })

    /** Join futures in map (discovery / persisted-list) order, so
     *  the item order is identical to the sequential version's. */
    private fun joinInOrder(
        futures: Map<String, Future<List<ApkItem>>>
    ): List<ApkItem> {
        val items = mutableListOf<ApkItem>()
        for ((_, future) in futures) {
            try {
                items += future.get()
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
     *  unreachable. */
    private fun fetchItem(fileName: String, repo: String): ApkItem? {
        val releases = releasesFor(repo) ?: return null

        var best: JSONObject? = null
        var bestKey: LongArray? = null
        var bestTag: String? = null
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
                bestTag = tag
            }
        }
        val release = best ?: return null
        val assets = release.optJSONArray("assets") ?: return null
        val apkAsset = findAsset(assets, fileName) ?: return null

        val digest = apkAsset.optString("digest")
            .removePrefix("sha256:").lowercase().ifEmpty { null }
        val assetSize = apkAsset.optLong("size", -1L)
        // Releases-only metadata (v0.2.0): the scan NEVER fetches
        // the update.json asset. Everything it needs is already in
        // this release record — versionName from the tag, size and
        // SHA-256 digest from the asset — plus the installed
        // version from PackageManager on the UI side. (A VM
        // benchmark put the repos phase at a 3,427ms median with
        // per-file update.json fetches vs 930ms releases-only; the
        // fetches were the dominant remaining scan cost.)
        // Publishers still attach update.json for other tooling.
        // The synthesized UpdateInfo carries the digest as its
        // sha256, so download verification, the install gate and
        // status keying work exactly as when it came from
        // update.json (both state the APK's SHA-256). Files that
        // update.json never named (phone-node's helper) are
        // unaffected: their verification was always the digest.
        val update = UpdateInfo(
            file = fileName,
            buildId = null,
            versionName = bestTag?.removePrefix("v"),
            versionCode = null,
            sha256 = digest,
            size = if (assetSize > 0) assetSize else null
        )
        return ApkItem(
            project = repo.substringAfter('/'),
            fileName = fileName,
            size = if (assetSize > 0) assetSize else -1L,
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
        // Serialise on this repo's own lock only. Different repos
        // fetch in parallel; a second caller for the same repo
        // waits, then re-checks the cache and reuses the first
        // caller's result — the releases list is never fetched
        // twice for one repo in one scan.
        synchronized(repoFetchLock(repo)) {
            synchronized(releasesCache) {
                if (releasesCache.containsKey(repo)) return releasesCache[repo]
            }
            // Only real fetches are timed; per-scan cache hits
            // return above without a log line.
            val fetchStart = Timing.now()
            val text = httpGetText("https://api.github.com/repos/$repo/releases?per_page=30")
            val parsed = text?.let {
                try {
                    JSONArray(it)
                } catch (e: Exception) {
                    null
                }
            }
            Timing.log("github releases $repo", Timing.now() - fetchStart)
            // Cache only successful parses: a failed fetch must not
            // poison the rest of this scan (or any later one).
            if (parsed != null) {
                synchronized(releasesCache) { releasesCache[repo] = parsed }
            }
            return parsed
        }
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

    /** Small text GET for API payloads (discovery + release
     *  lists). Asset bytes do NOT go through here — Store streams
     *  those with progress and hashing. Returns null on any
     *  failure: GitHub being unreachable must never break the
     *  listing. Cache-Control: no-cache makes every refresh read
     *  the current state instead of an HTTP-cached response
     *  (v0.2.0). */
    private fun httpGetText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AppsUpdater")
                setRequestProperty("Cache-Control", "no-cache")
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
