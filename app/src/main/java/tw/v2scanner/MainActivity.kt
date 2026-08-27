package tw.v2scanner

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.EditText
import android.text.InputType
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedInputStream
import java.io.DataOutputStream


class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var scan: Button
    private lateinit var auto: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24,24,24,24)
        }
        val title = TextView(this).apply {
            text = "台股 V2 整合測試 V0.5"
            textSize = 24f
        }
        val note = TextView(this).apply {
            text = "動態清單・完整性稽核・自動重試・盤中快照・資料匯出"
            textSize = 15f
        }
        auto = CheckBox(this).apply {
            text = "啟用盤中自動掃描（09:05 起每小時至 13:30）"
            isChecked = getSharedPreferences("settings",0).getBoolean("auto", false)
        }
        val save = Button(this).apply { text = "儲存排程設定" }
        scan = Button(this).apply { text = "立即執行完整測試" }
        val exportJson = Button(this).apply { text = "匯出最後一次 JSON" }
        val exportCsv = Button(this).apply { text = "匯出最後一次 CSV" }
        val shareJson = Button(this).apply { text = "分享最後一次 JSON（可選 ChatGPT / Drive）" }
        val testUpload = Button(this).apply { text = "測試傳送最後一次 JSON" }

        status = TextView(this).apply { textSize = 16f; setPadding(0,8,0,8) }
        result = TextView(this).apply { textSize = 16f }
        val scroll = ScrollView(this).apply { addView(result) }

        root.addView(title); root.addView(note); root.addView(auto)
        root.addView(autoUpload); root.addView(host); root.addView(port)
        root.addView(save); root.addView(scan); root.addView(exportJson)
        root.addView(exportCsv); root.addView(shareJson); root.addView(testUpload); root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)

        status.text = "排程：${if(auto.isChecked) "已啟用" else "未啟用"}"

        save.setOnClickListener {
            prefs.edit()
                .putBoolean("auto", auto.isChecked)
                .putBoolean("auto_upload", autoUpload.isChecked)
                .putString("gateway_host", host.text.toString().trim())
                .putString("gateway_port", port.text.toString().trim())
                .apply()
            if (auto.isChecked) ScanScheduler.schedule(this) else ScanScheduler.cancel(this)
            status.text = if(auto.isChecked) "排程已啟用：09:05 起每60分鐘" else "排程已關閉"
        }

        scan.setOnClickListener {
            scan.isEnabled = false
            status.text = "完整測試執行中……"
            ScanWorker.run(this) { report ->
                runOnUiThread {
                    status.text = "測試完成"
                    result.text = report
                    scan.isEnabled = true
                }
            }
        }

        exportJson.setOnClickListener { exportLast("json") }
        exportCsv.setOnClickListener { exportLast("csv") }
        shareJson.setOnClickListener { shareLastJson() }
        testUpload.setOnClickListener {
            val content = prefs.getString("last_json", null)
            if (content == null) {
                Toast.makeText(this, "請先完成一次掃描。", Toast.LENGTH_LONG).show()
            } else {
                testUpload.isEnabled = false
                status.text = "測試傳送中……"
                Thread {
                    val r = GatewayUploader.uploadJson(
                        host.text.toString().trim(), port.text.toString().trim(), content
                    )
                    runOnUiThread {
                        status.text = r
                        testUpload.isEnabled = true
                    }
                }.start()
            }
        }
    }

    private fun exportLast(ext:String) {
        val content = getSharedPreferences("scan",0).getString("last_$ext", null)
        if(content == null) {
            Toast.makeText(this,"請先完成一次掃描。",Toast.LENGTH_LONG).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
        val file = File(getExternalFilesDir(null), "TaiwanV2Scanner_$stamp.$ext")
        file.writeText(content,Charsets.UTF_8)
        shareFile(file, if(ext=="json") "application/json" else "text/csv")
    }

    private fun shareLastJson() {
        val content = getSharedPreferences("scan",0).getString("last_json", null)
        if(content == null) {
            Toast.makeText(this,"請先完成一次掃描。",Toast.LENGTH_LONG).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
        val file = File(cacheDir,"TaiwanV2Scanner_$stamp.json")
        file.writeText(content,Charsets.UTF_8)
        shareFile(file,"application/json")
    }

    private fun shareFile(file: File, mime: String) {
        val shareUri = FileProvider.getUriForFile(
            applicationContext,
            "${applicationContext.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "分享資料"))
    }
}

object ScanWorker {
    fun run(context:Context,done:(String)->Unit) {
        Thread {
            try {
                val codes=Market.fetchCodes()
                val candidates=listOf(50,75,100,125,150)
                val sb=StringBuilder()
                var bestSize=100
                var bestRate=-1.0
                var bestFailed=Int.MAX_VALUE
                var bestSeconds=Double.MAX_VALUE

                for(size in candidates){
                    val r=Market.batch(codes,size,0)
                    sb.append("【${size}檔/批】 ${r.returned}/${codes.size}｜${"%.2f".format(r.rate)}%｜失敗${r.failed}｜${"%.2f".format(r.seconds)}秒\n")
                    if(r.rate>bestRate || (r.rate==bestRate && r.failed<bestFailed) ||
                        (r.rate==bestRate && r.failed==bestFailed && r.seconds<bestSeconds)){
                        bestRate=r.rate;bestSize=size;bestFailed=r.failed;bestSeconds=r.seconds
                    }
                }

                val final=Market.batch(codes,bestSize,2)
                val missing=codes.filterNot{final.symbols.contains(it)}
                val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())

                val json=buildString {
                    append("{\"scan_time\":\"$stamp\",\"market\":\"TWSE\",\"expected_count\":${codes.size},")
                    append("\"returned_count\":${final.returned},\"missing_count\":${missing.size},")
                    append("\"completeness_rate\":${final.rate},\"best_batch\":$bestSize,")
                    append("\"failed_batches\":${final.failed},\"elapsed_seconds\":${final.seconds},")
                    append("\"missing_symbols\":[")
                    append(missing.joinToString(","){"\"$it\""})
                    append("],\"stocks\":[")
                    append(final.records.joinToString(","))
                    append("]}")
                }

                val csv=buildString{
                    append("scan_time,code,name,price,yesterday,open,high,low,volume,time,status\n")
                    final.csvRecords.forEach{append(it).append('\n')}
                }

                context.getSharedPreferences("scan",0).edit()
                    .putString("last_json",json).putString("last_csv",csv)
                    .putString("last_time",stamp).apply()

                val settings = context.getSharedPreferences("settings",0)
                val autoUpload = settings.getBoolean("auto_upload", false)
                val uploadReport = if (autoUpload) {
                    GatewayUploader.uploadJson(
                        settings.getString("gateway_host", "")?.trim().orEmpty(),
                        settings.getString("gateway_port", "8080")?.trim().orEmpty(),
                        json,
                        "TaiwanV2Scanner_$stamp.json"
                    )
                } else {
                    "未自動傳送（功能未啟用）"
                }

                val missText=if(missing.isEmpty()) "無" else missing.joinToString(", ")
                done("當次上市清單：${codes.size} 檔\n\n$sb\n"+
                    "【最佳批次＋2次重試】${bestSize}檔/批\n"+
                    "最終：${final.returned}/${codes.size}\n"+
                    "完整率：${"%.2f".format(final.rate)}%\n"+
                    "缺失：${missing.size} 檔\n"+
                    "缺失代號：$missText\n"+
                    "耗時：${"%.2f".format(final.seconds)} 秒\n"+
                    "自動傳送：$uploadReport\n\n"+
                    "完整 JSON 與 CSV 已保存，可用按鈕匯出或分享。")
            }catch(e:Exception){done("整合測試失敗：${e.javaClass.simpleName}\n${e.message}")}
        }.start()
    }
}

object GatewayUploader {
    fun uploadJson(host: String, port: String, json: String, fileName: String = "TaiwanV2Scanner_test.json"): String {
        if (host.isBlank()) return "失敗：Gateway IP 尚未設定"
        val p = port.toIntOrNull() ?: return "失敗：Port 必須是數字"
        return try {
            val boundary = "----TaiwanV2ScannerBoundary${System.currentTimeMillis()}"
            val url = URL("http://$host:$p/api/file/upload?path=%2F")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 10000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"files[]\"; filename=\"$fileName\"\r\n")
                out.writeBytes("Content-Type: application/json\r\n\r\n")
                out.write(json.toByteArray(Charsets.UTF_8))
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code == 204) "成功：JSON 已傳送到 $host:$p"
            else "失敗：Gateway HTTP $code"
        } catch (e: Exception) {
            "失敗：${e.javaClass.simpleName} - ${e.message ?: "無詳細訊息"}"
        }
    }
}

data class BatchResult(val returned:Int,val rate:Double,val failed:Int,val seconds:Double,
                       val symbols:Set<String>,val records:List<String>,val csvRecords:List<String>)

object Market {
    fun fetchCodes():List<String>{
        val arr=org.json.JSONArray(java.net.URL("https://openapi.twse.com.tw/v1/opendata/t187ap03_L").readText())
        val out=ArrayList<String>()
        for(i in 0 until arr.length()){
            val c=arr.getJSONObject(i).optString("公司代號").trim()
            if(c.matches(Regex("\\d{4,6}"))) out.add(c)
        }
        return out.distinct()
    }

    fun batch(codes:List<String>,size:Int,retries:Int):BatchResult{
        val start=System.currentTimeMillis()
        val records=LinkedHashMap<String,String>()
        var failed=0
        var missing=codes.toSet()

        repeat(retries+1){
            if(missing.isEmpty()) return@repeat
            for(chunk in missing.toList().chunked(size)){
                try{
                    val channels=chunk.joinToString("|"){"tse_${it}.tw"}
                    val enc=java.net.URLEncoder.encode(channels,"UTF-8")
                    val obj=org.json.JSONObject(java.net.URL(
                        "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=$enc&json=1&delay=0"
                    ).readText())
                    val msg=obj.optJSONArray("msgArray")
                    if(msg==null){failed++;continue}
                    for(j in 0 until msg.length()){
                        val q=msg.getJSONObject(j)
                        val c=q.optString("c").trim()
                        if(c.isNotEmpty()) records[c]=q.toString()
                    }
                }catch(_:Exception){failed++}
                Thread.sleep(250)
            }
            missing=codes.filterNot{records.containsKey(it)}.toSet()
        }

        val sec=(System.currentTimeMillis()-start)/1000.0
        val rate=if(codes.isEmpty())0.0 else records.size*100.0/codes.size
        val csv=records.values.map{q->
            val o=org.json.JSONObject(q)
            listOf(o.optString("t"),o.optString("c"),o.optString("n"),o.optString("z"),
                o.optString("y"),o.optString("o"),o.optString("h"),o.optString("l"),
                o.optString("v"),o.optString("t"),"success")
                .joinToString(","){"\"${it.replace("\"","\"\"")}\""}
        }
        return BatchResult(records.size,rate,failed,sec,records.keys,records.values.toList(),csv)
    }
}

object ScanScheduler {
    private const val ID = 2426005

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ID,
            Intent(context, ScanAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            60 * 60 * 1000L,
            pendingIntent
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ID,
            Intent(context, ScanAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}

class ScanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val now = Calendar.getInstance()
        val day = now.get(Calendar.DAY_OF_WEEK)
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) return
        if (minutes < 9 * 60 + 5 || minutes > 13 * 60 + 59) return

        ScanWorker.run(context) { }
    }
}
