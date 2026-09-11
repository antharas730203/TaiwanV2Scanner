package tw.v2scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rolling per-schedule history. Keeps only the latest 35 entries. */
object ScheduleDiagnosticsHistory {
    private const val PREF = "diagnostics"
    private const val HISTORY_KEY = "schedule_history"
    private const val MAX_ENTRIES = 35

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun prefs(context: Context) = context.getSharedPreferences(PREF, 0)

    fun start(context: Context, index: Int, hour: Int, minute: Int, network: String, mode: String): String {
        val id = "${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}_${index}_${hour.toString().padStart(2, '0')}${minute.toString().padStart(2, '0')}"
        val entry = JSONObject().apply {
            put("id", id)
            put("time", now())
            put("schedule", "%02d:%02d".format(hour, minute))
            put("index", index)
            put("mode", mode)
            put("status", "TRIGGERED")
            put("alarm", true)
            put("network", network)
        }
        val array = read(context)
        removeSameId(array, id)
        array.put(entry)
        trim(array)
        prefs(context).edit().putString(HISTORY_KEY, array.toString()).apply()
        return id
    }

    fun update(context: Context, id: String, status: String? = null, key: String? = null, value: String? = null) {
        val array = read(context)
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optString("id") != id) continue
            if (status != null) o.put("status", status)
            if (key != null && value != null) o.put(key, value)
            o.put("updated", now())
            break
        }
        prefs(context).edit().putString(HISTORY_KEY, array.toString()).apply()
    }

    fun history(context: Context): JSONArray = read(context)

    private fun read(context: Context): JSONArray = try {
        JSONArray(prefs(context).getString(HISTORY_KEY, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    private fun removeSameId(array: JSONArray, id: String) {
        for (i in array.length() - 1 downTo 0) if (array.optJSONObject(i)?.optString("id") == id) array.remove(i)
    }

    private fun trim(array: JSONArray) {
        while (array.length() > MAX_ENTRIES) array.remove(0)
    }
}
