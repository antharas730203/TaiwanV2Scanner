package tw.v2scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("settings", 0)
            val autoEnabled = prefs.getBoolean("auto", false)
            val postMarketEnabled = prefs.getBoolean("post_market_enabled", false)
            if (autoEnabled || postMarketEnabled) {
                val times = prefs.getString("schedule_times", ScanScheduler.DEFAULT_TIMES) ?: ScanScheduler.DEFAULT_TIMES
                val postTime = if (postMarketEnabled) {
                    prefs.getString("post_market_time", PostMarketScanner.DEFAULT_TIME) ?: PostMarketScanner.DEFAULT_TIME
                } else null
                if (ExactScanScheduler.canScheduleExact(context)) {
                    ExactScanScheduler.schedule(context, if (autoEnabled) times else "", postTime)
                } else {
                    ScheduleDiagnostics.mark(context, "schedule_engine", "EXACT_ALARM_PERMISSION_MISSING")
                    ScheduleDiagnostics.mark(context, "schedule_error", "開機後未取得精確鬧鐘權限")
                }
            }
        }
    }
}
