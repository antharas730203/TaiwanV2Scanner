# TaiwanV2Scanner — AI Project Context

> This document is the project's persistent technical context for future AI sessions. Read this file before modifying the App.

## 1. Project identity

- Project: TaiwanV2Scanner（台股 V2 掃描器）
- Repository: `antharas730203/TaiwanV2Scanner`
- Default branch: `main`
- Package / applicationId: `tw.v2scanner`
- Current development version: **V0.8.5 / versionCode 21**
- Previous verified baseline: V0.8.4 / versionCode 20
- Historical stable baseline: V0.6.8

## 2. Scope

This repository is the Android App project. The App window should focus on App implementation, testing, build, data export, scheduling, GitHub archive, security/signing, and UI.

The stock-selection strategy itself is maintained separately. Do not redefine or overwrite the strategy specification in this file.

## 3. Core architecture

Main flow:

`ScannerCore → ScanEngine → BatchExporter → DataArchiveUploader → GitHub`

Important components:

- `ScannerCore.kt`: TWSE/TPEX data acquisition and adaptive batch scanning. This is a verified core component and must not be rewritten casually.
- `ScanEngine`: coordinates full scan and Layer1 generation.
- `BatchExporter.kt`: creates local complete JSON outputs and handles scheduled GitHub archive upload when enabled.
- `DataArchiveUploader.kt`: uploads complete TWSE/TPEX/LAYER1 history files to GitHub and securely loads the GitHub token.
- `MainActivity.kt`: App UI, settings, manual actions, status and diagnostics.
- `ExactScanScheduler.kt`: exact alarm scheduling, including independent post-market scheduling.
- `BootReceiver.kt`: restores intraday and post-market scheduling after reboot.
- `PostMarketScanner.kt`: checks the five expected intraday Layer1 archive records and runs the independent post-market scan when at least 3 of 5 records exist.
- `ScheduleDiagnostics.kt`: stores rolling schedule history for diagnostics.

## 4. Scanner rules that must remain stable

- Full scan covers TWSE + TPEX.
- Current real-world scan result has been around 1980 records with high completion rate in successful runs.
- Adaptive batch sizes are `[150, 125, 100, 75, 50]`.
- MIS endpoint is the established TWSE quote endpoint.
- Do not replace the working scanner API or batch algorithm unless explicitly requested and tested.

## 5. Layer1 and JSON data architecture

The archive consists of three complete JSON files per scan:

1. TWSE
2. TPEX
3. LAYER1

Record indexing is embedded in the JSON records. Each market has its own sequential `record_index`, and records carry numbered `_read_index` markers such as `STOCK_START_0001`.

Root data carries the 6000-character safe reading rule. The 6000-character value is a safe reading/processing unit, not a requirement to split files at exactly 6000 characters.

Never split an individual stock JSON object in the middle.

No separate INDEX JSON is required. Record markers are embedded in the records themselves.

Layer1 records its layer, strategy, source records, qualified count, top_n and qualified/result stock data as implemented by the current source.

## 6. GitHub archive naming

Manual upload:

- `scanner_data/history/YYYYMMDD_HHMMSS_MANUAL_TWSE.json`
- `scanner_data/history/YYYYMMDD_HHMMSS_MANUAL_TPEX.json`
- `scanner_data/history/YYYYMMDD_HHMMSS_MANUAL_LAYER1.json`

Scheduled automatic upload:

- `scanner_data/history/YYYYMMDD_HHMMSS_AUTO_TWSE.json`
- `scanner_data/history/YYYYMMDD_HHMMSS_AUTO_TPEX.json`
- `scanner_data/history/YYYYMMDD_HHMMSS_AUTO_LAYER1.json`

A manual scan must not implicitly upload to GitHub. The user must press `上傳最新 JSON` for a manual upload.

A scheduled scan uploads automatically only when `排程掃描完成後自動上傳 GitHub` is enabled.

## 7. Post-market scheduling

Post-market scheduling is independent from the intraday schedule.

- Intraday default: `09:05,10:05,11:05,12:05,13:05`.
- Post-market default: `14:05`.
- Drawer setting: `啟用盤後排程` plus one configurable `盤後排程時間`.
- Do not add 14:05 into the intraday schedule list because trading mode ends at 13:30.
- At the post-market trigger, inspect the current day's five expected Layer1 history records.
- Existing file = that timepoint produced a record.
- Missing file = no record; it does NOT mean the market was closed.
- At least 3 of 5 existing records is required to run the post-market scan.
- Stock count is not used as the existence criterion.
- No separate persistent status file is required.
- Post-market scheduling is restored after device reboot.

## 8. GitHub credentials and signing — critical

Never put real tokens, passwords, keystore contents, or secret values in source control or AI documentation.

GitHub Token is stored on-device encrypted with Android Keystore and AES/GCM. The current uploader uses the established Android Keystore alias `TaiwanV2ScannerGitHubKey`.

Release APK signing must continue using the existing official release signing key. Do NOT generate a new keystore for normal UI/function updates.

CI restores the established signing material from GitHub Secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

The workflow creates `keystore.properties`, builds Release, verifies the APK signature, verifies package/version metadata, then removes signing material.

Any future version update must preserve this signing setup so V0.8.x/V0.9.x updates can install over the existing signed App.

## 9. Scheduling diagnostics

`ScheduleDiagnosticsHistory` keeps a rolling history of up to 35 schedule events in SharedPreferences.

The App's `查看排程診斷` UI now provides two tabs:

- `last_*`: current diagnostic key/value state.
- `schedule_history`: recent scheduled alarm history, newest first.

History can show trigger time, configured schedule, status, index, mode, alarm/network state and additional fields written by the scheduler/worker.

This history is intended to diagnose missing scheduled scans and upload gaps. It must not expose tokens or secret values.

## 10. Current UI direction

Main screen should be clean:

- Title only: `台股 V2 掃描器`
- Version and feature descriptions belong in `選單 → 關於`, not on the main screen.
- Main screen keeps current operational status and action buttons.

Current main actions:

1. `手動完整掃描`
2. `驗證 GitHub`
3. `匯出最新 JSON`
4. `上傳最新 JSON`
5. `查看排程診斷`

Drawer requirements:

- Hamburger opens left drawer.
- Drawer title `選單` centered.
- Close control is a left arrow `←`, aligned in size/position with the main hamburger control.
- Section expand/collapse arrows are on the RIGHT.
- Open = `▼`; closed = `▶`.
- Drawer must scroll vertically.
- Hide the visible gray scrollbar while preserving scrolling.
- Keyboard must not obscure schedule time input.
- Keep clear spacing between sections and expanded controls.

## 11. About section

Current intended version: **V0.8.5**.

Feature descriptions include:

- 台股 V2 掃描器
- 完整 TWSE＋TPEX 市場掃描
- 第一層轉機／動能市場預篩
- Exact Alarm 精確排程
- 獨立盤後排程
- 排程歷史診斷
- GitHub JSON 歸檔
- 手動／排程上傳支援
- TWSE、TPEX、LAYER1 三份完整資料

Do not put strategy details here; only describe the App's implemented role.

## 12. Display wording

Prefer user-friendly status wording. For example:

- `LAYER1_COMPLETE` → `完成`
- `最大批次字元` should be interpreted/displayed as `最大檔案字元` when it represents the largest exported JSON size, not an API batch size.
- Avoid exposing internal implementation names unless useful for diagnostics.

## 13. Safe development procedure

Before modifying code:

1. Read this file.
2. Read `AI_DEVELOPMENT_RULES.md`.
3. Read `CHANGELOG_AI.md` for version/regression history.
4. Fetch the current file from `main` and use its current blob SHA for updates.
5. Check the current version and signing configuration.
6. Do not overwrite large/core files from memory or partial snippets.

After modifying code:

1. Verify the changed files on `main`.
2. Check the resulting commit.
3. Wait for GitHub Actions to finish.
4. Verify Debug and Release builds.
5. Verify APK signing.
6. Verify package, versionName and versionCode.
7. Only then report the build as successful.

## 14. Historical safety notes

- V0.6.8 is the last known-good historical baseline.
- Do not delete useful historical source merely to clean up the repository.
- ScannerCore has previously been accidentally overwritten; protect it from unnecessary full-file replacement.
- DataArchiveUploader has been verified to upload all three complete files.
- A single App success message may summarize all three uploads; verify the actual GitHub paths when testing uploads.
- Previous UI repair work briefly introduced an intermediate MainActivity source; the current `main` file is the source of truth and must be fetched before future edits.
