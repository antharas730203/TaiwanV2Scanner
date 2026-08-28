package tw.v2scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
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
    private val prefs by lazy { getSharedPreferences("settings", 0) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "台股 V2 掃描器 V0.6.2"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "上市＋上櫃・動態筆數・150檔基準・自動降批・可編輯排程・GitHub同步"
            textSize = 15f
        })

        auto = CheckBox(this).apply {
            text = "啟用盤中自動掃描"
            isChecked = prefs.getBoolean("auto", false)
        }
        autoGitHub = CheckBox(this).apply {
            text = "掃描完成後自動上傳 GitHub"
            isChecked = prefs.getBoolean("github_auto_upload", false)
        }
        scheduleTimes = EditText(this).apply {
            hint = "排程時間，例如 09:05,10:05,11:05,12:05,13:05"
            setText(prefs.getString("schedule_times", ScanScheduler.DEFAULT_TIMES))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        githubOwner = EditText(this).apply {
            hint = "GitHub 擁有者"
            setText(prefs.getString("github_owner", "antharas730203"))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        githubRepo = EditText(this).apply {
            hint = "GitHub Repository"
            setText(prefs.getString("github_repo", "TaiwanV2Scanner"))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        githubBranch = EditText(this).apply {
            hint = "GitHub 分支"
            setText(prefs.getString("github_branch", "main"))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        githubToken = EditText(this).apply {
            hint = if (GitHubTokenStore.hasToken(this@MainActivity)) "GitHub Token（已設定；留白即保留）" else "GitHub Token（第一次請輸入）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val save = Button(this).apply { text = "儲存設定／排程" }
        scan = Button(this).apply { text = "手動測試：150檔／1批" }
        testGitHub = Button(this).apply { text = "測試 GitHub 上傳最後一次 JSON" }
        val exportJson = Button(this).apply { text = "匯出最後一次 JSON" }
        val exportCsv = Button(this).apply { text = "匯出最後一次 CSV" }
        val shareJson = Button(this).apply { text = "分享最後一次 JSON" }
        val showDiag = Button(this).apply { text = "查看排程診斷" }

        status = TextView(this).apply { textSize = 16f; setPadding(0, 8, 0, 8) }
        result = TextView(this).apply { textSize = 16f }
        val scroll = ScrollView(this).apply { addView(result) }

        root.addView(auto)
        root.addView(autoGitHub)
        root.addView(scheduleTimes)
        root.addView(githubOwner)
        root.addView(githubRepo)
        root.addView(githubBranch)
        root.addView(githubToken)
        root.addView(save)
        root.addView(scan)
        root.addView(testGitHub)
        root.addView(exportJson)
        root.addView(exportCsv)
        root.addView(shareJson)
        root.addView(showDiag)
        root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
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
                status.text = "設定已儲存｜排程：${if (auto.isChecked) "已啟用" else "未啟用"}｜GitHub：${if (autoGitHub.isChecked && GitHubTokenStore.hasToken(this)) "已啟用" else "未啟用"}"
                githubToken.setText("")
                githubToken.hint = "GitHub Token（已設定；留白即保留）"
            } catch (e: Exception) {
                status.text = "設定失敗：${e.message}"
            }
        }

        scan.setOnClickListener {
            scan.isEnabled = false
            status.text = "手動150檔單批測試執行中……"
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
            Thread {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val report = GitHubUploader.uploadScan(this, json, stamp, "test")
                runOnUiThread {
                    status.text = report
                    testGitHub.isEnabled = true
                }
            }.start()
        }

        exportJson.setOnClickListener { exportLast("json") }
        exportCsv.setOnClickListener { exportLast("csv") }
        shareJson.setOnClickListener { shareLastJson() }
        showDiag.setOnClickListener { showDiagnostics() }
    }

    private fun refreshStatus() {
        val tokenText = if (GitHubTokenStore.hasToken(this)) "已設定" else "未設定"
        status.text = "排程：${if (auto.isChecked) "已啟用" else "未啟用"}｜GitHub Token：$tokenText"
    }

    private fun showDiagnostics() {
        val p = getSharedPreferences("diagnostics", 0)
        result.text = buildString {
            append("排程設定：${p.getString("schedule_configured", "無") }\n")
            append("最近排程觸發：${p.getString("last_schedule_trigger", "無") }\n")
            append("排程當時網路：${p.getString("last_schedule_network", "無") }\n")
            append("最近 Worker 啟動：${p.getString("last_worker_started", "無") }\n")
            append("最近掃描開始：${p.getString("last_run_started", "無") }\n")
            append("掃描模式：${p.getString("last_run_mode", "無") }\n")
            append("動態清單：${p.getString("dynamic_lists", "無") }\n")
            append("最近完成：${p.getString("last_run_finished", "無") }\n")
            append("成功：${p.getInt("last_returned", -1)} / ${p.getInt("last_expected", -1)}\n")
            append("完整率：${p.getFloat("last_rate", -1f)}%\n")
            append("最終批次：${p.getInt("last_final_batch", -1)}\n")
            append("最近錯誤：${p.getString("last_run_error", "無") }\n\n")
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
}
