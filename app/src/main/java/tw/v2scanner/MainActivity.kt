package tw.v2scanner

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var drawer: View
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var prefs: android.content.SharedPreferences

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun buttonBackground(primary: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(if (primary) 14 else 12).toFloat()
        setColor(if (primary) Color.rgb(35, 110, 220) else Color.rgb(45, 48, 55))
        setStroke(dp(1), Color.rgb(75, 78, 86))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        prefs = getSharedPreferences("scanner_prefs", MODE_PRIVATE)
        buildMainUi()
    }

    private fun buildMainUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(18, 19, 22)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val menu = TextView(this).apply {
            text = "☰"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { drawer.visibility = View.VISIBLE }
        }
        top.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(TextView(this).apply {
            text = "台股 V2 掃描器"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(top)

        statusText = TextView(this).apply {
            text = "尚未掃描"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(dp(4), dp(10), dp(4), dp(12))
        }
        content.addView(statusText)

        scanButton = makeButton("手動完整掃描", true) { runManualScan() }
        content.addView(scanButton, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(12) })
        content.addView(makeButton("驗證 GitHub") { verifyGithub() }, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(10) })
        content.addView(makeButton("匯出最新 JSON") { exportLatest() }, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(10) })
        content.addView(makeButton("上傳最新 JSON") { uploadLatest() }, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(10) })
        content.addView(makeButton("查看排程診斷") { showScheduleDiagnostics() }, LinearLayout.LayoutParams(-1, dp(50)))

        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        drawer = buildDrawer()
        drawer.visibility = View.GONE
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun makeButton(textValue: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = textValue
        textSize = if (primary) 17f else 15f
        setTextColor(Color.WHITE)
        background = buttonBackground(primary)
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun buildDrawer(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(Color.rgb(24, 25, 29))
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this@MainActivity).apply {
            text = "選單"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        titleRow.addView(TextView(this@MainActivity).apply {
            text = "←"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { drawer.visibility = View.GONE }
        }, LinearLayout.LayoutParams(dp(48), dp(52)))
        panel.addView(titleRow)

        val githubBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(sectionHeader("GitHub 設定") { toggleSection(githubBox, it) })
        githubBox.addView(editField("Owner", "github_owner"))
        githubBox.addView(editField("Repository", "github_repo"))
        githubBox.addView(editField("Branch", "github_branch"))
        githubBox.addView(editField("Token", "github_token", true))
        panel.addView(githubBox)

        val scheduleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(space(10))
        panel.addView(sectionHeader("自動排程") { toggleSection(scheduleBox, it) })
        scheduleBox.addView(CheckBox(this).apply {
            text = "排程掃描完成後自動上傳 GitHub"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("schedule_github_upload", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("schedule_github_upload", checked).apply() }
        })
        scheduleBox.addView(makeTimeField("排程時間 1", "schedule_time_1"))
        scheduleBox.addView(makeTimeField("排程時間 2", "schedule_time_2"))
        scheduleBox.addView(CheckBox(this).apply {
            text = "測試模式"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("trading_test_mode", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("trading_test_mode", checked).apply() }
        })
        scheduleBox.addView(makeButton("儲存排程設定") { saveScheduleSettings() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        panel.addView(scheduleBox)

        val aboutBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(space(10))
        panel.addView(sectionHeader("關於") { toggleSection(aboutBox, it) })
        aboutBox.addView(TextView(this).apply {
            text = "V0.8.3"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(4))
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

    private fun sectionHeader(textValue: String, callback: (TextView) -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(14), dp(12), dp(14))
        background = buttonBackground(false)
        addView(TextView(this@MainActivity).apply {
            text = textValue
            textSize = 16f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@MainActivity).apply {
            text = "▼"
            textSize = 16f
            setTextColor(Color.WHITE)
            callback(this)
        }, LinearLayout.LayoutParams(dp(40), -2))
    }

    private fun attachExpandable(parent: LinearLayout, box: View) { }
    private fun toggleSection(box: View, arrow: TextView) {
        val open = box.visibility != View.VISIBLE
        box.visibility = if (open) View.VISIBLE else View.GONE
        arrow.text = if (open) "▼" else "▶"
    }
    private fun space(h: Int) = Space(this).apply { minimumHeight = dp(h) }
    private fun editField(label: String, key: String, password: Boolean = false): EditText = EditText(this).apply {
        hint = label
        setText(prefs.getString(key, ""))
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        inputType = if (password) 0x81 else 0x1
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnFocusChangeListener { _, hasFocus -> if (hasFocus) postDelayed({ requestFocus() }, 50) }
    }
    private fun makeTimeField(label: String, key: String): EditText = editField(label, key)

    private fun saveScheduleSettings() {
        prefs.edit().apply()
        Toast.makeText(this, "排程設定已儲存", Toast.LENGTH_SHORT).show()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }
    private fun runManualScan() { statusText.text = "掃描中…"; scanButton.isEnabled = false; ScanEngine.runFull(this) { result -> runOnUiThread { scanButton.isEnabled = true; statusText.text = result.toString() } } }
    private fun verifyGithub() { Toast.makeText(this, "驗證 GitHub", Toast.LENGTH_SHORT).show() }
    private fun exportLatest() { Toast.makeText(this, "匯出最新 JSON", Toast.LENGTH_SHORT).show() }
    private fun uploadLatest() { Toast.makeText(this, "上傳最新 JSON", Toast.LENGTH_SHORT).show() }
    private fun showScheduleDiagnostics() { Toast.makeText(this, "排程診斷", Toast.LENGTH_SHORT).show() }
}
