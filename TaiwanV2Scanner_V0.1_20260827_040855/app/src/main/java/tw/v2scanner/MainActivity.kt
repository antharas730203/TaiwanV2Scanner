package tw.v2scanner

import android.app.Activity
import android.os.Bundle
import android.widget.*
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24,24,24,24)
        }
        val title = TextView(this).apply {
            text = "台股 V2 掃描器 V0.1"
            textSize = 24f
        }
        val code = EditText(this).apply {
            hint = "股票代號，例如 2426"
            setText("2426")
        }
        val button = Button(this).apply { text = "取得 TWSE 即時行情" }
        val result = TextView(this).apply {
            text = "尚未測試"
            textSize = 16f
        }
        box.addView(title); box.addView(code); box.addView(button); box.addView(result)
        setContentView(box)

        button.setOnClickListener {
            result.text = "連線中……"
            thread {
                try {
                    val symbol = code.text.toString().trim()
                    val channel = "tse_${symbol}.tw"
                    val url = URL("https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=$channel")
                    val text = url.readText(Charsets.UTF_8)
                    runOnUiThread { result.text = text }
                } catch (e: Exception) {
                    runOnUiThread { result.text = "連線失敗：${e.javaClass.simpleName}\n${e.message}" }
                }
            }
        }
    }
}
