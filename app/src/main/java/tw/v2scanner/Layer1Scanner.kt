package tw.v2scanner

import org.json.JSONArray
import org.json.JSONObject

object Layer1Scanner {
    fun buildResult(stamp: String, records: List<StockRecord>, logicText: String): String {
        val validation = Layer1RuleEngine.validate(logicText)
        require(validation.ok) { "L1 邏輯無法解析：" + validation.message }
        val meta = validation.meta!!
        val rootLogic = JSONObject(logicText)
        val passScore = rootLogic.getInt("pass_score")
        val topN = Layer1RuleEngine.topN(logicText)

        val evaluated = records.mapNotNull { record ->
            val r = Layer1RuleEngine.evaluate(record.raw, logicText)
            if (r.score < passScore) return@mapNotNull null
            JSONObject().apply {
                put("market", record.market)
                put("code", record.raw.optString("c"))
                put("name", record.raw.optString("n"))
                put("price", record.raw.optString("z"))
                put("yesterday", record.raw.optString("y"))
                put("open", record.raw.optString("o"))
                put("high", record.raw.optString("h"))
                put("low", record.raw.optString("l"))
                put("volume", record.raw.optString("v"))
                put("layer1_score", r.score)
                put("layer1_action", r.action)
                put("reasons", JSONArray(r.reasons))
            }
        }.sortedWith(compareByDescending<JSONObject> { it.optInt("layer1_score") }.thenBy { it.optString("code") })

        return JSONObject().apply {
            put("scan_time", stamp)
            put("layer", 1)
            put("strategy", meta.strategyName)
            put("strategy_name", meta.strategyName)
            put("logic_version", meta.logicVersion)
            put("logic_updated", meta.logicUpdated)
            put("logic_schema_version", Layer1RuleEngine.SCHEMA_VERSION)
            put("score_max", 100)
            put("pass_score", passScore)
            put("source_records", records.size)
            put("qualified_count", evaluated.size)
            put("top_n", minOf(topN, evaluated.size))
            put("fundamental_items", "Layer2")
            put("stocks", JSONArray().apply { evaluated.take(topN).forEach { put(it) } })
        }.toString()
    }
}
