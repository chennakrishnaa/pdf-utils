# Offkit 1.0.0 platform and artifact security audit (task SB)

> **Note (2026-11-25):** The audit body below and the SR re-check were written before the UI redesign (merged on `main` at `c8e0f22`). The redesign added a share `FileProvider`, keeps the job output after a save, and changed the image picker. Several statements below are now out of date. They are left as written and corrected in [Redesign addendum (2026-11-25)](#redesign-addendum-2026-11-25) at the end of this document.

Auditor: independent review of the app surface and the shipped artifacts (everything except the file-parsing engine `app/src/main/java/app/venkat/offkit/engine/**`, which another audit covers).
Date: 2026-11-23. Source at `main` = `2e433df`. The release artifacts were built from `8f61110` (`META-INF/version-control-info.textproto`). The only difference between the two commits is `dist/offkit-debug.apk` (`git diff --stat 8f61110 HEAD`), so the artifacts match the audited source.

## Summary

**Verdict: acceptable for public release, with the listed low-severity fixes recommended.** There are no Critical, High or Medium findings. I checked the signed release APK and AAB directly, not only the source. The merged manifest requests **no permissions at all** (not even INTERNET). It declares exactly one exported component, the launcher activity, which reads nothing from the incoming intent. The androidx.startup provider is not exported, and the ProfileInstaller receiver has been removed. Backup and device transfer are fully excluded, and the app is neither debuggable nor testOnly. No app code references sockets, logging, the clipboard or PendingIntents. Every network-capable class in the dex is unreachable library code, and the OS enforces the missing INTERNET permission in any case. The native libraries import no socket or DNS functions. The APK is signed v2+v3 with the expected RSA-4096 certificate, is 16 KB aligned and is 64-bit only. No secrets appear in the tracked tree or in any of the 127 commits. OSV reports no known vulnerabilities for any of the 111 resolved Maven coordinates.

The findings are hardening items:
- Two privacy claims ("Your files never leave your device", "Nothing is uploaded") are only partly true, because a file saved to or shared with a cloud provider does leave the device.
- Provider display names are stored and shown without a length cap or bidi sanitisation.
- The MuPDF repository is not declared `exclusiveContent`.
- The Gradle wrapper has no `distributionSha256Sum` and there is no dependency verification.
- The tracked debug APK uses the release applicationId and is debuggable.
- Minor Info items: task affinity, FLAG_SECURE, tapjacking and R8.

## Method

Every shell command used `export ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`. Artifacts were read without being rebuilt:
- `aapt2 dump xmltree/badging/resources` for the manifest and resources.
- `apksigner verify --verbose --print-certs` and `zipalign -c -P 16 -v 4` for signing and alignment.
- `readelf -lW / -dW / --dyn-syms` and `strings` on `lib/*/*.so`.
- `dexdump -d` on `classes.dex` and `classes2.dex` (4.3 M lines), with each sensitive reference attributed to its declaring class.
- `unzip -l` on the APK, the AAB and the tracked debug APK.

Other inputs:
- One offline Gradle call: `./gradlew --offline :app:dependencies --configuration releaseRuntimeClasspath -q`.
- One OSV `querybatch` POST containing package coordinates only, with a generic user agent.
- A full history dump (`git log -p --all --text`), grepped for secrets and key-file names.
- A read of the security-relevant non-engine sources: all of `core/**`, `MainActivity`, `PdfUtilApp`, `ui/nav/AppNavHost.kt`, `ui/home/HomeViewModel.kt`, the recents actions in `HomeScreen.kt`, `ui/components/{Pickers,Format}.kt`, `ui/support/{SupportLinks,SupportStore,HousePromos}.kt`, `ui/onboarding/OnboardingStore.kt`, `openLink` in `AboutScreen.kt`, and the input and suggested-name paths of the four feature ViewModels. The remaining Compose screens (rendering only) were covered by grep for intents, logs, clipboard and `.message`, not read line by line. Also read: the manifest, the XML rules, the Gradle files, `.gitignore`, `PRIVACY.md` and the onboarding strings.

Nothing under `/home/ubuntu/keys/` and no `keystore.properties` content was read. `keystore.properties` was checked by name only: it exists in the main checkout with mode 0600, is ignored and is untracked.

## Findings (sorted by severity)

| ID | Severity | Location | Description | Attacker / exploitation | Impact | How confirmed | Suggested fix |
|---|---|---|---|---|---|---|---|
| SB-1 | Low | `res/values/strings_onboarding.xml` (`onboarding_private_text`, `onboarding_control_nothing`), shipped in `resources.arsc` | The onboarding says "Your files never leave your device." and "Nothing is uploaded or backed up". These hold for the app itself, but not for files the user saves to a cloud provider via SAF (Drive, OneDrive), or shares or opens from Recent files. PRIVACY.md is more careful ("hands over to another app"). | None. This is a claims accuracy issue (store policy / user trust). | Misleading privacy statement. | `aapt2 dump resources` of the release APK; `RecentFiles.kt` share/view intents; CreateDocument allows any provider. | Reword, e.g. "Offkit never uploads your files; they go only where you save or share them." |
| SB-2 | Low | `core/UriIo.kt` `describe()`; `core/RecentFiles.kt` `add`/`encode`; `ui/home/HomeScreen.kt:376`, `ui/components/FileList.kt:115` | The provider-supplied `DISPLAY_NAME` is untrusted, yet it is stored in `shared_prefs/recent_files.xml` and shown with no length cap and no stripping of control or bidi characters. UI rendering is bounded (`maxLines` + ellipsis), and the name is never used as a path (verified). | A **malicious documents provider** the user picks or saves to. (1) A name up to the CursorWindow limit (~2 MB) × 20 entries is rewritten to prefs on every change and parsed at store creation, causing slowness or OOM. (2) A name with U+202E such as `invoice\u202Efdp.exe` displays as `invoiceexe.pdf`, misleading the user about which file is listed. | Local DoS of the Recent list or a cosmetic spoof. No code execution or path traversal. | Source review: `name` flows unchanged from the cursor to `JSONObject.put("name", …)` and `Text(file.name)`. | Truncate names to about 255 chars and strip `\p{Cc}`, `\p{Cf}` (bidi overrides and isolates) and newlines in `UriIo.describe` before storing or displaying. Also note that `AddImagesViewModel.suggestedNameFor` passes the picked PDF's name (unsanitised, no length cap) as the CreateDocument title. `CompressViewModel.compressedFileName` does sanitise and cap at 100 chars. DocumentsUI sanitises the title, so this is cosmetic; reuse the compress sanitiser. |
| SB-3 | Low | `settings.gradle.kts` `dependencyResolutionManagement` | `maven.ghostscript.com` is HTTPS and restricted by `content { includeGroup("com.artifex.mupdf") }`, so it cannot serve other groups. However, `google()` and `mavenCentral()` are listed **first** and are not excluded from `com.artifex.mupdf`, so Gradle will use a `com.artifex.mupdf:fitz` from Central or Google if one ever appears there. | **Supply chain**: someone who manages to publish under `com.artifex.mupdf` on Maven Central. Central requires namespace (domain) verification, which makes this hard. | Substituted native library in the build. | Read of `settings.gradle.kts`. | Use `exclusiveContent { forRepository { maven("https://maven.ghostscript.com") }; filter { includeGroup("com.artifex.mupdf") } }`. |
| SB-4 | Low | `gradle/wrapper/gradle-wrapper.properties`; `gradle/` | There is no `distributionSha256Sum` (only `validateDistributionUrl=true`) and no `gradle/verification-metadata.xml`, so neither the Gradle distribution nor any dependency or plugin artifact is checksum-pinned. The plugins come from `google()`, `mavenCentral()` and `gradlePluginPortal()` with no content filters. | **Supply chain or network attacker** against the build host's TLS, or a compromised repository. | Tampered build toolchain or dependency. | `ls gradle/` shows only `libs.versions.toml` and `wrapper/`; read of the properties file. | Add `distributionSha256Sum=` for gradle-9.6.1-bin.zip. Generate `./gradlew --write-verification-metadata sha256 help` and commit it. Optionally restrict the plugin repositories by group. |
| SB-5 | Low | `dist/offkit-debug.apk` (tracked, force-added despite `*.apk` in `.gitignore`) | The debug APK uses the **same applicationId** `app.venkat.offkit`, is `android:debuggable=true` and is signed with the machine's Android Debug key (SHA-256 `7d3d4a40…`). Anyone who installs it from the repo gets a debuggable build: `run-as` exposes the app's private data (recent names and URIs) over adb. They also cannot upgrade to the Play build without uninstalling it, and the "update" channel is then controlled by whoever holds that debug keystore. | **Someone with the unlocked phone plus adb** (data access); confusion over which build is genuine. | Private recents readable; upgrade friction. | `aapt2 dump badging` shows `application-debuggable` and `package: name='app.venkat.offkit'`; `apksigner --print-certs`. | Give debug builds `applicationIdSuffix = ".debug"`, or stop tracking the debug APK and publish signed releases only. |
| SB-6 | Info | Merged manifest, `MainActivity` | No `android:taskAffinity=""`. The default affinity equals the package and the launch mode is standard. A StrandHogg-1 style task hijack (a malicious app declaring `taskAffinity="app.venkat.offkit"`) remains possible on older Android versions (minSdk 26). | **Another app on the device**, which shows a fake Offkit screen. | Low: Offkit has no credentials to phish. | `aapt2 dump xmltree`. | Add `android:taskAffinity=""` to the application or activity. |
| SB-7 | Info | Whole app | No `FLAG_SECURE`, so file names in Recent files and in the tool lists appear in screenshots and in the recents thumbnail. | **Someone with the unlocked phone**, or a screen recorder the user enabled. | File names visible. | No `FLAG_SECURE` in app dex or source. | Optional. It is a product choice and not a defect. |
| SB-8 | Info | Save and confirm buttons | No `filterTouchesWhenObscured`. Android 12+ blocks untrusted overlay touches system-wide, and the destination is chosen in the system SAF UI. | **Another app** that draws overlays (needs SYSTEM_ALERT_WINDOW). | Negligible: the worst case is starting a job or opening the save dialog. | Source and dex grep. | Note only. |
| SB-9 | Info | `app/build.gradle.kts` `isMinifyEnabled = false` | R8 is off: no shrinking or obfuscation. All library code ships, including dead network-capable helpers (see check 5). For AGPL source this is fine. | n/a | Slightly larger dead code surface. | Dex class listing. | Optional: enable R8 with keep rules for MuPDF JNI. |
| SB-10 | Info | APK `META-INF/version-control-info.textproto`, AAB `BUNDLE-METADATA/.../dependencies.pb` | The APK carries the git revision `8f61110…` and the AAB carries the dependency list (standard AGP output; Play encrypts it). | n/a | None (the source is public). | `unzip`. | None needed. |
| SB-11 | Info | `AndroidManifest.xml` removes `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | If any library ever calls `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`, the app will **crash on API 26–32** (`obtainAndCheckReceiverPermission` throws). It fails closed, so this is not a vulnerability. The only current caller, Compose `AndroidComposeView.initializeMediaQueryListeners`, uses flag 2. The other wrapper, `androidx.core.content.ContextKt.registerReceiver`, has 0 call sites in the dex. Flag 2 (EXPORTED) for the system-protected `DOCK_EVENT` broadcast, so it is unaffected today. | n/a | A future library update could cause a crash. | dexdump of `ContextCompat$Api26Impl.registerReceiver` and all its callers. | Keep a device test on API ≤32, or keep the permission (signature level, as AndroidX declares it). |
| SB-13 | Info | `NavController.handleDeepLink(Intent)` (navigation-runtime 2.10.2) | The `deepLinkIds` extra branch is gated by `shouldTrustIntent`, but the **URI-matching branch is not** (dexdump: `matchDeepLinkComprehensive(NavDeepLinkRequest(intent))` runs without a trust check). Composable routes may also be reachable as internal `android-app://androidx.navigation/<route>` links (the string is in the dex). I did not confirm dynamically whether 2.10.2 adds route links to the matchable deep-link list. | **Another app on the device** sends an explicit intent to the exported `MainActivity` with such a data Uri. | At most, Offkit opens on a tool, About or Support screen. Routes take no arguments and every action needs a user tap, so the impact is nil. | Bytecode inspection of the release dex. | Optional: in `MainActivity`, clear `intent.data` (or set `intent = Intent(intent).setData(null)`) before composing `AppNavHost`. To verify: `adb shell am start -n app.venkat.offkit/.MainActivity -d android-app://androidx.navigation/support`. |
| SB-14 | Info | `UriIo.describe`, `PdfJobs.deleteDestination(IfEmpty)` | These functions contain `file:` scheme branches (File length, `File.delete()`), and no callback rejects non-`content` Uris. Today this is not reachable from outside: `OpenDocument`, `OpenMultipleDocuments` and `CreateDocument` are answered by the system DocumentsUI, which always returns `content://` document Uris. `PickVisualMedia` is answered by the system or GMS photo picker (androidx requires a system app for the backport). | Only if a future picker path returned `file://` Uris, for example `GetContent`, which third-party apps can answer. | It could then delete or overwrite app-private files, or crash on ACTION_VIEW (`FileUriExposedException` is not caught by `tryStart`). | Source review. | Defence in depth: accept only `uri.scheme == "content"` in the picker and saver callbacks, and drop the `file:` branches. |
| SB-12 | Info | `PdfJobs.save` failure path | If a save fails after the destination was opened with `"wt"`, the destination is deleted. When the provider's CreateDocument returned an existing document that the user confirmed to replace, the old content is lost. It was already truncated by `"wt"`, so this is consistent behaviour. | Not attacker-driven. | Data loss only on a failed overwrite. | Source review. | Optionally write to a new document and rename, where the provider supports it. |

## Verified OK

### 1. Manifest and components (release APK)
- [x] **Permissions: none.** `aapt2 dump badging` has no `uses-permission` lines. The only feature is the default implied `android.hardware.faketouch`. There is no INTERNET, no storage and no `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (removed via `tools:node="remove"`). The only `uses-library` entries are `androidx.window.extensions` and `androidx.window.sidecar` (`required=false`).
- [x] **Components** (`aapt2 dump xmltree --file AndroidManifest.xml`):
  - `MainActivity` is `exported=true` with only MAIN/LAUNCHER, no data/BROWSABLE filters and no deep links.
  - `androidx.startup.InitializationProvider` is `exported=false` (EmojiCompat, ProcessLifecycle and ProfileInstaller initializers).
  - **There is no ProfileInstallReceiver**: it is removed in the merged manifest.
  - There are no services, other receivers or a `<queries>` element.
- [x] `allowBackup=false`. `fullBackupContent` and `dataExtractionRules` exclude every domain (root, file, database, sharedpref, external and the device_* variants) for cloud backup and device transfer (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`).
- [x] Not `debuggable` and not `testOnly` (both absent in the release manifest). `usesCleartextTraffic=false`, there is no `networkSecurityConfig` (and no network permission anyway), and `extractNativeLibs=false`.
- [x] minSdk 26 / targetSdk 36 / compileSdk 37. targetSdk 36 brings the modern defaults: implicit `exported` is required, package visibility applies, and background-start restrictions apply.

### 2. Intents and IPC
- [x] `MainActivity.onCreate` never reads `intent`, data or extras (source; no `getIntent` in app classes in the dex).
- [x] **Navigation deep links:** the graph (`ui/nav/AppNavHost.kt`) declares no `deepLink`s. The `android-support-nav:controller:deepLinkIds` extra is honoured only if `NavController.shouldTrustIntent` passes. Dexdump shows that this rejects intents carrying `EXTRA_REFERRER`/`EXTRA_REFERRER_NAME`, and otherwise requires the calling package (or the referrer authority) to equal Offkit's own package. Another app therefore cannot force navigation through that extra. The Uri branch is not gated (SB-13). In either case every destination is a tool screen that needs user action.
- [x] Outgoing intents, all built from constants or app data:
  - **openLink**: `ACTION_VIEW` + `CATEGORY_BROWSABLE` for the `source_code_url`, `privacy_policy_url` and `support_issues_url` resources. All three are `https://github.com/chennakrishnaa/pdf-utils…`, verified in `resources.arsc`.
  - **Donate**: `support_donate_url` is shipped empty, and `HOUSE_PROMOS` is empty.
  - **Rate**: `market://details?id=` + `context.packageName`, falling back to `https://play.google.com/store/apps/details?id=` + package.
  - **Share app**: a `text/plain` chooser.
  - **Email**: `ACTION_SENDTO mailto:` + the constant address, with the subject from the app name and version.
  - The dex contains only these URL literals: `https://play.google.com/store/apps/details?id=`, `market://details?id=`, and library `http://`/`https://` prefixes and XML namespace strings.
- [x] **Recent files** (`core/RecentFiles.kt`):
  - View is an `ACTION_VIEW` of the saved Uri with `application/pdf` and **only** `FLAG_GRANT_READ_URI_PERMISSION`.
  - Share is a chooser around `ACTION_SEND` with `EXTRA_STREAM` and `ClipData.newRawUri`, again with read only.
  - There is never a write grant and never `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`.
  - The ClipData label is the display name (see SB-2); it is shown only in the chooser preview.
- [x] **No PendingIntent** in app code. The 496 dex references are all in androidx.core, activity, foundation and navigation (`NavDeepLinkBuilder`, unused). There is no intent redirection: the app never forwards a received Intent.

### 3. Storage Access Framework
- [x] `takePersistableUriPermission` is called only in `RecentFilesStore.add`, with READ only, and only from `JobViewModel.recordRecent` with the Uri returned by `CreateDocument` after a successful save.
- [x] Grants are released on `remove`, on `clear` and for entries evicted beyond `MAX_ENTRIES = 20`, which is far below the platform limit of 128 (or 512 on API 30+) persisted grants per app.
- [x] Pickers are `OpenDocument`, `OpenMultipleDocuments` and `PickMultipleVisualMedia(ImageOnly)`, with transient grants that are never persisted (`ui/components/Pickers.kt`).
- [x] Display names are never used as paths. Job files are `cacheDir/jobs/<UUID>/in/in_%03d.pdf`, `img_%03d.*` and `output.pdf` (`PdfJobs.kt`, `ImageNormalizer.kt`). The length and bidi caveat is SB-2.
- [x] Stored recents JSON is robust: `decode` catches a malformed array (→ empty list) and malformed entries (skipped), dedups by Uri and caps at 20. `load` is wrapped in try/catch.
- [x] CreateDocument output is written with `"wt"` (truncate). A failed write deletes the partial file (SB-12).

### 4. Local data at rest
- [x] Files written:
  - `shared_prefs/recent_files.xml` (Uri, name, size, time, tool id).
  - `shared_prefs/onboarding.xml` (one boolean).
  - `shared_prefs/support.xml` (a boolean and a string set).
  - `cacheDir/jobs/<uuid>/` (scratch files, deleted per job and wiped at the first `PdfJobs.get` of a process).

  All are under the app's private data directory and use `MODE_PRIVATE`. App code makes no `filesDir` use. androidx.profileinstaller (started by the startup provider) writes small marker files such as `filesDir/profileinstaller_profileWrittenFor_lastUpdateTime.dat`; these hold timestamps only, are private and are excluded from backup. There is no database, no WebView (the only `WebView` dex reference is in `androidx.core.text`, unused), and no external storage.
- [x] Everything is excluded from backup and transfer (check 1).
- [x] No logging, `println`, `printStackTrace` or clipboard writes in `app.venkat.offkit.*`. The dex attribution shows all 203 `Log` references and all 47 `ClipboardManager` references in androidx or kotlin classes. The only clipboard user is the Compose text field/selection infrastructure, which acts on user action.
- [x] Exception messages containing Uris (`"Cannot read $src"`) never reach the UI. `pdfErrorMessage` maps every `PdfError`, including `Io`, to a constant string resource (`ui/components/Format.kt`).

### 5. "No network", verified in the artifact
- [x] **Enforcing control:** the APK has no `android.permission.INTERNET`, so the kernel denies socket creation (the app's uid is not in the inet group). Nothing in the app can bypass this. The only way data reaches a network is an explicit, user-visible hand-off to another app: browser links, Play Store, email, share sheet, or a cloud SAF provider the user chooses.
- [x] **Dex** (attributed per class): the `java.net` and `android.net` references (`Socket`, `DatagramSocket`, `SocketImpl`, `InetAddress`, `ConnectivityManager`, `NetworkInfo`, `TrafficStats`) appear only in `androidx.core.net` (TrafficStatsCompat and ConnectivityManagerCompat helpers, never called). `java.net.URL` appears only in `kotlin.io.TextStreamsKt` (`URL.readBytes` extension, unused) and `kotlinx.coroutines.internal` (ServiceLoader resource URLs). There is no OkHttp, no `javax.net.ssl`, no `HttpURLConnection` and no WebView usage. **No app class references any of these.**
- [x] **Native:** `libmupdf_java.so` NEEDs only `libjnigraphics`, `liblog`, `libz`, `libm`, `libc`, `libstdc++` and `libdl`. Its undefined dynamic symbols include no `socket`, `connect`, `getaddrinfo`, `gethostbyname`, `send`/`recv`, `curl` or SSL functions. This holds for **both** `arm64-v8a` and `x86_64`, and the AAB's libraries are byte-identical to the APK's. The only notable imports are `getenv` and `system`, which the engine audit covers; without INTERNET, `system` cannot reach the network either. `libandroidx.graphics.path.so` imports only libc, libm and libdl basics (`__system_property_get`, `__register_atfork`). The `http://` strings in MuPDF are the Brotli static dictionary, XML/OOXML/ODF/SVG namespace URIs and HTML DTDs, which are data, not endpoints. A complete host list (`grep -oE 'https?://…' | uniq -c`) gives schemas.microsoft.com, schemas.openxmlformats.org, w3.org, openoffice.org, purl.org, docs.oasis-open.org, schemas.openxps.org, inkscape.org, ibm.com (an XHTML DTD), android.googlesource.com/toolchain/llvm-project (compiler ident), and fragments of the Brotli dictionary such as `http://www.`. None of these is used as a request target.

### 6. Dependencies
See the dependency section below. The OSV check succeeded: there are 0 known vulnerabilities across the 111 Maven coordinates.

### 7. Supply chain and build
- [x] Repositories: `google()`, `mavenCentral()` and `https://maven.ghostscript.com`, the last HTTPS and limited to the group `com.artifex.mupdf`, which prevents confusion for other groups (see SB-3 for the reverse direction). `FAIL_ON_PROJECT_REPOS` is set.
- [x] The wrapper URL is `https://services.gradle.org/distributions/gradle-9.6.1-bin.zip` with `validateDistributionUrl=true`. The checksum is missing (SB-4). Local `gradle-wrapper.jar` SHA-256: `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`. I did not compare it online; compare it with the published `gradle-9.6.1-wrapper.jar.sha256`.
- [x] The plugins are pinned in `libs.versions.toml`: AGP 9.5.2 and Kotlin compose plugin 2.4.21.

### 8. Secrets
- [x] The history (127 commits, `git log -p --all --text`) has no matches for `BEGIN … PRIVATE KEY`, AWS (`AKIA…`), GitHub (`ghp_`, `github_pat_`), Slack or Google API key patterns. The only `storePassword=`/`keyPassword=` hits are the `...` placeholders in RELEASING.md. The `userpw`/`ownerpw` hits are test-fixture PDF passwords in unit tests.
- [x] No `.jks`, `.keystore`, `.p12`, `.pem`, `keystore.properties`, `local.properties` or `dist/release/` path appears in any commit (file-name scan of the whole history). The only binaries ever committed are debug APKs (`dist/offkit-debug.apk`, `dist/paperpixel-debug.apk`, `dist/pdf-utils-debug.apk`).
- [x] `git check-ignore -v`: `keystore.properties` is matched by `.gitignore:14` and `dist/release/*` by `.gitignore:15`. `git ls-files` shows neither tracked; `git log --all -- keystore.properties dist/release` is empty.
- [x] Artifact contents:
  - The release APK contains only dex, `lib/`, `res/`, `resources.arsc`, `assets/{LICENSE,PRIVACY.md,THIRD_PARTY_NOTICES.md,dexopt/*}`, standard META-INF version, licence and service files, `DebugProbesKt.bin` (kotlinx-coroutines) and the AGP metadata.
  - The AAB contains the same plus `BundleConfig.pb`, `BUNDLE-METADATA` and the `UPLOAD.SF/RSA` signature.
  - The debug APK contains the same set.
  - None of them contains properties files with values, keys, test fixtures or source maps. The shipped `assets/PRIVACY.md` is byte-identical to the repo `PRIVACY.md`.

### 9. Signing and release hygiene
- [x] `apksigner verify --verbose --print-certs` reports **Verifies**: v2 true, v3 true, v1 false (fine for minSdk 26), one signer, `CN=PaperPixel, O=venkat.app`, **SHA-256 `f940420d7203c77b8edadc2d394b1a18eb331e36736433ba8b570e6b8f13f895`** (matches the expected value), RSA 4096, valid 2026-11-23 to 2056-11-15. The CN still carries the project's old name (cosmetic).
- [x] The AAB is signed with UPLOAD.RSA. `keytool -printcert -jarfile` reports Owner `CN=PaperPixel, O=venkat.app`, SHA256 `F9:40:42:0D:…:F8:95` (identical to the expected digest), SHA384withRSA, 4096-bit RSA. The AAB's `base/dex/*` and `base/lib/**` are byte-identical (`cmp`) to the APK's, so the dex and native conclusions apply to the bundle. `strings` on the proto manifest finds no permission, debuggable or testOnly entries.
- [x] `zipalign -c -P 16 -v 4` reports "Verification successful", and all `.so` entries are OK. ELF `LOAD` segment alignment is `0x4000` (16 KB) for all four libraries.
- [x] Only the `arm64-v8a` and `x86_64` ABIs are present, so the app is 64-bit only.
- [x] Artifact SHA-256: APK `0f78bfa8ae01189a225513b0aa1c1a84ca0ef081808415d73f923d914a9da8cd`, AAB `e05e4f081b0c2cb6a9443bc290881ffcd36bb3563e2c129ccdeb7dceef23b259`.

### 10. UI level
- [x] External links visibly leave the app (they open a browser, Play Store, email client or share sheet), and their targets are only the constant URLs above. The About and Support screens render only string resources. Tapjacking is covered under SB-8 (Info).

## Dependency versions (releaseRuntimeClasspath, resolved)

The OSV batch query (`https://api.osv.dev/v1/querybatch`, ecosystem Maven, coordinates only) returned **0 vulnerabilities for all 111 coordinates** (BOMs excluded). As a control, the same script and endpoint report 60 advisories for `com.fasterxml.jackson.core:jackson-databind:2.9.8`, so a zero result is meaningful. The coordinates were:

- `com.artifex.mupdf:fitz:1.28.5`
- `org.jetbrains.kotlin:kotlin-stdlib:2.4.21`
- `org.jetbrains.kotlinx:kotlinx-coroutines-{core,android}:1.11.0`
- `org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3`
- `org.jetbrains:annotations:23.0.0`, `org.jspecify:jspecify:1.0.0`, `com.google.guava:listenablefuture:1.0`
- Compose BOM 2026.11.01:
  - `androidx.compose.{ui,foundation,animation,runtime,material:material-ripple}` 1.13.0
  - `material3` 1.5.0
  - `material-icons-core` 1.7.8
- `androidx.activity:*` 1.13.0, `androidx.navigation:*` 2.10.2, `androidx.navigationevent:*` 1.1.2, `androidx.lifecycle:*` 2.11.1, `androidx.savedstate:*` 1.5.0
- `androidx.core:core` / `core-ktx` 1.19.0, `core-viewtree` 1.0.0
- `androidx.emoji2:emoji2` 1.7.0, `androidx.startup:startup-runtime` 1.1.1, `androidx.profileinstaller` 1.4.0
- `androidx.window:*` 1.5.0, `androidx.graphics:graphics-path` 1.1.0, `graphics-shapes` 1.0.1
- `androidx.collection` 1.6.0, `androidx.annotation` 1.10.0, `annotation-experimental` 1.4.1, `androidx.arch.core` 2.2.0, `androidx.tracing` 1.2.0
- `androidx.autofill` 1.0.0, `androidx.interpolator` 1.0.0, `androidx.customview:customview-poolingcontainer` 1.0.0, `androidx.versionedparcelable` 1.1.1, `androidx.concurrent:concurrent-futures` 1.1.0

Native components compiled into `libmupdf_java.so` (from `THIRD_PARTY_NOTICES.md`). These were **not queried** against OSV or any CVE database; check them manually:
- MuPDF 1.28.5, jbig2dec 0.20, Little CMS (lcms2mt) 2.19, FreeType 2.14.3, HarfBuzz 13.0.1, OpenJPEG 2.5.4, IJG libjpeg 10, Brotli 1.2.0.
- The versions of extract, MuJS, Gumbo and cmark-gfm are those bundled with MuPDF 1.28.5.
- zlib is the system `libz.so`, updated with the OS.

## Claims vs reality (from the artifact)

| Source | Statement | Verdict | Evidence |
|---|---|---|---|
| PRIVACY | Collects no personal data; no server storage; no sharing or selling | TRUE | No INTERNET permission; no network or analytics code in app classes (dex attribution). |
| PRIVACY | No accounts, ads, analytics, crash reporting or trackers | TRUE | The dependency list has no such SDKs; the dex has no Firebase, Crashlytics or ads packages. |
| PRIVACY | Requests no Android permissions | TRUE | `aapt2 dump badging` lists no uses-permission. |
| PRIVACY | Makes no network connections | TRUE | No INTERNET permission (OS-enforced); no socket imports in native code. |
| PRIVACY | Files chosen through the system picker or photo picker and processed on the device | TRUE | Pickers.kt contracts; no other file access. |
| PRIVACY | Temporary copies in the private cache, deleted after each job; leftovers deleted the next time a tool is opened after the app starts | TRUE | `PdfJobs.run`/`save`/`discard`; `PdfJobs.get` → `wipeAll()` on first use, which happens via `JobViewModel` when a tool opens. |
| PRIVACY | Result written only to the place chosen in the save dialog | TRUE | `CreateDocument` Uri → `openOutputStream(dest,"wt")`. |
| PRIVACY | Recents keep name, size, time, tool and a link; stored only on the device; excluded from backup; removed by Clear, clearing data or uninstall; Clear doesn't delete files | TRUE | `RecentFiles.kt` encode; the backup rules; `clear()` only edits prefs and releases grants. |
| PRIVACY | Open or share hands the file to another app | TRUE | ACTION_VIEW / ACTION_SEND with a read grant. |
| PRIVACY | Intro-seen and hidden-card settings contain nothing about the user or files and are excluded from backup | TRUE | `OnboardingStore` and `SupportStore` hold booleans and promo ids; the backup rules. |
| PRIVACY | Photo metadata removed when placing images (with caveats) | PARTLY (as the policy itself states) | Non-JPEG/PNG images are re-encoded through a Bitmap, which drops metadata (`ImageNormalizer.transcode`). JPEG and PNG are passed through **byte for byte** to the engine, so stripping depends on MuPDF's image embedding (engine audit scope). |
| PRIVACY | Links open in the browser or another app; Offkit itself sends nothing | TRUE | `openLink`, `SupportLinks`; constant URLs verified in `resources.arsc`. |
| PRIVACY | Source under AGPL-3.0 at the GitHub URL | TRUE | `assets/LICENSE`, `source_code_url`. |
| Onboarding | "PDF tools that work entirely on your phone." | TRUE | No network capability. |
| Onboarding | "Your files never leave your device." | PARTLY | True of the app itself, but saving to a cloud provider or sharing sends them off the device (SB-1). |
| Onboarding | "No internet" / "No permissions" / "No ads" / "No accounts" | TRUE | Manifest and dependency list. |
| Onboarding | "Location and camera details are removed from photos placed into PDFs." | PARTLY | As above: JPEG/PNG pass-through depends on the engine; the policy's caveat is not repeated here. |
| Onboarding | "You choose the files, and where results are saved." | TRUE | SAF only. |
| Onboarding | "Recently saved files appear on the home screen" | TRUE | RecentFilesStore. |
| Onboarding | "Nothing is uploaded or backed up" | PARTLY | App data is not backed up (TRUE). "Nothing is uploaded" is true of Offkit, but false if the user saves to a cloud location (SB-1). |

## Not covered
- The parsing engine (`engine/**`, MuPDF behaviour on hostile PDFs and images, the `system`/`getenv` imports in `libmupdf_java.so`, and metadata stripping of passed-through JPEG/PNG): this is the other auditor's scope.
- Dynamic testing on a device or emulator (no install or run was performed). Every result here is from static artifact and source analysis.
- A CVE check of the native components bundled in MuPDF (versions listed above for manual checking) and an online check of the Gradle wrapper jar checksum.
- Google Play-side processing of the AAB (splits generated by Play, Play App Signing key).

## Re-check after fixes (SR)

Re-checker: SR, independent of the fix authors, at `main` `ca22d9a`. The check is source-level (no APK was rebuilt or inspected and no device was run).

### Status of the original findings

| ID | Status | Evidence |
|---|---|---|
| SB-1 | **Fixed** | "Your files never leave your device" and "Nothing is uploaded or backed up" are gone. Onboarding now says "Offkit never sends your files anywhere." / "Offkit uploads nothing. Results go only where you save or share them."; PRIVACY.md mentions that a cloud location may upload. |
| SB-2 | **Mostly fixed** (residual: SR-6) | `core/DisplayNames.kt` is applied in `UriIo.describe` (provider `DISPLAY_NAME` and the `lastPathSegment` fallback), in `RecentFiles.add` and the decoder (stored names are re-sanitised), and in the Compress and Add-images suggestions. Merge and Images to PDF suggest fixed strings. `grep DISPLAY_NAME / lastPathSegment` finds only `UriIo`. Controls, bidi controls and isolates, ZW characters, BOM and lone surrogates are removed; `/ \ :` become `_`; the length is capped at 120 code points, keeping an extension of up to 10 characters; work is bounded at 960+32 characters. |
| SB-3 | **Fixed** | `settings.gradle.kts`: `exclusiveContent { forRepository { maven.ghostscript.com } filter { includeGroup("com.artifex.mupdf") } }`. `com.artifex.mupdf` can now come only from that repository, and nothing else can come from it. `pluginManagement` repositories (google, mavenCentral, gradlePluginPortal) are separate and unaffected. |
| SB-4 | **Partly fixed** | The wrapper now has `distributionSha256Sum=9c0f7f…` (not verified online: no network). There is still no `gradle/verification-metadata.xml`, so dependencies and plugins are not checksum-pinned. |
| SB-5 | **Fixed** | `git ls-files` has no `.apk/.aab/.jks/.keystore/keystore.properties/.p12/.pem`. `.gitignore` covers `dist/*.apk` and `keystore.properties` (`*.apk` was already listed). The APK remains in history (commit `2e433df` and earlier); rewriting history is the owner's call. |
| SB-6 | **Fixed** | `android:taskAffinity=""` on `MainActivity`. |
| SB-13 | **Fixed** (by reading) | `MainActivity.onCreate` replaces `intent` with a bare MAIN/LAUNCHER intent (component kept, grant flags dropped) **before** `super.onCreate` and `setContent`. On recreation Android re-delivers the original intent, which is scrubbed again in the same place. `onNewIntent` passes only the scrubbed copy to `super` and does not call `setIntent`. The NavController never sees the hostile intent, so no saved back stack can contain a destination derived from it. I found no lifecycle path that reads the original before scrubbing. `HostileIntentTest` exists (not run by me, see below). |
| SB-14 | Fixed (by reading) | Picker results are now gated to `content:` (`PickerSchemeGateTest` exists). |
| SB-7, SB-8, SB-9, SB-10, SB-11, SB-12 | Accepted risk / unchanged (Info) | Not re-checked. |

### New findings

| ID | Sev | Where | Description | Who | Impact | Evidence | Fix |
|---|---|---|---|---|---|---|---|
| SR-6 | Low | `core/DisplayNames.kt` `isRemoved` | Only a fixed list of invisible characters is removed. Other default-ignorable or blank-rendering characters survive, so a name can still look empty or be padded invisibly: U+3164 / U+115F / U+FFA0 Hangul fillers (letters, not whitespace, so `isBlank()` is false and the fallback is not used); U+00AD soft hyphen; U+034F CGJ; U+180E; U+2061–2064; U+206A–206F; variation selectors; tag characters U+E0000–E007F; U+FFF9–FFFB. Combining marks are not limited, so 119 combining marks on one letter (Zalgo) pass the 120-code-point cap and draw far outside the text line. Plain RTL letters are kept (correct), so a name that starts with Hebrew or Arabic shows its `.pdf` on the left; no bidi *control* survives. All-dot names (`....`) are kept for display; the Compress suggestion trims leading dots and falls back. `%2F`, `%00` stay literal (harmless: names never build paths). | A documents provider (any installed app) that sets `DISPLAY_NAME`. | Cosmetic or confusing UI only: names are never used as paths. | Read; not run on a device | Remove every `Character.getType == FORMAT` code point plus the Hangul fillers, cap combining marks (for example at most 2 per base character), and treat a name with no visible character as blank. |

### Claims (re-marked against the current source)

| Source | Statement | Verdict |
|---|---|---|
| Onboarding | "PDF tools that work entirely on your phone." | TRUE |
| Onboarding | "Offkit never sends your files anywhere." / "Offkit uploads nothing. Results go only where you save or share them." | TRUE |
| Onboarding | "No internet" / "No permissions" / "No ads" / "No accounts" | TRUE (source manifest has no `uses-permission`; artifact not re-inspected) |
| Onboarding, README, Play listing | "Location and camera details are removed from photos placed into PDFs." | **PARTLY**: EXIF/GPS, XMP, IPTC, COM and JFIF/Adobe tails are removed, but ICC profiles are kept (they can name the camera or device model). APPn segments after the first scan of a progressive JPEG are not classified and are embedded. PRIVACY.md states the ICC caveat; this short string does not. |
| Images to PDF | "Full picture quality; location and camera details removed" (`imagestopdf_quality_original_detail`) | **PARTLY** (same reason) |
| Home footer | "Everything happens on this device. Offkit uploads nothing." | TRUE |
| About | "This app works fully offline and requests no permissions. Your files are processed on this device. Offkit uploads nothing; results go only where you save or share them." | TRUE |
| README / Play listing | "never connects to the internet", "No internet. The app has no network access at all." | TRUE (by source) |
| PRIVACY.md | Permissions/network, recents, temporary files, metadata section with caveats | TRUE (the metadata section is now accurately hedged) |

### Updated verdict (platform)
**Acceptable for public release.** SB-1, SB-3, SB-5, SB-6 and SB-13 are fixed; SB-2 is fixed apart from cosmetic residuals (SR-6, Low); SB-4 is half done (wrapper pinned, dependencies not). The only claim that is still not fully true is the short "location and camera details are removed" line, which should say "most photo metadata" or link to the policy.

### Not checked (SR)
- No APK was rebuilt, so the merged manifest and permissions were not re-verified in the artifact.
- No device run of the intent scrubbing.
- Unit tests (`RecentFilesStoreTest`, `DisplayNamesTest`, `HostileIntentTest`) and throwaway hostile-JSON cases: not run in this session yet.
- Wrapper checksum not verified online.

## Process isolation (PI1/PI2/PI3)

**What runs where.**
- **UI process** (`app.venkat.offkit`): the Compose UI, the Storage Access Framework, and `PdfJobs`. `PdfJobs` copies each picked document or image as raw bytes (size-capped by `UriIo`) into `cacheDir/jobs/<uuid>/in/`, and saves and deletes outputs. It loads no MuPDF: no source outside `engine/` imports `com.artifex.mupdf`, which `scripts/verify.sh` checks. It also decodes no untrusted image. The evidence is the code path, not a runtime check: the only user of `BitmapFactory`, `ImageDecoder` and `ExifInterface` is `core/ImageNormalizer.kt`, its only production caller is `InProcessImagePreparer`, and that is used only by `EngineServer` in the engine process; `PdfJobs` hands raw bytes to the engine (`RemoteEngine.prepareImage`). (`scripts/isolation-smoke.sh` checks only that the UI process maps no `libmupdf`; platform codecs are not visible to that check.)
- **`:engine` process** (`EngineService`, `android:process=":engine"`, `exported=false`, no intent filter, which `scripts/verify.sh` checks in both APKs): MuPDF for every PDF operation and estimate. Since PI2 it also does image normalisation, so WebP, HEIC, GIF and BMP are decoded by the platform codecs here (`EngineRequest.NormalizeImage`).
- **Between the processes**, only paths and small Bundles cross, never file bytes. The engine canonicalises every path and refuses anything outside `cacheDir/jobs/`. A normalisation reply names only a file, which the client checks against `img_NNN.jpg` or `img_NNN.png`.

**What it protects against.**
- **Crashes and memory exhaustion from hostile files.** A native crash (SIGSEGV), an abort, an OOM kill or the low-memory killer ends only the engine. The job returns `EngineCrashed`, its directory is deleted, and the next job binds a new engine. `EngineIsolationOnDeviceTest` checks this on device for SIGKILL and SIGSEGV mid-job and for a kill while idle. Measured on the API 31 emulator: `EngineCrashed` 38 ms after SIGKILL and 533 ms after SIGSEGV.
- **Low-memory ordering.** This holds while the activity is in front: then `oom_score_adj` was 0 for the UI and 100 for the engine, so the engine is killed first. In the background both processes are cached, and either may be frozen or killed; see the next two items.
- **App frozen or in the background (PI4, FR-1/FR-3).** The watchdog clock is `CLOCK_MONOTONIC` (`System.nanoTime`, the same clock as `uptimeMillis`), which stops in deep sleep. The cached-apps freezer does not stop it, so the client's wait loop (which wakes about every 100 ms) treats a gap of more than 2 s between two wake-ups as time this process was frozen; the engine, bound only by this app, was frozen with it. That gap is not counted toward the stall, total, image or cancel limits, up to 1 hour per call; beyond that, gaps count again, so the limits cannot be postponed forever. An engine death noticed within 5 s after such a gap, or while the app is cached (`ActivityManager.getMyMemoryState`), is reported as `EngineUnavailable` ("Processing was interrupted. Please try again."), as is a bind failure or timeout; `EngineCrashed` ("may be damaged") is kept for a death while a job runs in the foreground. Checked by JVM tests with an injected clock; not reproduced with a real freezer.
- **Client gone (PI4, FR-2).** The engine links to the death of each request's callback binder. If that binder dies, or the service is unbound or destroyed while a request is queued or running, it deletes the pending outputs and kills its own process, so no job continues without a watchdog. An idle unbind does nothing. There is one `EngineServer` per process, so two jobs never run in MuPDF at once. `EngineIsolationOnDeviceTest` checks on device that an unbind mid-job makes the engine exit within 2 s and that the next job runs in a new engine.
- **Hard cancel.** If the engine does not stop within 2 s of a cancel, the UI kills it. This was measured with a SIGSTOPped engine: `Cancelled` came back after 2.0 s.
- **Watchdog (PI3).** MuPDF calls cannot be interrupted (SR3 in `engine-audit.md`), so `RemoteEngine` also kills the engine without a cancel. The limits are in `engine/remote/EngineLimits.kt`:
  - **Stall:** a PDF operation that gets no progress event and no result for 90 s returns `TimedOut`. The clock starts when the request is sent and restarts on every progress event.
  - **Total:** a PDF operation may take 15 minutes in all, and an image estimate or normalisation 30 s. Past that, it returns `TimedOut`.
  - **Memory:** while it waits, the client samples the engine's RSS from `/proc/<pid>/statm` about once a second. Over the limit, it returns `OutOfMemory`. The limit is min(1536 MiB, 40 % of `ActivityManager.MemoryInfo.totalMem`) and is computed once in `PdfUtilApp`. If `/proc` cannot be read, this one check is skipped and logged once.
  - After any watchdog kill, partial output is deleted and the next job binds a new engine. A user cancel still wins and returns `Cancelled`. The user sees "This file is taking too long to process…" for `TimedOut`.
  - Measured on the API 31 emulator (`EngineIsolationOnDeviceTest`, small limits injected): a SIGSTOPped engine with a 3 s stall limit gave `TimedOut` 3.0 s after it stopped, and the next job ran in a new engine. A 1 MiB memory limit gave `OutOfMemory` after 0.3 s, and the next job with the production limit (787 MiB on this 2 GB emulator) succeeded. During an ordinary HIGH compress of a 69 MiB, 16-page photo PDF, the engine's RSS was 152 MiB at the end, 171 MiB at the highest progress-time sample, and 219 MiB at peak (VmHWM). A different, idle engine in the memory test was at 153 MiB. The stall limit's main false-positive risk is the silent phase after the last progress event (final save and output validation) of a very large input on a slow phone.

**What it does not protect against.**
- The engine runs with the **same UID and the same data directory** as the UI, and it is **not `isolatedProcess`**. It is therefore not a sandbox against code execution: code running in the engine could read or write everything the app can, including other jobs, preferences and recents. The service's path check protects only against a misbehaving client.
- **Leaving the app mid-job.** There is no foreground service (it would need a permission), so a job runs only while the system lets the app run. Frozen time is not counted against the limits (up to 1 hour per call), but the system may still kill the cached engine; the job then ends as `EngineUnavailable` and must be started again. Without the freezer, a death in the background is classified by the app's importance at the moment it is noticed.
- A hostile file still gets up to the watchdog limits before it is stopped: up to 90 s stuck in one call, 15 minutes while it keeps reporting progress, and memory up to the limit between two one-second samples. A spike faster than that is left to the system's OOM handling, which ends only the engine.

## Redesign addendum (2026-11-25)

Reviewer: T20, at `main` = `c8e0f22` (the redesign merge). This check reads the source; it is not a new full audit. Each statement is marked:
- **R**: verified by reading the source or the artifact, as cited.
- **NV**: not verified. This covers platform or library behaviour that I took from documentation, not from this repository.

Nothing was built, installed or run. `dist/release/app.venkat.offkit-1.0.0.apk` in the main checkout was inspected read-only. Its `BUILD_INFO.txt` says `commit c8e0f22`, and its `META-INF/version-control-info.textproto` names revision `c8e0f223…`, so it matches the source read here. On that APK I ran `aapt2 dump xmltree --file AndroidManifest.xml`, `aapt2 dump badging`, `aapt2 dump resources` (to find `xml/share_paths`), `aapt2 dump xmltree` of that resource, and a string grep of the `classes*.dex` files. I did **not** repeat the following on the new APK: signing, alignment, dex attribution, native imports, the secrets scan, OSV, or the artifact hashes.

### What changed in the attack surface

| Area | Statement | Status | Evidence |
|---|---|---|---|
| Share provider | One new `<provider>`: `androidx.core.content.FileProvider`, `android:exported="false"`, `android:authorities="${applicationId}.share"` (in the APK, `app.venkat.offkit.share`), `android:grantUriPermissions="true"`, with paths from `@xml/share_paths`. | R | `app/src/main/AndroidManifest.xml:43-51`; APK `aapt2 dump xmltree`: provider with `exported=false`, `authorities="app.venkat.offkit.share"`, `grantUriPermissions=true`. |
| Paths exposed | Only `<cache-path name="shared" path="shared/" />`, which is `cacheDir/shared/`. There is no `files-path`, `root-path`, `external-*` path, and no other `cache-path`. | R | `app/src/main/res/xml/share_paths.xml:4-6`. APK: `xml/share_paths` is `res/l3.xml`, whose dump shows exactly that one element. No other XML resource in the APK contains a `*-path` element. |
| Nothing else reachable | The provider is not exported, so another app can open a URI only if it holds a grant for that exact URI. The only grants the app issues for provider URIs come from `sharePdfIntent`. The only code that produces provider URIs is `PdfJobs.prepareShare` → `FileProvider.getUriForFile(…, shareAuthority, target)`, where `target` is `cacheDir/shared/<random UUID>/<name>`. `cacheDir/jobs/`, `files/` and `shared_prefs/` are outside the configured root. | R (app side) / NV (FileProvider side) | `PdfJobs.kt:384-391`; a repo-wide grep finds no other use of `getUriForFile` or `FileProvider`. **NV:** that androidx `FileProvider` canonicalises the requested path and rejects anything outside the root, including `..`. That is library behaviour; I did not read or test it. |
| Who can read a shared URI | The chooser and the inner `ACTION_SEND` both carry `FLAG_GRANT_READ_URI_PERMISSION` only. There is no write, persistable or prefix flag. The URI goes in `EXTRA_STREAM` and in `ClipData`. | R | `ui/components/Pickers.kt:62-70`. A repo-wide grep finds no `revokeUriPermission`. The only other `FLAG_GRANT_*` uses are `RecentFiles.kt` (read-only view and share intents, and the persisted read grant for saved files), `openPdfIntent` (`Pickers.kt:78`) and the `MainActivity` intent scrub (`MainActivity.kt:48-49`). The pickers set no grant flags. |
| How long the grant lasts | A read grant passed with `startActivity` normally goes to the system chooser and then to the app the user picks. It lasts until that app's task or activity stack ends, or until the device restarts. The app never revokes it. | NV | Android platform behaviour; no receiving app was exercised. |
| When the shared copy is deleted | `cacheDir/shared/` is wiped (1) at the start of every job, by `JobViewModel.startJob` → `jobs.wipeShared()`, and (2) on the first `PdfJobs.get` in a process, together with `jobs/`. That first call happens when a tool's ViewModel is created: `JobViewModel.kt:58` and `ImagesToPdfViewModel.kt:81` are the only callers, and `PdfUtilApp` does not call it. A process start alone therefore wipes nothing; the wipe happens when a tool is first opened. Sharing again with a different name deletes the old copy's directory. `PdfJobs.discard` only forgets the copy (`sharedCopy = null`) and does **not** delete it. Leaving the result (`reset`, `invalidateResult`, `onCleared`) therefore leaves the copy in `cacheDir/shared/` until the next job starts or a tool is first opened in a new process. See RA-1 and RA-2. | R | `JobViewModel.kt:116`; `PdfJobs.kt:402-411` (`wipeShared`/`deleteShared`), `:508-518` (`get` → `wipeAll()` + `deleteShared()`), `:377-382` (stale copy), `:419-422` (`discard`). Greps find no other caller of `wipeShared`, `deleteShared` or `PdfJobs.get(`. |
| Shared file name | `shareFileName` runs `sanitizeDisplayName`, which removes Cc, Cf, Cs, Co and Cn code points and the default-ignorables, keeps at most 3 combining marks per base character, replaces `/ \ :` with `_` and caps the name at 120 code points. It then adds `.pdf` if missing and cuts the name to **200 UTF-8 bytes** (`MAX_SHARE_NAME_BYTES`), with the extension kept. A name with nothing usable, or one containing `/`, `\` or NUL, becomes `document.pdf`. The name is one path segment inside a fresh random-UUID directory. | R | `PdfJobs.kt:441`, `:450-462`; `core/DisplayNames.kt:33-90`. The name comes from `Done.suggestedName`. For Compress and Add images it derives from the picked file's provider display name (`CompressViewModel.kt:78`, `AddImagesViewModel.kt:93`); Merge and Images to PDF use constants. |
| Output kept after save | `PdfJobs.save` no longer deletes the output. Its body copies the output, and on failure deletes only the destination; it never touches `output.file` or the job directory. The KDoc says the output "is kept in every case (the caller discards it)". The output in `cacheDir/jobs/<id>/` lives until `reset`, `invalidateResult`, the next `startJob` or `onCleared`. A save still in progress discards it when it ends. Leftovers from a dead process are wiped by the first `PdfJobs.get`. | R | `PdfJobs.kt:282-292`; `JobViewModel.kt:51-52`, `:186-193`, `:345-351`, `:355-367`. |
| Image picker | `rememberImagePicker` now uses `ActivityResultContracts.OpenMultipleDocuments()` with `image/*`. According to the KDoc at `Pickers.kt:133-134`, that contract sends `ACTION_OPEN_DOCUMENT` with `EXTRA_ALLOW_MULTIPLE` (androidx behaviour, NV). A grep of `app/src/main/java` for `PickVisualMedia`, `PickMultipleVisualMedia`, `GetContent`, `GetMultipleContents`, `ACTION_GET_CONTENT` and `ACTION_PICK` finds nothing. The only activity-result launchers are `OpenDocument`, `OpenMultipleDocuments` (twice) and `CreateDocument` (`Pickers.kt:115,118,143,162`). Picked URIs are still filtered to `content:` in every tool ViewModel. | R (contract mapping NV) | `ui/components/Pickers.kt:31`, `:140-150`; `UriIo.kt:41`; `MergeViewModel.kt:41`, `ImagesToPdfViewModel.kt:131`, `AddImagesViewModel.kt:50,62`, `CompressViewModel.kt:46`. |
| Who answers the picker | `ACTION_OPEN_DOCUMENT` is handled by the system DocumentsUI, which returns `content://` document URIs from any documents provider the user picks, including third-party providers. | NV | Platform behaviour. The app-side `content:` gate above is R. |
| Open from the result | The result screen's Open sends `ACTION_VIEW` of the **saved SAF URI**, not a provider URI, with read grant only. | R | `JobFlowScreens.kt:303` (`onOpen(savedUri)`) → `JobFlow.kt:148` → `Pickers.kt:76-86`. |
| Permissions | The source manifest has no requested permission. Its only `<uses-permission>` element is a `tools:node="remove"` for `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. The APK's `aapt2 dump badging` contains 0 `uses-permission` lines. | R | `AndroidManifest.xml:5-11`; APK badging. |
| Exported components | Only `MainActivity` is exported (MAIN/LAUNCHER only). `EngineService` (`:engine`), the share `FileProvider` and `androidx.startup.InitializationProvider` are all `exported=false`. There is no receiver, because ProfileInstallReceiver is still removed. | R | `AndroidManifest.xml:24-58`; the APK `xmltree` lists exactly these four components. |
| UI rebuild | The theme is light only (`parent="android:Theme.Material.Light.NoActionBar"`). The Manrope font is bundled as `res/font/manrope_*.ttf` (5 weights), and `THIRD_PARTY_NOTICES.md` lists it under SIL OFL 1.1. Icons are app drawables (`AppIcons.kt` → `painterResource(R.drawable.ic_*)`). | R | `res/values/themes.xml:5`; `res/font/`; `assets/THIRD_PARTY_NOTICES.md:329-331`, `:1126-1134`; `ui/components/AppIcons.kt:13-30`. None of these adds code paths that touch files or IPC. |
| `material-icons-core` | Still declared (`app/build.gradle.kts:142`, `gradle/libs.versions.toml:23`), but **nothing in `app/src` imports `androidx.compose.material.icons`**. R8 is off (SB-9), so its classes still ship: `classes.dex` of the release APK contains `Landroidx/compose/material/icons/` strings. It is unused but shipped, which means dead code rather than new surface. | R | `grep -rn androidx.compose.material.icons app/src` finds nothing; dex string grep. |
| Dependencies | `gradle/libs.versions.toml` and `app/build.gradle.kts` have one commit since the audited `2e433df` (`2afd50e`, PI1), and none from the redesign. OSV was not re-run. | R (git log) / NV (OSV) | `git log 2e433df..HEAD -- gradle/libs.versions.toml app/build.gradle.kts`. |

### Earlier statements that are now out of date

The rows above are not edited in place. Each entry below gives the corrected statement.

| Where in the body | Earlier statement | Corrected statement (as of `c8e0f22`) |
|---|---|---|
| Header (Date line) and check 9 (artifact SHA-256) | The artifacts were built from `8f61110`, with the listed APK and AAB hashes. | `dist/release/` was rebuilt on 2026-11-25 from `c8e0f22`. The old hashes describe an earlier artifact. The new artifact's signature and hashes were not re-checked here. |
| Summary | "It declares exactly one exported component, the launcher activity … The androidx.startup provider is not exported" | It is still true that exactly one component is exported. The app now also declares a second, non-exported provider (the share `FileProvider`, `cacheDir/shared/` only) and the non-exported `EngineService` (added in PI1). |
| Check 1, Components | "There are no services, other receivers or a `<queries>` element." The only provider listed is `androidx.startup.InitializationProvider`. | There is one service, `EngineService` (`exported=false`, `:engine`), and two providers: InitializationProvider and the share `FileProvider` (`exported=false`, `grantUriPermissions=true`). There is still no receiver and no `<queries>` element. |
| Check 2, Outgoing intents | The list (openLink, Donate, Rate, Share app, Email, Recent files) | Add the result screen's **Share**: a chooser around `ACTION_SEND` of a provider URI, read grant only (`Pickers.kt:62-70`). Add **Open**: `ACTION_VIEW` of the saved SAF URI, read grant only (`Pickers.kt:76-86`). |
| Check 3, Pickers | "Pickers are `OpenDocument`, `OpenMultipleDocuments` and `PickMultipleVisualMedia(ImageOnly)`" | The pickers are `OpenDocument` and `OpenMultipleDocuments` (PDFs), plus `OpenMultipleDocuments` with `image/*` (images). There is no photo picker. |
| Check 3, and SB-2's "never used as a path (verified)" | "Display names are never used as paths. Job files are `cacheDir/jobs/<UUID>/…`" | That still holds for job files. However, the **shared copy's file name** comes from the suggested name, and for Compress and Add images that comes from a provider display name. It is used as one path segment, `cacheDir/shared/<UUID>/<name>`, after `sanitizeDisplayName`, removal of separators and NUL, and a 200-byte cap (`PdfJobs.kt:450-462`). |
| SR-6, "names never build paths" | As quoted. | Same correction as the previous row. Separately, `DisplayNames.kt` now removes all `FORMAT` code points and the listed default-ignorables, and caps combining marks at 3 (`DisplayNames.kt:65-90`). Most of SR-6 has therefore been addressed since SR. I did not re-check each character listed in SR-6. |
| Check 4, Files written | `cacheDir/jobs/<uuid>/` "deleted per job" | `cacheDir/jobs/<uuid>/output.pdf` is now kept after a save until the result is left or the next job starts. There is also a new `cacheDir/shared/<uuid>/<name>.pdf`, which lives until the next job starts or a tool is first opened in a new process (RA-2). |
| SR claims table, "Images to PDF" row (`imagestopdf_quality_original_detail`) and the onboarding metadata line | "Location and camera details are removed …" | That string id no longer exists. The current wording is narrower: `quality_original_desc` (`res/values/strings_design.xml:49`) says "Full picture quality; GPS location and camera settings (EXIF) removed", and `onboarding_private_metadata` (`strings_onboarding.xml:26`) says "GPS location and camera settings (EXIF) are removed from photos placed into PDFs." The wording was read (R). Whether it is accurate depends on the engine's metadata stripping, which is outside this scope and was not re-checked (NV). |
| SB-14 | "`PickVisualMedia` is answered by the system or GMS photo picker (androidx requires a system app for the backport)." | No photo-picker contract is used any more. All three pickers are SAF `ACTION_OPEN_DOCUMENT` (answered by DocumentsUI, NV). The `content:` gate from the SR re-check is still in every ViewModel (R, cited above). |
| SB-2 Location | `ui/home/HomeScreen.kt:376`, `ui/components/FileList.kt:115` | `FileList.kt` no longer exists (and neither does `JobPanel.kt`). Names are now rendered at `ui/home/HomeScreen.kt:510-514` (`maxLines = 1`) and `ui/components/FileCards.kt:145` (`maxLines = 1`, ellipsis). Other name renderings in the rebuilt screens were not traced. |
| Dependency list | "`material-icons-core` 1.7.8" listed as a Compose BOM dependency | It is still declared and still shipped, but no source file uses it. It could be dropped from `app/build.gradle.kts:142` and `libs.versions.toml:23`. |
| Claims table, PRIVACY | "Files chosen through the system picker or photo picker and processed on the device" | `PRIVACY.md:21` now says "You choose files through the system file picker." That is TRUE (R): every picker is SAF. |
| Claims table, PRIVACY | "Temporary copies in the private cache, deleted after each job …", evidence `PdfJobs.run`/`save`/`discard` | `save` no longer deletes anything. The output now lives until the result is left. Shared copies outlive the result (RA-1). |
| Claims table, PRIVACY | "Open or share hands the file to another app", evidence "ACTION_VIEW / ACTION_SEND with a read grant" | Still TRUE for the Recent list, and the result screen does the same with read grants. However, `PRIVACY.md:32` mentions sharing only "from the list", not from the result screen (part of RA-1). |
| Check 4, "No logging … in `app.venkat.offkit.*`" | As quoted. | Not a redesign change, but no longer exact: `engine/remote/RemoteEngine.kt:90` logs watchdog messages with `Log.w` (added in PI). I did not check what those messages contain. |

### New findings

| ID | Severity | Location | Description | Attacker / exploitation | Impact | How confirmed | Suggested fix |
|---|---|---|---|---|---|---|---|
| RA-1 | Low | `PRIVACY.md:23`, `:32` | The policy says the temporary copies "are deleted after each job (when the result is saved or discarded, …)". After the redesign, a save keeps the output. A shared copy is not deleted when the result is discarded or left. It stays until the next job starts, or until a tool is first opened after the app's process has restarted. The policy also mentions sharing only from the Recent list, not from the result screen. | None. This is a claims accuracy issue. | The privacy statement is slightly inaccurate about how long the private cache holds a copy. The copy is not reachable by other apps without a grant. | R: `PdfJobs.kt:282-292`, `:419-422`; `JobViewModel.kt:116`; `PRIVACY.md:23,32`. | Reword, for example: "…deleted when you leave the result or start another job; a copy made for sharing is kept until you start another job or next open a tool after the app restarts, so the receiving app can read it." Also mention Share on the result screen. |
| RA-2 | Info | `PdfJobs.discard` (`:419-422`); `JobViewModel` `clearResult`/`onCleared` | The shared copy outlives the result screen and the tool. While it exists, any app that received a read grant (the chosen target, and the system chooser) can keep reading it for as long as its grant lives (NV). That can be well after the user has left the result. | The app the user chose to share with. It already had the content, so nothing new is disclosed. | None beyond what the user chose to share. It is a lifetime and cache-size consideration only. | R (app side); grant lifetime NV. | Accept. This is the trade-off behind RD-6: deleting earlier would break late readers. Optionally, delete copies older than some minutes when the result is left. |
| RD-1 (review, recorded as it stands) | Medium (review severity; data loss, not exposure) | `ui/components/Pickers.kt:93-104`, `core/JobViewModel.kt:266-268`, `ui/components/JobState.kt:57` | `ShareRequestEffect` reports `launched = true` once `startActivity(chooser)` returns. `onShareHandled(true)` then sets `Done.shared = true`, and `hasUnsavedWork()` returns false for an unsaved result that is `shared`. If the user dismisses the share sheet without picking an app, Close, Done or Back therefore leave without the discard confirmation, and the unsaved result is lost. | Not attacker-driven. | Loss of an unsaved result, with no confirmation. | R (code path as cited). Not reproduced on a device. | Owner decision, as in `REVIEW.md` RD-1. Learn the chosen target through `createChooser` with an `IntentSender`, or let only `savedUri` suppress the dialog. |
| RD-6 (review, recorded as it stands) | Info | `core/JobViewModel.kt:116` | `cacheDir/shared/` is wiped at the start of every job. A receiving app that reads the URI late (for example a background upload or a Bluetooth transfer) fails if the user starts another job first. | Not attacker-driven. | The receiving app fails to read the file. Offkit cannot tell. | R (wipe at job start); the failure is inferred and NV (no receiving app exercised). | Accept, or wipe only copies older than a few minutes. |

Apart from RA-1, RA-2 and the two recorded review items, I found nothing new. Specifically, I found no write grant, no persistable or prefix grant on a provider URI, no exported provider, no path wider than `cacheDir/shared/`, and no new permission.

### Not verified
- Nothing was run on a device or emulator, and no unit or instrumented test was run.
- No receiving app was exercised. The share-sheet behaviour, how long the grants last, RD-6's late-read failure, and whether the system chooser reads the URI for its preview are all NV.
- The androidx `FileProvider` root and `..` checks were not read or tested. They are library behaviour.
- That `ACTION_OPEN_DOCUMENT` is answered only by DocumentsUI on every device (OEM builds) is NV.
- On the new APK, the following were not repeated: signing (`apksigner`), alignment, dex attribution of network, log and clipboard references, native imports, the secrets scan, OSV, and the artifact hashes. Only the manifest, the badging, the `share_paths` resource and a dex string grep were inspected.
- Name rendering was checked only at `HomeScreen.kt:510-514` and `FileCards.kt:145`. Other text in the rebuilt screens that shows file names (for example the saved name on the result screen) was not traced.
- The `RemoteEngine` log messages were not checked for file names or URIs.
- In the SR claims table, only the metadata strings were re-read. The Home footer, About, README and Play listing claims were not re-checked against the rebuilt screens, apart from `PRIVACY.md:21` and the "Android's own file picker" line in `docs/play-listing.md:26`. The files and tests the PI section cites (`scripts/verify.sh`, `scripts/isolation-smoke.sh`, `HostileIntentTest`, `EngineIsolationOnDeviceTest`) still exist, but their contents were not re-read.

### Status after the addendum (2026-11-25, later the same day)

Written by the conductor from the workers' reports; the rows above are left as written.

| Item | Status |
|---|---|
| RD-1 | Fixed: sharing no longer marks a result as kept; an unsaved result always asks before it is discarded. |
| RA-1 | Fixed: `PRIVACY.md`, its in-app copy and `docs/privacy/index.html` now describe when temporary and shared copies are deleted, and mention sharing from the result screen. |
| RA-2, RD-6 | Unchanged (accepted). |
| `material-icons-core` | Removed from the build; it no longer resolves and is no longer in the notices. |
| PI4R-1 (engine watchdog) | Fixed and independently reviewed; see `REVIEW.md`, "Later the same day". |

## Reader "Open with" addendum (branch `pdf-reader`, 2026-11-26)

Written by a docs worker from the code at `561c0e6` (H1 "Open with", H3 copy text). Not merged to `main`. This is a reading of the source, not a new audit: nothing was built, installed or run for it. Marks as in the redesign addendum: **R** read in the source, **NV** not verified by me. The rows above are not edited.

### What changed

| Area | Statement | Status | Evidence |
|---|---|---|---|
| Intent filter | `MainActivity` has a second filter: `VIEW`, `DEFAULT`, `scheme="content"`, `mimeType="application/pdf"`. It is still the only exported component; no component, permission or provider was added. | R | `AndroidManifest.xml:24-42`. |
| Launch mode | `launchMode="singleTask"`, `taskAffinity=""` kept: one instance in Offkit's own task. A `VIEW` while Offkit runs arrives in `onNewIntent` and opens the reader on top of the current back stack; it does not start a second copy sharing the same jobs and engine. | R (manifest, `AppNavHost`) / NV (task placement on every Android version) | `AndroidManifest.xml:26-28`; `ui/nav/AppNavHost.kt` (`navigate(Routes.READER) { launchSingleTop = true }`). |
| What is read from an intent | Only `data`, and only when the action is `ACTION_VIEW`, the scheme is `content` and the type is `application/pdf` (the intent's type, else `ContentResolver.getType` of the Uri). Extras, clip data, categories and flags are ignored. An explicit intent skips the filter, so this check, not the filter, is the gate. **Updated by J2 (H5-3, H5-4):** `documentUri` no longer calls the provider. An intent type of `application/pdf` is accepted; any other type is dropped at once; with no type the Uri goes to the reader as "type unverified" and `PdfReader.open` asks `getType` on the IO dispatcher, detached, with a 3 s limit, before any copy (anything but `application/pdf`, an exception or no answer: the reader's error screen, nothing copied). An intent with `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` (a relaunch from Recents) is ignored. | R | `MainActivity.kt` (`documentUri`); `core/PdfReader.kt` (`open`, `providerSaysPdf`); `OpenWithIntentTest` (`noIntentType…`, `aViewRelaunchedFromRecentsIsNotOpenedAgain`). |
| SB-13 kept | The Uri is taken first, then `intent` is replaced by the bare MAIN/LAUNCHER copy (`launchIntentOnly`, grant flags dropped) **before** `super.onCreate` and `setContent`, as before; `onNewIntent` passes only the scrubbed copy to `super`. The NavController still never sees the received intent. The Uri goes to an activity-scoped `ViewModel` (`ViewRequests`), in memory only, never in navigation or saved state. On recreation (`savedInstanceState != null`) the intent is not read again; after process death the redelivered intent is not reused (the grant would be gone). | R | `MainActivity.kt:33-39`, `:60-63`, `:84-95`, `:100-115`; tests `ui/OpenWithIntentTest.kt` (`recreationDoesNotOpenTheDocumentAgain`, `afterProcessDeathTheRedeliveredViewIsNotReused`, `otherIntentsLandOnHome`, `onNewIntentIgnoresANonPdfView`) and `HostileIntentTest`. I did not run them. |
| Read grant | The read grant of a `VIEW` intent belongs to the receiving activity, not to the `Intent` object, so the reader can still read the Uri after the rewrite. | NV (platform behaviour). Reported by the conductor as verified on the emulator (API 31) by `androidTest/.../OpenWithOnDeviceTest`, with a stand-in file manager in the test APK (own uid, non-exported `FixtureProvider`, `ViewTrampolineActivity`); its no-grant case shows the reader's error screen. |
| What is done with the Uri | `ReaderViewModel.open` → `PdfReader.open`: the same path as a picked file. `UriIo.describe` (name sanitised), size check, one copy to `cacheDir/jobs/reader-<uuid>/document.pdf` capped at 1 GiB, then `PageInfo` in `:engine`. The Uri is not kept after the copy, never persisted, never `takePersistableUriPermission`; only a read is ever done with it. The copy is deleted when the reader closes or opens another PDF, or by the first `PdfJobs.get` of the next process. | R | `core/PdfReader.kt` (`open`, `close`); `feature/reader/ReaderViewModel.kt` (`open`, `closeAll`, `onCleared`). On a first run the request waits in memory behind the introduction, and the copy starts when the reader opens. |
| Back | Back from the reader returns to Offkit's previous screen (Home, or the screen that was open, for example a tool's unsaved result), not to the calling app. | R | `AppNavHost.kt`; `OpenWithIntentTest` (`coldViewOpensTheReaderOnTheDocumentAndBackGoesHome`, `viewWhileOnAToolScreenPushesTheReaderAndBackReturnsToTheTool`). |
| Offkit's own Open | The result screen's Open (`openPdfIntent`) and Recents' Open (`RecentFiles.viewIntent`) are implicit `ACTION_VIEW` of `application/pdf` with a read grant, no package set, so Offkit is now one of the apps that can answer them. Chosen, the reader opens on top of the result. | R | `ui/components/Pickers.kt:76-86`; `core/RecentFiles.kt:94-97`. |
| Clipboard (H3) | The app now writes to the clipboard: `ClipboardManager.setPrimaryClip(ClipData.newPlainText("Offkit", text))`, only from the reader's Copy button or its "Copy page text" accessibility action. It never reads the clipboard. | R | `feature/reader/ReaderScreen.kt` (`copyToClipboard`, called only for a `ReaderTextEvent.Copy`, which `ReaderViewModel` sends only for the Copy button and `copyPageText`). |

### What a hostile app on the device can now do

- **Make Offkit copy and parse a file.** Send `VIEW` of a `content:` Uri with a read grant. Offkit copies up to 1 GiB into its private cache and parses it only in the `:engine` process. This is the same exposure as a file the user picks, with the same pre-checks, image gate, limits and watchdog (`security/engine-audit.md`, "Reader render path" and "Reader text path"). A provider that lies about the size or type gets the 1 GiB cap or an error screen.
- **Bring Offkit to the front on the reader**, showing a document of its choosing under the name its provider reports, also replacing a document the user is reading. Starting an activity from the background is limited by Android's background-start rules (NV). Nothing is saved or shared without a user tap, and an unsaved tool result stays on the back stack under the reader.
- **Name a Uri that Offkit itself can read** without granting it: an explicit `VIEW` of, for example, Offkit's own share provider (`cache/shared/<random UUID>/…`, only copies of results the user shared) or a saved file Offkit holds a persisted read grant for (Recents). The caller has to know the exact Uri. Offkit would show that file in its own reader; nothing is returned to the caller. (R for the code path; no such case is tested.)
- ~~**Delay the main thread.**~~ Fixed by J2 (H5-3): with no type on the intent, `getType` is asked on the IO dispatcher, not the main thread, with a 3 s limit. A provider that never answers now pins one IO thread until the system's provider timeout (H5-5) and the reader shows its error screen; Offkit's UI is not blocked. Tested with a stalling provider under Robolectric (R); not tried on a device (NV).
- ~~**Replay a VIEW from Recents.**~~ Fixed by J2 (H5-4): after Back finished the task's root (API 26-30) or after process death, a relaunch from Recents redelivered the original `VIEW` with no saved state and the old Uri was opened again. Such an intent (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`) is now ignored.

### What it still cannot do

- Choose a screen other than the reader, or pass anything to navigation: data, extras and deep links never reach the NavController (SB-13).
- Get anything back: Offkit sets no result, sends no reply and opens nothing towards the caller.
- Write to the file, keep Offkit's access to it, or make Offkit persist the Uri: only a read is done, once, and nothing is persisted.
- Have a `file:` or other non-`content:` Uri read, or a Uri whose declared type (or, with none, the provider's) is not `application/pdf`. A file of another kind sent as `application/pdf` only reaches the engine's checks and an error screen.
- Start a job, a save, a share or a copy: each needs a user tap.
- Read the clipboard through Offkit, or the text a user copies, except as any app can read the clipboard (Android's own rules apply, NV).
- Reach the `:engine` service, the share provider or any other component: they are still not exported.

### Earlier statements that are now out of date

| Where in the body | Earlier statement | Corrected statement (on `pdf-reader` at `561c0e6`) |
|---|---|---|
| Summary; check 1, Components | "exactly one exported component, the launcher activity, which reads nothing from the incoming intent"; "`MainActivity` is `exported=true` with only MAIN/LAUNCHER, no data/BROWSABLE filters" | Still the only exported component, now with a `VIEW` filter for `content:` `application/pdf` and `singleTask`. It reads the `data` of a `VIEW` intent as above; still no BROWSABLE filter and no deep links. |
| Check 2 | "`MainActivity.onCreate` never reads `intent`, data or extras" | It reads `action`, `data` and `type` (and may query the provider's type) of the received intent before the scrub, and nothing else. |
| Check 4 | "No … clipboard writes in `app.venkat.offkit.*`" | The reader writes plain text to the clipboard on the user's Copy. |
| SR re-check, SB-13 row; redesign addendum, "Exported components" | `onNewIntent` passes only the scrubbed copy; "MAIN/LAUNCHER only" | The scrub is unchanged and still runs before `super.onCreate`; the Uri is read just before it. The filter list is MAIN/LAUNCHER and VIEW (`content`, `application/pdf`). |

### Not verified

- No build, no APK and no test was run for this addendum. The emulator result for the read grant is the conductor's report.
- Not tried on a real phone, or with a real file manager, mail or browser app as the caller.
- Background-start rules, task placement with `singleTask` and `taskAffinity=""` across Android versions, and who can read the clipboard on each version are platform behaviour (NV).
