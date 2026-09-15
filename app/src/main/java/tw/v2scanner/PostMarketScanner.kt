package tw.v2scanner

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PostMarketScanner {
    const val DEFAULT_TIME = "14:05"
    const val MIN_VALID_RECORDS = 3
    private val EXPECTED_TIMES = listOf("09:05", "10:05", "11:05", "12:05", "13:05")

    data class Timepoint(val time: String, val found: Boolean, val fileName: String?)
    data class CheckResult(val date: String, val points: List<Timepoint>) {
        val validCount: Int get() = points.count { it.found }
        val passed: Boolean get() = validCount >= MIN_VALID_RECORDS

        fun display(): String = buildString {
            append("盤後掃描紀錄檢查\n\n")
            points.forEach { point ->
                append(point.time)
                append(if (point.found) " ✓ 有紀錄" else " ✗ 無紀錄")
                if (point.found && point.fileName != null) append("\n　${point.fileName}")
                append("\n")
            }
            append("\n有效紀錄：$validCount / ${points.size}\n")
            append("門檻：$MIN_VALID_RECORDS / ${points.size}\n")
            append(if (passed) "結果：通過 → 執行盤後掃描" else "結果：未通過 → 跳過盤後掃描")
        }
    }

    fun checkToday(context: Context): CheckResult = checkDate(
        context,
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    )

    fun checkDate(context: Context, date: String): CheckResult {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, "scanner_data/history")
        val points = EXPECTED_TIMES.map { time ->
            findTimepoint(dir, date, time)
        }
        val result = CheckResult(date, points)
        context.getSharedPreferences("diagnostics", 0).edit()
            .putString("post_market_check_date", date)
            .putString("post_market_check_result", result.display())
            .putInt("post_market_valid_count", result.validCount)
            .putBoolean("post_market_passed", result.passed)
            .apply()
        return result
    }

    private fun findTimepoint(dir: File, date: String, time: String): Timepoint {
        if (!dir.isDirectory) return Timepoint(time, false, null)
        val prefix = "${date}_${time.replace(":", "")}"
        val file = dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.filter { it.name.startsWith(prefix) }
            ?.filter { it.name.endsWith("_AUTO_LAYER1.json") }
            ?.sortedBy { it.name }
            ?.firstOrNull()
        return Timepoint(time, file != null, file?.name)
    }

    fun run(context: Context): String {
        val check = checkToday(context)
        if (!check.passed) return check.display()

        val settings = context.getSharedPreferences("settings", 0)
        val previousOrigin = settings.getString("scan_origin", null)
        settings.edit().putString("scan_origin", "post_market").apply()
        return try {
            val report = ScanEngine.runFull(context)
            context.getSharedPreferences("diagnostics", 0).edit()
                .putString("post_market_last_run", report)
                .apply()
            check.display() + "\n\n===== 盤後掃描 =====\n" + report
        } finally {
            val edit = settings.edit()
            if (previousOrigin == null) edit.remove("scan_origin") else edit.putString("scan_origin", previousOrigin)
            edit.apply()
        }
    }
}
