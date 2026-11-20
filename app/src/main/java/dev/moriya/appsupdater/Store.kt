package dev.moriya.appsupdater

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One APK offered by the GitHub Releases source (the only
 * source since v0.2.0; the Drive/SAF source was removed).
 *
 * Items carry a [downloadUrl] and the release asset's own SHA-256
 * [assetDigest] as reported by the GitHub API; [update] holds the
 * release metadata synthesized from the release record (version
 * name from the tag, the digest as its sha256 — see GitHubSource),
 * so the download is verified against the asset record's hash. */
data class ApkItem(
    val project: String,
    val fileName: String,
    val size: Long,
    val modified: Long,
    val update: UpdateInfo?,
    val source: String = "GitHub",
    val downloadUrl: String? = null,
    val assetDigest: String? = null
)

data class UpdateInfo(
    val file: String,
    val buildId: Long?,
    val versionName: String?,
    val versionCode: Long?,
    val sha256: String?,
    val size: Long?
)

/** State shared between MainActivity and InstallReceiver. */
object AppState {
    @Volatile var pendingConfirmIntent: Intent? = null
    @Volatile var pendingConfirmFile: String? = null
}

object Store {
    private const val PREFS = "appsupdater-prefs"
    private const val KEY_STATUS_PREFIX = "status_"
    private const val KEY_STATUS_VER_PREFIX = "statusver_"
    private const val KEY_SHA_PREFIX = "sha_"

    const val ACTION_INSTALL_STATUS = "dev.moriya.appsupdater.INSTALL_STATUS"
    const val EXTRA_FILE = "file"
    const val DOWNLOADS_REL_PATH = "Download/apps-updater"
    const val DOWNLOADS_ABS_DIR = "/sdcard/Download/apps-updater"

    /** APK file name -> package name, for installed-version display. */
    val KNOWN_PACKAGES = mapOf(
        "phonenode-app.apk" to "dev.moriya.phonenode",
        "phonenode-helper.apk" to "dev.moriya.phonenode.helper",
        "phone-node-waker.apk" to "dev.moriya.phonenode.waker",
        "yt-player.apk" to "app.ytplayer",
        "apps-updater.apk" to "dev.moriya.appsupdater",
        "mokavpn.apk" to "dev.moriya.vpn"
    )

    private const val KEY_PKG_PREFIX = "pkg_"

    /** Package name learned by probing the release asset itself
     *  (v0.2.1, see PackageProbe), persisted per file name so the
     *  probe runs at most once per file. */
    fun learnedPackage(context: Context, fileName: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PKG_PREFIX + fileName, null)

    fun learnPackage(context: Context, fileName: String, packageName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PKG_PREFIX + fileName, packageName).apply()
    }

    /**
     * The package name for [fileName], from the curated
     * [KNOWN_PACKAGES] map or, failing that, the learned map. A
     * learned value never silently overrides a known entry: the
     * map is curated and the probe is heuristic, so on disagreement
     * the known entry wins and the disagreement is logged.
     */
    fun packageFor(context: Context, fileName: String): String? {
        val known = KNOWN_PACKAGES[fileName]
        val learned = learnedPackage(context, fileName)
        if (known != null) {
            if (learned != null && learned != known) {
                Timing.logLine(
                    "package learned $learned for $fileName disagrees with known $known; using known"
                )
            }
            return known
        }
        return learned
    }

    // -- prefs ------------------------------------------------------------

    /**
     * Persist a per-file status together with the key of the
     * published artifact it was recorded against. Statuses outlive
     * the release they belong to otherwise: a "SHA-256 mismatch"
     * recorded against v0.1.2 was still displayed under v0.1.4's
     * metadata on a real device, long after it stopped describing
     * the current file. [statusFor] hides a status whose key no
     * longer matches the artifact now being offered.
     */
    fun setStatus(context: Context, fileName: String, status: String, versionKey: String? = null) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATUS_PREFIX + fileName, status)
        if (versionKey != null) {
            edit.putString(KEY_STATUS_VER_PREFIX + fileName, versionKey)
        } else {
            edit.remove(KEY_STATUS_VER_PREFIX + fileName)
        }
        edit.apply()
    }

    fun status(context: Context, fileName: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS_PREFIX + fileName, null)

    private fun statusKey(context: Context, fileName: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS_VER_PREFIX + fileName, null)

    /** Key identifying the artifact an [ApkItem] currently offers. */
    fun statusKeyFor(item: ApkItem): String? =
        item.update?.sha256 ?: item.update?.buildId?.toString()
            ?: item.update?.versionCode?.toString()

    /** The persisted status, unless it was recorded against a
     *  different artifact than the one [item] now offers. */
    fun statusFor(context: Context, item: ApkItem): String? {
        val text = status(context, item.fileName) ?: return null
        val offered = listOfNotNull(
            item.update?.versionCode?.toString(),
            item.update?.buildId?.toString(),
            item.update?.sha256
        )
        if (offered.isEmpty()) return text
        val recorded = statusKey(context, item.fileName)
        return if (recorded != null && recorded in offered) text else null
    }

    fun setDownloadedSha(context: Context, fileName: String, sha: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SHA_PREFIX + fileName, sha).apply()
    }

    fun downloadedSha(context: Context, fileName: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SHA_PREFIX + fileName, null)

    fun downloadedFile(context: Context, fileName: String): File =
        File(File(context.filesDir, "downloads").apply { mkdirs() }, fileName)

    // -- version tuples -----------------------------------------------------
    //
    // Update judgement (v0.2.0) compares version NAMES, not
    // versionCodes: the scan no longer downloads update.json, so
    // the latest versionCode is unknown, while the tag gives the
    // latest versionName and PackageManager gives the installed
    // one. All watched projects publish monotonic X.Y.Z names, so
    // ordering by name tuple is sound.

    /** "0.1" -> [0, 1, 0]; null when the name is missing or any
     *  part is non-numeric (the caller then makes no update
     *  judgement for that row, like the old digest-only rows). */
    fun versionTuple(name: String?): List<Long>? {
        if (name.isNullOrBlank()) return null
        val out = mutableListOf<Long>()
        for (part in name.trim().split('.')) {
            out.add(part.toLongOrNull() ?: return null)
        }
        while (out.size < 3) out.add(0L)
        return out
    }

    /** True when [latestName] is strictly newer than
     *  [installedName]; false when equal or older; null when
     *  either name is unparseable (no judgement). */
    fun isUpdateAvailable(latestName: String?, installedName: String?): Boolean? {
        val latest = versionTuple(latestName) ?: return null
        val installed = versionTuple(installedName) ?: return null
        val n = maxOf(latest.size, installed.size)
        for (i in 0 until n) {
            val l = latest.getOrElse(i) { 0L }
            val v = installed.getOrElse(i) { 0L }
            if (l != v) return l > v
        }
        return false
    }

    // -- last-scan rows (stale-while-revalidate) -----------------------------
    //
    // The last completed scan's rows, persisted as JSON in
    // filesDir, so the next open (or refresh) can render them
    // immediately and replace them when the fresh scan lands.
    // Only the fields needed to render a row and start a download
    // are kept. Downloads started from a stale row stay safe: the
    // bytes are verified against the release asset's digest after
    // download (see download()).

    private const val LAST_SCAN_FILE = "last-scan.json"

    fun saveLastScan(context: Context, items: List<ApkItem>) {
        try {
            val arr = JSONArray()
            for (item in items) {
                arr.put(
                    JSONObject()
                        .put("project", item.project)
                        .put("fileName", item.fileName)
                        .put("size", item.size)
                        .put("modified", item.modified)
                        .put("versionName", item.update?.versionName ?: JSONObject.NULL)
                        .put("downloadUrl", item.downloadUrl ?: JSONObject.NULL)
                        .put("assetDigest", item.assetDigest ?: JSONObject.NULL)
                        .put("source", item.source)
                )
            }
            File(context.filesDir, LAST_SCAN_FILE).writeText(arr.toString())
        } catch (e: Exception) {
            // The cache is best-effort: losing it only costs the
            // instant first render, never correctness.
        }
    }

    fun loadLastScan(context: Context): List<ApkItem> {
        return try {
            val file = File(context.filesDir, LAST_SCAN_FILE)
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            val out = mutableListOf<ApkItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val fileName = o.optString("fileName")
                if (fileName.isEmpty()) continue
                val digest = if (o.isNull("assetDigest")) null
                    else o.optString("assetDigest").ifEmpty { null }
                val size = o.optLong("size", -1L)
                out.add(
                    ApkItem(
                        project = o.optString("project"),
                        fileName = fileName,
                        size = size,
                        modified = o.optLong("modified", 0L),
                        update = UpdateInfo(
                            file = fileName,
                            buildId = null,
                            versionName = if (o.isNull("versionName")) null
                                else o.optString("versionName").ifEmpty { null },
                            versionCode = null,
                            sha256 = digest,
                            size = if (size > 0) size else null
                        ),
                        source = o.optString("source").ifEmpty { "GitHub" },
                        downloadUrl = if (o.isNull("downloadUrl")) null
                            else o.optString("downloadUrl").ifEmpty { null },
                        assetDigest = digest
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    // -- item streams ---------------------------------------------------
    //
    // Producing the rows lives in GitHubSource. What stays here is
    // the byte path: an HTTPS stream of the release asset.

    /**
     * HTTP client for the asset download path (v0.2.4).
     *
     * Until v0.2.3 downloads ran on the platform HttpURLConnection
     * stack. On the user's moto g05 that stack plateaued at
     * ~5 MB/s for release assets — single-stream and 8-way
     * parallel alike, CPU idle, on a WiFi link negotiated at
     * 390 Mbps — while Chrome (Cronet) pulled the same asset at
     * ~21 MB/s on the same phone in the same minutes, and a wired
     * Mac on the same gateway pulled it at ~36 MB/s single-flow.
     * The network was never the bottleneck; the transport was.
     * OkHttp brings its own connection/buffer management and
     * HTTP/2, which is the closest available equivalent of the
     * browser stack. If a future measurement shows this made no
     * difference, the remaining candidate is Cronet itself.
     */
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun assetRequest(url: String, range: String? = null): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "AppsUpdater")
            .header("Accept", "application/octet-stream")
            .header("Cache-Control", "no-cache")
        if (range != null) builder.header("Range", range)
        return builder.build()
    }

    /** Stream for one item: the release asset over HTTPS. */
    private fun openItemStream(context: Context, item: ApkItem): InputStream? {
        val url = item.downloadUrl ?: return null
        val response = downloadClient.newCall(assetRequest(url)).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IllegalStateException("GitHub から取得できませんでした (HTTP $code)")
        }
        return response.body?.byteStream()
    }

    // -- package info -------------------------------------------------------

    fun installedInfo(context: Context, packageName: String): PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    fun archiveInfo(context: Context, file: File): PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }
        } catch (e: Exception) {
            null
        }

    // -- download -----------------------------------------------------------

    data class DownloadResult(val file: File, val sha256: String, val bytes: Long)

    /** The streamed bytes do not match the release asset's
     *  SHA-256 digest. Carries what actually arrived so the UI can
     *  name it. */
    class ShaMismatchException(
        val expected: String,
        val actual: String,
        val bytes: Long,
        val archivePackage: String?,
        val archiveVersionName: String?,
        val archiveVersionCode: Long?
    ) : Exception("SHA-256 mismatch")

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Stream the release asset into internal storage (the app's
     * dedicated downloads directory — the single APK storage
     * location since v0.2.0), computing SHA-256 on the way, then
     * publish the same bytes into the public Downloads folder via
     * MediaStore so that `adb shell pm install` can install exactly
     * this file without any further transfer.
     *
     * When the release record carries a SHA-256 digest and the
     * streamed bytes do not match it, the downloaded file is
     * deleted again and
     * [ShaMismatchException] is thrown: a mismatched file must never
     * stay behind in installable form. (Lesson carried over from
     * the Drive era: a stale file left in place after a reported
     * mismatch could later be installed as if it were the update.)
     */
    // -- parallel (segmented) download --------------------------------------
    //
    // The scan was parallelised in v0.2.0, but the asset download
    // itself stayed single-stream until v0.2.3, when it was split
    // into Range segments fetched on several connections at once
    // (the asset host answers Range requests — the package probe
    // in PackageProbe relies on exactly that). Parallelism alone
    // did not raise the plateau on the user's device (see the
    // downloadClient note: the transport stack was the limit, not
    // the flow count), but segments stay: they cost nothing when
    // the transport is healthy and they localise retries. Any
    // failure in the parallel path (no Range support, a short or
    // failed segment) falls back to the single stream, so a server
    // quirk can never make a download impossible, only slower.
    // The SHA-256 check afterwards is unchanged and covers the
    // reassembled bytes either way.

    private const val PARALLEL_MIN_BYTES = 4L * 1024 * 1024
    private const val PARALLEL_MAX_SEGMENTS = 8
    private const val PARALLEL_TARGET_SEGMENT = 2L * 1024 * 1024

    /** Number of segments for [total] bytes, or 0 when the file is
     *  too small for parallel fetching to pay off. */
    private fun parallelSegments(total: Long): Int {
        if (total < PARALLEL_MIN_BYTES) return 0
        val bySize = (total + PARALLEL_TARGET_SEGMENT - 1) / PARALLEL_TARGET_SEGMENT
        return bySize.coerceIn(2, PARALLEL_MAX_SEGMENTS.toLong()).toInt()
    }

    /**
     * Fetch one byte range [start, end] of [url] into [file] at
     * offset [start]. OkHttp follows redirects itself and re-sends
     * the Range header on redirect (it only strips Authorization),
     * which the manual redirect loop in the HttpURLConnection era
     * had to do by hand. Returns false when the server answers
     * 200 instead of 206 (Range ignored) or the segment arrives
     * short — the caller then falls back.
     */
    private fun fetchSegment(
        file: File, url: String, start: Long, end: Long,
        onChunk: (Int) -> Unit
    ): Boolean {
        return try {
            downloadClient.newCall(assetRequest(url, "bytes=$start-$end")).execute().use { response ->
                if (response.code != 206) return false
                val input = response.body?.byteStream() ?: return false
                val want = end - start + 1
                var got = 0L
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(start)
                    input.use { stream ->
                        val buf = ByteArray(256 * 1024)
                        while (got < want) {
                            val n = stream.read(
                                buf, 0, minOf(buf.size.toLong(), want - got).toInt()
                            )
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            got += n
                            onChunk(n)
                        }
                    }
                }
                got == want
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Download [item] into [tmp] as parallel Range segments.
     * Returns the total byte count on success, or null when the
     * parallel path does not apply or any segment failed (the
     * caller then retries single-stream; [tmp] may hold partial
     * bytes and is truncated by that retry).
     */
    private fun downloadParallel(
        tmp: File, item: ApkItem, onProgress: (Long, Long) -> Unit
    ): Long? {
        val total = item.size
        val url = item.downloadUrl ?: return null
        val segments = parallelSegments(total)
        if (segments == 0) return null
        val started = Timing.now()
        RandomAccessFile(tmp, "rw").use { it.setLength(total) }
        val progress = AtomicLong(0)
        val failed = AtomicBoolean(false)
        val firstError = AtomicReference<String?>(null)
        val progressLock = Any()
        val segSize = (total + segments - 1) / segments
        val pool = Executors.newFixedThreadPool(segments)
        try {
            val futures = (0 until segments).map { i ->
                val start = i * segSize
                val end = minOf(start + segSize, total) - 1
                pool.submit<Boolean> {
                    val ok = fetchSegment(tmp, url, start, end) { n ->
                        val now = progress.addAndGet(n.toLong())
                        synchronized(progressLock) { onProgress(now, total) }
                    }
                    if (!ok) {
                        failed.set(true)
                        firstError.compareAndSet(null, "segment $i ($start-$end)")
                    }
                    ok
                }
            }
            futures.forEach { it.get() }
        } catch (e: Exception) {
            failed.set(true)
            firstError.compareAndSet(null, e.javaClass.simpleName)
        } finally {
            pool.shutdownNow()
        }
        val ms = Timing.now() - started
        if (failed.get() || progress.get() != total) {
            Timing.logLine(
                "download ${item.fileName}: parallel segments=$segments failed " +
                    "(${firstError.get()}, ${progress.get()}/$total B, ${ms}ms); falling back to single stream"
            )
            return null
        }
        Timing.logLine(
            "download ${item.fileName}: parallel segments=$segments ok ($total B, ${ms}ms)"
        )
        return total
    }

    fun download(context: Context, item: ApkItem, onProgress: (Long, Long) -> Unit): DownloadResult {
        val target = downloadedFile(context, item.fileName)
        val tmp = File(target.parentFile, item.fileName + ".part")
        var copied = 0L
        var sha: String
        val parallelBytes = downloadParallel(tmp, item, onProgress)
        if (parallelBytes != null) {
            copied = parallelBytes
            // Segments were written by offset, so no digest could
            // be accumulated in order on the way; hash the
            // reassembled file (a local read, far faster than the
            // network path it replaces).
            sha = sha256Of(tmp)
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            openItemStream(context, item)?.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        copied += n
                        onProgress(copied, item.size)
                    }
                }
            } ?: throw IllegalStateException("APK を開けませんでした")
            sha = digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        // The expectation is the release asset's digest from the
        // GitHub API; it is carried both as the item's assetDigest
        // and as its update.sha256 (synthesized from the same
        // record), and either statement disagreeing with the
        // streamed bytes is a mismatch.
        val expected = item.update?.sha256 ?: item.assetDigest
        val assetExpected = item.assetDigest
        val mismatchExpected = when {
            expected != null && !expected.equals(sha, ignoreCase = true) -> expected
            assetExpected != null && !assetExpected.equals(sha, ignoreCase = true) -> assetExpected
            else -> null
        }
        if (mismatchExpected != null) {
            val archive = archiveInfo(context, target)
            target.delete()
            throw ShaMismatchException(
                mismatchExpected, sha, copied,
                archive?.packageName, archive?.versionName, archive?.longVersionCode
            )
        }
        publishToDownloads(context, target, item.fileName)
        writeDoneMarker(context, item, sha, copied)
        return DownloadResult(target, sha, copied)
    }

    /**
     * Download one item end to end and compose + persist the
     * status text the UI shows for the outcome. Used by
     * DownloadService (the foreground service that owns transfers
     * since v0.1.9); the activity no longer downloads on its own
     * threads, because a backgrounded activity's process can have
     * its sockets aborted mid-transfer — observed on a real device
     * with "SocketException: Software caused connection abort"
     * while pulling a 28 MB release asset.
     *
     * Returns the same text it persisted, so callers can broadcast
     * it to a listening activity.
     */
    fun runDownload(
        context: Context,
        item: ApkItem,
        onProgress: (Long, Long) -> Unit
    ): String {
        val text = try {
            val result = download(context, item, onProgress)
            setDownloadedSha(context, item.fileName, result.sha256)
            val archive = archiveInfo(context, result.file)
            val archiveText = archive?.let {
                " / ${it.packageName} ${it.versionName} (code ${it.longVersionCode})"
            } ?: ""
            if (item.update?.sha256 != null) {
                "ダウンロード完了・SHA-256 一致$archiveText\n" +
                    "adb: $DOWNLOADS_ABS_DIR/${item.fileName}"
            } else {
                "ダウンロード完了（照合用の sha256 が無いため未照合）$archiveText\n" +
                    "adb: $DOWNLOADS_ABS_DIR/${item.fileName}"
            }
        } catch (e: ShaMismatchException) {
            // Name what actually arrived: the archive identity makes
            // a wrong-bytes download visible instead of a bare
            // "mismatch". The mismatched file itself was already
            // deleted by download(), so it cannot be installed later.
            val sb = StringBuilder(
                "SHA-256 不一致のため破棄しました。期待 ${e.expected.take(12)}… / 実際 ${e.actual.take(12)}…"
            )
            if (e.archivePackage != null) {
                sb.append("\nダウンロードされた実体: ${e.archivePackage} ${e.archiveVersionName} (code ${e.archiveVersionCode})")
            }
            val expSize = item.update?.size
            if (expSize != null && expSize != e.bytes) {
                sb.append("\nサイズも不一致: 期待 $expSize / 実際 ${e.bytes}")
            }
            sb.toString()
        } catch (e: Exception) {
            "ダウンロード失敗: ${e.javaClass.simpleName}: ${e.message}"
        }
        setStatus(context, item.fileName, text, statusKeyFor(item))
        return text
    }

    private fun publishToDownloads(context: Context, source: File, fileName: String) {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // Replace any older copy of the same file name in our subfolder.
        try {
            val old = context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(fileName),
                null
            )
            old?.use { c ->
                while (c.moveToNext()) {
                    context.contentResolver.delete(
                        android.content.ContentUris.withAppendedId(collection, c.getLong(0)), null, null
                    )
                }
            }
        } catch (e: Exception) {
            // Best effort: a leftover copy is replaced below only if the
            // provider allows it; failing that, the insert may duplicate.
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOADS_REL_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Download フォルダへ書き込めませんでした")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("Download フォルダを開けませんでした")
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null)
    }

    /** Small JSON marker next to the APK in Downloads/apps-updater/. */
    private fun writeDoneMarker(context: Context, item: ApkItem, sha256: String, bytes: Long) {
        try {
            val archive = archiveInfo(context, downloadedFile(context, item.fileName))
            val json = JSONObject()
                .put("file", item.fileName)
                .put("project", item.project)
                .put("sha256", sha256)
                .put("size", bytes)
                .put("package", archive?.packageName ?: JSONObject.NULL)
                .put("versionName", archive?.versionName ?: JSONObject.NULL)
                .put("versionCode", archive?.longVersionCode ?: JSONObject.NULL)
                .put("downloadedAt", System.currentTimeMillis())
            val tmp = File(context.filesDir, "done.json")
            tmp.writeText(json.toString())
            val markerName = item.fileName + ".done.json"
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            try {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=?",
                    arrayOf(markerName),
                    null
                )?.use { c ->
                    while (c.moveToNext()) {
                        context.contentResolver.delete(
                            android.content.ContentUris.withAppendedId(collection, c.getLong(0)), null, null
                        )
                    }
                }
            } catch (e: Exception) {
                // Best effort, same as the APK replace above.
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, markerName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOADS_REL_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values) ?: return
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        } catch (e: Exception) {
            // The marker is a convenience for the adb-side script's
            // logging; the APK's own SHA-256 is the real completion
            // signal, so a marker failure must not fail the download.
        }
    }

    // -- install --------------------------------------------------------------

    /**
     * Install a downloaded APK with PackageInstaller, asking for no
     * user action (Android 12+). If the platform still wants a
     * confirmation, InstallReceiver surfaces it via notification and
     * the in-app confirm button.
     */
    fun install(context: Context, item: ApkItem) {
        val apkFile = downloadedFile(context, item.fileName)
        require(apkFile.exists()) { "先にダウンロードしてください" }
        abandonStaleSessions(context)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            } catch (e: Exception) {
                // Quirky platform: confirmation prompt is the fallback.
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("app.apk", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ACTION_INSTALL_STATUS)
                .setPackage(context.packageName)
                .putExtra(EXTRA_FILE, item.fileName)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
        }
    }

    /** Abandon this app's stale sessions (phone-node's lesson: an
     *  unanswered confirmation session stays active and counts against
     *  the per-UID cap until every later install fails). */
    private fun abandonStaleSessions(context: Context) {
        try {
            val installer = context.packageManager.packageInstaller
            val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
            for (info in installer.mySessions) {
                val created = if (Build.VERSION.SDK_INT >= 31) info.createdMillis else 0L
                if (created in 1 until cutoff) {
                    try {
                        installer.abandonSession(info.sessionId)
                    } catch (e: Exception) {
                        // Already finalized.
                    }
                }
            }
        } catch (e: Exception) {
            // Sweep failure must not block the install itself.
        }
    }
}
