package tw.v2scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val enabled = context.getSharedPreferences("settings", 0).getBoolean("auto", false)
            if (enabled) {
                ScanScheduler.schedule(
                    context,
                    context.getSharedPreferences("settings", 0)
                        .getString("schedule_times", ScanScheduler.DEFAULT_TIMES) ?: ScanScheduler.DEFAULT_TIMES
                )
            }
        }
    }
}
