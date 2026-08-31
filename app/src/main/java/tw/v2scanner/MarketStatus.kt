package tw.v2scanner

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** V0.6.6: lightweight market-status preflight before scheduled full scans. */
object MarketStatus {
    data class Result(
        val ok: Boolean,
        val tradingDay: Boolean,
        val inSession: Boolean,
        val reason: String,
        val source: String = "MIS small-sample preflight",
        val sampleReturned: Int = 0,
        val checkedAt: Long = System.currentTimeMillis()
    )

    private const val OPEN_MINUTE = 9 * 60
    private const val CLOSE_MINUTE = 13 * 60 + 30

    /**
     * Checks the local session window first, then performs the MIS request on a
     * background thread.  The caller may be an AlarmManager/BroadcastReceiver
     * callback running on Android's main thread, so the HTTP request must never
     * execute directly on that thread.
     */
    fun check(calendar: Calendar = Calendar.getInstance()): Result {
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        if (minute !in OPEN_MINUTE..CLOSE_MINUTE) {
            return Result(
                false,
                tradingDay = true,
                inSession = false,
                reason = if (minute < OPEN_MINUTE) "尚未開盤（09:00前）" else "已超過盤中時段（13:30後）"
            )
        }

        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<Result> { checkMisApi() }.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Result(false, false, true, "市場狀態 API 無法確認：${e.javaClass.simpleName}")
        } finally {
            executor.shutdownNow()
        }
    }

    private fun checkMisApi(): Result {
        return try {
            val channels = "tse_2330.tw|otc_6488.tw"
            val encoded = URLEncoder.encode(channels, "UTF-8")
            val url = URL("https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=$encoded&json=1&delay=0")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 8000
                useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return Result(false, false, true, "市場狀態 API HTTP $code")
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONObject(body).optJSONArray("msgArray") ?: JSONArray()
                val returned = arr.length()
                if (returned > 0) {
                    Result(true, true, true, "交易日且 API 有行情回應", sampleReturned = returned)
                } else {
                    Result(false, false, true, "盤中時間但市場 API 無行情回應；可能休市，跳過完整掃描", sampleReturned = 0)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result(false, false, true, "市場狀態 API 無法確認：${e.javaClass.simpleName}")
        }
    }
}
