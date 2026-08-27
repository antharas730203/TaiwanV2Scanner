package tw.v2scanner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class ScanAlarmReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScanWorker.run(context) { }
    }
}
