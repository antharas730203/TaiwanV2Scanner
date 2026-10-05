package tw.v2scanner

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Safe, declarative Layer1 rule engine.
 * The App never executes pasted Kotlin/Java/script code. Only this JSON schema is accepted.
 */
object Layer1RuleEngine {
    const val PREF_KEY = "layer1_logic_json"
    const val SCHEMA_VERSION = 1

    data class Meta(val strategyName: String, val logicVersion: String, val logicUpdated: String)
    data class Result(val score: Int, val action: String, val reasons: List<String>)
    data class Validation(val ok: Boolean, val message: String, val meta: Meta? = null)

    val DEFAULT_LOGIC: String = """
{
  "schema_version": 1,
  "strategy_name": "轉機＋動能",
  "logic_version": "V1.0",
  "logic_updated": "2026-10-05",
  "pass_score": 55,
  "strong_score": 70,
  "top_n": 100,
  "rules": [
    {
      "metric": "change_pct",
      "bands": [
        {"min": 2.0, "max": 6.99, "score": 30, "reason": "日漲幅 {value}%，強勢但未過熱"},
        {"min": 0.5, "max": 1.99, "score": 20, "reason": "日漲幅 {value}%，偏強"},
        {"min": 7.0, "max": 9.49, "score": 12, "reason": "日漲幅 {value}%，強但開始追價"},
        {"min": 9.5, "score": -12, "reason": "接近漲停，追高風險"},
        {"min": -1.99, "max": 0.49, "score": 8, "reason": "日線整理／小幅波動"},
        {"max": -5.000001, "score": -8, "reason": "日線弱勢"}
      ]
    },
    {
      "metric": "day_position",
      "bands": [
        {"min": 0.85, "score": 20, "reason": "位於日內高檔"},
        {"min": 0.60, "max": 0.849999, "score": 12},
        {"min": 0.40, "max": 0.599999, "score": 6},
        {"max": 0.199999, "score": -6}
      ]
    },
    {
      "metric": "from_open_pct",
      "bands": [
        {"min": 3.0, "score": 15, "reason": "相對開盤價明顯轉強"},
        {"min": 1.0, "max": 2.999999, "score": 10},
        {"max": -3.0, "score": -8}
      ]
    },
    {
      "metric": "volume",
      "bands": [
        {"min": 5000, "score": 15, "reason": "成交活躍"},
        {"min": 1000, "max": 4999.999999, "score": 10},
        {"min": 300, "max": 999.999999, "score": 6},
        {"min": 100, "max": 299.999999, "score": 3}
      ]
    },
    {
      "metric": "change_pct",
      "bands": [
        {"min": 9.5, "score": -8}
      ]
    }
  ]
}
""".trimIndent()

    fun validate(text: String): Validation = try {
        val root = JSONObject(text)
        require(root.optInt("schema_version", -1) == SCHEMA_VERSION) { "schema_version 必須為 $SCHEMA_VERSION" }
        val name = root.optString("strategy_name").trim()
        val version = root.optString("logic_version").trim()
        val updated = root.optString("logic_updated").trim()
        require(name.isNotEmpty()) { "缺少 strategy_name" }
        require(version.isNotEmpty()) { "缺少 logic_version" }
        require(Regex("""\d{4}-\d{2}-\d{2}""").matches(updated)) { "logic_updated 必須為 YYYY-MM-DD" }
        val pass = root.optInt("pass_score", -1)
        val strong = root.optInt("strong_score", -1)
        val topN = root.optInt("top_n", -1)
        require(pass in 0..100) { "pass_score 必須為 0～100" }
        require(strong in pass..100) { "strong_score 必須 >= pass_score 且 <= 100" }
        require(topN in 1..500) { "top_n 必須為 1～500" }
        val rules = root.optJSONArray("rules") ?: throw IllegalArgumentException("缺少 rules")
        require(rules.length() in 1..100) { "rules 必須有 1～100 條" }
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: throw IllegalArgumentException("rules[$i] 必須是物件")
            val metric = rule.optString("metric")
            require(metric in setOf("change_pct", "day_position", "from_open_pct", "volume")) { "rules[$i] metric 不支援：$metric" }
            val bands = rule.optJSONArray("bands") ?: throw IllegalArgumentException("rules[$i] 缺少 bands")
            require(bands.length() in 1..30) { "rules[$i] bands 必須有 1～30 條" }
            for (j in 0 until bands.length()) {
                val band = bands.optJSONObject(j) ?: throw IllegalArgumentException("rules[$i].bands[$j] 必須是物件")
                require(band.has("score")) { "rules[$i].bands[$j] 缺少 score" }
                val score = band.optDouble("score", Double.NaN)
                require(score.isFinite() && score in -100.0..100.0) { "rules[$i].bands[$j] score 超出範圍" }
                require(band.has("min") || band.has("max")) { "rules[$i].bands[$j] 至少要有 min 或 max" }
                val min = if (band.has("min")) band.optDouble("min", Double.NaN) else null
                val max = if (band.has("max")) band.optDouble("max", Double.NaN) else null
                require(min == null || min.isFinite()) { "rules[$i].bands[$j] min 無效" }
                require(max == null || max.isFinite()) { "rules[$i].bands[$j] max 無效" }
                require(min == null || max == null || min <= max) { "rules[$i].bands[$j] min 不可大於 max" }
            }
        }
        Validation(true, "格式正確：$name / $version / $updated", Meta(name, version, updated))
    } catch (e: Exception) {
        Validation(false, e.message ?: "無法解析 L1 邏輯")
    }

    fun meta(text: String): Meta {
        val v = validate(text)
        require(v.ok) { v.message }
        return v.meta!!
    }

    fun topN(text: String): Int {
        val v = validate(text)
        require(v.ok) { v.message }
        return JSONObject(text).getInt("top_n")
    }

    fun evaluate(stock: JSONObject, text: String): Result {
        val validation = validate(text)
        require(validation.ok) { validation.message }
        val root = JSONObject(text)
        val price = number(stock, "z") ?: return Result(0, "資料不足", listOf("缺少現價"))
        val prev = number(stock, "y")
        val high = number(stock, "h")
        val low = number(stock, "l")
        val open = number(stock, "o")
        val volume = number(stock, "v")

        val metrics = mapOf(
            "change_pct" to if (prev != null && prev > 0) (price / prev - 1.0) * 100.0 else null,
            "day_position" to if (high != null && low != null && high > low) ((price - low) / (high - low)).coerceIn(0.0, 1.0) else null,
            "from_open_pct" to if (open != null && open > 0) (price / open - 1.0) * 100.0 else null,
            "volume" to volume
        )

        var score = 0.0
        val reasons = ArrayList<String>()
        val rules = root.getJSONArray("rules")
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val value = metrics[rule.getString("metric")] ?: continue
            val bands = rule.getJSONArray("bands")
            for (j in 0 until bands.length()) {
                val band = bands.getJSONObject(j)
                val minOk = !band.has("min") || value >= band.getDouble("min")
                val maxOk = !band.has("max") || value <= band.getDouble("max")
                if (minOk && maxOk) {
                    score += band.getDouble("score")
                    val reason = band.optString("reason").trim()
                    if (reason.isNotEmpty()) reasons += reason.replace("{value}", fmt(value))
                    break
                }
            }
        }

        val finalScore = score.roundToInt().coerceIn(0, 100)
        val pass = root.getInt("pass_score")
        val strong = root.getInt("strong_score")
        val action = when {
            finalScore >= strong -> "第一層通過"
            finalScore >= pass -> "第一層觀察"
            else -> "第一層淘汰"
        }
        return Result(finalScore, action, reasons.distinct().take(5))
    }

    private fun number(o: JSONObject, key: String): Double? =
        o.optString(key, "").trim().replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
