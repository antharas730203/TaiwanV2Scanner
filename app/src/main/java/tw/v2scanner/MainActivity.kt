package tw.v2scanner

import android.app.Activity
import android.os.Bundle
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var buttons: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "台股 V2 掃描器 V0.3"
            textSize = 24f
        }

        val note = TextView(this).apply {
            text = "逐步放大測試：只需安裝一次，以下按鈕各自測試。"
            textSize = 16f
            setPadding(0, 10, 0, 10)
        }

        status = TextView(this).apply {
            text = "尚未測試"
            textSize = 16f
        }

        result = TextView(this).apply {
            textSize = 17f
            setPadding(0, 16, 0, 0)
        }

        root.addView(title)
        root.addView(note)

        val b100 = makeButton("① 測試 100 檔/批", 100)
        val b200 = makeButton("② 測試 200 檔/批", 200)
        val b500 = makeButton("③ 測試 500 檔/批", 500)
        val ball = makeButton("④ 全上市自動最佳化", -1)
        buttons = listOf(b100, b200, b500, ball)

        root.addView(b100)
        root.addView(b200)
        root.addView(b500)
        root.addView(ball)
        root.addView(status)
        root.addView(result)

        setContentView(root)
    }

    private fun makeButton(label: String, size: Int): Button {
        return Button(this).apply {
            text = label
            setOnClickListener { runBatch(size) }
        }
    }

    private fun setBusy(busy: Boolean) {
        buttons.forEach { it.isEnabled = !busy }
    }

    private fun runBatch(requestedSize: Int) {
        setBusy(true)
        status.text = "取得上市公司清單……"
        result.text = ""

        thread {
            val start = System.currentTimeMillis()
            try {
                val arr = JSONArray(
                    URL("https://openapi.twse.com.tw/v1/opendata/t187ap03_L")
                        .readText(Charsets.UTF_8)
                )
                val codes = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i).optString("公司代號").trim()
                    if (c.matches(Regex("\\d{4,6}"))) codes.add(c)
                }

                val sizes = if (requestedSize > 0) {
                    listOf(requestedSize)
                } else {
                    // 全上市按由小到大的安全策略連續測試，最後再給出比較結果
                    listOf(100, 200, 500)
                }

                val report = StringBuilder()
                for (size in sizes) {
                    val testStart = System.currentTimeMillis()
                    val chunks = codes.chunked(size)
                    val returned = HashSet<String>()
                    var failed = 0
                    var total = 0

                    for ((idx, chunk) in chunks.withIndex()) {
                        runOnUiThread {
                            status.text = "測試 ${size} 檔/批：第 ${idx + 1}/${chunks.size} 批"
                        }

                        try {
                            val channels = chunk.joinToString("|") { "tse_${it}.tw" }
                            val enc = URLEncoder.encode(channels, "UTF-8")
                            val url = URL(
                                "https://mis.twse.com.tw/stock/api/getStockInfo.jsp" +
                                "?ex_ch=$enc&json=1&delay=0"
                            )
                            val obj = JSONObject(url.readText(Charsets.UTF_8))
                            val msg = obj.optJSONArray("msgArray")
                            if (msg != null) {
                                total += msg.length()
                                for (j in 0 until msg.length()) {
                                    returned.add(msg.getJSONObject(j).optString("c").trim())
                                }
                            } else {
                                failed++
                            }
                        } catch (_: Exception) {
                            failed++
                        }

                        // 避免連續大量請求過快；測試目的不是追求極限速度
                        Thread.sleep(250)
                    }

                    val elapsed = (System.currentTimeMillis() - testStart) / 1000.0
                    val missing = codes.count { !returned.contains(it) }
                    val successRate = if (codes.isEmpty()) 0.0
                                      else returned.size * 100.0 / codes.size

                    report.append(
                        "【${size} 檔/批】\n" +
                        "批次數：${chunks.size}\n" +
                        "API 回傳筆數：$total\n" +
                        "去重後股票數：${returned.size}\n" +
                        "未回傳：$missing\n" +
                        "失敗批次：$failed\n" +
                        "回傳率：${"%.2f".format(successRate)}%\n" +
                        "耗時：${"%.2f".format(elapsed)} 秒\n\n"
                    )

                    // 只有自動最佳化模式才連續比較三種批次
                    if (requestedSize == -1) Thread.sleep(500)
                }

                val totalElapsed = (System.currentTimeMillis() - start) / 1000.0
                runOnUiThread {
                    status.text = if (requestedSize == -1)
                        "全上市三種批次測試完成"
                    else
                        "${requestedSize} 檔/批測試完成"

                    result.text =
                        "上市清單：${codes.size} 檔\n\n" +
                        report.toString() +
                        "整體耗時：${"%.2f".format(totalElapsed)} 秒\n\n" +
                        "判斷原則：優先選擇回傳率高、失敗批次少、耗時合理的批次大小。\n" +
                        "目前仍只做行情鏈路測試，尚未加入 V2 選股評分。"
                    setBusy(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "測試失敗"
                    result.text = "${e.javaClass.simpleName}: ${e.message}"
                    setBusy(false)
                }
            }
        }
    }
}
