package tw.v2scanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

private const val PREF_SETTINGS = "settings"
private const val PREF_SCAN = "scan"
private const val PREF_DIAG = "diagnostics"

const val DEFAULT_SCHEDULE_TIMES = "09:05,10:05,11:05,12:05,13:05"

private fun nowStamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
private fun nowReadable(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

private fun httpGet(url: URL, headers: Map<String, String> = emptyMap(), connectTimeout: Int = 8000, readTimeout: Int = 15000): String {
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        this.connectTimeout = connectTimeout
        this.readTimeout = readTimeout
        useCaches = false
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${body.take(200)}")
        return body
    } finally {
        conn.disconnect()
    }
}

data class MarketCodes(val market: String, val codes: List<String>)
data class StockRecord(val market: String, val raw: JSONObject)
data class BatchResult(
    val requested: Int,
    val returned: Int,
    val rate: Double,
    val failedBatches: Int,
    val seconds: Double,
    val records: List<StockRecord>
)
data class FullScanResult(
    val expected: Int,
    val returned: Int,
    val rate: Double,
    val failedBatches: Int,
    val seconds: Double,
    val finalBatchSize: Int,
    val markets: List<MarketCodes>,
    val records: List<StockRecord>,
    val missing: List<String>,
    val attempts: List<String>
)

object Market {
    private val batchSizes = listOf(150, 125, 100, 75, 50)
    private const val MIN_ACCEPT_RATE = 90.0

    fun fetchAllCodes(): List<MarketCodes> {
        val twse = fetchTwseCodes()
        val tpex = fetchTpexCodes()
        return listOf(MarketCodes("TWSE", twse), MarketCodes("TPEX", tpex))
    }

    private fun fetchTwseCodes(): List<String> {
        val body = httpGet(URL("https://openapi.twse.com.tw/v1/opendata/t187ap03_L"), headers = mapOf("User-Agent" to "Mozilla/5.0"))
        val arr = JSONArray(body)
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optString("公司代號").trim()
            if (code.matches(Regex("\\d{4,6}"))) out += code
        }
        return out.distinct()
    }

    private fun fetchTpexCodes(): List<String> {
        val body = httpGet(URL("https://www.tpex.org.tw/openapi/v1/tpex_mainboard_quotes"), headers = mapOf("User-Agent" to "Mozilla/5.0"))
        val arr = JSONArray(body)
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = listOf("SecuritiesCompanyCode", "公司代號", "Code", "SecuritiesCode")
                .asSequence().map { o.optString(it).trim() }.firstOrNull { it.isNotBlank() } ?: continue
            if (code.matches(Regex("\\d{4}"))) out += code
        }
        return out.distinct()
    }

    fun fullAdaptiveScan(markets: List<MarketCodes>, initialBatch: Int = 150): FullScanResult {
        val allCodes = markets.flatMap { it.codes }.distinct()
        var batchSize = initialBatch
        val attempts = ArrayList<String>()
        while (true) {
            val started = System.currentTimeMillis()
            val records = LinkedHashMap<String, StockRecord>()
            var failedBatches = 0
            for (market in markets) {
                val chunks = market.codes.chunked(batchSize)
                for ((index, chunk) in chunks.withIndex()) {
                    val r = queryBatch(chunk, market.market, batchSize)
                    failedBatches += r.failedBatches
                    for (record in r.records) records[record.raw.optString("c")] = record
                    appendDiag(attempts, "${market.market} 第${index + 1}/${chunks.size}批 ${batchSize}檔：${r.returned}/${chunk.size}")
                }
            }
            val rate = if (allCodes.isEmpty()) 0.0 else records.size * 100.0 / allCodes.size
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            attempts += "批次 $batchSize 完成：${records.size}/${allCodes.size} = ${"%.2f".format(rate)}%｜失敗批次 $failedBatches｜${"%.2f".format(seconds)} 秒"
            if (rate >= MIN_ACCEPT_RATE || batchSize == batchSizes.last()) {
                val missing = allCodes.filterNot { records.containsKey(it) }
                return FullScanResult(
                    expected = allCodes.size,
                    returned = records.size,
                    rate = rate,
                    failedBatches = failedBatches,
                    seconds = seconds,
                    finalBatchSize = batchSize,
                    markets = markets,
                    records = records.values.toList(),
                    missing = missing,
                    attempts = attempts
                )
            }
            batchSize = batchSizes.first { it < batchSize }
        }
    }

    fun manualSingleBatch(markets: List<MarketCodes>, size: Int = 150): BatchResult {
        val items = markets.flatMap { market -> market.codes.map { market.market to it } }.distinctBy { it.second }.take(size)
        return queryMixedBatch(items)
    }

    private fun queryMixedBatch(items: List<Pair<String, String>>): BatchResult {
        if (items.isEmpty()) return BatchResult(0, 0, 0.0, 0, 0.0, emptyList())
        val started = System.currentTimeMillis()
        val channels = items.joinToString("|") { (market, code) ->
            val prefix = if (market == "TPEX") "otc_" else "tse_"
            "${prefix}${code}.tw"
        }
        val encoded = URLEncoder.encode(channels, "UTF-8")
        val url = URL("https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=$encoded&json=1&delay=0")
        return try {
            val body = httpGet(url, headers = mapOf("User-Agent" to "Mozilla/5.0", "Accept" to "application/json"), connectTimeout = 7000, readTimeout = 12000)
            val arr = JSONObject(body).optJSONArray("msgArray") ?: JSONArray()
            val expectedCodes = items.map { it.second }.toSet()
            val marketByCode = items.associate { it.second to it.first }
            val records = ArrayList<StockRecord>()
            for (i in 0 until arr.length()) {
                val raw = arr.optJSONObject(i) ?: continue
                val code = raw.optString("c").trim()
                if (code in expectedCodes) {
                    val market = marketByCode[code] ?: "TWSE"
                    raw.put("market", market)
                    records += StockRecord(market, raw)
                }
            }
            val rate = records.size * 100.0 / items.size
            BatchResult(items.size, records.size, rate, 0, (System.currentTimeMillis() - started) / 1000.0, records)
        } catch (_: Exception) {
            BatchResult(items.size, 0, 0.0, 1, (System.currentTimeMillis() - started) / 1000.0, emptyList())
        }
    }

    private fun queryBatch(codes: List<String>, market: String, size: Int): BatchResult {
        if (codes.isEmpty()) return BatchResult(0, 0, 0.0, 0, 0.0, emptyList())
        val started = System.currentTimeMillis()
        val prefix = if (market == "TPEX") "otc_" else "tse_"
        val channels = codes.joinToString("|") { "${prefix}${it}.tw" }
        val encoded = URLEncoder.encode(channels, "UTF-8")
        val url = URL("https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=$encoded&json=1&delay=0")
        return try {
            val body = httpGet(url, headers = mapOf("User-Agent" to "Mozilla/5.0", "Accept" to "application/json"), connectTimeout = 7000, readTimeout = 12000)
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("msgArray") ?: JSONArray()
            val records = ArrayList<StockRecord>()
            for (i in 0 until arr.length()) {
                val raw = arr.optJSONObject(i) ?: continue
                val code = raw.optString("c").trim()
                if (code.isNotBlank() && codes.contains(code)) {
                    raw.put("market", market)
                    records += StockRecord(market, raw)
                }
            }
            val rate = records.size * 100.0 / codes.size
            BatchResult(codes.size, records.size, rate, 0, (System.currentTimeMillis() - started) / 1000.0, records)
        } catch (e: Exception) {
            BatchResult(codes.size, 0, 0.0, 1, (System.currentTimeMillis() - started) / 1000.0, emptyList())
        }
    }

    private fun appendDiag(target: MutableList<String>, line: String) {
        if (target.size < 250) target += line
    }
}

object ScanPersistence {
    fun saveResult(context: Context, json: String, csv: String, stamp: String) {
        context.getSharedPreferences(PREF_SCAN, 0).edit()
            .putString("last_json", json)
            .putString("last_csv", csv)
            .putString("last_time", stamp)
            .apply()
    }

    fun lastJson(context: Context): String? = context.getSharedPreferences(PREF_SCAN, 0).getString("last_json", null)
    fun lastCsv(context: Context): String? = context.getSharedPreferences(PREF_SCAN, 0).getString("last_csv", null)
}

object ScanEngine {
    fun runFull(context: Context): String {
        val diag = context.getSharedPreferences(PREF_DIAG, 0)
        diag.edit().putString("last_run_started", nowReadable()).putString("last_run_mode", "scheduled/full").apply()
        return try {
            val networks = NetworkState.summary(context)
            diag.edit().putString("network_at_scan_start", networks).apply()
            val markets = Market.fetchAllCodes()
            diag.edit().putString("dynamic_lists", "TWSE=${markets[0].codes.size}, TPEX=${markets[1].codes.size}").apply()
            val result = Market.fullAdaptiveScan(markets, 150)
            val stamp = nowStamp()
            val json = JsonBuilder.buildFull(stamp, result)
            val csv = JsonBuilder.buildCsv(stamp, result.records)
            ScanPersistence.saveResult(context, json, csv, stamp)
            diag.edit()
                .putString("last_run_finished", nowReadable())
                .putInt("last_expected", result.expected)
                .putInt("last_returned", result.returned)
                .putFloat("last_rate", result.rate.toFloat())
                .putInt("last_final_batch", result.finalBatchSize)
                .putString("last_attempts", result.attempts.joinToString("\n"))
                .apply()
            val upload = if (context.getSharedPreferences(PREF_SETTINGS, 0).getBoolean("github_auto_upload", false)) {
                GitHubUploader.uploadScan(context, json, stamp, mode = "full")
            } else "未自動上傳（功能未啟用）"
            "完整市場掃描\n上市：${result.markets.first { it.market == "TWSE" }.codes.size} 檔｜上櫃：${result.markets.first { it.market == "TPEX" }.codes.size} 檔\n全市場：${result.expected} 檔\n\n成功：${result.returned}/${result.expected}\n完整率：${"%.2f".format(result.rate)}%\n最終批次：${result.finalBatchSize} 檔/批\n失敗批次：${result.failedBatches}\n缺失：${result.missing.size} 檔\n缺失代號：${result.missing.take(30).joinToString(", ")}${if (result.missing.size > 30) " …" else ""}\n耗時：${"%.2f".format(result.seconds)} 秒\n\n${result.attempts.joinToString("\n")}\n\nGitHub：$upload"
        } catch (e: Exception) {
            diag.edit().putString("last_run_finished", nowReadable()).putString("last_run_error", "${e.javaClass.simpleName}: ${e.message}").apply()
            "自動/完整掃描失敗：${e.javaClass.simpleName}\n${e.message ?: "無詳細訊息"}\n\n網路：${NetworkState.summary(context)}"
        }
    }

    fun runManual150(context: Context): String {
        val diag = context.getSharedPreferences(PREF_DIAG, 0)
        diag.edit().putString("last_run_started", nowReadable()).putString("last_run_mode", "manual-150").apply()
        return try {
            val networks = NetworkState.summary(context)
            diag.edit().putString("network_at_scan_start", networks).apply()
            val markets = Market.fetchAllCodes()
            val r = Market.manualSingleBatch(markets, 150)
            val stamp = nowStamp()
            val sampleCodes = markets.flatMap { market -> market.codes.map { it } }.distinct().take(150)
            val missing = sampleCodes.filterNot { code -> r.records.any { it.raw.optString("c") == code } }
            val full = FullScanResult(
                expected = sampleCodes.size,
                returned = r.returned,
                rate = r.rate,
                failedBatches = r.failedBatches,
                seconds = r.seconds,
                finalBatchSize = 150,
                markets = markets,
                records = r.records,
                missing = missing,
                attempts = listOf("手動150檔單批：${r.returned}/${sampleCodes.size} = ${"%.2f".format(r.rate)}%")
            )
            val json = JsonBuilder.buildManual(stamp, full)
            val csv = JsonBuilder.buildCsv(stamp, r.records)
            ScanPersistence.saveResult(context, json, csv, stamp)
            diag.edit().putString("last_run_finished", nowReadable()).putString("last_manual_result", "${r.returned}/${r.requested}").apply()
            "手動150檔單批測試\n\n成功：${r.returned}/${r.requested}\n完整率：${"%.2f".format(r.rate)}%\n失敗批次：${r.failedBatches}\n耗時：${"%.2f".format(r.seconds)} 秒\n\n網路：$networks\n\nJSON 已保存。"
        } catch (e: Exception) {
            diag.edit().putString("last_run_error", "${e.javaClass.simpleName}: ${e.message}").apply()
            "手動150檔測試失敗：${e.javaClass.simpleName}\n${e.message ?: "無詳細訊息"}"
        }
    }
}

object JsonBuilder {
    fun buildFull(stamp: String, result: FullScanResult): String = buildJson(stamp, result, "FULL")
    fun buildManual(stamp: String, result: FullScanResult): String = buildJson(stamp, result, "MANUAL_150")

    private fun buildJson(stamp: String, result: FullScanResult, mode: String): String {
        val root = JSONObject()
        root.put("scan_time", stamp)
        root.put("mode", mode)
        root.put("twse_count", result.markets.firstOrNull { it.market == "TWSE" }?.codes?.size ?: 0)
        root.put("tpex_count", result.markets.firstOrNull { it.market == "TPEX" }?.codes?.size ?: 0)
        root.put("expected_count", result.expected)
        root.put("returned_count", result.returned)
        root.put("missing_count", result.expected - result.returned)
        root.put("completeness_rate", result.rate)
        root.put("batch_size", result.finalBatchSize)
        root.put("failed_batches", result.failedBatches)
        root.put("elapsed_seconds", result.seconds)
        root.put("missing_symbols", JSONArray(result.missing))
        root.put("stocks", JSONArray().apply { result.records.forEach { put(it.raw) } })
        return root.toString()
    }

    fun buildMarket(stamp: String, result: FullScanResult, market: String): String {
        val root = JSONObject()
        root.put("scan_time", stamp)
        root.put("mode", "FULL")
        root.put("market", market)
        root.put("stock_count", result.records.count { it.market == market })
        root.put("stocks", JSONArray().apply {
            result.records.filter { it.market == market }.forEach { put(it.raw) }
        })
        return root.toString()
    }

    fun buildCsv(stamp: String, records: List<StockRecord>): String {
        val sb = StringBuilder("scan_time,market,code,name,price,yesterday,open,high,low,volume,time,status\n")
        records.forEach { r ->
            val o = r.raw
            val row = listOf(stamp, r.market, o.optString("c"), o.optString("n"), o.optString("z"), o.optString("y"), o.optString("o"), o.optString("h"), o.optString("l"), o.optString("v"), o.optString("t"), "success")
            sb.append(row.joinToString(",") { csvEscape(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}

object NetworkState {
    fun summary(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "無 active network"
        val caps = cm.getNetworkCapabilities(network) ?: return "無 network capabilities"
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("行動網路")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "${transports.joinToString("+").ifEmpty { "其他" }}｜InternetValidated=$validated"
    }
}

object ScheduleDiagnostics {
    fun mark(context: Context, key: String, value: String = nowReadable()) {
        context.getSharedPreferences(PREF_DIAG, 0).edit().putString(key, value).apply()
    }
}

object GitHubTokenStore {
    private const val PREFS = "github_secure"
    private const val TOKEN_KEY = "token"
    private const val ALIAS = "TaiwanV2ScannerGitHubKey"

    fun hasToken(context: Context): Boolean = !load(context).isNullOrBlank()

    fun save(context: Context, token: String) {
        require(token.isNotBlank())
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generator.generateKey()
        }
        val key = ks.getKey(ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val enc = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(enc, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, 0).edit().putString(TOKEN_KEY, value).apply()
    }

    fun load(context: Context): String? {
        return try {
            val stored = context.getSharedPreferences(PREFS, 0).getString(TOKEN_KEY, null)
                ?: return null
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey(ALIAS, null) as? SecretKey ?: return null
            if (bytes.size <= 12) return null
            val iv = bytes.copyOfRange(0, 12)
            val payload = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

object GitHubUploader {
    private const val API = "https://api.github.com"
    private const val MIN_RATE = 90.0

    fun uploadScan(context: Context, json: String, stamp: String, mode: String): String {
        val token = GitHubTokenStore.load(context) ?: return "失敗：GitHub Token 未設定"
        val settings = context.getSharedPreferences(PREF_SETTINGS, 0)
        val owner = settings.getString("github_owner", "antharas730203").orEmpty().trim()
        val repo = settings.getString("github_repo", "TaiwanV2Scanner").orEmpty().trim()
        val branch = settings.getString("github_branch", "main").orEmpty().trim().ifEmpty { "main" }
        if (owner.isBlank() || repo.isBlank()) return "失敗：GitHub Repo 未設定"
        return try {
            val root = JSONObject(json)
            val rate = root.optDouble("completeness_rate", 0.0)
            val valid = rate >= MIN_RATE
            val stocks = root.optJSONArray("stocks") ?: JSONArray()
            val twse = JSONObject().apply {
                put("scan_time", root.optString("scan_time", stamp))
                put("mode", root.optString("mode", mode))
                put("market", "TWSE")
                put("stock_count", 0)
                put("stocks", JSONArray())
            }
            val tpex = JSONObject().apply {
                put("scan_time", root.optString("scan_time", stamp))
                put("mode", root.optString("mode", mode))
                put("market", "TPEX")
                put("stock_count", 0)
                put("stocks", JSONArray())
            }
            for (i in 0 until stocks.length()) {
                val stock = stocks.optJSONObject(i) ?: continue
                val market = stock.optString("market").uppercase(Locale.US)
                val target = if (market == "TPEX") tpex else twse
                target.optJSONArray("stocks")?.put(stock)
                target.put("stock_count", target.optInt("stock_count") + 1)
            }
            val category = when (mode) {
                "test" -> "test"
                else -> if (valid) "history" else "failed"
            }
            // History is split by market so each historical file remains small enough to read.
            val historyTwsePath = "scanner_data/$category/${stamp}_TWSE.json"
            val historyTpexPath = "scanner_data/$category/${stamp}_TPEX.json"
            val historyTwse = if (valid || mode == "test") putFile(token, owner, repo, branch, historyTwsePath, twse.toString(), "Add scanner $category TWSE $stamp") else "不保存 TWSE（完整率不足）"
            val historyTpex = if (valid || mode == "test") putFile(token, owner, repo, branch, historyTpexPath, tpex.toString(), "Add scanner $category TPEX $stamp") else "不保存 TPEX（完整率不足）"
            if (mode == "full" && valid) {
                val latestTwse = putFile(token, owner, repo, branch, "scanner_data/latest_TWSE.json", twse.toString(), "Update latest_TWSE $stamp")
                val latestTpex = putFile(token, owner, repo, branch, "scanner_data/latest_TPEX.json", tpex.toString(), "Update latest_TPEX $stamp")
                "$latestTwse｜$latestTpex｜$historyTwse｜$historyTpex"
            } else {
                val reason = if (mode == "full" && !valid) "完整率 ${"%.2f".format(rate)}% < ${MIN_RATE.toInt()}%，不更新 latest_TWSE/latest_TPEX" else "測試資料不覆蓋 latest_TWSE/latest_TPEX"
                "$historyTwse｜$historyTpex｜$reason"
            }
        } catch (e: Exception) {
            "失敗：GitHub ${e.javaClass.simpleName} - ${e.message ?: "無詳細訊息"}"
        }
    }

    private fun putFile(token: String, owner: String, repo: String, branch: String, path: String, text: String, message: String): String {
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
            setRequestProperty("User-Agent", "TaiwanV2Scanner/0.6.6")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = try { conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } catch (_: Exception) { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty() }
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("$path HTTP $code ${extractMessage(body)}")
        return "成功：$path"
    }

    private fun getSha(token: String, owner: String, repo: String, branch: String, path: String): String? {
        val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val conn = (URL("$API/repos/$owner/$repo/contents/$encodedPath?ref=${URLEncoder.encode(branch, "UTF-8")}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "TaiwanV2Scanner/0.6.6")
        }
        return try {
            val code = conn.responseCode
            if (code == 404) {
                null
            } else if (code !in 200..299) {
                val body = try { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty() } catch (_: Exception) { "" }
                throw IllegalStateException("讀取 $path HTTP $code ${extractMessage(body)}")
            } else {
                org.json.JSONObject(
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                ).optString("sha").takeIf { it.isNotBlank() }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractMessage(body: String): String = try { JSONObject(body).optString("message").ifBlank { body.take(160) } } catch (_: Exception) { body.take(160) }
}

object ScanScheduler {
    const val DEFAULT_TIMES = DEFAULT_SCHEDULE_TIMES
    private const val BASE_ID = 2426005

    fun parseAndValidate(raw: String): List<Pair<Int, Int>> {
        val source = raw.trim().ifEmpty { DEFAULT_TIMES }
        val tokens = source.split(',', '，', ';', '；', ' ', '\n', '\t').filter { it.isNotBlank() }
        val times = tokens.map { token ->
            val parts = token.trim().split(':')
            if (parts.size != 2) throw IllegalArgumentException("格式錯誤：$token，請用 HH:MM")
            val h = parts[0].toIntOrNull() ?: throw IllegalArgumentException("無效小時：$token")
            val m = parts[1].toIntOrNull() ?: throw IllegalArgumentException("無效分鐘：$token")
            if (h !in 0..23 || m !in 0..59) throw IllegalArgumentException("時間超出範圍：$token")
            h to m
        }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (times.isEmpty()) throw IllegalArgumentException("至少設定一個排程時間")
        return times
    }

    fun isTradingSessionNow(calendar: Calendar = Calendar.getInstance()): Boolean {
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) return false
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return minutes in (9 * 60)..(13 * 60 + 30)
    }

    fun tradingSessionSkipReason(calendar: Calendar = Calendar.getInstance()): String {
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) return "週末／非交易日"
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return if (minutes < 9 * 60) "尚未開盤（09:00前）" else "已超過盤中時段（13:30後）"
    }

    fun schedule(context: Context, raw: String = DEFAULT_TIMES) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        cancel(context)
        parseAndValidate(raw).forEachIndexed { index, hm ->
            val pi = android.app.PendingIntent.getBroadcast(
                context, BASE_ID + index, android.content.Intent(context, ScanAlarmReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hm.first)
                set(Calendar.MINUTE, hm.second)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            alarm.setInexactRepeating(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, 24L * 60L * 60L * 1000L, pi)
        }
        context.getSharedPreferences(PREF_DIAG, 0).edit().putString("schedule_configured", raw).apply()
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        for (i in 0 until 64) {
            val pi = android.app.PendingIntent.getBroadcast(
                context, BASE_ID + i, android.content.Intent(context, ScanAlarmReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            alarm.cancel(pi)
            pi.cancel()
        }
    }
}

class ScanAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent?) {
        val prefs = context.getSharedPreferences(PREF_SETTINGS, 0)
        if (!prefs.getBoolean("auto", false)) return
        val mode = prefs.getString("schedule_mode", "trading") ?: "trading"
        ScheduleDiagnostics.mark(context, "last_schedule_trigger")
        ScheduleDiagnostics.mark(context, "last_schedule_mode", mode)
        ScheduleDiagnostics.mark(context, "last_schedule_network", NetworkState.summary(context))
        if (mode == "trading") {
            val marketStatus = MarketStatus.check()
            ScheduleDiagnostics.mark(context, "last_market_status", marketStatus.reason)
            ScheduleDiagnostics.mark(context, "last_market_status_source", marketStatus.source)
            ScheduleDiagnostics.mark(context, "last_market_status_sample", marketStatus.sampleReturned.toString())
            if (!marketStatus.ok) {
                ScheduleDiagnostics.mark(context, "last_schedule_skip", marketStatus.reason)
                return
            }
        }
        val request = OneTimeWorkRequestBuilder<ScheduledScanWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("taiwan_v2_scheduled_scan")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "taiwan_v2_scheduled_scan",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}

class ScheduledScanWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        ScheduleDiagnostics.mark(applicationContext, "last_worker_started")
        return try {
            val report = ScanEngine.runFull(applicationContext)
            ScheduleDiagnostics.mark(applicationContext, "last_worker_finished")
            applicationContext.getSharedPreferences(PREF_DIAG, 0).edit().putString("last_worker_report", report.take(3000)).apply()
            Result.success()
        } catch (e: Exception) {
            ScheduleDiagnostics.mark(applicationContext, "last_worker_error", "${e.javaClass.simpleName}: ${e.message}")
            Result.retry()
        }
    }
}
