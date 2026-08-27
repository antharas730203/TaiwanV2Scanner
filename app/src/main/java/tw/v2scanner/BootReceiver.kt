package tw.v2scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED &&
            context.getSharedPreferences("settings", 0).getBoolean("auto", false)) {
            ScanScheduler.schedule(context)
        }
    }
}
