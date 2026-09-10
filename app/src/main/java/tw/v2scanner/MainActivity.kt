package tw.v2scanner

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var drawer: View
    private val prefs by lazy { getSharedPreferences("settings", 0) }
    private var drawerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        showMain()
    }

    private fun showMain() {
        setContentView(buildMainUi())
        refreshStatus()
    }

    private fun buildMainUi(): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(16))
            setBackgroundColor(Color.BLACK)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(roundButton("☰", 52, false).apply {
            contentDescription = "開啟選單"
            setOnClickListener { toggleDrawer(true) }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        header.addView(TextView(this).apply {
            text = "台股 V2 掃描器"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardBackground()
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(14) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actions.addView(roundButton("手動完整掃描", 58, true).apply {
            setOnClickListener { runFullScan(this) }
        }, actionParams(0))
        actions.addView(roundButton("驗證 GitHub", 50, false).apply {
            setOnClickListener { verifyGitHubButton(this) }
        }, actionParams(10))
        actions.addView(roundButton("匯出最新 JSON", 50, false).apply {
            setOnClickListener { exportLatestJson() }
        }, actionParams(10))
        actions.addView(roundButton("上傳最新 JSON", 50, false).apply {
            setOnClickListener { uploadLatestJson(this) }
        }, actionParams(10))
        actions.addView(roundButton("查看排程診斷", 50, false).apply {
            setOnClickListener { showDiagnostics() }
        }, actionParams(10))
        root.addView(actions)

        root.addView(TextView(this).apply {
            text = "執行結果"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(0, dp(18), 0, dp(6))
        })
        result = TextView(this).apply {
            text = "尚未執行掃描。"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(result)
            background = cardBackground()
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))

        drawer = buildDrawer()
        val drawerParams = FrameLayout.LayoutParams(dp(310), -1).apply { gravity = Gravity.START }
        drawer.translationX = -dp(310).toFloat()
        frame.addView(drawer, drawerParams)
        return frame
    }

    private fun buildDrawer(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(20))
            setBackgroundColor(Color.rgb(20, 20, 20))
            elevation = dp(12).toFloat()
        }

        val top = FrameLayout(this)
        top.addView(TextView(this).apply {
            text = "選單"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, dp(52)))
        top.addView(roundButton("←", 52, false).apply {
            contentDescription = "關閉選單"
            setOnClickListener { toggleDrawer(false) }
        }, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START))
        panel.addView(top)

        panel.addView(sectionHeader("GitHub 設定"), actionParams(12))
        val githubBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        githubOwner = edit("擁有者", prefs.getString("github_owner", "antharas730203"))
        githubRepo = edit("Repository", prefs.getString("github_repo", "TaiwanV2Scanner"))
        githubBranch = edit("分支", prefs.getString("github_branch", "main"))
        githubToken = edit("Token（留白代表保留既有 Token）", null).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        githubBox.addView(githubOwner, editParams())
        githubBox.addView(githubRepo, editParams())
        githubBox.addView(githubBranch, editParams())
        githubBox.addView(githubToken, editParams())
        githubBox.addView(roundButton("驗證 GitHub", 48, false).apply { setOnClickListener { verifyGitHubButton(this) } }, actionParams(4))
        githubBox.addView(roundButton("儲存 GitHub 設定", 48, true).apply { setOnClickListener { saveGitHubOnly() } }, actionParams(6))
        panel.addView(githubBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        attachExpandable(panel, githubBox)

        panel.addView(sectionHeader("自動排程"), actionParams(12))
        val scheduleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        auto = CheckBox(this).apply {
            text = "啟用自動排程"
            isChecked = prefs.getBoolean("auto", false)
            setTextColor(Color.WHITE)
        }
        autoGitHub = CheckBox(this).apply {
            text = "排程掃描完成後自動上傳 GitHub"
            isChecked = prefs.getBoolean("github_auto_upload", false)
            setTextColor(Color.WHITE)
        }
        scheduleBox.addView(auto)
        scheduleBox.addView(autoGitHub)
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val trading = RadioButton(this).apply {
            text = "交易時段模式（09:00～13:30）"
            id = View.generateViewId()
            isChecked = prefs.getString("schedule_mode", "trading") != "test"
            setTextColor(Color.WHITE)
        }
        val test = RadioButton(this).apply {
            text = "測試模式（不限交易時段）"
            id = View.generateViewId()
            isChecked = prefs.getString("schedule_mode", "trading") == "test"
            setTextColor(Color.WHITE)
        }
        group.addView(trading)
        group.addView(test)
        scheduleBox.addView(group)
        scheduleBox.addView(TextView(this).apply {
            text = "排程時間"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(10), 0, dp(4))
        })
        scheduleTimes = EditText(this).apply {
            setText(prefs.getString("schedule_times", ScanScheduler.DEFAULT_TIMES))
            hint = "09:05,10:05,11:05,12:05,13:05"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = cardBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        scheduleBox.addView(scheduleTimes, editParams())
        scheduleBox.addView(roundButton("儲存排程設定", 50, true).apply { setOnClickListener { saveSettings(trading.isChecked) } }, actionParams(4))
        panel.addView(scheduleBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        attachExpandable(panel, scheduleBox)

        panel.addView(sectionHeader("關於"), actionParams(12))
        val aboutBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(12))
        }
        aboutBox.addView(TextView(this).apply {
            text = "V0.8.3"
            textSize = 18f
            setTextColor(Color.WHITE)
        })
        aboutBox.addView(TextView(this).apply {
            text = "台股 V2 掃描器\n完整 TWSE＋TPEX 市場掃描\n第一層轉機／動能市場預篩\nExact Alarm 精確排程\nGitHub JSON 歸檔\n手動／排程上傳支援\nTWSE、TPEX、LAYER1 三份完整資料"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, 0)
        })
        panel.addView(aboutBox)
        attachExpandable(panel, aboutBox)

        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setFillViewport(true)
            addView(panel)
        }
    }

    private fun sectionHeader(textValue: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(14), dp(12), dp(14))
        background = buttonBackground(false)
        addView(TextView(this@MainActivity).apply {
            text = textValue
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@MainActivity).apply {
            text = "▶"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(36), -1))
    }

    private fun attachExpandable(panel: LinearLayout, content: View) {
        val header = panel.getChildAt(panel.childCount - 2) as LinearLayout
        val arrow = header.getChildAt(1) as TextView
        header.setOnClickListener {
            content.visibility = if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            arrow.text = if (content.visibility == View.VISIBLE) "▼" else "▶"
        }
    }

    private fun saveGitHubOnly() {
        prefs.edit()
            .putString("github_owner", githubOwner.text.toString().trim())
            .putString("github_repo", githubRepo.text.toString().trim())
            .putString("github_branch", githubBranch.text.toString().trim().ifEmpty { "main" })
            .apply()
        val token = githubToken.text.toString().trim()
        if (token.isNotEmpty()) GitHubTokenStore.save(this, token)
        githubToken.text.clear()
        Toast.makeText(this, "GitHub 設定已儲存", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun toggleDrawer(open: Boolean) {
        drawerOpen = open
        drawer.animate().translationX(if (open) 0f else -dp(310).toFloat()).setDuration(220).start()
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
        val token = GitHubTokenStore.load(this) ?: return "GitHub 驗證失敗：Token 未設定。\n\n請在 GitHub 設定中貼上新的 Fine-grained PAT，儲存後再驗證。"
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
                setRequestProperty("User-Agent", "TaiwanV2Scanner/0.8.3")
            }
            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } finally { conn.disconnect() }
            when (code) {
                200 -> {
                    val obj = JSONObject(body)
                    "GitHub 驗證成功 ✓\n\nRepository：${obj.optString("full_name", "$owner/$repo")}\n分支：$branch\nToken：有效\n\n目前已確認 App 可以用這組 Token 讀取 Repository。\n接下來可用「上傳最新 JSON」確認 Contents 寫入。"
                }
                401 -> "GitHub 驗證失敗：HTTP 401\n\nToken 無效、已撤銷、過期，或 App 裡保存的 Token 已失效。"
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
                .putBoolean("schedule_github_upload", autoGitHub.isChecked)
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
                    Toast.makeText(this, "請允許『鬧鐘與提醒』後，再按一次「儲存排程設定」", Toast.LENGTH_LONG).show()
                    return
                }
                ExactScanScheduler.schedule(this, times)
            } else ExactScanScheduler.cancel(this)
            githubToken.text.clear()
            Toast.makeText(this, "設定已儲存", Toast.LENGTH_SHORT).show()
            refreshStatus()
        } catch (e: Exception) {
            Toast.makeText(this, "設定失敗：${e.message ?: "未知錯誤"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportLatestJson() {
        val content = ScanPersistence.lastJson(this)
        if (content.isNullOrEmpty()) {
            Toast.makeText(this, "尚未有掃描資料", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(getExternalFilesDir(null), "exports")
        dir.mkdirs()
        val file = File(dir, "TaiwanV2Scanner_${System.currentTimeMillis()}.json")
        file.writeText(content, Charsets.UTF_8)
        Toast.makeText(this, "已匯出：${file.name}", Toast.LENGTH_SHORT).show()
    }

    private fun uploadLatestJson(button: View) {
        val fullJson = ScanPersistence.lastJson(this)
        if (fullJson.isNullOrEmpty()) {
            Toast.makeText(this, "尚未有掃描資料", Toast.LENGTH_SHORT).show()
            return
        }
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val layer1File = File(baseDir, "layer1_latest.json")
        if (!layer1File.exists()) {
            Toast.makeText(this, "找不到最新第一層 JSON，請先重新掃描", Toast.LENGTH_LONG).show()
            return
        }
        val layer1Json = layer1File.readText(Charsets.UTF_8)
        val stamp = try {
            JSONObject(fullJson).optString("scan_time").ifBlank { System.currentTimeMillis().toString() }
        } catch (_: Exception) {
            System.currentTimeMillis().toString()
        }
        button.isEnabled = false
        status.text = "正在上傳最新 TWSE／TPEX／LAYER1 JSON……"
        result.text = "正在上傳最新完整資料至 GitHub。\n\n手動上傳不會啟動新的掃描。"
        Thread {
            val report = DataArchiveUploader.upload(this, stamp, fullJson, layer1Json, "MANUAL")
            getSharedPreferences("diagnostics", 0).edit().putString("manual_archive_upload", report).apply()
            runOnUiThread {
                result.text = report
                status.text = if (report.startsWith("成功")) "最新 JSON 上傳完成" else "最新 JSON 上傳失敗"
                button.isEnabled = true
            }
        }.start()
    }

    private fun showDiagnostics() {
        val diag = getSharedPreferences("diagnostics", 0)
        val layer1 = diag.getString("layer1_status", "尚未執行") ?: "尚未執行"
        val layer1Display = if (layer1 == "LAYER1_COMPLETE") "完成" else layer1
        result.text = buildString {
            append("最近掃描\n")
            append("開始：${diag.getString("last_run_started", "無")}\n")
            append("完成：${diag.getString("last_run_finished", "無")}\n")
            append("預期：${diag.getInt("last_expected", 0)}\n")
            append("取得：${diag.getInt("last_returned", 0)}\n")
            append("完整率：${diag.getFloat("last_rate", 0f)}\n")
            append("安全批次：${diag.getInt("last_safe_batch_count", 0)}\n")
            append("最大批次字元：${diag.getInt("last_safe_batch_max_chars", 0)}\n")
            append("第一層：$layer1Display\n")
            append("第一層候選：${diag.getInt("layer1_count", 0)}\n")
            append("自動上傳：${diag.getString("archive_upload", "尚未執行")}\n")
            append("手動上傳：${diag.getString("manual_archive_upload", "尚未執行")}")
        }
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val token = if (GitHubTokenStore.hasToken(this)) "已設定" else "未設定"
        val times = prefs.getString("schedule_times", ScanScheduler.DEFAULT_TIMES) ?: ScanScheduler.DEFAULT_TIMES
        val diag = getSharedPreferences("diagnostics", 0)
        val l1 = diag.getString("layer1_status", "尚未執行") ?: "尚未執行"
        val l1Display = if (l1 == "LAYER1_COMPLETE") "完成" else l1
        val engine = diag.getString("schedule_engine", "EXACT_ALARM") ?: "EXACT_ALARM"
        status.text = "排程：${if (prefs.getBoolean("auto", false)) "已啟用" else "未啟用"}\n時間：$times\n排程引擎：$engine\nGitHub Token：$token\n第一層：$l1Display"
    }

    override fun onBackPressed() {
        if (drawerOpen) toggleDrawer(false) else super.onBackPressed()
    }

    private fun edit(hintText: String, value: String?): EditText = EditText(this).apply {
        hint = hintText
        if (value != null) setText(value)
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        background = cardBackground()
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun roundButton(textValue: String, heightDp: Int, primary: Boolean): Button = Button(this).apply {
        text = textValue
        setAllCaps(false)
        gravity = Gravity.CENTER
        textSize = if (primary) 16f else 14f
        setTextColor(Color.WHITE)
        background = buttonBackground(primary)
        minimumHeight = 0
        minHeight = 0
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = ViewGroup.LayoutParams(-1, dp(heightDp))
        setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.alpha = 0.72f
                    view.scaleX = 0.985f
                    view.scaleY = 0.985f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            }
            false
        }
    }

    private fun cardBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(Color.rgb(24, 24, 24))
        setStroke(dp(1), Color.rgb(55, 55, 55))
    }

    private fun buttonBackground(primary: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(if (primary) Color.rgb(55, 55, 55) else Color.rgb(32, 32, 32))
        setStroke(dp(1), Color.rgb(70, 70, 70))
    }

    private fun actionParams(top: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun editParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
