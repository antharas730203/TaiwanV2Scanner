package tw.v2scanner

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLEncoder
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** V0.8.4 archive uploader. */
object DataArchiveUploader {
    private const val API = "https://api.github.com"
    private const val PREFS = "github_secure"
    private const val TOKEN_KEY = "token"
    private const val ALIAS = "TaiwanV2ScannerGitHubKey"
    private const val MAX_TRANSIENT_RETRIES = 3

    fun upload(context: Context, stamp: String, fullJson: String, layer1Json: String, sourceTag: String = "AUTO"): String {
        val token = loadToken(context) ?: return "失敗：GitHub Token 未設定"
        val settings = context.getSharedPreferences("settings", 0)
        val owner = settings.getString("github_owner", "antharas730203").orEmpty().trim()
        val repo = settings.getString("github_repo", "TaiwanV2Scanner").orEmpty().trim()
        val branch = settings.getString("github_branch", "main").orEmpty().trim().ifEmpty { "main" }
        if (owner.isBlank() || repo.isBlank()) return "失敗：GitHub Repo 未設定"
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
            val twseText = marketJson(stamp, "TWSE", twse)
            val tpexText = marketJson(stamp, "TPEX", tpex)
            val layer1Text = addReadMarkers(layer1Json)
            val basePath = "scanner_data/history"
            val twsePath = "$basePath/${stamp}_${tag}_TWSE.json"
            val tpexPath = "$basePath/${stamp}_${tag}_TPEX.json"
            val layer1Path = "$basePath/${stamp}_${tag}_LAYER1.json"
            context.getSharedPreferences("diagnostics", 0).edit().putString("archive_upload_stage", "準備上傳：$stamp").apply()
            uploadWithRetry(context, token, owner, repo, branch, twsePath, twseText, "Add $tag TWSE archive $stamp")
            uploadWithRetry(context, token, owner, repo, branch, tpexPath, tpexText, "Add $tag TPEX archive $stamp")
            uploadWithRetry(context, token, owner, repo, branch, layer1Path, layer1Text, "Add $tag LAYER1 archive $stamp")
            context.getSharedPreferences("diagnostics", 0).edit().putString("archive_upload_stage", "三檔上傳完成：$stamp").apply()
            "成功：TWSE/TPEX/LAYER1｜$tag｜$stamp"
        } catch (e: Exception) {
            val msg = e.message ?: "無詳細訊息"
            context.getSharedPreferences("diagnostics", 0).edit().putString("archive_upload_stage", "失敗：${e.javaClass.simpleName} - $msg").apply()
            "失敗：Archive ${e.javaClass.simpleName} - $msg"
        }
    }

    private fun uploadWithRetry(context: Context, token: String, owner: String, repo: String, branch: String, path: String, text: String, message: String) {
        var last: Exception? = null
        for (attempt in 0..MAX_TRANSIENT_RETRIES) {
            try {
                context.getSharedPreferences("diagnostics", 0).edit().putString("archive_upload_stage", "上傳中：$path").apply()
                putFile(token, owner, repo, branch, path, text, message)
                context.getSharedPreferences("diagnostics", 0).edit().putString("archive_upload_stage", "完成：$path").apply()
                return
            } catch (e: Exception) {
                last = e
                if (!isTransientNetworkError(e) || attempt >= MAX_TRANSIENT_RETRIES) throw e
                Thread.sleep(when (attempt) { 0 -> 5000L; 1 -> 15000L; else -> 30000L })
            }
        }
        throw last ?: IllegalStateException("Archive upload failed")
    }

    private fun isTransientNetworkError(e: Exception): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is UnknownHostException || current is ConnectException || current is SocketTimeoutException) return true
            current = current.cause
        }
        return false
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

    private fun loadToken(context: Context): String? {
        return try {
            val stored = context.getSharedPreferences(PREFS, 0).getString(TOKEN_KEY, null) ?: return null
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            if (bytes.size <= 12) return null
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey(ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun putFile(token: String, owner: String, repo: String, branch: String, path: String, text: String, message: String) {
        val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val existingSha = getSha(token, owner, repo, branch, path)
        val payload = JSONObject().apply {
            put("message", message)
            put("content", Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            put("branch", branch)
            if (existingSha != null) put("sha", existingSha)
        }
        val conn = (URL("$API/repos/$owner/$repo/contents/$encodedPath").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "TaiwanV2Scanner/0.8.4")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = try { conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } catch (_: Exception) { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty() }
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("$path HTTP $code ${extractMessage(body)}")
    }

    private fun getSha(token: String, owner: String, repo: String, branch: String, path: String): String? {
        val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val encodedBranch = URLEncoder.encode(branch, "UTF-8")
        val conn = (URL("$API/repos/$owner/$repo/contents/$encodedPath?ref=$encodedBranch").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "TaiwanV2Scanner/0.8.4")
        }
        return try {
            when (val code = conn.responseCode) {
                404 -> null
                in 200..299 -> JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).optString("sha").takeIf { it.isNotBlank() }
                else -> throw IllegalStateException("讀取 $path HTTP $code")
            }
        } finally { conn.disconnect() }
    }

    private fun extractMessage(body: String): String = try { JSONObject(body).optString("message").ifBlank { body.take(160) } } catch (_: Exception) { body.take(160) }
}
