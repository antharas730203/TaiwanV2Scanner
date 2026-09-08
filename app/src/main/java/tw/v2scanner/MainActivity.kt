package tw.v2scanner

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
    private val prefs by lazy { getSharedPreferences("settings", 0) }
    private var showingSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        showMain()
    }

    private fun showMain() {
        showingSettings = false
        setContentView(buildMainUi())
        refreshStatus()
    }

    private fun showSettings() {
        showingSettings = true
        setContentView(buildSettingsUi())
    }

    private fun buildMainUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "台股 V2 掃描器 V0.8.1"
            textSize = 24f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "⚙"
            setAllCaps(false)
            contentDescription = "設定"
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "TWSE＋TPEX｜完整掃描｜第一層轉機／動能預篩｜原始 JSON｜6000 字元安全分批｜GitHub"
            textSize = 13f
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(status)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actions.addView(button("手動完整掃描").apply { setOnClickListener { runFullScan(this) } })
        actions.addView(button("驗證 GitHub").apply { setOnClickListener { verifyGitHubButton(this) } })
        actions.addView(button("匯出最後一次 JSON").apply { setOnClickListener { exportLast("json") } })
        actions.addView(button("匯出最後一次 CSV").apply { setOnClickListener { exportLast("csv") } })
        actions.addView(button("分享最後一次 JSON").apply { setOnClickListener { shareLastJson() } })
        actions.addView(button("查看排程診斷").apply { setOnClickListener { showDiagnostics() } })
        root.addView(actions)
        root.addView(TextView(this).apply { text = "執行結果"; textSize = 17f })
        result = TextView(this).apply {
            text = "尚未執行掃描。"
            textSize = 14f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(ScrollView(this).apply { addView(result) }, LinearLayout.LayoutParams(-1, 0, 1f))
        applyDarkTheme(root)
        return root
    }

    private fun buildSettingsUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(TextView(this).apply { text = "設定"; textSize = 24f })
        val scroll = ScrollView(this)
        val config = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(config)

        config.addView(TextView(this).apply { text = "GitHub 設定"; textSize = 18f })
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
        config.addView(button("驗證 GitHub").apply {
            setOnClickListener { verifyGitHubButton(this) }
        })

        config.addView(TextView(this).apply { text = "自動排程"; textSize = 18f })
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

        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
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
        group.addView(trading)
        group.addView(test)
        config.addView(group)

        config.addView(TextView(this).apply { text = "排程時間" })
        scheduleTimes = EditText(this).apply {
            setText(prefs.getString("schedule_times", ScanScheduler.DEFAULT_TIMES))
            hint = "09:05,10:05,11:05,12:05,13:05"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        config.addView(scheduleTimes)
        config.addView(button("儲存設定").apply { setOnClickListener { saveSettings(trading.isChecked) } })

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button("返回主畫面").apply { setOnClickListener { showMain() } })
        applyDarkTheme(root)
        return root
    }

    private fun applyDarkTheme(view: View) {
        view.setBackgroundColor(Color.BLACK)
        if (view is TextView) {
            view.setTextColor(Color.WHITE)
            if (view is EditText) view.setHintTextColor(Color.GRAY)
        }
        if (view is Button) {
            view.setBackgroundColor(Color.rgb(45, 45, 45))
            view.setTextColor(Color.WHITE)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyDarkTheme(view.getChildAt(i))
        }
    }

    private fun runFullScan(scanButton: View) {
        scanButton.isEnabled = false
        status.text = "正在完整掃描＋第一層……"
        Thread {
            val report = ScanEngine.runFull(this)
            runOnUiThread {
                result.text = report
                status.text = "手動掃描＋第一層完成"
                scanButton.isEnabled = true
                refreshStatus()
            }
        }.start()
    }

    private fun verifyGitHubButton(button: View) {
        button.isEnabled = false
        status.text = "正在驗證 GitHub Token／Repository……"
        result.text = "正在向 GitHub API 驗證目前 Token。\n\n這一步只讀取 Repository，不會修改任何檔案。"
        Thread {
            val report = verifyGitHubAccess()
            runOnUiThread {
                status.text = report.lineSequence().firstOrNull() ?: report
                result.text = report
                button.isEnabled = true
            }
        }.start()
    }

    private fun verifyGitHubAccess(): String {
        val token = GitHubTokenStore.load(this)
            ?: return "GitHub 驗證失敗：Token 未設定。\n\n請在 GitHub 設定中貼上新的 Fine-grained PAT，儲存後再驗證。"
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
                setRequestProperty("User-Agent", "TaiwanV2Scanner/0.8.2")
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
                401 -> "GitHub 驗證失敗：HTTP 401\n\nToken 無效、已撤銷、過期，或 App 裡保存的 Token 已失效。\n\n請重新貼上目前有效的 Fine-grained PAT。"
                403 -> "GitHub 驗證失敗：HTTP 403\n\nToken 已被 GitHub 辨識，但權限不足。請確認 Token 對 TaiwanV2Scanner 的 Contents 具有 Read and write 權限。"
                404 -> "GitHub 驗證失敗：HTTP 404\n\nRepository 找不到，或 Token 沒有被授權存取 $owner/$repo。"
                else -> "GitHub 驗證失敗：HTTP $code\n\nGitHub 回應：${body.take(300)}"
            }
        } catch (e: Exception) {
            "GitHub 驗證失敗：${e.javaClass.simpleName}\n\n${e.message ?: "無詳細訊息"}"
        }
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

            ScanScheduler.cancel(this)
            if (auto.isChecked) {
                if (!ExactScanScheduler.canScheduleExact(this)) {
                    ScheduleDiagnostics.mark(this, "schedule_engine", "EXACT_ALARM_PERMISSION_MISSING")
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                    Toast.makeText(this, "請允許『鬧鐘與提醒』後，再按一次「儲存設定」", Toast.LENGTH_LONG).show()
                    return
                }
                ExactScanScheduler.schedule(this, times)
            } else {
                ExactScanScheduler.cancel(this)
            }
            githubToken.text.clear()
            Toast.makeText(this, "設定已儲存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "設定失敗：${e.message ?: "未知錯誤"}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onBackPressed() {
        if (showingSettings) showMain() else super.onBackPressed()
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val token = if (GitHubTokenStore.hasToken(this)) "已設定" else "未設定"
        val times = prefs.getString("schedule_times", ScanScheduler.DEFAULT_TIMES) ?: ScanScheduler.DEFAULT_TIMES
        val diag = getSharedPreferences("diagnostics", 0)
        val l1 = diag.getString("layer1_status", "尚未執行") ?: "尚未執行"
        val engine = diag.getString("schedule_engine", "EXACT_ALARM") ?: "EXACT_ALARM"
        status.text = "排程：${if (prefs.getBoolean("auto", false)) "已啟用" else "未啟用"}\n時間：$times\n排程引擎：$engine\nGitHub Token：$token\n第一層：$l1"
    }

    private fun exportLast(type: String) {
        val content = if (type == "json") ScanPersistence.lastJson(this) else ScanPersistence.lastCsv(this)
        if (content.isNullOrEmpty()) { Toast.makeText(this, "尚未有掃描資料", Toast.LENGTH_SHORT).show(); return }
        val dir = File(getExternalFilesDir(null), "exports")
        dir.mkdirs()
        val file = File(dir, "TaiwanV2Scanner_${System.currentTimeMillis()}.$type")
        file.writeText(content, Charsets.UTF_8)
        Toast.makeText(this, "已匯出：${file.name}", Toast.LENGTH_SHORT).show()
    }

    private fun shareLastJson() {
        val content = ScanPersistence.lastJson(this)
        if (content.isNullOrEmpty()) { Toast.makeText(this, "尚未有 JSON", Toast.LENGTH_SHORT).show(); return }
        val dir = File(getExternalFilesDir(null), "share")
        dir.mkdirs()
        val file = File(dir, "TaiwanV2Scanner_latest.json")
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        startActivity(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
