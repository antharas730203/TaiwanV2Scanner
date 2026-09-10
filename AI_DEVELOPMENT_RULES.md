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
- Keep `_read_index: STOCK_START` on stock records.
- Preserve the <=6000-character safe reading rule.
- Never split a single stock JSON object in the middle.
- Do not introduce a required separate INDEX file unless explicitly requested.

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
- Do not claim a new APK is ready until CI has verified the Release APK.

## 8. UI changes

- Keep the main screen clean and focused on operation.
- Version and feature descriptions belong in `關於`.
- Preserve the requested drawer behavior: right-side arrows, `▼` open / `▶` closed, scrolling, hidden scrollbar, keyboard avoidance, clear spacing.
- Do not modify scanning behavior while making UI-only changes.

## 9. Build verification

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

## 10. Do not expose secrets in diagnostics

Diagnostics may report whether a credential is configured, but must never display the credential itself, keystore passwords, or secret contents.

## 11. Preserve working history

- Do not delete historical source just to make the repository look cleaner.
- Do not delete working versions, backups, or records without explicit approval.
- Prefer small, traceable commits.

## 12. Failure handling

If a build fails:

- Read the actual CI failure.
- Identify the changed file responsible.
- Fix the smallest possible scope.
- Re-run verification.
- Do not declare success based on a previous successful build.
