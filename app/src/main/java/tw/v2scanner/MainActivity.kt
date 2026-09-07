package tw.v2scanner

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var auto: CheckBox
    private lateinit var autoGitHub: CheckBox
    private lateinit var scheduleModeGroup: RadioGroup
    private lateinit var scheduleTimes: EditText
    private lateinit var githubOwner: EditText
    private lateinit var githubRepo: EditText
    private lateinit var githubBranch: EditText
    private lateinit var githubToken: EditText
    private val prefs by lazy { getSharedPreferences("settings", 0) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshStatus()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        root.addView(TextView(this).apply {
            text = "台股 V2 掃描器 V0.8.0"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "TWSE＋TPEX｜完整掃描｜第一層轉機／動能預篩｜原始 JSON｜6000 字元安全分批｜GitHub"
            textSize = 13f
        })

        status = TextView(this).apply {
            textSize = 15f
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(status)

        val scroll = ScrollView(this)
        val config = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(config)

        auto = CheckBox(this).apply {
            text = "啟用自動排程"
            isChecked = prefs.getBoolean("auto", false)
        }
        autoGitHub = CheckBox(this).apply {
            text = "掃描完成後自動上傳 GitHub"
            isChecked = prefs.getBoolean("github_auto_upload", false)
        }
        config.addView(auto)
        config.addView(autoGitHub)

        config.addView(TextView(this).apply { text = "排程模式" })
        scheduleModeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val trading = RadioButton(this).apply {
            text = "交易時段模式（09:00～13:30）"
            id = View.generateViewId()
            isChecked = prefs.getString("schedule_mode", "trading") != "test"
        }
        val test = RadioButton(this).apply {
            text = "測試模式（不限交易時段）"
            id = View.generateViewId()
            isChecked = prefs.getString("schedule_mode", "trading") == "test"
        }
        scheduleModeGroup.addView(trading)
        scheduleModeGroup.addView(test)
        config.addView(scheduleModeGroup)

        config.addView(TextView(this).apply { text = "排程時間" })
        scheduleTimes = EditText(this).apply {
            setText(prefs.getString("schedule_times", ScanScheduler.DEFAULT_TIMES))
            hint = "09:05,10:05,11:05,12:05,13:05"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        config.addView(scheduleTimes)

        config.addView(TextView(this).apply {
            text = "GitHub 設定"
            textSize = 16f
        })
        githubOwner = edit("擁有者", prefs.getString("github_owner", "antharas730203"))
        githubRepo = edit("Repository", prefs.getString("github_repo", "TaiwanV2Scanner"))
        githubBranch = edit("分支", prefs.getString("github_branch", "main"))
        githubToken = edit("Token（留白代表保留既有 Token）", null).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        config.addView(githubOwner)
        config.addView(githubRepo)
        config.addView(githubBranch)
        config.addView(githubToken)

        val save = button("儲存設定／排程")
        val scan = button("手動完整掃描")
        val verify = button("驗證 GitHub")
        val exportJson = button("匯出最後一次 JSON")
        val exportCsv = button("匯出最後一次 CSV")
        val share = button("分享最後一次 JSON")
        val diag = button("查看排程診斷")
        listOf(save, scan, verify, exportJson, exportCsv, share, diag).forEach { config.addView(it) }

        save.setOnClickListener { saveSettings(trading.isChecked) }
        scan.setOnClickListener {
            scan.isEnabled = false
            status.text = "正在完整掃描＋第一層……"
            Thread {
                val report = ScanEngine.runFull(this)
                runOnUiThread {
                    result.text = report
                    status.text = "手動掃描＋第一層完成"
                    scan.isEnabled = true
                    refreshStatus()
                }
            }.start()
        }
        verify.setOnClickListener {
            Toast.makeText(this, "GitHub 驗證功能沿用既有核心", Toast.LENGTH_SHORT).show()
        }
        exportJson.setOnClickListener { exportLast("json") }
        exportCsv.setOnClickListener { exportLast("csv") }
        share.setOnClickListener { shareLastJson() }
        diag.setOnClickListener { showDiagnostics() }

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "執行結果"
            textSize = 17f
        })
        result = TextView(this).apply {
            text = "尚未執行掃描。"
            textSize = 14f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val resultScroll = ScrollView(this).apply { addView(result) }
        root.addView(resultScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun saveSettings(tradingMode: Boolean) {
        try {
            val times = scheduleTimes.text.toString().trim().ifEmpty { ScanScheduler.DEFAULT_TIMES }
            ScanScheduler.parseAndValidate(times)
            val token = githubToken.text.toString().trim()
            if (token.isNotEmpty()) GitHubTokenStore.save(this, token)
            prefs.edit()
                .putBoolean("auto", auto.isChecked)
                .putBoolean("github_auto_upload", autoGitHub.isChecked)
                .putString("schedule_mode", if (tradingMode) "trading" else "test")
                .putString("schedule_times", times)
                .putString("github_owner", githubOwner.text.toString().trim())
                .putString("github_repo", githubRepo.text.toString().trim())
                .putString("github_branch", githubBranch.text.toString().trim().ifEmpty { "main" })
                .apply()
            if (auto.isChecked) ScanScheduler.schedule(this, times) else ScanScheduler.cancel(this)
            githubToken.text.clear()
            refreshStatus()
            Toast.makeText(this, "設定已儲存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            status.text = "設定失敗：${e.message ?: "未知錯誤"}"
        }
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val token = if (GitHubTokenStore.hasToken(this)) "已設定" else "未設定"
        val times = prefs.getString("schedule_times", ScanScheduler.DEFAULT_TIMES) ?: ScanScheduler.DEFAULT_TIMES
        val diag = getSharedPreferences("diagnostics", 0)
        val l1 = diag.getString("layer1_status", "尚未執行") ?: "尚未執行"
        status.text = "排程：${if (prefs.getBoolean("auto", false)) "已啟用" else "未啟用"}\n時間：$times\nGitHub Token：$token\n第一層：$l1"
    }

    private fun exportLast(type: String) {
        val content = if (type == "json") ScanPersistence.lastJson(this) else ScanPersistence.lastCsv(this)
        if (content.isNullOrEmpty()) {
            Toast.makeText(this, "尚未有掃描資料", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(getExternalFilesDir(null), "exports")
        dir.mkdirs()
        val file = File(dir, "TaiwanV2Scanner_${System.currentTimeMillis()}.$type")
        file.writeText(content, Charsets.UTF_8)
        Toast.makeText(this, "已匯出：${file.name}", Toast.LENGTH_SHORT).show()
    }

    private fun shareLastJson() {
        val content = ScanPersistence.lastJson(this)
        if (content.isNullOrEmpty()) {
            Toast.makeText(this, "尚未有 JSON", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(getExternalFilesDir(null), "share")
        dir.mkdirs()
        val file = File(dir, "TaiwanV2Scanner_latest.json")
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        startActivity(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun showDiagnostics() {
        val diag = getSharedPreferences("diagnostics", 0)
        result.text = buildString {
            append("最近掃描\n")
            append("開始：${diag.getString("last_run_started", "無")}\n")
            append("完成：${diag.getString("last_run_finished", "無")}\n")
            append("預期：${diag.getInt("last_expected", 0)}\n")
            append("取得：${diag.getInt("last_returned", 0)}\n")
            append("完整率：${diag.getFloat("last_rate", 0f)}\n")
            append("安全批次：${diag.getInt("last_safe_batch_count", 0)}\n")
            append("最大批次字元：${diag.getInt("last_safe_batch_max_chars", 0)}\n")
            append("第一層：${diag.getString("layer1_status", "尚未執行")}\n")
            append("第一層候選：${diag.getInt("layer1_count", 0)}")
        }
    }

    private fun edit(hintText: String, value: String?): EditText = EditText(this).apply {
        hint = hintText
        if (value != null) setText(value)
        setSingleLine(true)
    }

    private fun button(textValue: String) = Button(this).apply {
        text = textValue
        setAllCaps(false)
        gravity = Gravity.CENTER
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
