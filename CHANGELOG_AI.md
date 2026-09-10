# TaiwanV2Scanner — AI Change Log

This is the persistent AI-facing development history. Keep entries concise and factual.

## V0.8.3 — 2026-09-10

### In progress / current release work

- UI refinement: remove version/feature description from the main screen and consolidate it under `關於`.
- Drawer refinement: replace close `×` with `←`; align close control with the main hamburger control; move section arrows to the right; use `▼` for open and `▶` for closed; make drawer vertically scrollable; hide the visible scrollbar; avoid keyboard obstruction of schedule input; improve section spacing.
- Status wording refinement: use user-facing wording such as `完成` instead of internal `LAYER1_COMPLETE`; use `最大檔案字元` when the value represents the largest exported JSON size.
- Archive naming refinement: distinguish manual and scheduled automatic history uploads with `_MANUAL_` and `_AUTO_` in filenames.
- Preserve existing scanner, JSON architecture, scheduling, and signing behavior while making these changes.
- Release signing must continue to use the established signing key.

### V0.8.3 files / commits created during this work

- `AI_PROJECT_CONTEXT.md` — persistent technical context for future AI sessions.
- `AI_DEVELOPMENT_RULES.md` — mandatory AI development and safety rules.
- `CHANGELOG_AI.md` — this persistent change log.

### Current CI state at the time of this entry

- V0.8.3 / versionCode 19 is the intended current App version.
- GitHub Actions is building the signed APK from the latest main commit.
- Do not call the build successful until Release signing and APK verification have completed.

## V0.8.2 — verified baseline

- versionCode 18, versionName 0.8.2.
- Full TWSE + TPEX scan.
- Adaptive scan batch sizes: 150 / 125 / 100 / 75 / 50.
- Layer1 generation.
- Complete TWSE/TPEX/LAYER1 JSON archive architecture.
- Embedded `_read_index: STOCK_START` markers.
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
