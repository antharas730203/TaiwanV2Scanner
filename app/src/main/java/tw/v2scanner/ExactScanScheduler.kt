package tw.v2scanner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar

/**
 * Exact daily scheduler. Intraday and post-market schedules are independent.
 * Each configured time gets its own one-shot exact alarm and is re-scheduled
 * for the following day after it fires.
 */
object ExactScanScheduler {
    private const val BASE_ID = 3526005
    private const val POST_BASE_ID = 3526105
    private const val EXTRA_INDEX = "schedule_index"
    private const val EXTRA_KIND = "schedule_kind"

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarm.canScheduleExactAlarms()
    }

    fun schedule(context: Context, raw: String = ScanScheduler.DEFAULT_TIMES, postTime: String? = null) {
        require(canScheduleExact(context)) { "需要允許『鬧鐘與提醒』的精確鬧鐘權限" }
        cancel(context)
        if (raw.isNotBlank()) {
            val times = ScanScheduler.parseAndValidate(raw)
            times.forEachIndexed { index, hm -> scheduleOne(context, index, hm.first, hm.second, false) }
        }
        if (!postTime.isNullOrBlank()) {
            val hm = ScanScheduler.parseAndValidate(postTime).single()
            schedulePostOne(context, hm.first, hm.second, false)
        }
        context.getSharedPreferences("diagnostics", 0).edit()
            .putString("schedule_engine", "EXACT_ALARM")
            .putString("schedule_configured", raw)
            .putString("post_market_schedule_configured", postTime ?: "")
            .apply()
    }

    private fun scheduleOne(context: Context, index: Int, hour: Int, minute: Int, tomorrow: Boolean) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = Calendar.getInstance().apply {
            if (tomorrow) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (!tomorrow && cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        val intent = Intent(context, ExactScanAlarmReceiver::class.java).apply {
            putExtra(EXTRA_INDEX, index); putExtra("hour", hour); putExtra("minute", minute); putExtra(EXTRA_KIND, "intraday")
        }
        val pi = PendingIntent.getBroadcast(context, BASE_ID + index, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    private fun schedulePostOne(context: Context, hour: Int, minute: Int, tomorrow: Boolean) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = Calendar.getInstance().apply {
            if (tomorrow) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (!tomorrow && cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        val intent = Intent(context, ExactPostMarketAlarmReceiver::class.java).apply {
            putExtra("hour", hour); putExtra("minute", minute); putExtra(EXTRA_KIND, "post_market")
        }
        val pi = PendingIntent.getBroadcast(context, POST_BASE_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    fun rescheduleNextDay(context: Context, index: Int, hour: Int, minute: Int) {
        if (canScheduleExact(context)) scheduleOne(context, index, hour, minute, true)
    }

    fun reschedulePostNextDay(context: Context, hour: Int, minute: Int) {
        if (canScheduleExact(context)) schedulePostOne(context, hour, minute, true)
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until 64) {
            val pi = PendingIntent.getBroadcast(context, BASE_ID + i, Intent(context, ExactScanAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.cancel(pi); pi.cancel()
        }
        val postPi = PendingIntent.getBroadcast(context, POST_BASE_ID, Intent(context, ExactPostMarketAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(postPi); postPi.cancel()
    }
}

class ExactScanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val index = intent?.getIntExtra("schedule_index", -1) ?: -1
        val hour = intent?.getIntExtra("hour", -1) ?: -1
        val minute = intent?.getIntExtra("minute", -1) ?: -1
        val network = NetworkState.summary(context)
        ScheduleDiagnostics.mark(context, "last_schedule_trigger")
        ScheduleDiagnostics.mark(context, "last_schedule_engine", "EXACT_ALARM")
        ScheduleDiagnostics.mark(context, "last_schedule_index", index.toString())
        ScheduleDiagnostics.mark(context, "last_schedule_network", network)
        val prefs = context.getSharedPreferences("settings", 0)
        val mode = prefs.getString("schedule_mode", "trading") ?: "trading"
        val historyId = if (index >= 0 && hour in 0..23 && minute in 0..59) ScheduleDiagnosticsHistory.start(context, index, hour, minute, network, mode) else null

        if (prefs.getBoolean("auto", false)) {
            ScheduleDiagnostics.mark(context, "last_schedule_mode", mode)
            if (mode == "trading") {
                historyId?.let {
                    ScheduleDiagnosticsHistory.update(context, it, "QUEUED", "market_status", "排程觸發後交由 WorkManager 等待網路，再確認市場狀態")
                    ScheduleDiagnosticsHistory.update(context, it, key = "network_at_trigger", value = network)
                }
                enqueueScan(context, historyId)
            } else {
                historyId?.let { ScheduleDiagnosticsHistory.update(context, it, "QUEUED") }
                enqueueScan(context, historyId)
            }
        } else historyId?.let { ScheduleDiagnosticsHistory.update(context, it, "AUTO_DISABLED") }

        if (index >= 0 && hour in 0..23 && minute in 0..59) ExactScanScheduler.rescheduleNextDay(context, index, hour, minute)
    }

    private fun enqueueScan(context: Context, historyId: String?) {
        val input = Data.Builder().apply { if (historyId != null) putString("schedule_history_id", historyId) }.build()
        val request = OneTimeWorkRequestBuilder<ScheduledAutoUploadWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS)
            .addTag("taiwan_v2_scheduled_scan_exact")
            .build()
        val uniqueName = historyId?.let { "taiwan_v2_scheduled_scan_exact_$it" } ?: "taiwan_v2_scheduled_scan_exact_${System.currentTimeMillis()}"
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class ExactPostMarketAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val hour = intent?.getIntExtra("hour", -1) ?: -1
        val minute = intent?.getIntExtra("minute", -1) ?: -1
        val network = NetworkState.summary(context)
        val prefs = context.getSharedPreferences("settings", 0)
        ScheduleDiagnostics.mark(context, "last_post_market_trigger")
        ScheduleDiagnostics.mark(context, "last_post_market_engine", "EXACT_ALARM")
        ScheduleDiagnostics.mark(context, "last_post_market_network", network)
        val timeValid = hour in 0..23 && minute in 0..59
        val historyId = if (timeValid) ScheduleDiagnosticsHistory.start(context, 9000, hour, minute, network, "post_market") else null
        if (prefs.getBoolean("post_market_enabled", false)) {
            historyId?.let { ScheduleDiagnosticsHistory.update(context, it, "QUEUED") }
            enqueuePostMarket(context, historyId)
        } else historyId?.let { ScheduleDiagnosticsHistory.update(context, it, "POST_MARKET_DISABLED") }
        if (timeValid) ExactScanScheduler.reschedulePostNextDay(context, hour, minute)
    }

    private fun enqueuePostMarket(context: Context, historyId: String?) {
        val input = Data.Builder().apply { if (historyId != null) putString("schedule_history_id", historyId) }.build()
        val request = OneTimeWorkRequestBuilder<ScheduledPostMarketWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("taiwan_v2_post_market_exact").build()
        WorkManager.getInstance(context).enqueueUniqueWork("taiwan_v2_post_market_exact", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class ScheduledPostMarketWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val historyId = inputData.getString("schedule_history_id")
        ScheduleDiagnostics.mark(applicationContext, "last_post_market_worker_started")
        historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "STARTED") }
        return try {
            val check = PostMarketScanner.checkToday(applicationContext)
            applicationContext.getSharedPreferences("diagnostics", 0).edit()
                .putString("post_market_check_result", check.display()).putInt("post_market_valid_count", check.validCount).putBoolean("post_market_passed", check.passed).apply()
            if (!check.passed) {
                ScheduleDiagnostics.mark(applicationContext, "last_post_market_skip", "有效紀錄 ${check.validCount}/5，未達 3/5")
                historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "SKIPPED", "check", check.display().take(1200)) }
                return Result.success()
            }
            val report = PostMarketScanner.run(applicationContext)
            ScheduleDiagnostics.mark(applicationContext, "last_post_market_worker_finished")
            applicationContext.getSharedPreferences("diagnostics", 0).edit().putString("post_market_last_run", report.take(5000)).apply()
            historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "FINISHED", "report", report.take(500)) }
            Result.success()
        } catch (e: Exception) {
            ScheduleDiagnostics.mark(applicationContext, "last_post_market_worker_error", "${e.javaClass.simpleName}: ${e.message}")
            historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "ERROR", "error", "${e.javaClass.simpleName}: ${e.message}") }
            Result.retry()
        }
    }
}

class ScheduledAutoUploadWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    private fun isTransientNetworkFailure(text: String): Boolean {
        val markers = listOf(
            "UnknownHostException", "ConnectException", "SocketTimeoutException",
            "NoRouteToHostException", "SocketException", "ProtocolException",
            "unexpected end of stream", "Software caused connection abort",
            "Unable to resolve host"
        )
        return markers.any { text.contains(it, ignoreCase = true) }
    }

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("settings", 0)
        val diagnostics = applicationContext.getSharedPreferences("diagnostics", 0)
        val oldLegacyUpload = prefs.getBoolean("github_auto_upload", false)
        val historyId = inputData.getString("schedule_history_id")
        prefs.edit().putBoolean("github_auto_upload", false).putString("scan_origin", "scheduled").apply()
        return try {
            val now = Calendar.getInstance()
            val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            if (minute > 13 * 60 + 30) {
                ScheduleDiagnostics.mark(applicationContext, "last_worker_expired", "超過13:30，等待網路的本輪排程不再執行")
                historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "EXPIRED_WAITING_NETWORK", "reason", "等待網路期間已超過盤中有效時間 13:30") }
                return Result.success()
            }

            val mode = prefs.getString("schedule_mode", "trading") ?: "trading"
            if (mode == "trading") {
                val marketStatus = MarketStatus.check(now)
                ScheduleDiagnostics.mark(applicationContext, "last_market_status", marketStatus.reason)
                ScheduleDiagnostics.mark(applicationContext, "last_market_status_source", marketStatus.source)
                ScheduleDiagnostics.mark(applicationContext, "last_market_status_sample", marketStatus.sampleReturned.toString())
                when (marketStatus.decision) {
                    MarketStatus.Decision.RETRY -> {
                        ScheduleDiagnostics.mark(applicationContext, "last_schedule_wait", marketStatus.reason)
                        historyId?.let {
                            ScheduleDiagnosticsHistory.update(applicationContext, it, "WAIT_NETWORK", "market_status", marketStatus.reason)
                            ScheduleDiagnosticsHistory.update(applicationContext, it, key = "network", value = NetworkState.summary(applicationContext))
                        }
                        return Result.retry()
                    }
                    MarketStatus.Decision.SKIP -> {
                        historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "SKIPPED", "market_status", marketStatus.reason) }
                        return Result.success()
                    }
                    MarketStatus.Decision.OK -> Unit
                }
            }

            ScheduleDiagnostics.mark(applicationContext, "last_worker_started")
            historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "STARTED") }
            diagnostics.edit().remove("last_run_error").apply()
            val report = ScanEngine.runFull(applicationContext)
            val runError = diagnostics.getString("last_run_error", "").orEmpty()
            val failureText = "$runError\n$report"

            if (isTransientNetworkFailure(failureText)) {
                val reason = runError.ifBlank {
                    report.lineSequence().firstOrNull { isTransientNetworkFailure(it) } ?: "掃描期間發生暫時性網路錯誤"
                }
                ScheduleDiagnostics.mark(applicationContext, "last_schedule_wait", reason)
                historyId?.let {
                    ScheduleDiagnosticsHistory.update(applicationContext, it, "WAIT_NETWORK", "scan_network_error", reason.take(1000))
                    ScheduleDiagnosticsHistory.update(applicationContext, it, key = "network", value = NetworkState.summary(applicationContext))
                }
                return Result.retry()
            }

            if (report.startsWith("自動/完整掃描失敗：")) {
                val reason = runError.ifBlank { report.take(1000) }
                ScheduleDiagnostics.mark(applicationContext, "last_worker_error", reason)
                historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "ERROR", "error", reason.take(1000)) }
                return Result.failure()
            }

            ScheduleDiagnostics.mark(applicationContext, "last_worker_finished")
            diagnostics.edit().putString("last_worker_report", report.take(3000)).apply()
            historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "FINISHED", "report", report.take(500)) }
            Result.success()
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            ScheduleDiagnostics.mark(applicationContext, "last_worker_error", detail)
            if (isTransientNetworkFailure(detail)) {
                historyId?.let {
                    ScheduleDiagnosticsHistory.update(applicationContext, it, "WAIT_NETWORK", "scan_network_error", detail)
                    ScheduleDiagnosticsHistory.update(applicationContext, it, key = "network", value = NetworkState.summary(applicationContext))
                }
                Result.retry()
            } else {
                historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "ERROR", "error", detail) }
                Result.failure()
            }
        } finally {
            prefs.edit().putBoolean("github_auto_upload", oldLegacyUpload).putString("scan_origin", "idle").apply()
        }
    }
}
