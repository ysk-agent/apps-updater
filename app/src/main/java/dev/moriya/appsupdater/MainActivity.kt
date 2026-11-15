package dev.moriya.appsupdater

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Apps Updater: lists the APKs published in the Drive `apps` folder
 * (granted once via SAF), shows the installed version next to each,
 * and downloads / installs on demand.
 *
 * Downloads land in two places: internal storage (used for the
 * in-app PackageInstaller flow) and the public
 * Download/apps-updater/ folder (so `adb shell pm install` can
 * finish an update silently, with no transfer over the adb link:
 * the file is already on the device, fetched at line speed through
 * the Drive app's provider).
 *
 * Automation entry point (used by tools/adb_update.py):
 *   am start -n dev.moriya.appsupdater/.MainActivity \
 *     --es auto_project phone-node-waker --es auto_file phone-node-waker.apk
 * starts the download of that one item as soon as the list is built.
 */
class MainActivity : Activity() {

    private lateinit var sourceView: TextView
    private lateinit var permView: TextView
    private lateinit var confirmButton: Button
    private lateinit var itemsContainer: LinearLayout

    private var autoProject: String? = null
    private var autoFile: String? = null
    private var autoConsumed = false

    private class Row(
        val item: ApkItem,
        val status: TextView,
        val progress: ProgressBar,
        val installButton: Button,
        @Volatile var downloading: Boolean = false
    )

    private val rows = mutableListOf<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readAutoExtras(intent)
        maybeRunTestDownload(intent)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Apps Updater"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val selfInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        root.addView(TextView(this).apply {
            text = "version ${selfInfo?.versionName ?: "?"} (code ${selfInfo?.longVersionCode ?: "?"})"
            textSize = 13f
        })
        sourceView = TextView(this).apply { textSize = 14f }
        root.addView(sourceView)
        permView = TextView(this).apply { textSize = 13f }
        root.addView(permView)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        buttons.addView(Button(this).apply {
            text = "フォルダを選ぶ"
            setOnClickListener { launchPicker() }
        })
        buttons.addView(Button(this).apply {
            text = "再読み込み"
            setOnClickListener { refresh() }
        })
        buttons.addView(Button(this).apply {
            text = "インストール許可"
            setOnClickListener { openInstallPermissionSettings() }
        })
        root.addView(buttons)

        confirmButton = Button(this).apply {
            text = "インストール確認を開く"
            visibility = View.GONE
            setOnClickListener {
                val confirm = AppState.pendingConfirmIntent
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        startActivity(confirm)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "確認画面を開けませんでした", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        root.addView(confirmButton)

        root.addView(TextView(this).apply {
            text = "ダウンロード先（adb 用）: ${Store.DOWNLOADS_ABS_DIR}/<ファイル名>"
            textSize = 12f
        })

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadService.ACTION_DOWNLOAD_STATE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, IntentFilter(DownloadService.ACTION_DOWNLOAD_STATE))
        }
        refresh()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Not registered (defensive; onCreate always registers).
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readAutoExtras(intent)
        maybeRunTestDownload(intent)
        autoConsumed = false
        refresh()
    }

    override fun onResume() {
        super.onResume()
        updateHeader()
        // Reflect install outcomes recorded by InstallReceiver while
        // this activity was not in front, and download outcomes
        // recorded by DownloadService (a download may have finished
        // while this activity was backgrounded or recreated).
        for (row in rows) {
            Store.statusFor(this, row.item)?.let { row.status.text = it }
            if (!row.downloading) refreshInstallEnabled(row)
        }
        confirmButton.visibility =
            if (AppState.pendingConfirmIntent != null) View.VISIBLE else View.GONE
    }

    private fun readAutoExtras(intent: Intent?) {
        autoProject = intent?.getStringExtra("auto_project")
        autoFile = intent?.getStringExtra("auto_file")
    }

    /**
     * CI test hook (debug builds only): extras
     *   --es test_file ci-test.apk --es test_url http://10.0.2.2:8123/ci-test.apk \
     *   --es test_sha256 <hex> --el test_size <bytes>
     * start a DownloadService download of a synthetic GitHub-shaped
     * item, so the emulator test can exercise the real
     * foreground-service download + hash-verify + publish path end
     * to end without a network. Gated on FLAG_DEBUGGABLE: the
     * release build ignores these extras entirely.
     */
    private fun maybeRunTestDownload(intent: Intent?) {
        intent ?: return
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return
        }
        val file = intent.getStringExtra("test_file") ?: return
        val url = intent.getStringExtra("test_url") ?: return
        val sha = intent.getStringExtra("test_sha256")
        val size = intent.getLongExtra("test_size", -1L)
        val item = ApkItem(
            project = "ci-test",
            fileName = file,
            docUri = null,
            size = size,
            modified = 0L,
            update = UpdateInfo(
                file = file,
                versionName = "9.9.9",
                versionCode = 99L,
                sha256 = sha,
                size = if (size > 0) size else null
            ),
            source = "GitHub",
            downloadUrl = url,
            assetDigest = sha
        )
        try {
            DownloadService.start(this, item)
        } catch (e: Exception) {
            Toast.makeText(this, "test download start failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // -- header -------------------------------------------------------------

    private fun updateHeader() {
        val tree = Store.treeUri(this)
        sourceView.text = when {
            tree == null -> "Drive フォルダ: 未設定（「フォルダを選ぶ」から apps フォルダを指定してください）"
            else -> "Drive フォルダ: ${Store.rootName(this) ?: "apps（設定済み）"}"
        }
        permView.text = if (packageManager.canRequestPackageInstalls()) {
            "不明なアプリのインストール: 許可済み"
        } else {
            "不明なアプリのインストール: 未許可（「インストール許可」から許可してください）"
        }
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_TREE)
    }

    @Deprecated("Legacy result API keeps the app dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TREE && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Toast.makeText(this, "フォルダの許可を保持できませんでした", Toast.LENGTH_LONG).show()
            }
            Store.setTreeUri(this, uri)
            refresh()
        }
    }

    private fun openInstallPermissionSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    // -- list ---------------------------------------------------------------

    private fun refresh() {
        updateHeader()
        itemsContainer.removeAllViews()
        rows.clear()
        val loading = TextView(this).apply { text = "読み込み中…" }
        itemsContainer.addView(loading)
        thread(name = "scan", isDaemon = true) {
            var driveError: String? = null
            val driveItems = if (Store.treeUri(this) == null) {
                emptyList()
            } else {
                try {
                    Store.scan(this)
                } catch (e: Exception) {
                    driveError = "${e.javaClass.simpleName}: ${e.message}"
                    emptyList()
                }
            }
            // GitHub Releases source (pilot for moka-vpn in v0.1.8,
            // generalised in v0.1.9): for every file with a known
            // publishing repo, an available GitHub row replaces the
            // Drive row for the same file — the provider's view of
            // those folders is exactly what cannot be trusted. When
            // GitHub cannot be reached, the Drive row stays as the
            // fallback.
            val githubItems = GitHubSource.FILE_REPO.keys.mapNotNull { fileName ->
                try {
                    GitHubSource.fetchItem(fileName)
                } catch (e: Exception) {
                    null
                }
            }
            val githubFiles = githubItems.map { it.fileName.lowercase() }.toSet()
            val items = (
                driveItems.filterNot { it.fileName.lowercase() in githubFiles } +
                    githubItems
                ).sortedWith(compareBy({ it.project }, { it.fileName }))
            runOnUiThread {
                if (driveError != null) {
                    itemsContainer.addView(TextView(this@MainActivity).apply {
                        text = "Drive の読み込みに失敗しました: $driveError"
                        textSize = 14f
                    })
                }
                if (Store.treeUri(this) == null) {
                    itemsContainer.addView(TextView(this@MainActivity).apply {
                        text = "Drive の apps フォルダが未設定です。「フォルダを選ぶ」から指定すると Drive 側のアプリも表示されます（GitHub 側の行は設定なしで表示されます）。"
                        textSize = 14f
                    })
                }
                itemsContainer.removeView(loading)
                if (items.isEmpty()) {
                    itemsContainer.addView(TextView(this).apply {
                        text = "APK が見つかりませんでした。選んだフォルダが apps かどうかを確認してください。"
                        textSize = 14f
                    })
                }
                // Group explicitly instead of relying on the scan's
                // sort order: one header per project, every file of
                // that project underneath it.
                for ((project, groupItems) in items.groupBy { it.project }) {
                    itemsContainer.addView(TextView(this@MainActivity).apply {
                        text = project
                        textSize = 18f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(0, dp(18), 0, dp(2))
                    })
                    for (item in groupItems) {
                        addRow(item)
                    }
                }
                maybeRunAuto()
            }
        }
    }

    private fun addRow(item: ApkItem) {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        block.addView(TextView(this).apply {
            text = item.fileName
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        block.addView(TextView(this).apply {
            text = "${item.source}: ${fmtSize(item.size)}" +
                (if (item.modified > 0) " • ${fmtDate(item.modified)}" else "")
            textSize = 13f
        })
        item.update?.let { u ->
            val parts = mutableListOf<String>()
            u.versionName?.let { parts.add("version $it") }
            u.versionCode?.let { parts.add("code $it") }
            u.buildId?.let { parts.add("build $it") }
            parts.add(if (u.sha256 != null) "sha256 あり" else "sha256 なし")
            block.addView(TextView(this).apply {
                text = "update.json: ${parts.joinToString(" / ")}"
                textSize = 13f
            })
        }
        val knownPackage = Store.KNOWN_PACKAGES[item.fileName]
        val installedText = when {
            knownPackage == null -> "パッケージ: ダウンロード後に判明"
            else -> {
                val info = Store.installedInfo(this, knownPackage)
                if (info == null) {
                    "インストール済み: なし（$knownPackage）"
                } else {
                    "インストール済み: ${info.versionName} (code ${info.longVersionCode})"
                }
            }
        }
        block.addView(TextView(this).apply {
            text = installedText
            textSize = 13f
        })
        val status = TextView(this).apply {
            textSize = 13f
            text = Store.statusFor(this@MainActivity, item) ?: ""
        }
        block.addView(status)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        block.addView(progress)
        val rowButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val installButton = Button(this).apply {
            text = "インストール"
            // Enabled when a downloaded file exists and, where its
            // hash was recorded, it still matches what update.json
            // currently offers. The install action re-hashes the
            // file itself, so this is only the visual gate.
            val expectedSha = item.update?.sha256
            val recordedSha = Store.downloadedSha(this@MainActivity, item.fileName)
            isEnabled = Store.downloadedFile(this@MainActivity, item.fileName).exists() &&
                (expectedSha == null || recordedSha == null ||
                    expectedSha.equals(recordedSha, ignoreCase = true))
        }
        val row = Row(item, status, progress, installButton)
        rowButtons.addView(Button(this).apply {
            text = "ダウンロード"
            setOnClickListener { startDownload(row) }
        })
        installButton.setOnClickListener { startInstall(row) }
        rowButtons.addView(installButton)
        block.addView(rowButtons)
        block.addView(View(this).apply {
            setBackgroundColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        itemsContainer.addView(block)
        rows.add(row)
    }

    // -- actions --------------------------------------------------------------

    private fun setRowStatus(row: Row, text: String, persist: Boolean = true) {
        if (persist) Store.setStatus(this, row.item.fileName, text, Store.statusKeyFor(row.item))
        runOnUiThread { row.status.text = text }
    }

    private fun startDownload(row: Row) {
        if (row.downloading) return
        val item = row.item
        // Preflight for Drive items: when update.json states a size
        // and the provider's listing disagrees, the provider's view
        // of the folder is stale (observed with moka-vpn: the
        // listing still showed the previous generation while
        // update.json was already current). Downloading would pull
        // the stale bytes over a possibly metered line only to
        // discard them on hash mismatch, so stop before any byte
        // moves. GitHub items are exempt: their listing size comes
        // from the same release record as update.json.
        val offeredSize = item.update?.size
        if (item.source == "Drive" && offeredSize != null && item.size > 0 &&
            offeredSize != item.size
        ) {
            setRowStatus(
                row,
                "Drive 側の一覧が古い世代のままの可能性があります" +
                    "（update.json: $offeredSize bytes / 一覧: ${item.size} bytes）。" +
                    "ダウンロードを中止しました。"
            )
            return
        }
        // Since v0.1.9 the transfer itself runs in DownloadService,
        // a foreground service: on a bare activity thread, the
        // system aborted the socket as soon as the app was
        // backgrounded. The service broadcasts progress/completion
        // (see downloadReceiver); the outcome text is composed and
        // persisted by Store.runDownload.
        row.downloading = true
        row.progress.visibility = View.VISIBLE
        row.progress.isIndeterminate = item.size <= 0
        row.progress.progress = 0
        setRowStatus(row, "ダウンロード中: ${item.fileName}")
        try {
            DownloadService.start(this, item)
        } catch (e: Exception) {
            row.downloading = false
            row.progress.visibility = View.GONE
            setRowStatus(row, "ダウンロード開始に失敗: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Progress/completion updates from DownloadService. */
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val fileName = intent.getStringExtra(DownloadService.EXTRA_FILE) ?: return
            val row = rows.firstOrNull { it.item.fileName == fileName } ?: return
            val done = intent.getBooleanExtra(DownloadService.EXTRA_DONE, false)
            if (!done) {
                val copied = intent.getLongExtra(DownloadService.EXTRA_COPIED, 0L)
                val total = intent.getLongExtra(DownloadService.EXTRA_TOTAL, -1L)
                row.progress.visibility = View.VISIBLE
                if (total > 0) {
                    row.progress.isIndeterminate = false
                    row.progress.progress = ((copied * 100) / total).toInt()
                }
                row.status.text = "ダウンロード中: ${fmtSize(copied)} / ${fmtSize(total)}"
            } else {
                row.downloading = false
                row.progress.visibility = View.GONE
                val text = intent.getStringExtra(DownloadService.EXTRA_STATUS)
                    ?: Store.statusFor(this@MainActivity, row.item)
                if (text != null) row.status.text = text
                refreshInstallEnabled(row)
            }
        }
    }

    /** The install button is usable when a downloaded file is on
     *  disk and, where a download hash was recorded, it still
     *  matches the currently offered artifact. (The install action
     *  itself re-hashes the file, so this is only the visual gate;
     *  it mirrors the initial state computed in addRow.) */
    private fun refreshInstallEnabled(row: Row) {
        val item = row.item
        val downloadedSha = Store.downloadedSha(this, item.fileName)
        val offeredSha = item.update?.sha256
        row.installButton.isEnabled =
            Store.downloadedFile(this, item.fileName).exists() &&
                (offeredSha == null || downloadedSha == null ||
                    offeredSha.equals(downloadedSha, ignoreCase = true))
    }

    private fun startInstall(row: Row) {
        val item = row.item
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "先に「不明なアプリのインストール」を許可してください", Toast.LENGTH_LONG).show()
            openInstallPermissionSettings()
            return
        }
        setRowStatus(row, "インストール中: ${item.fileName}")
        thread(name = "install-${item.fileName}", isDaemon = true) {
            try {
                // Re-verify the stored file against update.json at
                // install time: whatever sits in internal storage is
                // what gets installed, so it must still be the
                // artifact the current update.json describes.
                val expected = item.update?.sha256
                if (expected != null) {
                    val actual = Store.sha256Of(Store.downloadedFile(this, item.fileName))
                    if (!expected.equals(actual, ignoreCase = true)) {
                        setRowStatus(
                            row,
                            "保存済みファイルの SHA-256 が現在の update.json と一致しないためインストールしません。期待 ${expected.take(12)}… / 実際 ${actual.take(12)}…（再ダウンロードしてください）"
                        )
                        return@thread
                    }
                }
                Store.install(this, item)
            } catch (e: Exception) {
                setRowStatus(row, "インストール開始に失敗: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun maybeRunAuto() {
        if (autoConsumed) return
        val project = autoProject ?: return
        val file = autoFile ?: return
        val row = rows.firstOrNull { it.item.project == project && it.item.fileName == file }
            ?: rows.firstOrNull { it.item.fileName == file }
            ?: return
        autoConsumed = true
        startDownload(row)
    }

    // -- helpers ---------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmtSize(bytes: Long): String = when {
        bytes < 0 -> "サイズ不明"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun fmtDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    companion object {
        private const val REQUEST_TREE = 1001
    }
}
