package tw.v2scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

class ScanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val now = Calendar.getInstance()
        val day = now.get(Calendar.DAY_OF_WEEK)
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        // Weekdays only; market scan window 09:05–13:30.
        val weekday = day != Calendar.SATURDAY && day != Calendar.SUNDAY
        val inWindow = minutes in (9 * 60 + 5)..(13 * 60 + 30)
        val enabled = context.getSharedPreferences("settings", 0)
            .getBoolean("auto", false)

        if (weekday && inWindow && enabled) {
            ScanWorker.run(context) { /* background result is saved by ScanWorker */ }
        }
    }
}
