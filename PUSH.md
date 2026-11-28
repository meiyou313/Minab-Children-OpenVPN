# Maintainer Release & CI Publishing Guide (`Minab OpenVPN`)

Everything in `Minab-Children-OpenVPN` builds through **GitHub Actions** (no local Android SDK/NDK required). All `0.9.8.x` hotfix builds stay on branch `minab-beta-0.9.8` until ready to promote `0.9.9` or `1.0.0`.

---

## 1. Required GitHub Actions Signing Secrets

All releases from **`v0.4` onward** are signed with **one permanent RSA-2048 keystore** (`alias = minab`, valid until November 2056) stored outside the repo (`~/workspace/minab-keystore/minab-release.p12`). Never commit the keystore, its base64, or passwords.

Configured repository secrets (**Settings → Secrets and variables → Actions**):
- `MINAB_KEYSTORE_BASE64_1`, `MINAB_KEYSTORE_BASE64_2`, `MINAB_KEYSTORE_BASE64_3` — split 3-part base64 string of `minab-release.p12` *(single `MINAB_KEYSTORE_BASE64` also supported as fallback)*
- `MINAB_KEYSTORE_PASSWORD`
- `MINAB_KEY_ALIAS` (`minab`)
- `MINAB_KEY_PASSWORD`

`.github/workflows/build-android.yml` fails loudly if these secrets are missing so no unsigned/wrong-key APK can ever replace an upgradeable release.

---

## 2. Versioning & Pre-Push Checks

1. Update **`main/build.gradle.kts`**:
   - Increment `versionCode` monotonically (e.g., `29`).
   - Update `versionName` (e.g., `"0.9.8.2-beta"`).
2. Prepend release highlights to **`CHANGELOG.md`**.
3. Run static validation locally before committing:
   - Parse all touched XML layouts/values with `[xml](Get-Content -Raw <file>)`.
   - Confirm zero direct `ui`-flavor imports inside `main/src/main/java` (preserve strict `MinabUiBridge` separation for `SkeletonOvpn2`).
   - Confirm `.github/workflows/*.yml` is untouched if pushing via a repo-scoped OAuth token (tokens without `workflow` scope reject commits modifying `.github/workflows/`).

---

## 3. Pushing, Tagging & Monitoring CI

```powershell
git add -A
git commit -m "v0.9.8.2: <concise summary>"
git push origin minab-beta-0.9.8
git tag -a v0.9.8.2 -m "Minab Beta v0.9.8.2"
git push origin v0.9.8.2
```

Two workflows run automatically on every branch push and tag push:
1. **`Minab Android` (`.github/workflows/build-android.yml`)** — runs `./gradlew assembleUiOvpn23Debug`, uploads the signed artifact, and auto-publishes the GitHub pre-release (`Minab-debug-Beta-v0.9.8.2.apk`) when triggered by a `v*` tag.
2. **`Build` (`.github/workflows/build.yaml`)** — runs full release builds and unit tests across **`Release SkeletonOvpn2`**, **`Release UiOvpn23`**, and **`Release UiOvpn2`**.

Monitor until both runs are `completed / success`:

```powershell
gh run list --limit 6
gh run watch <run-id> --exit-status
```

---

## 4. Updating Release Notes & Verifying Assets

After `Minab Android` publishes the release for tag `v0.9.8.2`, customize the GitHub Release notes via CLI (`repo` scope only) and verify the APK asset:

```powershell
gh release edit v0.9.8.2 --title "Minab Beta v0.9.8.2" --notes-file release-notes.md
gh release view v0.9.8.2
```

---

## 5. On-Device Smoke-Test Checklist (`v0.9.8.2`)

1. Install over the previous `v0.4+` beta without uninstalling.
2. Open **Settings → Help & Diagnostics → Diagnostics & Debug Logs** — verify Overview, Crashes, App Log, OpenVPN Log, and `.txt` export work with no crash.
3. Start connecting to a slow/unreachable server — verify amber row halo & spinner appear **immediately** on Home/Configs, then tap Disconnect while still `CONNECTING` — verify instant coral `DISCONNECTING` state, < 0.5 s stop, and zero ghost reconnect or false `CONNECTED`.
4. Run **Ping Test** or **Real Test** on a multi-server group (e.g., `Favorites`) — verify previous badges clear immediately when the test starts, and tapping **Cancel** (or hitting target server count) restores untouched pre-test results cleanly as **muted small gray pills** ranked below fresh results.
5. Verify **Add to group** (`[Group1 · Group2]`), **Smart Remove duplicates** (merges groups/stars), **Clean empty groups**, **Never-working servers**, **`✨` Smart Tools**, and zero-credential **Backup/Share** exports.
