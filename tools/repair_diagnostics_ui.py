from pathlib import Path

p = Path("app/src/main/java/tw/v2scanner/MainActivity.kt")
s = p.read_text(encoding="utf-8")

if "import android.app.AlertDialog" not in s:
    s = s.replace("import android.app.Activity\n", "import android.app.Activity\nimport android.app.AlertDialog\n")

old_start = s.index("    private fun showDiagnostics() {")
old_end = s.index("\n    private fun refreshStatus()", old_start)

new = '''    private fun showDiagnostics() {
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
            background = ColorDrawable(Color.BLACK)
        }
        val content = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            setScrollbarFadingEnabled(false)
            addView(content)
            background = cardBackground()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.BLACK)
        }
        val lastTab = roundButton("last_*", 46, true)
        val historyTab = roundButton("schedule_history", 46, false)
        tabs.addView(lastTab, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(5) })
        tabs.addView(historyTab, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(5) })
        root.addView(tabs)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun showLast() {
            val diag = getSharedPreferences("diagnostics", 0)
            val all = diag.all.entries.sortedBy { it.key }
            content.text = buildString {
                append("===== last_* =====\\n\\n")
                all.filter { it.key.startsWith("last_") }.forEach { (key, value) ->
                    append(key).append(" = ").append(value).append('\\n')
                }
                append("\\n===== 主要診斷 =====\\n")
                val layer1 = diag.getString("layer1_status", "尚未執行") ?: "尚未執行"
                append("layer1_status = ").append(if (layer1 == "LAYER1_COMPLETE") "完成" else layer1).append('\\n')
                append("layer1_count = ").append(diag.getInt("layer1_count", 0)).append('\\n')
                append("archive_upload = ").append(diag.getString("archive_upload", "尚未執行")).append('\\n')
                append("manual_archive_upload = ").append(diag.getString("manual_archive_upload", "尚未執行")).append('\\n')
                append("post_market_check_result = ").append(diag.getString("post_market_check_result", "尚未執行")).append('\\n')
            }
            lastTab.background = buttonBackground(true)
            historyTab.background = buttonBackground(false)
        }

        fun showHistory() {
            val history = ScheduleDiagnosticsHistory.history(this)
            content.text = buildString {
                append("===== schedule_history =====\\n")
                append("筆數：${history.length()} / 35\\n\\n")
                for (i in history.length() - 1 downTo 0) {
                    val o = history.optJSONObject(i) ?: continue
                    append("【${o.optString("time", "無")}】\\n")
                    append("排程：${o.optString("schedule", "無")}   ")
                    append("狀態：${o.optString("status", "無")}\\n")
                    append("index：${o.optInt("index", -1)}   ")
                    append("mode：${o.optString("mode", "無")}\\n")
                    append("alarm：${o.optBoolean("alarm", false)}   ")
                    append("network：${o.optString("network", "無")}\\n")
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key !in setOf("id", "time", "schedule", "index", "mode", "status", "alarm", "network")) {
                            append(key).append("：").append(o.opt(key)).append('\\n')
                        }
                    }
                    append('\\n')
                }
                if (history.length() == 0) append("目前沒有排程歷史紀錄。\\n")
            }
            lastTab.background = buttonBackground(false)
            historyTab.background = buttonBackground(true)
        }

        lastTab.setOnClickListener { showLast() }
        historyTab.setOnClickListener { showHistory() }
        showLast()

        val dialog = AlertDialog.Builder(this)
            .setTitle("排程診斷")
            .setView(root)
            .setNegativeButton("關閉", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            (resources.displayMetrics.heightPixels * 0.82).toInt()
        )
    }
'''

s = s[:old_start] + new + s[old_end:]
p.write_text(s, encoding="utf-8")
