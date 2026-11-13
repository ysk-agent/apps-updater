package dev.moriya.appsupdater

import android.app.Activity
import android.content.Intent
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
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readAutoExtras(intent)
        autoConsumed = false
        refresh()
    }

    override fun onResume() {
        super.onResume()
        updateHeader()
        // Reflect install outcomes recorded by InstallReceiver while
        // this activity was not in front.
        for (row in rows) {
            Store.status(this, row.item.fileName)?.let { row.status.text = it }
        }
        confirmButton.visibility =
            if (AppState.pendingConfirmIntent != null) View.VISIBLE else View.GONE
    }

    private fun readAutoExtras(intent: Intent?) {
        autoProject = intent?.getStringExtra("auto_project")
        autoFile = intent?.getStringExtra("auto_file")
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
        if (Store.treeUri(this) == null) {
            itemsContainer.addView(TextView(this).apply {
                text = "まず Drive の apps フォルダを選んでください。選択は端末に保持され、次回からは一覧がそのまま表示されます。"
                textSize = 14f
            })
            return
        }
        val loading = TextView(this).apply { text = "読み込み中…" }
        itemsContainer.addView(loading)
        thread(name = "scan", isDaemon = true) {
            val items = try {
                Store.scan(this)
            } catch (e: Exception) {
                runOnUiThread {
                    loading.text = "読み込みに失敗しました: ${e.javaClass.simpleName}: ${e.message}"
                }
                return@thread
            }
            runOnUiThread {
                itemsContainer.removeView(loading)
                if (items.isEmpty()) {
                    itemsContainer.addView(TextView(this).apply {
                        text = "APK が見つかりませんでした。選んだフォルダが apps かどうかを確認してください。"
                        textSize = 14f
                    })
                }
                var lastProject: String? = null
                for (item in items) {
                    if (item.project != lastProject) {
                        lastProject = item.project
                        itemsContainer.addView(TextView(this@MainActivity).apply {
                            text = item.project
                            textSize = 18f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setPadding(0, dp(18), 0, dp(2))
                        })
                    }
                    addRow(item)
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
            text = "Drive: ${fmtSize(item.size)}" +
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
            text = Store.status(this@MainActivity, item.fileName) ?: ""
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
            isEnabled = Store.downloadedFile(this@MainActivity, item.fileName).exists()
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
        if (persist) Store.setStatus(this, row.item.fileName, text)
        runOnUiThread { row.status.text = text }
    }

    private fun startDownload(row: Row) {
        if (row.downloading) return
        row.downloading = true
        val item = row.item
        runOnUiThread {
            row.progress.visibility = View.VISIBLE
            row.progress.isIndeterminate = item.size <= 0
            row.progress.progress = 0
        }
        setRowStatus(row, "ダウンロード中: ${item.fileName}")
        thread(name = "download-${item.fileName}", isDaemon = true) {
            try {
                val result = Store.download(this, item) { copied, total ->
                    runOnUiThread {
                        if (total > 0) {
                            row.progress.isIndeterminate = false
                            row.progress.progress = ((copied * 100) / total).toInt()
                        }
                        row.status.text = "ダウンロード中: ${fmtSize(copied)} / ${fmtSize(total)}"
                    }
                }
                val expected = item.update?.sha256
                val archive = Store.archiveInfo(this, result.file)
                val archiveText = archive?.let {
                    " / ${it.packageName} ${it.versionName} (code ${it.longVersionCode})"
                } ?: ""
                when {
                    expected != null && !expected.equals(result.sha256, ignoreCase = true) -> {
                        // Name what actually arrived: when the Drive
                        // app on the device still holds the previous
                        // release in its cache, it can stream those
                        // stale bytes for the same document even after
                        // the server content was replaced — the
                        // archive identity below makes that visible
                        // instead of a bare "mismatch".
                        val sb = StringBuilder(
                            "SHA-256 不一致のためインストールしません。期待 ${expected.take(12)}… / 実際 ${result.sha256.take(12)}…"
                        )
                        if (archive != null) {
                            sb.append("\nダウンロードされた実体: ${archive.packageName} ${archive.versionName} (code ${archive.longVersionCode})")
                        }
                        val expSize = item.update?.size
                        if (expSize != null && expSize != result.bytes) {
                            sb.append("\nサイズも不一致: 期待 $expSize / 実際 ${result.bytes}")
                        }
                        val expCode = item.update?.versionCode
                        if (archive != null && expCode != null && archive.longVersionCode < expCode) {
                            sb.append("\nDrive アプリに古いバージョンのキャッシュが残っている可能性があります。Drive アプリで対象ファイルを開き直してから、もう一度ダウンロードしてください。")
                        }
                        setRowStatus(row, sb.toString())
                        runOnUiThread { row.installButton.isEnabled = false }
                    }
                    expected != null -> {
                        Store.setDownloadedSha(this, item.fileName, result.sha256)
                        setRowStatus(
                            row,
                            "ダウンロード完了・SHA-256 一致$archiveText\nadb: ${Store.DOWNLOADS_ABS_DIR}/${item.fileName}"
                        )
                        runOnUiThread { row.installButton.isEnabled = true }
                    }
                    else -> {
                        Store.setDownloadedSha(this, item.fileName, result.sha256)
                        setRowStatus(
                            row,
                            "ダウンロード完了（update.json に sha256 が無いため未照合）$archiveText\nadb: ${Store.DOWNLOADS_ABS_DIR}/${item.fileName}"
                        )
                        runOnUiThread { row.installButton.isEnabled = true }
                    }
                }
            } catch (e: Exception) {
                setRowStatus(row, "ダウンロード失敗: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                row.downloading = false
                runOnUiThread { row.progress.visibility = View.GONE }
            }
        }
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
