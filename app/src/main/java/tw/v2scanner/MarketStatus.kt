package tw.v2scanner

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

/**
 * V0.6.6 market-session preflight.
 *
 * The scheduler should call check() before a full scan in trading mode.
 * It first asks the market API for a very small quote sample, then combines
 * that observation with the exchange trading-session time window.
 * This deliberately does not treat Monday-Friday alone as proof of a
 * trading day, so temporary closures (for example typhoon holidays) can be
 * handled without starting the full-market scan.
 */
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
     * Small preflight request. It intentionally asks for only two symbols,
     * rather than the full 1,982-symbol universe.
     */
    fun check(calendar: Calendar = Calendar.getInstance()): Result {
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) {
            return Result(false, false, false, "週末休市")
        }

        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val inSession = minute in OPEN_MINUTE..CLOSE_MINUTE
        if (!inSession) {
            return Result(false, true, false, if (minute < OPEN_MINUTE) "尚未開盤（09:00前）" else "已超過盤中時段（13:30後）")
        }

        return try {
            // One TWSE + one TPEX quote is enough for a lightweight preflight.
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
                if (code !in 200..299) return Result(false, false, true, "市場狀態 API HTTP $code")
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONObject(body).optJSONArray("msgArray") ?: JSONArray()
                val returned = arr.length()
                if (returned > 0) {
                    Result(true, true, true, "交易日且 API 有行情回應", sampleReturned = returned)
                } else {
                    Result(false, false, true, "交易時段內但市場 API 未回傳行情；不執行完整掃描")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // Fail closed: a scheduled full scan must not run when market
            // status cannot be verified.
            Result(false, false, true, "市場狀態 API 無法確認：${e.javaClass.simpleName}")
        }
    }
}
