package dev.moriya.appsupdater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * PackageInstaller session results for installs started by this app.
 *
 * On STATUS_PENDING_USER_ACTION the confirmation intent is kept in
 * [AppState] for the in-app button, and a high-importance notification
 * whose tap launches it is posted — the two routes that survive
 * Android's background-activity-launch restrictions (phone-node's
 * UpdateReceiver carries the full reasoning; the same design is used
 * here). A direct launch from this receiver is also attempted, as it
 * works while the user has interacted with the app recently.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -99)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val file = intent.getStringExtra(Store.EXTRA_FILE) ?: "(unknown)"
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Store.setStatus(context, file, "端末でのインストール確認待ち: $file")
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    AppState.pendingConfirmIntent = confirm
                    AppState.pendingConfirmFile = file
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        // Background launch dropped; notification and
                        // the in-app button remain.
                    }
                    postConfirmNotification(context, confirm, file)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Store.setStatus(context, file, "インストール完了: $file")
                AppState.pendingConfirmIntent = null
                AppState.pendingConfirmFile = null
                cancelConfirmNotification(context)
            }
            else -> {
                Store.setStatus(context, file, "インストール結果 status=$status $message: $file")
                AppState.pendingConfirmIntent = null
                AppState.pendingConfirmFile = null
                cancelConfirmNotification(context)
            }
        }
    }

    private fun postConfirmNotification(context: Context, confirm: Intent, file: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "アップデートの確認", NotificationManager.IMPORTANCE_HIGH)
            )
            val tap = PendingIntent.getActivity(
                context, REQUEST_CONFIRM, confirm,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Apps Updater のインストール確認")
                .setContentText("$file のインストール確認が必要です。タップして進めてください。")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // The in-app button still remains.
        }
    }

    private fun cancelConfirmNotification(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            // Nothing meaningful to do.
        }
    }

    companion object {
        private const val CHANNEL_ID = "appsupdater-install"
        private const val NOTIFICATION_ID = 3001
        private const val REQUEST_CONFIRM = 3001
    }
}
