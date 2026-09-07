package tw.v2scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object BatchExporter {
    const val CHAR_LIMIT = 6000

    data class ExportResult(val count: Int, val directory: File, val maxChars: Int)

    fun export(context: Context, stamp: String, fullJson: String): ExportResult {
        val root = JSONObject(fullJson)
        val stocks = root.optJSONArray("stocks") ?: JSONArray()
        val dir = File(context.getExternalFilesDir(null), "scanner_batches/$stamp")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        val index = JSONArray()
        var current = JSONArray()
        var batchNo = 1
        var maxChars = 0

        fun buildText(batch: JSONArray, number: Int): String {
            val first = batch.optJSONObject(0)?.optString("c").orEmpty()
            val last = batch.optJSONObject(batch.length() - 1)?.optString("c").orEmpty()
            return JSONObject().apply {
                put("scan_time", root.optString("scan_time", stamp))
                put("mode", root.optString("mode", "FULL"))
                put("batch", number)
                put("stock_start", first)
                put("stock_end", last)
                put("stock_count", batch.length())
                put("char_limit", CHAR_LIMIT)
                put("stocks", batch)
            }.toString()
        }

        fun flush() {
            if (current.length() == 0) return
            val text = buildText(current, batchNo)
            require(text.length <= CHAR_LIMIT) { "Batch $batchNo exceeds $CHAR_LIMIT characters" }
            val fileName = "batch_%03d.json".format(batchNo)
            File(dir, fileName).writeText(text, Charsets.UTF_8)
            index.put(JSONObject().apply {
                put("batch", batchNo)
                put("stock_start", current.optJSONObject(0)?.optString("c").orEmpty())
                put("stock_end", current.optJSONObject(current.length() - 1)?.optString("c").orEmpty())
                put("stock_count", current.length())
                put("characters", text.length)
                put("file", fileName)
            })
            maxChars = maxOf(maxChars, text.length)
            current = JSONArray()
            batchNo++
        }

        for (i in 0 until stocks.length()) {
            val stock = stocks.optJSONObject(i) ?: continue
            val candidate = JSONArray()
            for (j in 0 until current.length()) {
                candidate.put(current.optJSONObject(j))
            }
            candidate.put(stock)
            if (current.length() > 0 && buildText(candidate, batchNo).length > CHAR_LIMIT) {
                flush()
            }
            current.put(stock)
        }
        flush()

        val indexRoot = JSONObject().apply {
            put("scan_time", root.optString("scan_time", stamp))
            put("mode", root.optString("mode", "FULL"))
            put("char_limit", CHAR_LIMIT)
            put("stock_count", stocks.length())
            put("batch_count", index.length())
            put("max_batch_characters", maxChars)
            put("batches", index)
        }
        File(dir, "batch_index.json").writeText(indexRoot.toString(), Charsets.UTF_8)
        return ExportResult(index.length(), dir, maxChars)
    }
}
