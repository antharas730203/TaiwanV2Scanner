package tw.v2scanner

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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

        status = TextView(this).apply { textSize = 16f; setPadding(0,8,0,8) }
        result = TextView(this).apply { textSize = 16f }
        val scroll = ScrollView(this).apply { addView(result) }

        root.addView(title); root.addView(note); root.addView(auto)
        root.addView(save); root.addView(scan); root.addView(exportJson)
        root.addView(exportCsv); root.addView(shareJson); root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)

        status.text = "排程：${if(auto.isChecked) "已啟用" else "未啟用"}"

        save.setOnClickListener {
            getSharedPreferences("settings",0).edit().putBoolean("auto", auto.isChecked).apply()
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

    private fun shareFile(file:File,mime:String) {
        val intent=Intent(Intent.ACTION_SEND).apply {
            type=mime
            putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 某些 Android 版本不接受裸 file URI；這裡先提供系統分享入口。
        // 若系統拒絕 URI，使用「匯出」功能將檔案放到 App 外部檔案區。
        try { startActivity(Intent.createChooser(intent,"分享資料")) }
        catch(_:Exception) {
            Toast.makeText(this,"已建立檔案：${file.name}，請使用匯出功能。",Toast.LENGTH_LONG).show()
        }
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

                val missText=if(missing.isEmpty()) "無" else missing.joinToString(", ")
                done("當次上市清單：${codes.size} 檔\n\n$sb\n"+
                    "【最佳批次＋2次重試】${bestSize}檔/批\n"+
                    "最終：${final.returned}/${codes.size}\n"+
                    "完整率：${"%.2f".format(final.rate)}%\n"+
                    "缺失：${missing.size} 檔\n"+
                    "缺失代號：$missText\n"+
                    "耗時：${"%.2f".format(final.seconds)} 秒\n\n"+
                    "完整 JSON 與 CSV 已保存，可用按鈕匯出或分享。")
            }catch(e:Exception){done("整合測試失敗：${e.javaClass.simpleName}\n${e.message}")}
        }.start()
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
    private const val ID=2426005
    fun schedule(context:Context){
        val am=context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi=android.app.PendingIntent.getBroadcast(
            context,ID,Intent(context,ScanAlarmReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val cal=Calendar.getInstance().apply{
            set(Calendar.HOUR_OF_DAY,9);set(Calendar.MINUTE,5);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
            if(timeInMillis<=System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR,1)
        }
        am.setInexactRepeating(android.app.AlarmManager.RTC_WAKEUP,cal.timeInMillis,60*60*1000L,pi)
    }
    fun cancel(context:Context){
        val am=context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi=android.app.PendingIntent.getBroadcast(
            context,ID,Intent(context,ScanAlarmReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
    }
}
