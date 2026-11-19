package dev.moriya.appsupdater

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Scan timing (introduced on the timing-test branch to find the
 * slow phase on a real device, kept in v0.2.0 as the user's
 * on-device speed check): every measurement is emitted to logcat
 * under [TAG] (one line per phase), and the one-line summary of
 * the last scan is persisted so MainActivity can keep showing it
 * across process restarts.
 *
 * Additive only: nothing here changes scanning, selection,
 * download or install behaviour.
 */
object Timing {

    const val TAG = "UpdaterTiming"

    private const val PREFS = "appsupdater-timing"
    private const val KEY_SUMMARY = "last_scan_summary"

    fun now(): Long = SystemClock.elapsedRealtime()

    fun log(phase: String, ms: Long) {
        Log.i(TAG, "$phase: ${ms}ms")
    }

    fun logLine(line: String) {
        Log.i(TAG, line)
    }

    /** "12,340" style, or "-" when the phase did not run. */
    fun fmt(ms: Long?): String =
        if (ms == null) "-" else String.format(Locale.US, "%,d", ms)

    fun saveSummary(context: Context, summary: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUMMARY, summary).apply()
    }

    fun lastSummary(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SUMMARY, null)
}
