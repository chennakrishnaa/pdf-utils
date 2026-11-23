# Offkit 1.0.0 platform and artifact security audit (task SB)

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
