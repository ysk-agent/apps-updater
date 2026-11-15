package dev.moriya.appsupdater

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One APK offered by one of the sources.
 *
 * Drive items carry a [docUri] into the SAF tree. GitHub Releases
 * items (the v0.1.8 pilot covers moka-vpn only) carry a
 * [downloadUrl] and the release asset's own SHA-256 [assetDigest]
 * as reported by the GitHub API, so the download is verified
 * against two independent statements of the hash: update.json and
 * the asset record. */
data class ApkItem(
    val project: String,
    val fileName: String,
    val docUri: Uri?,
    val size: Long,
    val modified: Long,
    val update: UpdateInfo?,
    val source: String = "Drive",
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
    private const val KEY_TREE_URI = "tree_uri"
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

    // -- prefs ------------------------------------------------------------

    fun setTreeUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun treeUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)?.let { Uri.parse(it) }

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

    // -- SAF scan -----------------------------------------------------------

    private data class Child(val docId: String, val name: String, val mime: String, val size: Long, val modified: Long)

    private fun listChildren(context: Context, tree: Uri, parentDocId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = mutableListOf<Child>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Child(
                        docId = c.getString(0) ?: continue,
                        name = c.getString(1) ?: continue,
                        mime = c.getString(2) ?: "",
                        size = if (c.isNull(3)) -1L else c.getLong(3),
                        modified = if (c.isNull(4)) -1L else c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    private fun readText(context: Context, docUri: Uri): String? =
        try {
            context.contentResolver.openInputStream(docUri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }

    private fun parseUpdate(text: String): UpdateInfo? =
        try {
            val j = JSONObject(text)
            UpdateInfo(
                file = j.optString("file", ""),
                buildId = if (j.has("buildId")) j.optLong("buildId") else null,
                versionName = j.optString("versionName", "").ifEmpty { null },
                versionCode = if (j.has("versionCode")) j.optLong("versionCode") else null,
                sha256 = j.optString("sha256", "").ifEmpty { null }?.lowercase(),
                size = if (j.has("size")) j.optLong("size") else null
            )
        } catch (e: Exception) {
            null
        }

    /** Scan the granted tree: one level of project folders, APKs inside. */
    fun scan(context: Context): List<ApkItem> {
        val tree = treeUri(context) ?: return emptyList()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val items = mutableListOf<ApkItem>()
        for (child in listChildren(context, tree, rootId)) {
            if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                val updateByFile = mutableMapOf<String, UpdateInfo>()
                val apks = mutableListOf<Child>()
                for (grand in listChildren(context, tree, child.docId)) {
                    when {
                        grand.name.endsWith(".apk", ignoreCase = true) -> apks.add(grand)
                        grand.name == "update.json" -> {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, grand.docId)
                            readText(context, docUri)?.let { parseUpdate(it) }?.let { info ->
                                if (info.file.isNotEmpty()) updateByFile[info.file] = info
                            }
                        }
                    }
                }
                for (apk in apks) {
                    items.add(
                        ApkItem(
                            project = child.name,
                            fileName = apk.name,
                            docUri = DocumentsContract.buildDocumentUriUsingTree(tree, apk.docId),
                            size = apk.size,
                            modified = apk.modified,
                            update = updateByFile[apk.name]
                        )
                    )
                }
            } else if (child.name.endsWith(".apk", ignoreCase = true)) {
                items.add(
                    ApkItem(
                        project = "(apps 直下)",
                        fileName = child.name,
                        docUri = DocumentsContract.buildDocumentUriUsingTree(tree, child.docId),
                        size = child.size,
                        modified = child.modified,
                        update = null
                    )
                )
            }
        }
        // The Drive provider can surface the same document more than
        // once (observed on a real device: the same app appearing in
        // separated, duplicated rows). File names are unique per app
        // in this tree, so collapse duplicates by file name, keeping
        // the most recently modified copy.
        val deduped = items
            .groupBy { it.fileName.lowercase() }
            .values
            .map { group -> group.maxByOrNull { it.modified } ?: group.first() }
        return deduped.sortedWith(compareBy({ it.project }, { it.fileName }))
    }

    // -- GitHub Releases source (pilot: moka-vpn only) ---------------------
    //
    // Why this exists: on a real device the Drive documents provider
    // kept serving the first-indexed generation of the moka-vpn
    // folder — stale listing metadata and stale APK bytes for a
    // brand-new document — while update.json (replaced in place)
    // read fresh. The provider's folder view cannot be trusted for
    // this project, so moka-vpn is fetched over plain HTTPS from
    // GitHub Releases instead. The repository is public, so no
    // credentials are involved.
    //
    // Release selection deliberately does NOT use /releases/latest:
    // that endpoint currently resolves to an unrelated libtailscale
    // AAR cache release in this repository. Only tags of the form
    // vX.Y.Z with both the APK and update.json assets qualify, and
    // the highest such version wins.

    private const val GITHUB_REPO = "ysk-agent/moka-vpn"
    private const val GITHUB_FILE = "mokavpn.apk"

    private fun httpGet(url: String, accept: String): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "AppsUpdater")
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Stream for one item: HTTPS for GitHub items, the SAF
     *  document for Drive items. */
    private fun openItemStream(context: Context, item: ApkItem): InputStream? {
        val url = item.downloadUrl
        if (url == null) {
            val doc = item.docUri ?: return null
            return context.contentResolver.openInputStream(doc)
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AppsUpdater")
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("Cache-Control", "no-cache")
        }
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw IllegalStateException("GitHub から取得できませんでした (HTTP $code)")
        }
        return conn.inputStream
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** The current moka-vpn release from GitHub, or null when it
     *  cannot be determined (offline, rate-limited, malformed). A
     *  null here must never hide the Drive row: the caller falls
     *  back to whatever the Drive scan produced. */
    fun scanGitHubMokaVpn(): ApkItem? {
        val body = httpGet(
            "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=30",
            "application/vnd.github+json"
        ) ?: return null
        return try {
            val releases = JSONArray(String(body, Charsets.UTF_8))
            var best: ApkItem? = null
            var bestVersion: List<Int>? = null
            val tagPattern = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")
            for (i in 0 until releases.length()) {
                val rel = releases.optJSONObject(i) ?: continue
                if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue
                val m = tagPattern.matchEntire(rel.optString("tag_name")) ?: continue
                val version = m.groupValues.drop(1).map { it.toInt() }
                val assets = rel.optJSONArray("assets") ?: continue
                var apkUrl: String? = null
                var apkSize = -1L
                var apkDigest: String? = null
                var updateUrl: String? = null
                for (j in 0 until assets.length()) {
                    val a = assets.optJSONObject(j) ?: continue
                    when (a.optString("name")) {
                        GITHUB_FILE -> {
                            apkUrl = a.optString("browser_download_url")
                            apkSize = a.optLong("size", -1L)
                            apkDigest = a.optString("digest")
                                .removePrefix("sha256:").lowercase().ifEmpty { null }
                        }
                        "update.json" -> updateUrl = a.optString("browser_download_url")
                    }
                }
                if (apkUrl == null || updateUrl == null) continue
                val bv = bestVersion
                if (bv != null && compareVersion(version, bv) <= 0) continue
                val updateText = httpGet(updateUrl, "application/json")
                    ?.let { String(it, Charsets.UTF_8) } ?: continue
                val update = parseUpdate(updateText) ?: continue
                if (update.file != GITHUB_FILE) continue
                val modified = try {
                    java.time.Instant.parse(rel.optString("published_at")).toEpochMilli()
                } catch (e: Exception) {
                    0L
                }
                best = ApkItem(
                    project = "moka-vpn",
                    fileName = GITHUB_FILE,
                    docUri = null,
                    size = if (apkSize > 0) apkSize else (update.size ?: -1L),
                    modified = modified,
                    update = update,
                    source = "GitHub",
                    downloadUrl = apkUrl,
                    assetDigest = apkDigest
                )
                bestVersion = version
            }
            best
        } catch (e: Exception) {
            null
        }
    }

    /** Display name of the granted root folder, if resolvable. */
    fun rootName(context: Context): String? {
        val tree = treeUri(context) ?: return null
        return try {
            context.contentResolver.query(tree, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
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

    /** The bytes the provider streamed do not match update.json.
     *  Carries what actually arrived so the UI can name it. */
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
     * Copy the SAF document into internal storage, computing SHA-256 on
     * the way, then publish the same bytes into the public Downloads
     * folder via MediaStore so that `adb shell pm install` can install
     * exactly this file without any further transfer.
     *
     * When update.json carries a SHA-256 and the streamed bytes do not
     * match it, the downloaded file is deleted again and
     * [ShaMismatchException] is thrown: a mismatched file must never
     * stay behind in installable form. (Observed on a real device:
     * the Drive provider streamed a stale cached revision, the
     * mismatch was reported — and after a refresh the still-present
     * stale file could be installed as if it were the update.)
     */
    fun download(context: Context, item: ApkItem, onProgress: (Long, Long) -> Unit): DownloadResult {
        val target = downloadedFile(context, item.fileName)
        val tmp = File(target.parentFile, item.fileName + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
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
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        // Two independent expectations can apply: update.json's
        // sha256, and (GitHub items) the release asset's own digest
        // from the GitHub API. Either one disagreeing is a mismatch.
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
