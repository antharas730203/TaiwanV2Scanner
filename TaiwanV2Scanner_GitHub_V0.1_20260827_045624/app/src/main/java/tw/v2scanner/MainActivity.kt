package tw.v2scanner

import android.app.Activity
import android.os.Bundle
import android.widget.*
import org.json.JSONObject
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24,24,24,24)
        }
        val title = TextView(this).apply { text = "台股 V2 掃描器 V0.1"; textSize = 24f }
        val code = EditText(this).apply {
            hint = "上市股票代號"; setText("2426")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val button = Button(this).apply { text = "取得 TWSE 即時行情" }
        val status = TextView(this).apply { text = "準備測試"; textSize = 16f }
        val result = TextView(this).apply { textSize = 18f; setPadding(0,24,0,0) }
        root.addView(title); root.addView(code); root.addView(button); root.addView(status); root.addView(result)
        setContentView(root)

        button.setOnClickListener {
            val symbol = code.text.toString().trim()
            if (symbol.isEmpty()) { status.text = "請輸入股票代號"; return@setOnClickListener }
            status.text = "連線中……"; result.text = ""
            thread {
                try {
                    val url = URL("https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=tse_${symbol}.tw")
                    val json = JSONObject(url.readText(Charsets.UTF_8))
                    val data = json.optJSONArray("msgArray")
                    if (data == null || data.length() == 0) {
                        runOnUiThread { status.text = "查詢失敗"; result.text = json.optString("msg","無資料") }
                        return@thread
                    }
                    val q = data.getJSONObject(0)
                    val name = q.optString("n", symbol)
                    val price = q.optString("z", "-")
                    val yesterday = q.optString("y", "-")
                    val volume = q.optString("v", "-")
                    val time = q.optString("t", "-")
                    val high = q.optString("h", "-")
                    val low = q.optString("l", "-")
                    val open = q.optString("o", "-")
                    runOnUiThread {
                        status.text = "API 連線成功"
                        result.text = "股票：$symbol $name\n最新價：$price\n昨收：$yesterday\n開盤：$open\n最高：$high\n最低：$low\n累計成交量：$volume\n成交時間：$time"
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "API 連線失敗"; result.text = "${e.javaClass.simpleName}: ${e.message}" }
                }
            }
        }
    }
}
