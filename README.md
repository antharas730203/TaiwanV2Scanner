# Taiwan V2 Scanner V0.5.5

在 V0.5.4 基礎上新增「手機 Gateway 自動傳送」測試功能。

## 新增
- 設定 Gateway IP / Port（預設 192.168.1.103:8080）
- 可手動測試傳送最後一次 JSON
- 可勾選「掃描完成後自動傳送 JSON 到手機 Gateway」
- 使用 Simple HTTP Server 的 `PUT /api/file/upload?path=/` 上傳 API
- 原有 JSON/CSV 匯出、Android 分享、排程與掃描流程保留

## 測試前提
- 手機與 Scanner 電腦需在同一個區域網路
- SHTTPS 需啟用 Allow file modification
- SHTTPS Server 需保持 running
- 目前版本先使用 HTTP 區網傳送，不是公開網際網路服務
