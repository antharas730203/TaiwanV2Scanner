# TaiwanV2Scanner — AI Project Context

> This document is the project's persistent technical context for future AI sessions. Read this file before modifying the App.

## 1. Project identity

- Project: TaiwanV2Scanner（台股 V2 掃描器）
- Repository: `antharas730203/TaiwanV2Scanner`
- Default branch: `main`
- Package / applicationId: `tw.v2scanner`
- Current development version: V0.8.3 / versionCode 19
- Previous verified baseline: V0.8.2 / versionCode 18
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
- `ExactScanScheduler.kt`: exact alarm scheduling.
- `BootReceiver.kt`: restores scheduling after boot.

## 4. Scanner rules that must remain stable

- Full scan covers TWSE + TPEX.
- Current real-world scan result has been around 1981 records with about 99.95% success and zero failed batches in a successful run.
- Adaptive batch sizes are `[150, 125, 100, 75, 50]`.
- MIS endpoint is the established TWSE quote endpoint.
- Do not replace the working scanner API or batch algorithm unless explicitly requested and tested.

## 5. Layer1 and JSON data architecture

The archive consists of three complete JSON files per scan:

1. TWSE
2. TPEX
3. LAYER1

Every stock record carries:

`"_read_index": "STOCK_START"`

Root data carries the 6000-character read rule. The 6000-character value is a safe reading/processing unit, not a requirement to split files at exactly 6000 characters.

Never split an individual stock JSON object in the middle.

No separate INDEX JSON is required. Record markers are embedded in the records themselves.

Layer1 currently records fields such as layer, strategy, source_records, qualified_count, top_n and qualified/result stock data.

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

## 7. GitHub credentials and signing — critical

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

## 8. Scheduling

- Exact Alarm is the established scheduling mechanism.
- Android 31+ exact-alarm permission must be respected.
- Boot receiver restores scheduling.
- Trading mode and test mode are supported.
- Scheduled auto-upload is separate from manual upload.

## 9. Current UI direction

Main screen should be clean:

- Title only: `台股 V2 掃描器`
- Version and feature descriptions belong in `選單 → 關於`, not on the main screen.
- Main screen keeps current operational status and action buttons.

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

Current main actions:

1. `手動完整掃描`
2. `驗證 GitHub`
3. `匯出最新 JSON`
4. `上傳最新 JSON`
5. `查看排程診斷`

Do not re-add CSV/share actions to the main UI unless explicitly requested.

## 10. About section

Version and feature descriptions should be consolidated here. Current intended content includes:

- V0.8.3
- 台股 V2 掃描器
- 完整 TWSE＋TPEX 市場掃描
- 第一層轉機／動能市場預篩
- Exact Alarm 精確排程
- GitHub JSON 歸檔
- 手動／排程上傳支援
- TWSE、TPEX、LAYER1 三份完整資料

Do not put strategy details here; only describe the App's implemented role.

## 11. Display wording

Prefer user-friendly status wording. For example:

- `LAYER1_COMPLETE` → `完成`
- `最大批次字元` should be interpreted/displayed as `最大檔案字元` when it represents the largest exported JSON size, not an API batch size.
- Avoid exposing internal implementation names unless useful for diagnostics.

## 12. Safe development procedure

Before modifying code:

1. Read this file.
2. Read `AI_DEVELOPMENT_RULES.md`.
3. Fetch the current file from `main` and use its current blob SHA for updates.
4. Check the current version and signing configuration.
5. Do not overwrite large/core files from memory or partial snippets.

After modifying code:

1. Verify the changed files on `main`.
2. Check the resulting commit.
3. Wait for GitHub Actions to finish.
4. Verify Debug and Release builds.
5. Verify APK signing.
6. Verify package, versionName and versionCode.
7. Only then report the build as successful.

## 13. Historical safety notes

- V0.6.8 is the last known-good historical baseline.
- Do not delete useful historical source merely to clean up the repository.
- ScannerCore has previously been accidentally overwritten; protect it from unnecessary full-file replacement.
- DataArchiveUploader has been verified to upload all three complete files.
- A single App success message may summarize all three uploads; verify the actual GitHub paths when testing.
