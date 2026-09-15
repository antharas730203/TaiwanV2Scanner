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

    fun schedule(
        context: Context,
        raw: String = ScanScheduler.DEFAULT_TIMES,
        postTime: String? = null
    ) {
        require(canScheduleExact(context)) { "需要允許『鬧鐘與提醒』的精確鬧鐘權限" }
        cancel(context)

        if (raw.isNotBlank()) {
            val times = ScanScheduler.parseAndValidate(raw)
            times.forEachIndexed { index, hm ->
                scheduleOne(context, index, hm.first, hm.second, false)
            }
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
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!tomorrow && cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val intent = Intent(context, ExactScanAlarmReceiver::class.java).apply {
            putExtra(EXTRA_INDEX, index)
            putExtra("hour", hour)
            putExtra("minute", minute)
            putExtra(EXTRA_KIND, "intraday")
        }
        val pi = PendingIntent.getBroadcast(
            context,
            BASE_ID + index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    private fun schedulePostOne(context: Context, hour: Int, minute: Int, tomorrow: Boolean) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = Calendar.getInstance().apply {
            if (tomorrow) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!tomorrow && cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val intent = Intent(context, ExactPostMarketAlarmReceiver::class.java).apply {
            putExtra("hour", hour)
            putExtra("minute", minute)
            putExtra(EXTRA_KIND, "post_market")
        }
        val pi = PendingIntent.getBroadcast(
            context,
            POST_BASE_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    fun rescheduleNextDay(context: Context, index: Int, hour: Int, minute: Int) {
        if (!canScheduleExact(context)) return
        scheduleOne(context, index, hour, minute, true)
    }

    fun reschedulePostNextDay(context: Context, hour: Int, minute: Int) {
        if (!canScheduleExact(context)) return
        schedulePostOne(context, hour, minute, true)
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until 64) {
            val intent = Intent(context, ExactScanAlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context,
                BASE_ID + i,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.cancel(pi)
            pi.cancel()
        }
        val postIntent = Intent(context, ExactPostMarketAlarmReceiver::class.java)
        val postPi = PendingIntent.getBroadcast(
            context,
            POST_BASE_ID,
            postIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.cancel(postPi)
        postPi.cancel()
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
        val historyId = if (index >= 0 && hour in 0..23 && minute in 0..59) {
            ScheduleDiagnosticsHistory.start(context, index, hour, minute, network, mode)
        } else null

        if (prefs.getBoolean("auto", false)) {
            ScheduleDiagnostics.mark(context, "last_schedule_mode", mode)
            if (mode == "trading") {
                val marketStatus = MarketStatus.check()
                ScheduleDiagnostics.mark(context, "last_market_status", marketStatus.reason)
                ScheduleDiagnostics.mark(context, "last_market_status_source", marketStatus.source)
                ScheduleDiagnostics.mark(context, "last_market_status_sample", marketStatus.sampleReturned.toString())
                if (!marketStatus.ok) {
                    ScheduleDiagnostics.mark(context, "last_schedule_skip", marketStatus.reason)
                    historyId?.let {
                        ScheduleDiagnosticsHistory.update(context, it, "SKIPPED", "market_status", marketStatus.reason)
                        ScheduleDiagnosticsHistory.update(context, it, key = "api_sample", value = marketStatus.sampleReturned.toString())
                    }
                } else {
                    historyId?.let { ScheduleDiagnosticsHistory.update(context, it, "QUEUED", "market_status", marketStatus.reason) }
                    enqueueScan(context, historyId)
                }
            } else {
                historyId?.let { ScheduleDiagnosticsHistory.update(context, it, "QUEUED") }
                enqueueScan(context, historyId)
            }
        } else {
            historyId?.let { ScheduleDiagnosticsHistory.update(context, it, "AUTO_DISABLED") }
        }

        if (index >= 0 && hour in 0..23 && minute in 0..59) {
            ExactScanScheduler.rescheduleNextDay(context, index, hour, minute)
        }
    }

    private fun enqueueScan(context: Context, historyId: String?) {
        val input = Data.Builder().apply {
            if (historyId != null) putString("schedule_history_id", historyId)
        }.build()
        val request = OneTimeWorkRequestBuilder<ScheduledAutoUploadWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("taiwan_v2_scheduled_scan_exact")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "taiwan_v2_scheduled_scan_exact",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
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
        val historyId = if (timeValid) {
            ScheduleDiagnosticsHistory.start(context, 9000, hour, minute, network, "post_market")
        } else null

        if (prefs.getBoolean("post_market_enabled", false)) {
            historyId?.let { ScheduleDiagnosticsHistory.update(context, it, "QUEUED") }
            enqueuePostMarket(context, historyId)
        } else {
            historyId?.let { ScheduleDiagnosticsHistory.update(context, it, "POST_MARKET_DISABLED") }
        }

        if (timeValid) ExactScanScheduler.reschedulePostNextDay(context, hour, minute)
    }

    private fun enqueuePostMarket(context: Context, historyId: String?) {
        val input = Data.Builder().apply {
            if (historyId != null) putString("schedule_history_id", historyId)
        }.build()
        val request = OneTimeWorkRequestBuilder<ScheduledPostMarketWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("taiwan_v2_post_market_exact")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "taiwan_v2_post_market_exact",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}

/**
 * Scheduled post-market scan. It is intentionally independent from the
 * intraday MarketStatus gate: at 14:05 the market is already closed, so the
 * worker first checks the five expected intraday archive files. Three or more
 * existing records are required before the post-market scan runs.
 */
class ScheduledPostMarketWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val historyId = inputData.getString("schedule_history_id")
        ScheduleDiagnostics.mark(applicationContext, "last_post_market_worker_started")
        historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "STARTED") }
        return try {
            val check = PostMarketScanner.checkToday(applicationContext)
            applicationContext.getSharedPreferences("diagnostics", 0).edit()
                .putString("post_market_check_result", check.display())
                .putInt("post_market_valid_count", check.validCount)
                .putBoolean("post_market_passed", check.passed)
                .apply()
            if (!check.passed) {
                ScheduleDiagnostics.mark(applicationContext, "last_post_market_skip", "有效紀錄 ${check.validCount}/5，未達 3/5")
                historyId?.let {
                    ScheduleDiagnosticsHistory.update(applicationContext, it, "SKIPPED", "check", check.display().take(1200))
                }
                return Result.success()
            }

            val report = PostMarketScanner.run(applicationContext)
            ScheduleDiagnostics.mark(applicationContext, "last_post_market_worker_finished")
            applicationContext.getSharedPreferences("diagnostics", 0).edit()
                .putString("post_market_last_run", report.take(5000))
                .apply()
            historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "FINISHED", "report", report.take(500)) }
            Result.success()
        } catch (e: Exception) {
            ScheduleDiagnostics.mark(applicationContext, "last_post_market_worker_error", "${e.javaClass.simpleName}: ${e.message}")
            historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "ERROR", "error", "${e.javaClass.simpleName}: ${e.message}") }
            Result.retry()
        }
    }
}

/**
 * Scheduled scan wrapper. The legacy github_auto_upload flag is disabled while
 * the scan runs so the old uploader cannot also write duplicate/legacy files.
 * BatchExporter handles the new archive upload using schedule_github_upload.
 */
class ScheduledAutoUploadWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("settings", 0)
        val oldLegacyUpload = prefs.getBoolean("github_auto_upload", false)
        val historyId = inputData.getString("schedule_history_id")
        prefs.edit()
            .putBoolean("github_auto_upload", false)
            .putString("scan_origin", "scheduled")
            .apply()
        ScheduleDiagnostics.mark(applicationContext, "last_worker_started")
        historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "STARTED") }
        return try {
            val report = ScanEngine.runFull(applicationContext)
            ScheduleDiagnostics.mark(applicationContext, "last_worker_finished")
            applicationContext.getSharedPreferences("diagnostics", 0).edit()
                .putString("last_worker_report", report.take(3000))
                .apply()
            historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "FINISHED", "report", report.take(500)) }
            Result.success()
        } catch (e: Exception) {
            ScheduleDiagnostics.mark(applicationContext, "last_worker_error", "${e.javaClass.simpleName}: ${e.message}")
            historyId?.let { ScheduleDiagnosticsHistory.update(applicationContext, it, "ERROR", "error", "${e.javaClass.simpleName}: ${e.message}") }
            Result.retry()
        } finally {
            prefs.edit()
                .putBoolean("github_auto_upload", oldLegacyUpload)
                .putString("scan_origin", "idle")
                .apply()
        }
    }
}
