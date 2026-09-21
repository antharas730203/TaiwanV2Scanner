# TaiwanV2Scanner — AI Change Log

## V0.8.8 — 2026-09-21

### Scheduling / temporary network recovery

- Intraday exact alarms remain `09:05,10:05,11:05,12:05,13:05`; post-market scheduling remains independent.
- `MarketStatus` now distinguishes `OK`, `RETRY`, and `SKIP`.
- Temporary network/API failures such as `UnknownHostException`, `ConnectException`, `SocketTimeoutException`, `NoRouteToHostException` and related interrupted I/O are classified as retryable instead of market/session skips.
- A scheduled trading scan that hits a temporary network failure is recorded as `WAIT_NETWORK` and enqueued through WorkManager with `NetworkType.CONNECTED`.
- The worker re-checks market status before scanning and retries while the failure remains transient.
- A waiting scan that reaches after 13:30 is recorded as `EXPIRED_WAITING_NETWORK` and is not executed after the intraday session.
- Confirmed non-session / no-market-response cases remain `SKIPPED`.
- Existing scan times, scanner acquisition logic, adaptive batch sizes, JSON/archive structure, GitHub credentials/upload flow, and 5314 monitoring schedule are unchanged.

### Diagnostics

- `schedule_history` can now distinguish `WAIT_NETWORK`, `FINISHED`, `SKIPPED`, and `EXPIRED_WAITING_NETWORK` for this failure path.
- Temporary network recovery is recorded with the actual market-status reason and current network summary.

This is the persistent AI-facing development history. Keep entries concise and factual.

## V0.8.5 — 2026-09-16

### Version

- `versionName = 0.8.5`
- `versionCode = 21`
- Previous App version: V0.8.4 / versionCode 20.
- Release signing identity remains unchanged.

### UI / diagnostics

- Main screen keeps only the operational actions: `手動完整掃描`, `驗證 GitHub`, `匯出最新 JSON`, `上傳最新 JSON`, `查看排程診斷`.
- Removed the old main-screen `盤後掃描` manual button.
- Drawer keeps independent post-market schedule controls under `自動排程`.
- `查看排程診斷` now has two tabs:
  - `last_*`: current diagnostic state.
  - `schedule_history`: recent rolling schedule history, newest first.
- Schedule history displays trigger time, schedule, status, index, mode, alarm/network state and additional diagnostic fields.
- About section now reports V0.8.5 and describes independent post-market scheduling and schedule-history diagnostics.

### Scheduling / post-market

- Intraday schedule remains independent from post-market schedule.
- Intraday default remains `09:05,10:05,11:05,12:05,13:05`.
- Post-market default remains `14:05` and is configured independently.
- Post-market validation checks the five expected current-day Layer1 archive records.
- At least 3 of 5 existing records is required before the post-market scan runs.
- Missing records are treated as missing scan output, not proof that the market was closed.
- Post-market scheduling is restored after reboot.

### AI-facing documentation

- `AI_PROJECT_CONTEXT.md` synchronized to V0.8.5 / versionCode 21 and current architecture.
- This changelog entry records the current UI, diagnostics, scheduling and post-market behavior for future AI sessions.

### Current CI state

- The V0.8.5 build must be treated as unverified until GitHub Actions completes Debug and Release builds, APK signature verification, package/version verification, and artifact upload.

## V0.8.4 — previous version

- versionCode 20, versionName 0.8.4.
- Independent post-market scheduling implementation was introduced.
- Post-market scan checks five expected intraday Layer1 records and uses a 3/5 threshold.
- Post-market exact alarm and reboot restoration were added.
- Complete TWSE/TPEX/LAYER1 archive upload path and indexed JSON export remained in place.

## V0.8.3 — previous release work

- UI refinement: remove version/feature description from the main screen and consolidate it under `關於`.
- Drawer refinement: replace close `×` with `←`; align close control with the main hamburger control; move section arrows to the right; use `▼` for open and `▶` for closed; make drawer vertically scrollable; hide the visible scrollbar; avoid keyboard obstruction of schedule input; improve section spacing.
- Status wording refinement: use user-facing wording such as `完成` instead of internal `LAYER1_COMPLETE`; use `最大檔案字元` when the value represents the largest exported JSON size.
- Archive naming refinement: distinguish manual and scheduled automatic history uploads with `_MANUAL_` and `_AUTO_` in filenames.
- Preserve existing scanner, JSON architecture, scheduling, and signing behavior while making these changes.

## V0.8.2 — verified baseline

- versionCode 18, versionName 0.8.2.
- Full TWSE + TPEX scan.
- Adaptive scan batch sizes: 150 / 125 / 100 / 75 / 50.
- Layer1 generation.
- Complete TWSE/TPEX/LAYER1 JSON archive architecture.
- Embedded `_read_index` markers.
- <=6000-character safe reading/processing rule; never split a stock JSON object.
- Exact Alarm scheduling.
- Scheduled auto-upload controlled by the `排程掃描完成後自動上傳 GitHub` setting.
- Manual scan does not implicitly upload.
- Manual `上傳最新 JSON` uploads all three complete files.
- DataArchiveUploader verified in practice to upload TWSE/TPEX/LAYER1.
- Release signing and CI verification established.

## V0.6.8 — historical stable baseline

- Last known-good historical App behavior before later UI/version changes.
- Preserve historical source and do not delete it casually.

## Development history notes

- ScannerCore has previously suffered an accidental full-file overwrite. Protect it from unnecessary replacement.
- A previous issue caused GitHub archives to contain TWSE/TPEX without LAYER1 through a legacy upload path. The current DataArchiveUploader path was corrected and manually verified with a real Layer1 history file.
- A successful manual archive test produced all three files for the same timestamp and confirmed their actual presence in GitHub.
