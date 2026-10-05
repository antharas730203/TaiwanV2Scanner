package tw.v2scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Drive archive uploader.
 *
 * The user selects the Drive history folder once through Android's system
 * document picker. The persisted tree URI is then used by manual and
 * scheduled scans without storing Google credentials in the app.
 */
object DataArchiveUploader {
    const val PREF_DRIVE_TREE_URI = "drive_history_tree_uri"

    fun isConfigured(context: Context): Boolean =
        !context.getSharedPreferences("settings", 0)
            .getString(PREF_DRIVE_TREE_URI, null)
            .isNullOrBlank()

    fun upload(
        context: Context,
        stamp: String,
        fullJson: String,
        layer1Json: String,
        sourceTag: String = "AUTO"
    ): String {
        val settings = context.getSharedPreferences("settings", 0)
        val treeText = settings.getString(PREF_DRIVE_TREE_URI, null)
            ?: return "失敗：尚未選擇 Google Drive history 資料夾"
        val tag = if (sourceTag.equals("MANUAL", true)) "MANUAL" else "AUTO"

        return try {
            val root = JSONObject(fullJson)
            val stocks = root.optJSONArray("stocks") ?: JSONArray()
            val twse = JSONArray()
            val tpex = JSONArray()
            var twseIndex = 0
            var tpexIndex = 0

            for (i in 0 until stocks.length()) {
                val stock = stocks.optJSONObject(i) ?: continue
                val isTpex = stock.optString("market").equals("TPEX", true)
                val index = if (isTpex) ++tpexIndex else ++twseIndex
                val marked = JSONObject(stock.toString()).apply {
                    put("record_index", index)
                    put("_read_index", "STOCK_START_${index.toString().padStart(4, '0')}")
                }
                if (isTpex) tpex.put(marked) else twse.put(marked)
            }

            val files = listOf(
                "${stamp}_${tag}_TWSE.json" to marketJson(stamp, "TWSE", twse),
                "${stamp}_${tag}_TPEX.json" to marketJson(stamp, "TPEX", tpex),
                "${stamp}_${tag}_LAYER1.json" to addReadMarkers(layer1Json)
            )

            val treeUri = Uri.parse(treeText)
            val diag = context.getSharedPreferences("diagnostics", 0)
            diag.edit().putString("archive_upload_stage", "Google Drive 準備上傳：$stamp").apply()

            for ((name, text) in files) {
                diag.edit().putString("archive_upload_stage", "Google Drive 上傳中：$name").apply()
                writeFile(context, treeUri, name, text)
            }

            diag.edit()
                .putString("archive_upload_stage", "Google Drive 三檔上傳完成：$stamp")
                .apply()
            "成功：Google Drive TWSE/TPEX/LAYER1｜$tag｜$stamp"
        } catch (e: Exception) {
            val msg = e.message ?: "無詳細訊息"
            context.getSharedPreferences("diagnostics", 0).edit()
                .putString("archive_upload_stage", "Google Drive 失敗：${e.javaClass.simpleName} - $msg")
                .apply()
            "失敗：Google Drive ${e.javaClass.simpleName} - $msg"
        }
    }

    private fun writeFile(context: Context, treeUri: Uri, name: String, text: String) {
        val resolver = context.contentResolver
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        val childUri = findChild(context, treeUri, treeDocumentId, name)
            ?: DocumentsContract.createDocument(
                resolver,
                parentUri,
                "application/json",
                name
            )
            ?: throw IllegalStateException("無法在 Google Drive 建立 $name")

        resolver.openOutputStream(childUri, "wt")?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.flush()
        } ?: throw IllegalStateException("無法寫入 Google Drive：$name")
    }

    private fun findChild(context: Context, treeUri: Uri, parentDocumentId: String, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                }
            }
        }
        return null
    }

    private fun marketJson(stamp: String, market: String, stocks: JSONArray): String = JSONObject().apply {
        put("scan_time", stamp)
        put("mode", "FULL")
        put("market", market)
        put("stock_count", stocks.length())
        put("read_rule", "max_6000_chars_per_read; never split a marked record when a boundary is available")
        put("stocks", stocks)
    }.toString(2)

    private fun addReadMarkers(layer1Json: String): String {
        val root = JSONObject(layer1Json)
        val keys = arrayOf("qualified", "stocks", "results", "items")
        for (key in keys) {
            val array = root.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                obj.put("record_index", i + 1)
                obj.put("_read_index", "STOCK_START_${(i + 1).toString().padStart(4, '0')}")
            }
        }
        root.put("read_rule", "max_6000_chars_per_read; use _read_index boundaries when available")
        return root.toString(2)
    }
}
