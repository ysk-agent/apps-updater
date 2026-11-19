package dev.moriya.appsupdater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

/**
 * Foreground service that owns APK downloads (v0.1.9).
 *
 * Why: downloads used to run on a bare thread in MainActivity.
 * When the user backgrounded the app mid-transfer, the process
 * lost its foreground standing and the socket was aborted by the
 * system ("SocketException: Software caused connection abort",
 * observed on a real device during a 28 MB GitHub Releases
 * download). A foreground service keeps the process in the
 * foreground state for the duration of the transfer and carries a
 * progress notification, so the download survives the activity
 * leaving the screen.
 *
 * Jobs run one at a time on a single-thread executor. Progress and
 * completion are broadcast (package-scoped) so a resumed
 * MainActivity can update its rows; the authoritative outcome is
 * also persisted through Store.setStatus / setDownloadedSha by
 * Store.runDownload, so nothing depends on the activity listening.
 */
class DownloadService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val item = intent?.let { itemFromIntent(it) }
        if (item == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            startForegroundWith(notificationFor(item, 0, item.size, indeterminate = true))
        }
        synchronized(this) { activeJobs++ }
        executor.execute {
            try {
                val text = Store.runDownload(this, item) { copied, total ->
                    broadcastProgress(item.fileName, copied, total, done = false, status = null)
                    updateNotification(item, copied, total)
                }
                broadcastProgress(item.fileName, item.size, item.size, done = true, status = text)
            } finally {
                jobFinished()
            }
        }
        return START_NOT_STICKY
    }

    @Volatile private var activeJobs = 0

    private fun jobFinished() {
        synchronized(this) {
            activeJobs--
            if (activeJobs <= 0) {
                activeJobs = 0
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startForegroundWith(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // -- notification -------------------------------------------------------

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "ダウンロード", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun notificationFor(
        item: ApkItem, copied: Long, total: Long, indeterminate: Boolean
    ): Notification {
        ensureChannel()
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ダウンロード中: ${item.fileName}")
            .setOngoing(true)
        if (indeterminate || total <= 0) {
            builder.setProgress(0, 0, true)
        } else {
            val pct = ((copied * 100) / total).toInt()
            builder.setProgress(100, pct, false)
            builder.setContentText("$pct%")
        }
        return builder.build()
    }

    @Volatile private var lastNotifiedPct = -1

    private fun updateNotification(item: ApkItem, copied: Long, total: Long) {
        if (total <= 0) return
        val pct = ((copied * 100) / total).toInt()
        if (pct == lastNotifiedPct) return
        lastNotifiedPct = pct
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notificationFor(item, copied, total, indeterminate = false))
    }

    // -- progress broadcast ---------------------------------------------------

    private fun broadcastProgress(
        fileName: String, copied: Long, total: Long, done: Boolean, status: String?
    ) {
        val intent = Intent(ACTION_DOWNLOAD_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_FILE, fileName)
            .putExtra(EXTRA_COPIED, copied)
            .putExtra(EXTRA_TOTAL, total)
            .putExtra(EXTRA_DONE, done)
        if (status != null) intent.putExtra(EXTRA_STATUS, status)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_DOWNLOAD_STATE = "dev.moriya.appsupdater.DOWNLOAD_STATE"
        const val EXTRA_FILE = "file"
        const val EXTRA_COPIED = "copied"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_DONE = "done"
        const val EXTRA_STATUS = "status"

        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001

        private const val X_PROJECT = "project"
        private const val X_FILENAME = "fileName"
        private const val X_SOURCE = "source"
        private const val X_SIZE = "size"
        private const val X_MODIFIED = "modified"
        private const val X_DOWNLOAD_URL = "downloadUrl"
        private const val X_ASSET_DIGEST = "assetDigest"
        private const val X_U_FILE = "u_file"
        private const val X_U_VERSION_NAME = "u_versionName"
        private const val X_U_VERSION_CODE = "u_versionCode"
        private const val X_U_BUILD_ID = "u_buildId"
        private const val X_U_SHA256 = "u_sha256"
        private const val X_U_SIZE = "u_size"

        /** Start (or enqueue) the download of one item. Must be
         *  called while the app is in the foreground (activity tap
         *  or the am-start auto flow), as startForegroundService
         *  requires. */
        fun start(context: Context, item: ApkItem) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(X_PROJECT, item.project)
                putExtra(X_FILENAME, item.fileName)
                putExtra(X_SOURCE, item.source)
                putExtra(X_SIZE, item.size)
                putExtra(X_MODIFIED, item.modified)
                putExtra(X_DOWNLOAD_URL, item.downloadUrl)
                putExtra(X_ASSET_DIGEST, item.assetDigest)
                item.update?.let { u ->
                    putExtra(X_U_FILE, u.file)
                    putExtra(X_U_VERSION_NAME, u.versionName)
                    putExtra(X_U_VERSION_CODE, u.versionCode ?: -1L)
                    putExtra(X_U_BUILD_ID, u.buildId ?: -1L)
                    putExtra(X_U_SHA256, u.sha256)
                    putExtra(X_U_SIZE, u.size ?: -1L)
                }
            }
            context.startForegroundService(intent)
        }

        private fun itemFromIntent(intent: Intent): ApkItem? {
            val fileName = intent.getStringExtra(X_FILENAME) ?: return null
            val update = if (intent.hasExtra(X_U_FILE)) {
                UpdateInfo(
                    file = intent.getStringExtra(X_U_FILE) ?: "",
                    buildId = intent.getLongExtra(X_U_BUILD_ID, -1L).takeIf { it >= 0 },
                    versionName = intent.getStringExtra(X_U_VERSION_NAME),
                    versionCode = intent.getLongExtra(X_U_VERSION_CODE, -1L).takeIf { it >= 0 },
                    sha256 = intent.getStringExtra(X_U_SHA256),
                    size = intent.getLongExtra(X_U_SIZE, -1L).takeIf { it >= 0 }
                )
            } else {
                null
            }
            return ApkItem(
                project = intent.getStringExtra(X_PROJECT) ?: "",
                fileName = fileName,
                size = intent.getLongExtra(X_SIZE, -1L),
                modified = intent.getLongExtra(X_MODIFIED, 0L),
                update = update,
                source = intent.getStringExtra(X_SOURCE) ?: "GitHub",
                downloadUrl = intent.getStringExtra(X_DOWNLOAD_URL),
                assetDigest = intent.getStringExtra(X_ASSET_DIGEST)
            )
        }
    }
}
