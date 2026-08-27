# TaiwanV2Scanner V0.5.5 Gateway（修正版）

本版修正 V0.5.5 Gateway 測試版的 Kotlin 編譯錯誤，補齊 MainActivity 的 Gateway 控制項與 settings prefs。

功能：
- 09:05 起每小時盤中自動掃描
- TWSE 上市清單動態取得
- 批次完整性測試與重試
- JSON / CSV 保存、匯出、分享
- Gateway IP / Port 設定
- 「測試傳送最後一次 JSON」
- 掃描完成後自動 PUT 上傳 JSON 到 SHTTPS Gateway

預設 Gateway：
- IP：192.168.1.103
- Port：8080

注意：目前 Gateway 使用區域網路 HTTP；手機與 Scanner 必須在可互通的網路。
