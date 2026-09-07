package tw.v2scanner

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Layer 1 is intentionally limited to fields available in the app's full-market
 * realtime payload. Fundamental items (turnaround, gross margin, revenue, theme,
 * institutional/large-holder flow) remain Layer 2 and are never fabricated here.
 * The raw stock JSON is preserved unchanged; this object only creates a derived result.
 */
object Layer1Scanner {
    data class Result(val score: Int, val action: String, val reasons: List<String>)

    private fun number(o: JSONObject, key: String): Double? {
        val s = o.optString(key, "").trim().replace(",", "")
        return s.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    fun evaluate(o: JSONObject): Result {
        val price = number(o, "z") ?: return Result(0, "資料不足", listOf("缺少現價"))
        val prev = number(o, "y")
        val high = number(o, "h")
        val low = number(o, "l")
        val yearHigh = number(o, "u")
        val yearLow = number(o, "w")
        val volume = number(o, "v")

        var score = 0.0
        val reasons = ArrayList<String>()

        // 1) Daily momentum: reward strength, but explicitly penalize chasing.
        val change = if (prev != null && prev > 0) (price / prev - 1.0) * 100.0 else null
        if (change != null) {
            when {
                change in 2.0..6.99 -> { score += 25; reasons += "日漲幅 ${fmt(change)}%，強勢但未過熱" }
                change in 0.5..1.99 -> { score += 17; reasons += "日漲幅 ${fmt(change)}%，偏強" }
                change in 7.0..9.49 -> { score += 10; reasons += "日漲幅 ${fmt(change)}%，強但開始追價" }
                change >= 9.5 -> { score -= 10; reasons += "接近漲停，追高風險" }
                change in -1.99..0.49 -> { score += 8; reasons += "日線整理／小幅波動" }
                change < -5.0 -> { score -= 8; reasons += "日線弱勢" }
            }
        }

        // 2) Intraday position: closing/last price near the day's high indicates strength.
        if (high != null && low != null && high > low) {
            val pos = (price - low) / (high - low)
            when {
                pos >= 0.85 -> { score += 15; reasons += "位於日內高檔" }
                pos >= 0.60 -> score += 10
                pos >= 0.40 -> score += 5
                pos < 0.20 -> score -= 5
            }
        }

        // 3) 52-week position: prefer room to run, penalize extreme highs.
        if (yearHigh != null && yearLow != null && yearHigh > yearLow) {
            val pos = ((price - yearLow) / (yearHigh - yearLow)).coerceIn(0.0, 1.0)
            when {
                pos in 0.35..0.80 -> { score += 20; reasons += "52週區間位置適中" }
                pos in 0.20..0.3499 -> { score += 12; reasons += "52週低檔偏中段" }
                pos in 0.80..0.90 -> { score += 5; reasons += "接近52週高檔" }
                pos > 0.90 -> { score -= 10; reasons += "接近52週高點，追高風險" }
                else -> score += 3
            }
        }

        // 4) Liquidity/activity: use current volume only as a coarse activity signal.
        // It is deliberately low weight because there is no volume average in this payload.
        if (volume != null) {
            when {
                volume >= 5000 -> { score += 15; reasons += "成交活躍" }
                volume >= 1000 -> score += 10
                volume >= 300 -> score += 6
                volume >= 100 -> score += 3
            }
        }

        // 5) Avoid obvious one-day chase setups.
        if (change != null && change >= 9.5) score -= 5
        if (change != null && yearHigh != null && yearHigh > 0 && price >= yearHigh * 0.97) {
            score -= 5
            reasons += "高檔區需避免追價"
        }

        val finalScore = score.roundToInt().coerceIn(0, 100)
        val action = when {
            finalScore >= 70 -> "第一層通過"
            finalScore >= 55 -> "第一層觀察"
            else -> "第一層淘汰"
        }
        return Result(finalScore, action, reasons.distinct().take(5))
    }

    fun buildResult(stamp: String, records: List<StockRecord>, topN: Int = 100): String {
        val evaluated = records.mapNotNull { record ->
            val r = evaluate(record.raw)
            if (r.score < 55) return@mapNotNull null
            JSONObject().apply {
                put("market", record.market)
                put("code", record.raw.optString("c"))
                put("name", record.raw.optString("n"))
                put("price", record.raw.optString("z"))
                put("yesterday", record.raw.optString("y"))
                put("volume", record.raw.optString("v"))
                put("layer1_score", r.score)
                put("layer1_action", r.action)
                put("reasons", JSONArray(r.reasons))
            }
        }.sortedWith(compareByDescending<JSONObject> { it.optInt("layer1_score") }.thenBy { it.optString("code") })

        val root = JSONObject().apply {
            put("scan_time", stamp)
            put("layer", 1)
            put("strategy", "轉機／動能第一層市場預篩")
            put("score_max", 100)
            put("source_records", records.size)
            put("qualified_count", evaluated.size)
            put("top_n", minOf(topN, evaluated.size))
            put("fundamental_items", "Layer2")
            put("stocks", JSONArray().apply { evaluated.take(topN).forEach { put(it) } })
        }
        return root.toString()
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
