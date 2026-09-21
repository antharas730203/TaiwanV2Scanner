# TaiwanV2Scanner — AI Development Rules

> Mandatory rules for any AI session modifying this Android App.

## 1. Always establish context first

- Read `AI_PROJECT_CONTEXT.md` before making changes.
- Read `CHANGELOG_AI.md` when the task concerns a version change, regression, or previous implementation.
- Treat the repository `main` branch as the source of truth for current code.

## 2. Never guess the current code

- Fetch the current file from `main` before editing it.
- Use the current blob SHA for an update.
- Do not reconstruct a large source file from memory or a truncated tool result.
- If a file is large, retrieve it in sections and reconstruct the complete source before replacing it.

## 3. Protect the scanning core

- `ScannerCore.kt` is a verified working component.
- Do not rewrite or replace it unless the user explicitly asks for a scanner-core change.
- Do not alter the working TWSE/TPEX API, adaptive batch logic, or successful data acquisition merely to implement UI, archive, or documentation changes.
- If a scanner-core change is necessary, test it separately and verify scan success before proceeding.

## 4. Protect data architecture

- Keep TWSE, TPEX and LAYER1 as complete JSON files.
- Keep sequential per-market `record_index` and numbered `_read_index` markers such as `STOCK_START_0001`.
- Preserve the <=6000-character safe reading rule.
- Never split a single stock JSON object in the middle.
- Do not introduce a required separate INDEX file unless explicitly requested.

## 4.1 AI讀檔與歷史資料搜尋規則

- 查詢 Scanner 歷史資料時，禁止直接猜測或拼接檔名後判定檔案不存在。
- 必須先讀取 `scanner_data/history/` 目錄，取得實際存在的檔案清單。
- 依檔名中的 `YYYYMMDD_HHMMSS` 時間排序，找出當日實際存在的最新資料檔。
- App 顯示的 `updated` 時間不可直接視為 GitHub 歷史檔案名稱時間。
- 例如 App 顯示 `11:05:21`，GitHub 實際檔案可能是 `11:05:12`；必須以 GitHub 實際存在的檔案為準。
- 找到檔案後，再讀取 JSON 內容。
- 查詢特定股票時，必須以股票代號欄位精確比對，例如 `c = "5314"`，不可只用全文模糊搜尋數字 `5314`。
- 找到股票後，至少核對：
  - `c` 股票代號
  - `n` 股票名稱
  - `market`
  - `record_index`
  - `_read_index`
- 若查詢的是 TPEX 股票，優先確認最新的 `*_AUTO_TPEX.json`；TWSE 股票則確認最新的 `*_AUTO_TWSE.json`。
- 若存在最新 AUTO 檔案，不得因預期的時間戳不存在而判定「沒有資料」。
- 若目錄清單顯示最新檔案存在，必須以該實際檔案內容為準。
- 只有在確認目錄中確實沒有較新的檔案後，才能回答「尚未產生最新掃描檔」。

## 5. Manual vs automatic upload

- Manual full scan must not implicitly upload to GitHub.
- Manual upload uses `上傳最新 JSON` and uploads TWSE/TPEX/LAYER1.
- Scheduled scan auto-uploads only when the scheduled GitHub upload setting is enabled.
- Manual history files use `_MANUAL_` in the filename.
- Scheduled automatic history files use `_AUTO_` in the filename.
- Verify actual GitHub files when testing uploads; do not rely only on the App's summary message.

## 6. Credentials and signing — DO NOT BREAK

### GitHub token

- Never print, commit, expose, or store a real GitHub Token in source code or documentation.
- The App uses Android Keystore + AES/GCM for the stored token.
- Preserve the established Android Keystore alias unless a security migration is explicitly requested.

### Release signing

- Never generate a replacement release keystore for a normal App update.
- Preserve the existing release signing identity so updates install over the existing App.
- CI uses GitHub Secrets for signing material:
  - `KEYSTORE_BASE64`
  - `KEYSTORE_PASSWORD`
  - `KEY_ALIAS`
  - `KEY_PASSWORD`
- The workflow must restore the established keystore, build Release, verify the APK signature, verify package/version metadata, and remove temporary signing files.
- Never commit `keystore.properties`, the release `.jks`, or any secret values.

## 7. Versioning

- Update `versionName` and `versionCode` intentionally.
- Any installable update over an existing release must have a higher `versionCode` and the same release signing identity.
- Current version: V0.8.9 / versionCode 25.
- Do not claim a new APK is ready until CI has verified the Release APK.

## 8. Scheduling / temporary network recovery

- `MarketStatus` uses `OK`, `RETRY`, and `SKIP`; transient network/API failures must remain retryable.
- For scheduled trading scans, a transient network failure must record `WAIT_NETWORK` and enqueue WorkManager with `NetworkType.CONNECTED`.
- The worker must re-check market status before scanning and return retry while the failure remains transient.
- Waiting intraday work must expire after 13:30 as `EXPIRED_WAITING_NETWORK`; it must not execute after the trading session.
- Confirmed non-session / no-market-response cases remain `SKIPPED`.
- Preserve test-mode scheduled scan behavior and scheduler-state restoration when changing this path.

## 9. UI changes

- Keep the main screen clean and focused on operation.
- Version and feature descriptions belong in `關於`.
- Preserve the requested drawer behavior: right-side arrows, `▼` open / `▶` closed, scrolling, hidden scrollbar, keyboard avoidance, clear spacing.
- Main screen actions currently include manual scan, GitHub verification, JSON export/upload and schedule diagnostics.
- Do not modify scanning behavior while making UI-only changes.

## 10. Build verification

After any code change:

1. Confirm changed files are on `main`.
2. Confirm the commit SHA.
3. Check the GitHub Actions run for that commit.
4. Wait for the workflow to finish.
5. Confirm Debug build success.
6. Confirm Release build success.
7. Confirm APK signature verification success.
8. Confirm package `tw.v2scanner`.
9. Confirm expected `versionName` and `versionCode`.
10. Confirm artifact upload.

Only after all applicable checks pass may the AI report the build as successful.

## 11. Do not expose secrets in diagnostics

Diagnostics may report whether a credential is configured, but must never display the credential itself, keystore passwords, or secret contents.

## 12. Preserve working history

- Do not delete historical source just to make the repository look cleaner.
- Do not delete working versions, backups, or records without explicit approval.
- Prefer small, traceable commits.

## 13. Failure handling

If a build fails:

- Read the actual CI failure.
- Identify the changed file responsible.
- Fix the smallest possible scope.
- Re-run verification.
- Do not declare success based on a previous successful build.


### V0.8.9 scheduling/network rules
- Do not perform a MarketStatus network probe inside the exact-alarm receiver before WorkManager is queued.
- Each intraday schedule round must have an independent WorkManager unique name derived from its schedule-history ID.
- Use WorkManager NetworkType.CONNECTED as the system-managed network wait; MarketStatus is authoritative only when the worker starts.
- Do not add force-Wi-Fi, force-cellular, or long-running WakeLock behavior unless separately justified by device evidence.
