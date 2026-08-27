# 台股 V2 掃描器 V0.1

目的：先在 Android 手機測試 TWSE MIS 即時行情。

目前功能：
- 輸入股票代號
- 預設 2426
- 直接呼叫 TWSE MIS getStockInfo.jsp
- 顯示原始 JSON
- 這一版尚未加入 V2 評分、全市場掃描、TPEX、推播

注意：
- APK 必須在可正常建置的 Android/Gradle 環境中編譯。
- 若 TWSE MIS 對手機網路環境拒絕請求，畫面會顯示錯誤。
