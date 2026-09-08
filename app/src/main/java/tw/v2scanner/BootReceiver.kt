package tw.v2scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("settings", 0)
            val enabled = prefs.getBoolean("auto", false)
            if (enabled) {
                val times = prefs.getString("schedule_times", ScanScheduler.DEFAULT_TIMES) ?: ScanScheduler.DEFAULT_TIMES
                if (ExactScanScheduler.canScheduleExact(context)) {
                    ExactScanScheduler.schedule(context, times)
                } else {
                    ScheduleDiagnostics.mark(context, "schedule_engine", "EXACT_ALARM_PERMISSION_MISSING")
                    ScheduleDiagnostics.mark(context, "schedule_error", "開機後未取得精確鬧鐘權限")
                }
            }
        }
    }
}
