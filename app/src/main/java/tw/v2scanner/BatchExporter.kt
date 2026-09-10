package tw.v2scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object BatchExporter {
    // 6000 is a safe reading/processing unit, not a file-splitting rule.
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
        val dir = File(baseDir, "scanner_data/history")
        check(dir.exists() || dir.mkdirs()) { "無法建立資料資料夾：${dir.absolutePath}" }

        val prefs = context.getSharedPreferences("settings", 0)
        val scanOrigin = prefs.getString("scan_origin", "manual") ?: "manual"
        val sourceTag = if (scanOrigin == "scheduled") "AUTO" else "MANUAL"

        val twse = JSONArray()
        val tpex = JSONArray()
        val records = ArrayList<StockRecord>(stocks.length())

        for (i in 0 until stocks.length()) {
            val source = stocks.optJSONObject(i) ?: continue
            val market = source.optString("market").ifBlank { "TWSE" }
            val marked = JSONObject(source.toString()).apply {
                put("_read_index", "STOCK_START")
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

        // Keep a tiny local manifest for diagnostics only. It is not needed by the
        // GitHub reader because the read marker lives inside each data file.
        val manifest = JSONObject().apply {
            put("scan_time", stamp)
            put("source", sourceTag)
            put("read_unit", CHAR_LIMIT)
            put("files", JSONArray().apply {
                put(fileInfo(twseFile, twseText))
                put(fileInfo(tpexFile, tpexText))
                put(fileInfo(layer1File, layer1Text))
            })
        }
        File(dir, "${stamp}_${sourceTag}_MANIFEST.json").writeText(manifest.toString(), Charsets.UTF_8)

        val layer1Count = JSONObject(layer1Json).optInt("qualified_count", 0)
        context.getSharedPreferences("diagnostics", 0).edit()
            .putString("layer1_status", "LAYER1_COMPLETE")
            .putInt("layer1_count", layer1Count)
            .putInt("layer1_source_records", records.size)
            .putString("archive_local", "成功：TWSE/TPEX/LAYER1｜$sourceTag｜$stamp")
            .apply()

        // Keep the latest Layer1 result for the manual "上傳最新 JSON" action.
        File(baseDir, "layer1_latest.json").writeText(layer1Text, Charsets.UTF_8)

        // GitHub archive upload is schedule-only.
        // Manual scans never upload implicitly; use the "上傳最新 JSON" button instead.
        val scheduleUpload = prefs.getBoolean("schedule_github_upload", false)
        val archiveResult = if (scanOrigin == "scheduled" && scheduleUpload) {
            DataArchiveUploader.upload(context, stamp, fullJson, layer1Text, "AUTO")
        } else {
            "未自動上傳（手動掃描或排程自動上傳未啟用）"
        }
        context.getSharedPreferences("diagnostics", 0).edit()
            .putString("archive_upload", archiveResult)
            .apply()

        val maxChars = maxOf(twseText.length, tpexText.length, layer1Text.length, manifest.length())
        return ExportResult(3, dir, maxChars, records.size)
    }

    private fun marketJson(stamp: String, market: String, stocks: JSONArray): String = JSONObject().apply {
        put("scan_time", stamp)
        put("mode", "FULL")
        put("market", market)
        put("stock_count", stocks.length())
        put("read_unit", CHAR_LIMIT)
        put("read_rule", "max_6000_chars_per_read; use _read_index boundaries when available")
        put("stocks", stocks)
    }.toString()

    private fun addReadMarkers(source: String): String {
        val root = JSONObject(source)
        val keys = arrayOf("qualified", "stocks", "results", "items")
        for (key in keys) {
            val array = root.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.put("_read_index", "STOCK_START")
            }
        }
        root.put("read_unit", CHAR_LIMIT)
        root.put("read_rule", "max_6000_chars_per_read; use _read_index boundaries when available")
        return root.toString()
    }

    private fun fileInfo(file: File, text: String): JSONObject = JSONObject().apply {
        put("file", file.name)
        put("characters", text.length)
        put("stock_count", JSONObject(text).optInt("stock_count", JSONObject(text).optJSONArray("stocks")?.length() ?: 0))
    }
}
