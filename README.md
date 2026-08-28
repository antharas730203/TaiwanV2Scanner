# TaiwanV2Scanner V0.6.2

台股掃描器 Android 專案。

## V0.6.2 重點
- TWSE + TPEX 動態股票清單；不寫死 1095。
- 正式掃描預設 150 檔/批；完整率低於 90% 時自動降為 125、100、75、50。
- 手動測試固定抓 150 檔單批，用來診斷即時資料取得穩定性。
- 排程時間可自由輸入；預設 09:05,10:05,11:05,12:05,13:05。
- AlarmManager 只負責喚醒，實際掃描交給 WorkManager，並留下排程/Worker/網路/批次診斷紀錄。
- GitHub 自動上傳：成功完整掃描寫入 scanner_data/latest.json，並保存 history/YYYYMMDD_HHmmss.json。
- 完整率低於 90% 不覆蓋 latest.json，改存 scanner_data/failed/。
- GitHub Token 不寫入程式碼，於 App 中輸入並使用 Android Keystore AES-GCM 加密保存。
- 保留 JSON/CSV 匯出與分享功能。

## GitHub 設定
App 內預設：
- owner: antharas730203
- repo: TaiwanV2Scanner
- branch: main

Token 需要對該 Repository 具備 Contents: Read and write。

## 編譯
GitHub Actions 會使用 Gradle 8.7 與 Android SDK 35 建立 debug APK。
