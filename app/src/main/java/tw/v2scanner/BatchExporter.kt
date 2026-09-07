package tw.v2scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object BatchExporter {
    const val CHAR_LIMIT = 6000

    data class ExportResult(
        val count: Int,
        val directory: File,
        val maxChars: Int,
        val stockCount: Int
    )

    fun export(context: Context, stamp: String, fullJson: String): ExportResult {
        val root = JSONObject(fullJson)
        val stocks = root.optJSONArray("stocks") ?: JSONArray()
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, "scanner_batches/$stamp")
        if (dir.exists()) dir.deleteRecursively()
        check(dir.mkdirs()) { "無法建立批次資料夾：${dir.absolutePath}" }

        // Layer 1 is a derived result. The original fullJson is never modified.
        val records = ArrayList<StockRecord>(stocks.length())
        for (i in 0 until stocks.length()) {
            val stock = stocks.optJSONObject(i) ?: continue
            val market = stock.optString("market").ifBlank { "TWSE" }
            records += StockRecord(market, stock)
        }
        val layer1Json = Layer1Scanner.buildResult(stamp, records, topN = 100)
        File(dir, "layer1_result.json").writeText(layer1Json, Charsets.UTF_8)
        File(baseDir, "layer1_latest.json").writeText(layer1Json, Charsets.UTF_8)
        File(dir, "layer1_status.json").writeText(
            JSONObject().apply {
                put("status", "LAYER1_COMPLETE")
                put("scan_time", stamp)
                put("source_records", records.size)
                put("completed_at", System.currentTimeMillis())
                put("result_file", "layer1_result.json")
            }.toString(),
            Charsets.UTF_8
        )

        val index = JSONArray()
        var current = ArrayList<JSONObject>()
        var batchNo = 1
        var maxChars = 0
        var writtenStocks = 0

        fun buildText(items: List<JSONObject>, number: Int): String {
            val result = JSONObject()
            result.put("scan_time", root.optString("scan_time", stamp))
            result.put("mode", root.optString("mode", "FULL"))
            result.put("batch", number)
            result.put("char_limit", CHAR_LIMIT)
            result.put("stock_count", items.size)
            result.put("stock_start", items.firstOrNull()?.optString("c").orEmpty())
            result.put("stock_end", items.lastOrNull()?.optString("c").orEmpty())
            result.put("stocks", JSONArray().apply { items.forEach { put(it) } })
            return result.toString()
        }

        fun writeBatch(items: List<JSONObject>) {
            if (items.isEmpty()) return
            val text = buildText(items, batchNo)
            require(text.length <= CHAR_LIMIT) {
                "單一批次無法控制在 $CHAR_LIMIT 字元內：batch=$batchNo stock=${items.firstOrNull()?.optString("c")} chars=${text.length}"
            }
            val fileName = "batch_%03d.json".format(batchNo)
            File(dir, fileName).writeText(text, Charsets.UTF_8)
            index.put(JSONObject().apply {
                put("batch", batchNo)
                put("file", fileName)
                put("stock_start", items.firstOrNull()?.optString("c").orEmpty())
                put("stock_end", items.lastOrNull()?.optString("c").orEmpty())
                put("stock_count", items.size)
                put("characters", text.length)
            })
            maxChars = maxOf(maxChars, text.length)
            writtenStocks += items.size
            batchNo++
        }

        for (i in 0 until stocks.length()) {
            val stock = stocks.optJSONObject(i) ?: continue
            val candidate = ArrayList(current)
            candidate.add(stock)
            if (current.isNotEmpty() && buildText(candidate, batchNo).length > CHAR_LIMIT) {
                writeBatch(current)
                current = ArrayList()
            }
            current.add(stock)
        }
        writeBatch(current)

        require(writtenStocks == stocks.length()) {
            "批次輸出遺失股票：原始=${stocks.length()}，輸出=$writtenStocks"
        }

        val indexRoot = JSONObject().apply {
            put("scan_time", root.optString("scan_time", stamp))
            put("mode", root.optString("mode", "FULL"))
            put("char_limit", CHAR_LIMIT)
            put("stock_count", stocks.length())
            put("batch_count", index.length())
            put("max_batch_characters", maxChars)
            put("layer1_file", "layer1_result.json")
            put("layer1_status", "LAYER1_COMPLETE")
            put("batches", index)
        }
        File(dir, "batch_index.json").writeText(indexRoot.toString(), Charsets.UTF_8)
        return ExportResult(index.length(), dir, maxChars, writtenStocks)
    }
}
