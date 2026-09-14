package tw.v2scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object BatchExporter {
    const val CHAR_LIMIT = 6000

    data class ExportResult(val count: Int, val directory: File, val maxChars: Int, val stockCount: Int)

    fun export(context: Context, stamp: String, fullJson: String): ExportResult {
        val root = JSONObject(fullJson)
        val stocks = root.optJSONArray("stocks") ?: JSONArray()
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, "scanner_data/history")
        check(dir.exists() || dir.mkdirs()) { "無法建立資料資料夾：${dir.absolutePath}" }

        val prefs = context.getSharedPreferences("settings", 0)
        val scanOrigin = prefs.getString("scan_origin", "manual") ?: "manual"
        val sourceTag = if (scanOrigin == "scheduled") "AUTO" else "MANUAL"
        val twse = JSONArray()
        val tpex = JSONArray()
        val records = ArrayList<StockRecord>(stocks.length())

        var twseIndex = 0
        var tpexIndex = 0
        for (i in 0 until stocks.length()) {
            val source = stocks.optJSONObject(i) ?: continue
            val market = source.optString("market").ifBlank { "TWSE" }
            val index = if (market.equals("TPEX", true)) ++tpexIndex else ++twseIndex
            val marked = JSONObject(source.toString()).apply {
                put("record_index", index)
                put("_read_index", "STOCK_START_${index.toString().padStart(4, '0')}")
            }
            if (market.equals("TPEX", true)) tpex.put(marked) else twse.put(marked)
            records += StockRecord(market, source)
        }

        val layer1Json = Layer1Scanner.buildResult(stamp, records, topN = 100)
        val twseText = marketJson(stamp, "TWSE", twse)
        val tpexText = marketJson(stamp, "TPEX", tpex)
        val layer1Text = addReadMarkers(layer1Json)
        val twseFile = File(dir, "${stamp}_${sourceTag}_TWSE.json")
        val tpexFile = File(dir, "${stamp}_${sourceTag}_TPEX.json")
        val layer1File = File(dir, "${stamp}_${sourceTag}_LAYER1.json")
        twseFile.writeText(twseText, Charsets.UTF_8)
        tpexFile.writeText(tpexText, Charsets.UTF_8)
        layer1File.writeText(layer1Text, Charsets.UTF_8)

        val manifest = JSONObject().apply {
            put("scan_time", stamp)
            put("source", sourceTag)
            put("read_unit", CHAR_LIMIT)
            put("read_rule", "max_6000_chars_per_read; use _read_index and record_index boundaries")
            put("files", JSONArray().apply {
                put(fileInfo(twseFile, twseText))
                put(fileInfo(tpexFile, tpexText))
                put(fileInfo(layer1File, layer1Text))
            })
        }
        val manifestText = manifest.toString(2)
        File(dir, "${stamp}_${sourceTag}_MANIFEST.json").writeText(manifestText, Charsets.UTF_8)

        val layer1Count = JSONObject(layer1Json).optInt("qualified_count", 0)
        context.getSharedPreferences("diagnostics", 0).edit()
            .putString("layer1_status", "LAYER1_COMPLETE")
            .putInt("layer1_count", layer1Count)
            .putInt("layer1_source_records", records.size)
            .putString("archive_local", "成功：TWSE/TPEX/LAYER1｜$sourceTag｜$stamp")
            .apply()

        File(baseDir, "layer1_latest.json").writeText(layer1Text, Charsets.UTF_8)

        val scheduleUpload = prefs.getBoolean("schedule_github_upload", false)
        val archiveResult = if (scanOrigin == "scheduled" && scheduleUpload) {
            try {
                context.getSharedPreferences("diagnostics", 0).edit()
                    .putString("archive_upload_stage", "開始上傳：$stamp")
                    .apply()
                DataArchiveUploader.upload(context, stamp, fullJson, layer1Text, "AUTO")
            } catch (e: Exception) {
                "失敗：ArchiveExporter ${e.javaClass.simpleName} - ${e.message ?: "無詳細訊息"}"
            }
        } else {
            "未自動上傳（手動掃描或排程自動上傳未啟用）"
        }
        context.getSharedPreferences("diagnostics", 0).edit()
            .putString("archive_upload", archiveResult)
            .putString("archive_upload_finished", "${System.currentTimeMillis()}")
            .apply()

        val maxChars = maxOf(twseText.length, tpexText.length, layer1Text.length, manifestText.length)
        return ExportResult(3, dir, maxChars, records.size)
    }

    private fun marketJson(stamp: String, market: String, stocks: JSONArray): String = JSONObject().apply {
        put("scan_time", stamp)
        put("mode", "FULL")
        put("market", market)
        put("stock_count", stocks.length())
        put("read_unit", CHAR_LIMIT)
        put("read_rule", "max_6000_chars_per_read; use _read_index and record_index boundaries")
        put("stocks", stocks)
    }.toString(2)

    private fun addReadMarkers(source: String): String {
        val root = JSONObject(source)
        val keys = arrayOf("qualified", "stocks", "results", "items")
        for (key in keys) {
            val array = root.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.apply {
                    put("record_index", i + 1)
                    put("_read_index", "STOCK_START_${(i + 1).toString().padStart(4, '0')}")
                }
            }
        }
        root.put("read_unit", CHAR_LIMIT)
        root.put("read_rule", "max_6000_chars_per_read; use _read_index and record_index boundaries")
        return root.toString(2)
    }

    private fun fileInfo(file: File, text: String): JSONObject = JSONObject().apply {
        put("file", file.name)
        put("characters", text.length)
        put("stock_count", JSONObject(text).optInt("stock_count", JSONObject(text).optJSONArray("stocks")?.length() ?: 0))
    }
}
