package tw.v2scanner

import android.app.Activity
import android.os.Bundle
import android.widget.*
import android.content.Context

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var auto: CheckBox
    private lateinit var scan: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24,24,24,24)
        }

        val title = TextView(this).apply {
            text = "台股 V2 整合測試 V0.4"
            textSize = 24f
        }
        val desc = TextView(this).apply {
            text = "一次安裝：行情批量＋自動重試＋排程＋快照"
            textSize = 15f
            setPadding(0,8,0,12)
        }
        auto = CheckBox(this).apply {
            text = "啟用每日 09:00 自動測試"
            isChecked = getPreferences(0).getBoolean("auto", false)
        }
        val save = Button(this).apply { text = "儲存排程設定" }
        scan = Button(this).apply { text = "立即執行整合測試" }
        status = TextView(this).apply { textSize = 16f }
        result = TextView(this).apply { textSize = 16f; setPadding(0,16,0,0) }

        root.addView(title); root.addView(desc); root.addView(auto)
        root.addView(save); root.addView(scan); root.addView(status); root.addView(result)
        setContentView(root)

        status.text = "排程：${if(auto.isChecked) "已啟用" else "未啟用"}"

        save.setOnClickListener {
            getPreferences(0).edit().putBoolean("auto", auto.isChecked).apply()
            if (auto.isChecked) ScanScheduler.schedule(this) else ScanScheduler.cancel(this)
            status.text = "排程已儲存：${if(auto.isChecked) "每日約09:00" else "關閉"}"
        }

        scan.setOnClickListener {
            scan.isEnabled = false
            status.text = "整合測試執行中……"
            ScanWorker.run(this) { report ->
                runOnUiThread {
                    status.text = "整合測試完成"
                    result.text = report
                    scan.isEnabled = true
                }
            }
        }
    }
}

object ScanWorker {
    fun run(context: Context, done: (String) -> Unit) {
        Thread {
            try {
                val codes = Market.fetchCodes()
                val candidates = listOf(50,75,100,125,150)
                val sb = StringBuilder()
                var bestSize = 100
                var bestRate = -1.0
                for (size in candidates) {
                    val r = Market.batch(codes, size, 0)
                    sb.append("【${size}檔/批】 ${r.returned}/${codes.size}，${"%.2f".format(r.rate)}%，失敗${r.failed}批，${"%.2f".format(r.seconds)}秒\n")
                    if (r.failed <= 1 && r.rate > bestRate) {
                        bestRate = r.rate
                        bestSize = size
                    }
                }
                val final = Market.batch(codes, bestSize, 2)
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val json = """{"time":"$stamp","total":${codes.size},"bestBatch":$bestSize,"returned":${final.returned},"rate":${final.rate},"failedBatches":${final.failed},"seconds":${final.seconds}}"""
                context.getSharedPreferences("scan",0).edit()
                    .putString("last_json", json)
                    .putString("last_report", sb.toString())
                    .apply()
                done(
                    "當次上市清單：${codes.size} 檔\n\n" +
                    sb.toString() +
                    "\n【最佳批次＋2次重試】\n" +
                    "${bestSize}檔/批：${final.returned}/${codes.size}\n" +
                    "最終完整率：${"%.2f".format(final.rate)}%\n" +
                    "失敗批次：${final.failed}\n" +
                    "耗時：${"%.2f".format(final.seconds)} 秒\n\n" +
                    "JSON 快照已保存：$stamp"
                )
            } catch (e: Exception) {
                done("整合測試失敗：${e.javaClass.simpleName}\n${e.message}")
            }
        }.start()
    }
}

data class BatchResult(val returned:Int, val rate:Double, val failed:Int, val seconds:Double)

object Market {
    fun fetchCodes(): List<String> {
        val arr = org.json.JSONArray(
            java.net.URL("https://openapi.twse.com.tw/v1/opendata/t187ap03_L").readText()
        )
        val codes = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i).optString("公司代號").trim()
            if (c.matches(Regex("\\d{4,6}"))) codes.add(c)
        }
        return codes
    }

    fun batch(codes:List<String>, size:Int, retries:Int): BatchResult {
        val start=System.currentTimeMillis()
        val returned=HashSet<String>()
        var failed=0
        var missing=codes.toSet()

        repeat(retries+1) {
            if (missing.isEmpty()) return@repeat
            for (chunk in missing.toList().chunked(size)) {
                try {
                    val channels=chunk.joinToString("|") { "tse_${it}.tw" }
                    val enc=java.net.URLEncoder.encode(channels,"UTF-8")
                    val obj=org.json.JSONObject(
                        java.net.URL("https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=$enc&json=1&delay=0")
                            .readText()
                    )
                    val msg=obj.optJSONArray("msgArray")
                    if (msg == null) { failed++; continue }
                    for (j in 0 until msg.length()) returned.add(msg.getJSONObject(j).optString("c").trim())
                } catch (_:Exception) { failed++ }
                Thread.sleep(250)
            }
            missing=codes.filterNot { returned.contains(it) }.toSet()
        }
        val sec=(System.currentTimeMillis()-start)/1000.0
        val rate=if(codes.isEmpty()) 0.0 else returned.size*100.0/codes.size
        return BatchResult(returned.size,rate,failed,sec)
    }
}

object ScanScheduler {
    private const val ID=2426001
    fun schedule(context:Context) {
        val am=context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent=android.content.Intent(context,ScanAlarmReceiver::class.java)
        val pi=android.app.PendingIntent.getBroadcast(context,ID,intent,android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val cal=java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY,9);set(java.util.Calendar.MINUTE,0);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0)
            if(timeInMillis<=System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR,1)
        }
        am.setInexactRepeating(android.app.AlarmManager.RTC_WAKEUP,cal.timeInMillis,android.app.AlarmManager.INTERVAL_DAY,pi)
    }
    fun cancel(context:Context) {
        val am=context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi=android.app.PendingIntent.getBroadcast(context,ID,android.content.Intent(context,ScanAlarmReceiver::class.java),android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
    }
}
