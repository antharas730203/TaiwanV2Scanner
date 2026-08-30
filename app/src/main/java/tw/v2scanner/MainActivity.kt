package tw.v2scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var auto: CheckBox
    private lateinit var autoGitHub: CheckBox
    private lateinit var scheduleTimes: EditText
    private lateinit var githubOwner: EditText
    private lateinit var githubRepo: EditText
    private lateinit var githubBranch: EditText
    private lateinit var githubToken: EditText
    private lateinit var scan: Button
    private lateinit var testGitHub: Button
    private lateinit var verifyGitHub: Button
    private lateinit var githubAdvanced: View
    private val prefs by lazy { getSharedPreferences("settings", 0) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(245, 246, 248)
        window.navigationBarColor = Color.rgb(245, 246, 248)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.rgb(245, 246, 248))
        }

        val title = TextView(this).apply {
            text = "台股 V2 掃描器 V0.6.4"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(30, 33, 38))
        }
        val subtitle = TextView(this).apply {
            text = "上市＋上櫃・動態筆數・150檔基準・自動降批・可編輯排程・GitHub同步"
            textSize = 13.5f
            setTextColor(Color.rgb(95, 100, 110))
            setPadding(0, dp(2), 0, dp(10))
        }
        root.addView(title)
        root.addView(subtitle)

        val statusCard = cardContainer()
        val statusLabel = TextView(this).apply {
            text = "目前狀態"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(78, 84, 94))
        }
        status = TextView(this).apply {
            textSize = 15.5f
            setTextColor(Color.rgb(30, 33, 38))
            setPadding(0, dp(4), 0, 0)
        }
        statusCard.addView(statusLabel)
        statusCard.addView(status)
        root.addView(statusCard, LinearLayout.LayoutParams(-1, dp(72)))

        val configScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val config = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        configScroll.addView(config)

        auto = CheckBox(this).apply {
            text = "啟用盤中自動掃描"
            textSize = 15f
            isChecked = prefs.getBoolean("auto", false)
        }
        autoGitHub = CheckBox(this).apply {
            text = "掃描完成後自動上傳 GitHub"
            textSize = 15f
            isChecked = prefs.getBoolean("github_auto_upload", false)
        }
        config.addView(auto)
        config.addView(autoGitHub)

        val scheduleLabel = sectionLabel("排程時間（可自訂）")
        config.addView(scheduleLabel)
        scheduleTimes = EditText(this).apply {
            hint = "例如 09:05,10:05,11:05,12:05,13:05"
            setText(prefs.getString("schedule_times", ScanScheduler.DEFAULT_TIMES))
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setBackground(roundStroke(Color.WHITE, Color.rgb(210, 214, 221), 10))
            setPadding(dp(12), 0, dp(12), 0)
        }
        config.addView(scheduleTimes, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(6) })

        val advancedToggle = Button(this).apply {
            text = "GitHub 設定 ▾"
            textSize = 14f
            setAllCaps(false)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            setBackground(roundStroke(Color.WHITE, Color.rgb(205, 209, 216), 10))
        }
        config.addView(advancedToggle, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })

        githubAdvanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(4))
        }
        githubOwner = smallEdit("GitHub 擁有者", prefs.getString("github_owner", "antharas730203"))
        githubRepo = smallEdit("GitHub Repository", prefs.getString("github_repo", "TaiwanV2Scanner"))
        githubBranch = smallEdit("GitHub 分支", prefs.getString("github_branch", "main"))
        githubToken = smallEdit(
            if (GitHubTokenStore.hasToken(this)) "GitHub Token（已設定；留白保留）" else "GitHub Token（第一次請輸入）",
            null
        ).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val githubAdvancedLayout = githubAdvanced as LinearLayout
        githubAdvancedLayout.addView(githubOwner, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) })
        githubAdvancedLayout.addView(githubRepo, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) })
        githubAdvancedLayout.addView(githubBranch, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) })
        githubAdvancedLayout.addView(githubToken, LinearLayout.LayoutParams(-1, dp(48)))
        config.addView(githubAdvanced)
        advancedToggle.setOnClickListener {
            githubAdvanced.visibility = if (githubAdvanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            advancedToggle.text = if (githubAdvanced.visibility == View.VISIBLE) "GitHub 設定 ▴" else "GitHub 設定 ▾"
        }

        val actionGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val row1 = buttonRow()
        val save = actionButton("儲存設定／排程")
        scan = actionButton("手動測試：150檔／1批")
        row1.addView(save, buttonWeightParams())
        row1.addView(scan, buttonWeightParams().apply { leftMargin = dp(6) })
        actionGrid.addView(row1)

        val row2 = buttonRow()
        testGitHub = actionButton("測試 GitHub 上傳 JSON")
        verifyGitHub = actionButton("驗證 GitHub Token")
        row2.addView(testGitHub, buttonWeightParams())
        row2.addView(verifyGitHub, buttonWeightParams().apply { leftMargin = dp(6) })
        actionGrid.addView(row2)

        val row3 = buttonRow()
        val exportJson = actionButton("匯出最後一次 JSON")
        val exportCsv = actionButton("匯出最後一次 CSV")
        row3.addView(exportJson, buttonWeightParams())
        row3.addView(exportCsv, buttonWeightParams().apply { leftMargin = dp(6) })
        actionGrid.addView(row3)

        val row4 = buttonRow()
        val shareJson = actionButton("分享最後一次 JSON")
        val showDiag = actionButton("查看排程診斷")
        row4.addView(shareJson, buttonWeightParams())
        row4.addView(showDiag, buttonWeightParams().apply { leftMargin = dp(6) })
        actionGrid.addView(row4)

        config.addView(actionGrid)
        root.addView(configScroll, LinearLayout.LayoutParams(-1, 0, 1.0f))

        val resultHeader = TextView(this).apply {
            text = "執行結果"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(45, 50, 58))
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        root.addView(resultHeader)

        val resultCard = cardContainer().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        val resultScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        result = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(45, 50, 58))
            setLineSpacing(0f, 1.08f)
            text = "尚未執行掃描。\n\n手動測試會以 150 檔／1 批執行；完整掃描則依動態清單與自動降批機制執行。"
        }
        resultScroll.addView(result)
        resultCard.addView(resultScroll, LinearLayout.LayoutParams(-1, -1))
        root.addView(resultCard, LinearLayout.LayoutParams(-1, 0, 1.75f))

        setContentView(root)
        refreshStatus()

        save.setOnClickListener {
            try {
                val normalizedTimes = scheduleTimes.text.toString().trim().ifEmpty { ScanScheduler.DEFAULT_TIMES }
                ScanScheduler.parseAndValidate(normalizedTimes)
                val token = githubToken.text.toString().trim()
                if (token.isNotEmpty()) GitHubTokenStore.save(this, token)
                prefs.edit()
                    .putBoolean("auto", auto.isChecked)
                    .putBoolean("github_auto_upload", autoGitHub.isChecked)
                    .putString("schedule_times", normalizedTimes)
                    .putString("github_owner", githubOwner.text.toString().trim())
                    .putString("github_repo", githubRepo.text.toString().trim())
                    .putString("github_branch", githubBranch.text.toString().trim().ifEmpty { "main" })
                    .apply()
                if (auto.isChecked) ScanScheduler.schedule(this, normalizedTimes) else ScanScheduler.cancel(this)
                status.text = "設定已儲存\n排程：${if (auto.isChecked) normalizedTimes else "未啟用"}｜GitHub：${if (autoGitHub.isChecked && GitHubTokenStore.hasToken(this)) "已啟用" else "未啟用"}"
                githubToken.setText("")
                githubToken.hint = "GitHub Token（已設定；留白保留）"
            } catch (e: Exception) {
                status.text = "設定失敗：${e.message ?: "無詳細訊息"}"
            }
        }

        scan.setOnClickListener {
            scan.isEnabled = false
            status.text = "手動150檔／1批掃描中……"
            result.text = "正在取得動態上市／上櫃清單並執行 150 檔單批測試……"
            Thread {
                val report = ScanEngine.runManual150(this)
                runOnUiThread {
                    result.text = report
                    status.text = "手動測試完成"
                    scan.isEnabled = true
                }
            }.start()
        }

        testGitHub.setOnClickListener {
            val json = ScanPersistence.lastJson(this)
            if (json == null) {
                Toast.makeText(this, "請先完成一次掃描。", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            testGitHub.isEnabled = false
            status.text = "GitHub 測試上傳中……"
            result.text = "正在將最後一次 JSON 上傳至 GitHub……"
            Thread {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val report = GitHubUploader.uploadScan(this, json, stamp, "test")
                runOnUiThread {
                    status.text = report
                    result.text = report
                    testGitHub.isEnabled = true
                }
            }.start()
        }

        verifyGitHub.setOnClickListener {
            verifyGitHub.isEnabled = false
            status.text = "正在驗證 GitHub Token／Repository……"
            result.text = "正在向 GitHub API 驗證目前 Token。\n\n這一步只讀取 Repository，不會修改任何檔案。"
            Thread {
                val report = verifyGitHubAccess()
                runOnUiThread {
                    status.text = report.lineSequence().firstOrNull() ?: report
                    result.text = report
                    verifyGitHub.isEnabled = true
                }
            }.start()
        }

        exportJson.setOnClickListener { exportLast("json") }
        exportCsv.setOnClickListener { exportLast("csv") }
        shareJson.setOnClickListener { shareLastJson() }
        showDiag.setOnClickListener { showDiagnostics() }
    }

    private fun verifyGitHubAccess(): String {
        val token = GitHubTokenStore.load(this)
            ?: return "GitHub 驗證失敗：Token 未設定。\n\n請展開「GitHub 設定」，貼上新的 Fine-grained PAT，按「儲存設定／排程」後再驗證。"
        val owner = prefs.getString("github_owner", "antharas730203").orEmpty().trim()
        val repo = prefs.getString("github_repo", "TaiwanV2Scanner").orEmpty().trim()
        val branch = prefs.getString("github_branch", "main").orEmpty().trim().ifEmpty { "main" }
        if (owner.isBlank() || repo.isBlank()) return "GitHub 驗證失敗：Repository 設定不完整。"
        return try {
            val url = URL("https://api.github.com/repos/$owner/$repo")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "TaiwanV2Scanner/0.6.4")
            }
            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } finally {
                conn.disconnect()
            }
            when (code) {
                200 -> {
                    val obj = JSONObject(body)
                    "GitHub 驗證成功 ✓\n\nRepository：${obj.optString("full_name", "$owner/$repo")}\n分支：$branch\nToken：有效\n\n目前已確認 App 可以用這組 Token 讀取 Repository。\n接下來「測試 GitHub 上傳 JSON」即可確認 Contents 寫入。"
                }
                401 -> "GitHub 驗證失敗：HTTP 401\n\nToken 無效、已撤銷、過期，或 App 裡保存的不是你剛建立的 Token。\n\n請重新貼上目前有效的 Fine-grained PAT，重新儲存。"
                403 -> "GitHub 驗證失敗：HTTP 403\n\nToken 本身已被辨識，但權限不足。請確認這個 Token 對 TaiwanV2Scanner 的 Contents 是 Read and write。"
                404 -> "GitHub 驗證失敗：HTTP 404\n\nRepository 找不到或 Token 沒有被授權存取 $owner/$repo。"
                else -> "GitHub 驗證失敗：HTTP $code\n\n${extractApiMessage(body)}"
            }
        } catch (e: Exception) {
            "GitHub 驗證失敗：${e.javaClass.simpleName}\n\n${e.message ?: "無詳細訊息"}"
        }
    }

    private fun extractApiMessage(body: String): String = try {
        JSONObject(body).optString("message").ifBlank { body.take(240) }
    } catch (_: Exception) {
        body.take(240)
    }

    private fun refreshStatus() {
        val tokenText = if (GitHubTokenStore.hasToken(this)) "已設定" else "未設定"
        val times = prefs.getString("schedule_times", ScanScheduler.DEFAULT_TIMES) ?: ScanScheduler.DEFAULT_TIMES
        status.text = "排程：${if (auto.isChecked) "已啟用" else "未啟用"}｜${times}\nGitHub Token：$tokenText"
    }

    private fun showDiagnostics() {
        val p = getSharedPreferences("diagnostics", 0)
        result.text = buildString {
            append("排程診斷\n\n")
            append("排程設定：${p.getString("schedule_configured", "無")}\n")
            append("最近排程觸發：${p.getString("last_schedule_trigger", "無")}\n")
            append("排程當時網路：${p.getString("last_schedule_network", "無")}\n")
            append("最近 Worker 啟動：${p.getString("last_worker_started", "無")}\n")
            append("最近掃描開始：${p.getString("last_run_started", "無")}\n")
            append("掃描模式：${p.getString("last_run_mode", "無")}\n")
            append("動態清單：${p.getString("dynamic_lists", "無")}\n")
            append("最近完成：${p.getString("last_run_finished", "無")}\n")
            append("成功：${p.getInt("last_returned", -1)} / ${p.getInt("last_expected", -1)}\n")
            append("完整率：${p.getFloat("last_rate", -1f)}%\n")
            append("最終批次：${p.getInt("last_final_batch", -1)}\n")
            append("最近錯誤：${p.getString("last_run_error", "無")}\n\n")
            append(p.getString("last_attempts", "無批次診斷") ?: "無批次診斷")
        }
    }

    private fun exportLast(ext: String) {
        val content = if (ext == "json") ScanPersistence.lastJson(this) else ScanPersistence.lastCsv(this)
        if (content == null) {
            Toast.makeText(this, "請先完成一次掃描。", Toast.LENGTH_LONG).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(getExternalFilesDir(null), "TaiwanV2Scanner_$stamp.$ext")
        file.writeText(content, Charsets.UTF_8)
        shareFile(file, if (ext == "json") "application/json" else "text/csv")
    }

    private fun shareLastJson() {
        val content = ScanPersistence.lastJson(this)
        if (content == null) {
            Toast.makeText(this, "請先完成一次掃描。", Toast.LENGTH_LONG).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(cacheDir, "TaiwanV2Scanner_$stamp.json")
        file.writeText(content, Charsets.UTF_8)
        shareFile(file, "application/json")
    }

    private fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享資料"))
    }

    private fun cardContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundStroke(Color.WHITE, Color.rgb(218, 221, 227), 14)
        elevation = dp(1).toFloat()
    }

    private fun sectionLabel(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.rgb(82, 87, 96))
        setPadding(dp(2), dp(4), dp(2), dp(4))
    }

    private fun smallEdit(hintValue: String, value: String?): EditText = EditText(this).apply {
        hint = hintValue
        if (value != null) setText(value)
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        setBackground(roundStroke(Color.WHITE, Color.rgb(210, 214, 221), 10))
        setPadding(dp(12), 0, dp(12), 0)
    }

    private fun actionButton(textValue: String) = Button(this).apply {
        text = textValue
        textSize = 13.5f
        setAllCaps(false)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(4), 0, dp(4), 0)
        background = roundStroke(Color.WHITE, Color.rgb(205, 209, 216), 10)
    }

    private fun buttonRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun buttonWeightParams() = LinearLayout.LayoutParams(0, dp(48), 1f)

    private fun roundStroke(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
