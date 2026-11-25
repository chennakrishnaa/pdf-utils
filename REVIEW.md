# Release review — PaperPixel 1.0.0 (FQ)

Reviewed main at `2481a84` ("Merge FO"). Artifacts under test: `dist/release/app.venkat.paperpixel-1.0.0.apk` (31,884,298 B) and `.aab` (20,584,254 B), built from main. The AAB's `version-control-info.textproto` records revision `2481a84`. Nothing was rebuilt and `scripts/release.sh` was not run.

**Verdict: ready to upload: yes, with caveats.** The signed release APK ran real MuPDF work on an Android 14 emulator. Merge and Images to PDF both produced valid PDFs, checked on the host. There were no crashes during the session, and the static release checks all pass. Caveats: Compress and Add images were not run on the release build; the open findings below remain (none blocks upload); and an uncaught `ActivityNotFoundException` crashes the app on a device with no document picker (R1).

### On-device results (release APK, signed)
The ATD image `pdfutils-smoke` (Android 12) booted and launched the app, but it has no DocumentsUI. Tapping "Add PDFs" there crashed the app (R1, `scratchpad/fq/atd_picker_crash.log`). So all steps below were run on **`pdfutils-shots` (Android 14, google_apis, x86_64)**, booted headless the way `release-smoke.sh` does (`sg kvm`, port 5584, same flags). Screenshots are in the session scratchpad `fq/`. The emulator was shut down afterwards, and no qemu process remained.

| Step | Result | Evidence |
|---|---|---|
| Install signed APK | OK, `primaryCpuAbi=x86_64`, versionName 1.0.0, `pkgFlags=[HAS_CODE ALLOW_CLEAR_USER_DATA]` (no DEBUGGABLE) | `adb install`, `dumpsys package` |
| Test inputs | Host-generated `alpha_2p.pdf` (2 pages), `beta_3p.pdf` (3 pages), `photo_one.jpg` and `photo_two.png` (1600×1200), pushed to `/sdcard/Download/` and media-scanned | `content query` listed all four |
| (a) Merge alpha + beta → Save as `merged.pdf` | **Pass.** UI showed "5 pages · 1.2 KB", then "Saved". The pulled file is `%PDF-1.7`, 1,233 B, `/Count 5`, five page objects, page texts in order Alpha 1–2 then Beta 1–3 | `03_merge_done.png`, `05_merge_saved.png`, host check of the inflated streams |
| MuPDF loaded in the release process | **Pass.** `/proc/<pid>/maps` has an `r-xp` mapping of `base.apk` at offset `0x12fc000`, which is exactly where zipalign puts `lib/x86_64/libmupdf_java.so`. The library is loaded straight from the APK (`extractNativeLibs=false`), so the maps line names `base.apk`, not the `.so` | `adb root` and maps; `zipalign -c -P 16 -v` offsets |
| logcat after all steps | **Clean**: 0 matches for `FATAL`/`Fatal signal`/`UnsatisfiedLinkError`; crash buffer empty | `fq/shots_session.log` |
| (b) Images to PDF, photo_one.jpg, **High** compression → Save | **Pass.** "1 page · 160.3 KB", then "Saved" as `images (1).pdf` (an `images.pdf` from an earlier session already existed on the AVD). Pulled: valid PDF, `/Count 1`, one image of `/Width 1000 /Height 750` (downscaled from 1600×1200 as promised) | `07b_high_selected.png`, host check |
| (c) Compress, (d) Add images | **Not run** (turn budget) | — |
| Recent files on home | **Pass.** Lists `images (1).pdf` (Images to PDF · 160.3 KB · Just now) and `merged.pdf` (Merge PDFs · 1.2 KB · 2 min ago) | `08_home_recents.png` |
| About | **Pass.** "PaperPixel", "Version 1.0.0", AGPL text, MuPDF credit, source `https://github.com/chennakrishnaa/pdf-utils` | `09_about.png`, uiautomator dump |
| AGPL licence screen | Opens and scrolls (reached "1. Source Code.") | `10_agpl_scrolled.png` |
| Open-source licences screen | Opens (header names PaperPixel), scrolls (reached the hyphenation-pattern licences) | `11_notices_scrolled.png` |
| Requested permissions | **None**: `dumpsys package … \| grep -A3 "requested permissions"` prints nothing | — |
| `run-as app.venkat.paperpixel` | **Fails** as expected: `package not debuggable` | — |

### Static release checks
| Check | Result |
|---|---|
| Badging | `app.venkat.paperpixel`, versionCode 1, versionName 1.0.0, label `PaperPixel`, minSdk 26, **targetSdk 36**, compileSdk 37, launcher `MainActivity` |
| Permissions (`aapt2 dump permissions`) | none |
| Manifest (`apkanalyzer manifest print`) | no `debuggable`, no `testOnly`, `allowBackup=false` (plus `fullBackupContent`/`dataExtractionRules`), `usesCleartextTraffic=false`. Only `MainActivity` is exported; the androidx-startup provider has `exported=false`. No services or receivers |
| Signature (`apksigner verify --print-certs`) | Verifies; v2 **true**, v3 **true**, v1 false (fine at minSdk 26). One signer, `CN=PaperPixel, O=venkat.app`, RSA 4096, SHA-256 `f940420d…f13f895`, **matches** |
| AAB signature (`jarsigner -verify`, `keytool -printcert -jarfile`) | "jar verified", the same certificate SHA-256 (this is the upload key) |
| Native libs | `arm64-v8a` and `x86_64` only (no 32-bit); `libmupdf_java.so` and `libandroidx.graphics.path.so`; stored uncompressed |
| 16 KB pages | `zipalign -c -P 16` passes; every ELF `LOAD` segment is aligned to `0x4000` |
| AAB `base/manifest` | Read with `strings` on the proto (bundletool not used): the same package, version 1.0.0, components and `allowBackup` |
| AAB stray files | None: only dex/res/lib, `assets/LICENSE` and `THIRD_PARTY_NOTICES.md`, AndroidX/Kotlin metadata, baseline profile, `version-control-info.textproto` (a commit SHA only). No keystore, no `.properties` beyond AGP's `app-metadata.properties`, no fixtures |
| Secrets in git | `git ls-files`: no `.jks`/`.keystore`/`.p12`/`keystore.properties`; the only tracked `.properties` files are `gradle.properties` and the wrapper's. `.gitignore` covers `keystore.properties`, `*.jks`, `*.keystore`, `*.p12` and `dist/release/` (confirmed with `git check-ignore -v`) |
| Signing values logged? | `app/build.gradle.kts` only reads them into `signingConfigs`. The one message (`GradleException`) lists the *names* of missing keys, never values. No `println`/logger |
| Old identity | `git grep` finds no `com.offlinepdf`, `PDF Utils`, `example.invalid` or `0.1.0` (outside REVIEW.md). APK `aapt2 dump strings`/`resources` and the dex: 0 hits |
| Notices | `THIRD_PARTY_NOTICES.md` is byte-identical to its asset copy, inside the APK and inside the AAB. `assets/LICENSE` is identical to the repo `LICENSE`. The notice text names PaperPixel and `https://github.com/chennakrishnaa/pdf-utils`; the APK string table holds the same URL |
| Play facts | targetSdk 36. AAB 20.6 MB (the universal APK is 31.9 MB; R8 is off). Play App Signing will re-sign with Google's app-signing key, so the delivered certificate will **not** be `f940420d…`; keep this upload key safe. Nothing in the manifest needs a Play declaration: no permissions, no foreground services, no `QUERY_ALL_PACKAGES`, no accessibility, no ads ID |

### Status of earlier findings for a public release
- **B1: Fixed, verified.** The About screen and the string table show `https://github.com/chennakrishnaa/pdf-utils`; `example.invalid` appears nowhere.
- **M6: Fixed, verified.** An Open-source licences screen shows `THIRD_PARTY_NOTICES.md`; the asset copies are identical; it opens and scrolls on the device. The completeness of the notice content was not re-audited in this round (FO's work).
- **N3** (save cleanup on cloud providers; overwrite-then-fail loses the old file): stands; not exercised on a real provider. Worth a release note, not a blocker.
- **N4** (serial, all-or-nothing estimates): stands. One 1600×1200 JPEG estimated instantly; 50 photos not tried.
- **N5** (large PNG raw stream on the Java heap): stands; an OOM risk on big PNGs, not measured on a device.
- **N6–N10** (JPEG walker and metadata residuals for non-conforming files): stand, Low; only odd or damaged files are affected.
- **M3** (inputs and options lost on process death): stands; mild UX loss.
- **M5** (normalizer pass-through skips the size cap): stands; overlaps N5.
- **L11–L19**: stand, cosmetic or robustness only. L17 (dead icons) and L18 (border contrast) are polish.

### New findings (most severe first)
| # | Sev | Where | What is wrong | Failure scenario | Confidence |
|---|---|---|---|---|---|
| R1 | Low | `ui/components/Pickers.kt:37` (`many.launch`/`single.launch`); likewise `:73` (CreateDocument) | Launching `OPEN_DOCUMENT` / `CREATE_DOCUMENT` is not guarded. With no handler, `ActivityNotFoundException` is thrown on the main thread and the app dies. `HomeScreen`/`AboutScreen` catch it for their own intents; the pickers do not. | **This is an app robustness bug that the emulator image exposed.** `pdfutils-smoke` is an ATD image without DocumentsUI (`com.android.fakesystemapp` home). Tapping Add PDFs gave `FATAL EXCEPTION … No Activity found to handle Intent { act=android.intent.action.OPEN_DOCUMENT }` (`atd_picker_crash.log`). On a normal Play phone DocumentsUI is always present, so users are unlikely to hit this. It can happen where DocumentsUI is disabled or missing (some kiosk/managed devices, TV/Wear-like builds). Fix: catch it and show "No file picker available". | Confirmed by running (ATD image) |
| R2 | Low (cosmetic) | Images to PDF / Add images item names | With the Android 14 photo picker, the image row shows the media id (`1000000026.jpg`) instead of `photo_one.jpg`. The picker's provider exposes that display name. | The user cannot tell picked photos apart by name in the list. | Confirmed by running |
| R3 | Info | `app/build.gradle.kts` `isMinifyEnabled = false` | R8 shrinking is off, so the universal APK is 31.9 MB and the AAB 20.6 MB (the native MuPDF libs dominate; dex is unshrunk). | Bigger download only. Enabling R8 needs keep rules for the MuPDF JNI classes and a re-test, so not for 1.0.0. | Confirmed by reading |
| R4 | Info | AAB `META-INF/version-control-info.textproto` | AGP embeds the commit SHA (`2481a84`). `local_root_path` is the `$PROJECT_DIR` placeholder, so no host path leaks. | None, since the source is public. | Confirmed by reading |

### Not proven
- Compress PDF and Add images on the **release** build (only Merge and Images to PDF ran). MuPDF native code is proven loaded and working, but those two code paths were not exercised under release.
- Medium/Low compression output. For this 1600 px JPEG the estimate at Low and Medium equalled Original (332.9 KB); I ran High instead. I did not check whether Medium re-encodes or keeps the original bytes.
- Real arm64 hardware: only the x86_64 library ran. The arm64 library was checked statically only (present, 16 KB aligned).
- Android 12 behaviour of the release build beyond launch: the ATD image could not drive pickers (R1).
- AAB → device install via bundletool / Play: not done (no network, no bundletool run). AAB manifest checked through proto strings only.
- Real cloud providers (N3), large inputs (N4/N5), process death (M3).
- Note: the `pdfutils-shots` AVD also has an old "PDF Utils" app installed from earlier sessions. It is a different package, not in this APK, and does not affect the result.

---

# Independent review, round 2 (FH)

Reviewed commit `1f242ad` (main), compared with `7c23a9c` (the build the owner tested). Follow-up: the N1 fix and the new on-device tests were re-checked at `26ec5db`. Second follow-up (FK): the N2b fix (metadata stripping) was re-checked at `9f50538` ("Merge FJ"), diff `e9cd787..9f50538`.

## Verdict
1. **Private trial: yes.** The security posture still holds on both APKs: no permissions, only `MainActivity` exported, backups off, and no network code. H1, H2, M1 and M2 are fixed. N1 (valid photos with trailing data rejected) is fixed at `26ec5db`, and the trailing-data part of N2 with it.
2. **Remaining Mediums:** N2b is fixed at `9f50538`: EXIF/GPS, XMP, MPF, IPTC and comments are stripped from conforming JPEG headers at every quality level, losslessly, with orientation unchanged. Three Low residuals remain for non-conforming layouts (N7, N8, N9). Save cleanup on real providers is not proven (N3). Estimates are slow, and one failure blanks them all (N4). Large PNGs are copied onto the Java heap (N5).
3. **Wider distribution: still no.** B1 (placeholder AGPL source URL) and M6 (third-party notices) are not addressed, and the release build is unsigned and has never run. On-device coverage is now 16 instrumented tests plus one manual end-to-end run on an Android 14 emulator.

## Commands run
| Command | Outcome |
|---|---|
| `git merge --ff-only main` | First run: `7c23a9c` → `1f242ad`. Follow-up: → `26ec5db`. Both times HEAD equalled `main`. |
| `./scripts/verify.sh` (once per commit) | **VERIFY OK**, exit 0, at `1f242ad` and again at `26ec5db`. Build, unit tests, lint and the APK checks passed. |
| Unit test results (`app/build/test-results/testDebugUnitTest`) | **298 tests, 0 failures, 0 errors** at `26ec5db` (290 at `1f242ad`; round 1 had 185). |
| `aapt2 dump permissions`, debug and release | Only `package: com.offlinepdf.utils`. No `uses-permission`. |
| `apkanalyzer manifest print`, debug and release | `MainActivity` is the only exported component. The only provider is `InitializationProvider`, with `exported="false"`. `allowBackup="false"`, `usesCleartextTraffic="false"`. `fullBackupContent` and `dataExtractionRules` exclude every domain, including `sharedpref`, so the new recent-files preferences are not backed up. Nothing new was merged in. |
| Native libs | `libmupdf_java.so` and `libandroidx.graphics.path.so`, for arm64-v8a and x86_64 only. Unchanged. |
| Greps over `app/src/main` | No `java.net`, `Socket`, `okhttp`, `WebView`, `FileProvider`, `ProcessBuilder`, `Log.*`, `println`, `printStackTrace`, world-readable modes or external storage. `takePersistableUriPermission` and `releasePersistableUriPermission` appear only in `core/RecentFiles.kt:60,114`. |
| First probe at `1f242ad` (real MuPDF; created, run, deleted) | Controls: the plain JPEG and the JPEG plus a 1 KiB trailer were accepted. Rejected: a JPEG plus 70 KiB of padding, a JPEG plus 1 MB of random bytes, and a PNG plus 70 KiB of trailing data. The old 64 KiB tail check rejected them (N1). A JPEG plus 100 KiB ending in `FF D9` was accepted, and its trailer was embedded: the output grew by 102,401 bytes (N2a). |
| Follow-up probe at `26ec5db` (real MuPDF; created, run, deleted) | **Accepted, trailer not embedded:** JPEG + 70 KiB padding, JPEG + 1 MB random bytes, and PNG + 70 KiB trailer. Each gave output identical in size to the plain image (1,006,513 and 169,936 bytes), and the estimate's `original` equals the image length without the trailer. JPEG + 1 MB at High: 86,211 bytes. A 6000×4000 JPEG (22 MB) plus an 8 MB trailer was accepted and trimmed; walking the 22 MB took 60 ms. **Rejected:** truncation to 90 % and to length − 3; `FF D8` alone; `FF D8 FF D9`, where the walker returns 4 and MuPDF has no pixels; an APP1 length running past EOF; segment lengths 0 and 1; a non-image starting `FF D8 FF`; a PNG whose first chunk length is `0xFFFFFFFF`; a PNG cut by 20 bytes. **Accepted, wrongly:** the first half of a JPEG followed by a complete small JPEG (N6). |
| Contrast, computed from `ui/theme/Theme.kt` | Light: onSurface/surface 16.3, onSurfaceVariant/surfaceContainerLow 8.4, onPrimaryContainer/primaryContainer 7.2, onPrimary/primary 6.4, primary text on the selected ChoiceCard fill 5.5. Dark: 14.4, 10.0, 7.2, 7.7, 7.8. All text pairs pass AA. Unselected-card border (outlineVariant on surfaceContainerLow): 1.54 light, 1.84 dark (L18). |
| FK, at `9f50538`: `./gradlew testDebugUnitTest --tests` for `JpegMetadataTest` and `ImagePagesTest` | 12 + 21 tests, 0 failures. `verify.sh`, lint and the emulator were **not** run at `9f50538`, because another build and an emulator were running on this machine. |
| FK probe at `9f50538` (real MuPDF, plain JUnit; JPEGs built with `javax.imageio` plus my own TIFF/EXIF writer, independent of `Fixtures`; created, run, deleted) | 7 probe tests, all assertions passed. Findings N7 to N9 come from the print-outs. **(1)** EXIF APP1 (II and MM: IFD0 with Make, Model, Orientation 6 and a GPS pointer; GPS IFD with lat/long rationals; IFD1 thumbnail JPEG with its own COM), XMP APP1, MPF APP2, plus a trailer holding a second JPEG. At Original/Low/Medium/High and through `appendImages`, none of the planted strings or the 24-byte GPS rational block appear in the PDF (raw file plus every stream, raw and decoded). At Original the embedded stream is **byte-identical** to the ImageIO JPEG before insertion. The page is 80×120, upright, and the estimate's `original` equals that length (1336). **(2)** Two `ICC_PROFILE` chunks (sRGB, 6876 B) interleaved with EXIF, XMP and MPF, plus Adobe APP14: the embedded stream equals base + ICC1 + ICC2 + Adobe in order, and the render is pixel-identical to the ICC-only file. **(3)** Progressive (SOF2) and DRI with 8 RSTn types, with fill bytes before DQT, before the first RST and before the stripped APP1: accepted; embedded stream = base including its fill bytes; render pixel-identical to the unstripped file. **(4)** Segment length 0 and 1, APP1 running past EOF, SOI+EXIF+EOI only, a full header with no SOS, and metadata with no EOI: each gives `Err(UnsupportedImage)` and an estimate `Err`, no output file, 0–1 ms. Zero-length APP0/1/2/14 and COM: accepted, embedded == base. 130 metadata segments (8.4 MB): accepted in 84 ms, no leak, embedded == base. Metadata after the first SOS: **leaks (N7)**. Non-marker bytes between header segments: **leak (N8)**. JFIF APP0 thumbnail bytes: **kept (N9)**; JFXX dropped. Truncated JPEG + a JPEG with EXIF: accepted, **EXIF, GPS and thumbnail all leak** (N6, now also a privacy issue). **(5)** Orientation 1–8 × II/MM × with and without JFIF APP0 × {Original, Low}, plus `appendImages` into a 300×300 page: 96 cases, all upright, with the right page size. The corner colours match what the pre-change code produced (MuPDF orientation of the unmodified file, for example EXIF 6 → code 4). **(7)** PNG with `tEXt`, `eXIf` and `iTXt`: nothing leaks (pixels are re-encoded). PDF info: the only document metadata is `Producer(MuPDF 1.28.5)` (placed inside the Catalog) and the `% Written by MuPDF 1.28.5` comment. No dates, Creator, `/ID` or XMP. `appendImages` keeps the host PDF's own info. |
| `scripts/emulator-smoke.sh` | **Not run by me**, as instructed. The conductor reports SMOKE OK at `26ec5db` with 16 instrumented tests. 5 are new in `EngineOnDeviceTest`: High compression vs none, `estimateImage`, an owner-password merge, a truncated JPEG, and failed-save cleanup. The conductor also reports one manual run of the real photo picker, estimates, create-document dialog, save and Recent files on an Android 14 emulator (Medium estimate 169.3 KB, actual 169.4 KB). I did not observe that run. |

## Status of previous findings
| # | Status | Evidence |
|---|---|---|
| B1 | **Not addressed** | `res/values/strings.xml:53` is still `https://example.invalid/pdf-utils-source`. |
| H1 | **Fixed, host-tested** (one on-device test) | `core/PdfJobs.kt:254-283`: once the destination has been opened, any failure or cancel deletes it; before that, it is deleted only if empty. `core/JobViewModel.kt:140-178`: new `Saving` state, the save runs in `processScope`, and `reset`, `cancel` and `startJob` are ignored while saving. Tests: `PdfJobsTest.failureMidWriteDeletesDestinationKeepsOutputAndRetryWorks`, `closeFailureCountsAsFailedSave`, `failureBeforeOpeningKeepsNonEmptyExistingDestination`; `JobViewModelTest.savingStateIgnoresResetStartCancelAndSave`, `clearingDuringSaveStillCompletesTheFile`. These run under Robolectric with `file:` or fake streams. On device: `EngineOnDeviceTest.failedSaveRemovesEmptyDestination` covers only the failure before opening, with a `file:` Uri. `DocumentsContract.deleteDocument` has never run against a real provider. Residual risk: N3. |
| H2 | **Fixed** | `engine/PdfInputs.kt:41,59-72` rejects the file when MuPDF has a crypt handler or the trailer has `/Encrypt`. Proven with real MuPDF: `PdfInputsTest.ownerPasswordOnlyIsEncrypted`, `CompressOpTest.ownerPasswordOnly_isErrEncrypted_noOutput`, `MergeOpTest.ownerPasswordOnlyInputIsEncryptedAndLeavesNoOutput`, `AppendImagesOpTest.ownerPasswordOnlyPdfIsEncryptedAndLeavesNoOutput`. On device: `EngineOnDeviceTest.ownerPasswordPdfMergeFailsWithEncrypted`. Only one fixture type is covered (`Fixtures.ownerPasswordPdf`); RC4 and AES-128 owner-only files are not tested. |
| M1 | **Fixed** (minor residual, L12) | `invalidateResult()` (`JobViewModel.kt:232-243`) is called on every add, remove, move, choose, level, page-size and quality change in all four ViewModels. Asynchronous adds call it after `describe` completes. Tests use the Robolectric fake: `*ScreenTest.changing…DiscardsReadyResult`, `JobViewModelTest.invalidateResultFrom*`. |
| M2 | **Fixed** | `ui/components/ToolScaffold.kt:60-109` adds a `BackHandler` plus a dialog, with `rememberSaveable` so it survives rotation. `hasUnsavedWork()` covers Running, ReadyToSave and Saving (`JobPanel.kt:62`). All four screens pass it. Tests: `ComponentsTest.toolScaffoldConfirmVia*`, `*ScreenTest.backWithResultAsksBeforeLeaving`. The dialog text is wrong during Saving (L11). |
| M3 | **Partly fixed** | If the process dies while the save dialog is open, the empty destination is now deleted and "result no longer available" is shown (`JobViewModel.kt:144-153`; `JobViewModelTest.saveWithoutPendingOutputDeletesDestinationAndExplains`). Picked inputs, options and quality are still lost: no ViewModel uses `SavedStateHandle`. |
| M4 | **Fixed** (at `26ec5db`; N6 is a minor gap) | The structural walkers `jpegEnd` and `pngEnd` (`engine/ImagePages.kt:161-205`) reject truncated files, including a truncation past an EXIF thumbnail's own end marker (`ImagePagesTest.truncatedJpegWithExifThumbnailIsRejected`). Real MuPDF on the host. Also run on a device: `EngineOnDeviceTest.truncatedJpegFailsWithUnsupportedImage`. |
| M5 | **Not addressed, slightly worse** | The ImageNormalizer pass-through still skips the size cap. New this round: `ImagePages.savedStreamSize` (`ImagePages.kt:272`) copies each PNG's raw pixels onto the Java heap through `readRawStream()`, for every estimate and for every compressed job image (N5). |
| M6 | **Not addressed** | The About screen is unchanged. The new vector icons look like Material Symbols path data, which is Apache-2.0 and needs a notice (plausible). |
| L1–L10 | **Not addressed** | None of the files involved changed this round. `CompressOpTest` only gained an owner-password test, so L6 and L7 remain. |

## New findings (most severe first)
| # | Sev | Where | What is wrong | Failure scenario | Confidence |
|---|---|---|---|---|---|
| N1 | ~~High~~ **Fixed at `26ec5db`** | `engine/ImagePages.kt:60-90,161-205` | Was: valid JPEG/PNG files with more than 64 KiB after the end marker (motion photos) were rejected. Now a streaming marker walk (JPEG) or chunk walk (PNG) finds the real end of the image. Data after it is accepted, and MuPDF only ever sees bytes `[0, imageEnd)` (`Image(readPrefix(...))`, line 79). | Evidence: my follow-up probe (see Commands run), plus `ImagePagesTest.jpegWithLongTrailerIsAcceptedAndTrailerNotEmbedded`, `pngWithLongTrailerIsAcceptedAndTrailerNotEmbedded`, `motionPhotoTrailerIsAcceptedNotEmbeddedAndNotEstimated`, `exifOrientation6WithTrailerIsUpright`, `walkerHandlesRestartsFillStuffingAndMultipleScans`. Real motion-photo files from a phone are still untested. | Confirmed by running |
| N2a | ~~Medium~~ **Fixed at `26ec5db`** | `engine/ImagePages.kt:247-249` | Was: data after the end-of-image marker was embedded in the PDF. Now the JPEG is trimmed at its end marker before embedding, and the estimate's `original` is `imageEnd`. | Probe: 1 MB trailer → output identical to the plain image's. | Confirmed by running |
| N2b | ~~Medium~~ **Fixed at `9f50538`** (residuals N7–N9, and N6) | `engine/ImagePages.kt:69-109,244-305` | Now, before the first SOS, `jpegScan` drops APP1, non-ICC APP2, APP3–13, non-Adobe APP14, APP15, non-JFIF APP0 and COM. The kept bytes are copied once (`readKept`) and the orientation comes from the original header. Independently verified by the FK probe (see Commands run): byte-exact output, multi-chunk ICC kept in order, progressive and restart files, hostile lengths, 96 orientation cases, all levels and Add images. Was: at Original quality the JPEG is still embedded byte for byte up to its end marker. That includes the APPn segments: EXIF with GPS location, device model and time, plus the thumbnail and XMP. | A user shares a PDF of receipts made from phone photos; it carries the home location of every photo. Low/Medium/High re-encode the image and drop the metadata. | Confirmed by reading |
| N3 | Medium | `core/PdfJobs.kt:276,290-300` | Cleanup after a failed save is best effort: `deleteDocument` failures are swallowed. Some providers refuse deletion or are not document Uris (`isDocumentUri` false, which means no delete at all). The user gets the same "Could not save the file there" message. Separately, if the user picked an existing file to overwrite, it has already been truncated by `"wt"` and is then deleted. | A save to a cloud provider fails mid-copy and the provider refuses delete. A truncated PDF stays at the destination and the user is not warned. Overwriting `report.pdf` with a save that then fails loses the old `report.pdf` entirely. | Confirmed by reading. Real providers were not tested. |
| N4 | Medium | `feature/imagestopdf/ImagesToPdfViewModel.kt:59-63,217-244`; `core/PdfJobs.kt:194-211` | Estimates run serially: each image is copied, decoded and JPEG-encoded at three levels. Totals appear only when every image has finished, and one failure hides all of them. The engine call is not interruptible, so Run waits for the estimate in progress. Nothing bounds the work or skips duplicates. | With 50 camera photos, the cards show "Estimating…" for a long time (not measured), the CPU runs continuously, and each tap on Run waits for the image currently being estimated. A transient `Io` or `OutOfMemory` on one image leaves "—" on every card until that image is removed. | Plausible (timing not measured) |
| N5 | Medium | `engine/ImagePages.kt:247-289,323` | `originalEmbeddedSize` adds the image to a scratch document and copies its uncompressed raw stream onto the Java heap to deflate it. This happens for every PNG estimate and for every PNG in a compressed job. A 24 MP RGBA PNG needs about 96 MB of heap. Also new: when an image has a trailer, `readPrefix` holds the whole image on the Java heap while MuPDF copies it. | A large screenshot or scanned PNG gives `OutOfMemory`, so the estimate fails (N4) or the job fails with a generic error, at a quality level that should have made the file smaller. | Plausible |
| N6 | Low | `engine/ImagePages.kt:167,174` | The walker resyncs on any byte and treats a second `FF D8` as a standalone marker. A truncated JPEG followed by any complete JPEG is therefore accepted: the walk ends at the second image's end marker. MuPDF then decodes the damaged first image. | Probe: the first half of a 4 MB JPEG plus a 64×64 JPEG → `Ok`, 2 MB page with a partly broken image. This needs a damaged file with a second image after it (for example a botched concatenation or resumed download), not plain truncation, which is still rejected. **FK at `9f50538`:** this is now also a privacy gap. The second JPEG's EXIF sits after the first SOS, so it is not stripped: the probe found Make, Model, the GPS rationals and the thumbnail all in the PDF. | Confirmed by running |
| N7 | Low | `engine/ImagePages.kt:269` (`scanStart < 0 &&`) | APPn and COM segments after the first SOS are not classified, so they are embedded unchanged. This is by design ("bytes from the first SOS on are unchanged"), but it is a legal layout: libjpeg skips APPn between progressive scans, and MuPDF renders the file. | An editor or tool that writes an APP1/COM between scans (uncommon; not seen from phone cameras) ships its content. Probe: EXIF APP1 + COM inserted before the 2nd SOS of a progressive JPEG → `Ok`, `PROBEMAKE`, XMP text and `Exif\0\0` all present in the PDF. The new Original-quality string promises that location and camera details are removed, which is not true for these files. | Confirmed by running |
| N8 | Low | `engine/ImagePages.kt:253` | Non-marker bytes between header segments are skipped by the walker as "garbage", never dropped, so they are embedded. libjpeg only warns ("Corrupt JPEG data: N extraneous bytes before marker"). | A file with padding or text between segments (for example from a buggy metadata editor) keeps those bytes in the PDF. Probe: 34 ASCII bytes after the JFIF APP0, and again after an EXIF APP1 → `Ok`, the text is in the PDF (the EXIF itself was dropped). Fix: drop non-FF bytes outside a scan, or reject them. | Confirmed by running |
| N9 | Low | `engine/ImagePages.kt:296` | A JFIF APP0 is kept whole, including the optional uncompressed RGB thumbnail (Xthumb×Ythumb) and any bytes after the 14-byte JFIF header. Only the `JFIF\0` prefix is checked. | A JFIF thumbnail is a small copy of the same picture. That is not location data, but it is not the picture the user sees if an editor cropped the image and left the old thumbnail. Probe: bytes after the JFIF header were present in the PDF. Fix: rewrite APP0 as a bare 16-byte JFIF header (thumbnail 0×0). | Confirmed by running |
| N10 | Low | `engine/ImagePages.kt:80,93` (`Format.OTHER` → `Image(path)`); MuPDF `source/pdf/pdf-image.c:604` | Formats other than JPEG and PNG are not structurally checked or stripped. MuPDF embeds a JPX (JPEG 2000) compressed buffer as is (`case FZ_IMAGE_JPX`), including its XML and UUID boxes (EXIF/XMP). In the app this is unreachable: `core/ImageNormalizer.sniff` passes only the JPEG and PNG magic bytes, whatever the file name, and transcodes everything else (JPX, GIF, TIFF, BMP, HEIC, WebP) through `Bitmap.compress`, which writes no EXIF. | Only a future caller that passes files to `PdfEngine.imagesToPdf` or `appendImages` without the normalizer. Defence in depth: reject `Format.OTHER` in `open`, or route it through the re-encode. | Confirmed by reading (MuPDF source and the normalizer); not run |
| N5 (update) | — | `engine/ImagePages.kt:91,143` | Wider than reported: nearly every camera JPEG carries EXIF, so nearly every photo at every level now takes the in-memory path (`readKept`: one Java-heap copy of the stripped image). `Image(byte[])` then makes a native copy (`platform/java/jni/image.c:95`, `fz_new_buffer_from_copied_data`), so the peak is the Java array plus the native buffer, and with GetByteArrayElements possibly one more on a JVM. `jpegOrientation` adds two transient copies of the header (`readPrefix`, then `head + EOI`), which is at most a few MB even with maximal metadata (8.4 MB probe: 84 ms). One image at a time; all MuPDF objects are destroyed on every path, including the probe `Image` in `finally` and `img` when the size check fails. | A 20–30 MB JPEG costs about 20–30 MB of transient heap per image, which is acceptable on current devices but adds to the M5/N5 pressure. | Confirmed by reading; Android heap not measured |
| L11 | Low | `core/JobViewModel.kt:169-170`; `res/values/strings_core.xml:13` | While saving, the leave dialog says the unsaved result "will be lost", but the save actually continues. If that save then fails after the user has left, the output is discarded with no message. | The user leaves during a slow save and is never told the save failed. | Confirmed by reading |
| L12 | Low | `feature/merge/MergeViewModel.kt:44-49`, likewise `CompressViewModel.kt:146-151`, `AddImagesViewModel.kt:278-295`, `ImagesToPdfViewModel.kt:131-133` | If a job or save starts while `describe` is still running, the picked files are dropped silently. | Files picked from a slow provider disappear if Run is tapped meanwhile. | Confirmed by reading |
| L13 | Low | `ui/home/HomeViewModel.kt` `shareIntent` | Share does not check whether the document still exists. Open does. | The share target receives a dead Uri. | Confirmed by reading |
| L14 | Low | `core/RecentFiles.kt:121-134` | Persisted grants are never reconciled with `persistedUriPermissions`. If the preferences are corrupt or a commit fails, grants stay held with no entry left to release them. | Leaked read grants accumulate. The 20-entry cap keeps this far below the platform limit. | Confirmed by reading |
| L15 | Low | `core/RecentFiles.kt:106-110` | `isAvailable` opens the document to test it. | On a cloud provider, tapping a recent entry may trigger a download before the viewer opens. It runs on IO, so the UI does not freeze. | Plausible |
| L16 | Low | `core/RecentFiles.kt:59-63` | If the provider refuses a persistable grant, the entry is still recorded. | After a restart the entry is listed, and tapping it shows "unavailable" and removes it. | Confirmed by reading |
| L17 | Low | `ui/components/AppIcons.kt` (`delete`, `open`); `res/drawable/ic_delete.xml`, `ic_open.xml` | Dead icons and resources. | None. | Confirmed by reading |
| L18 | Low | `ui/components/Common.kt:141` | The unselected ChoiceCard border has 1.54:1 contrast in light mode and 1.84:1 in dark, below 3:1 for non-text UI. The selection indicator still differs. | Low-vision users may not see the card boundaries. | Confirmed by computing |
| L19 | Low | Tests | `estimateMatchesRealOutput` allows 5 %. `RecentFilesStoreTest.shareIntentContents` does not assert that no write flag is set (by reading, none is set). The 100-byte-trailer gap is closed by the N1 tests. | Regressions in these areas pass. | Confirmed by reading |

Checked and found correct:
- **Recent-files intents and actions:** `viewIntent` and `shareIntent` grant read access only, `clipData` is set on the share intent, and `createChooser` propagates it. "Clear" and "Remove" only edit the list and release grants; they never delete documents, and Clear asks for confirmation. Saving the same Uri twice moves its entry to the top without duplicating it.
- **Recent-files storage:** the store holds only Uri, name, size, time and tool id, in private preferences excluded from backup. Store access from `HomeViewModel` and `JobViewModel` runs on `Dispatchers.IO`. Corrupt JSON gives an empty list.
- **MuPDF object lifetimes:** `encodeJpeg` and `estimate` destroy every `Pixmap`, `DrawDevice`, `Buffer`, `Image` and `PDFObject`, and the colour-space singletons are never destroyed.
- **Estimates:** they run on the single engine dispatcher and delete their job directory in `finally`.
- **Image compression:** the "never larger" rule holds at the image level, and EXIF 6 stays upright at every level.
- **JPEG walker:** it streams through a 64 KiB buffer (a 22 MB file walks in 60 ms). Segment lengths below 2, lengths that run past EOF, and PNG chunks that run past EOF all give `null`. The "resync on non-FF" rule cannot accept a plainly truncated file, because a truncated walk never reaches an end marker.
- **Icon buttons:** they are 48 dp and have content descriptions.

## Proven vs not proven
| Feature | Proven with real MuPDF (host, and the device where noted) | Only with Robolectric and `FakePdfEngine` | Not tested |
|---|---|---|---|
| Images to PDF quality and estimates (new) | Each level shrinks the output, caps the long edge and keeps page sizes; no upscaling; alpha flattened onto white; gray stays gray; EXIF 6; estimate within 5 % of the real output; bad or truncated images give `UnsupportedImage`; synthetic trailers accepted and not embedded. **On device (x86_64 emulator):** `imagesToPdf_highCompressionIsSmallerWithSamePageSizes`, `estimateImage_levelsDecreaseAndJobsEmpty`. Conductor-reported manual run on Android 14 (real photo picker): Medium estimate 169.3 KB, actual 169.4 KB. | Quality cards, Estimating/totals/"—", pause and resume around a job, invalidation | Metadata stripping on real phone photos or on a device (host only, synthetic files); CMYK JPEG, 16-bit or huge PNG (N5), many images (N4), real motion photos, ARM, state restoration |
| Recent files (new) | — | Store add/cap/remove/clear/persist/corrupt data; home rows; open, share and remove; clear confirmation; recording after a save. Conductor-reported: a save appeared in Recent files during the manual Android 14 run. | Real persistable grants across a restart, a revoked grant, cloud providers, a deleted or moved document on a real device |
| Restyle (new) | — | Component semantics (`StyledComponentsTest`), JobPanel states, dark theme for Failed | TalkBack, font scaling, small screens and long names, visual review (beyond the manual run) |
| Save robustness (H1/M3) | On device: `failedSaveRemovesEmptyDestination`. This covers only the failure before opening, with a `file:` Uri, so `deleteDocument` is not exercised. | All paths, with `file:` Uris and fake streams | A real SAF provider's `deleteDocument` after a mid-copy failure, process death on a device |
| H2, M4 | Yes (see the status table). On device: `ownerPasswordPdfMergeFailsWithEncrypted`, `truncatedJpegFailsWithUnsupportedImage`. | — | Other encryption types; real camera files |

Never exercised by an automated test: real system pickers and the create-document flow. The conductor drove these by hand once on an Android 14 emulator; I did not observe it. Also never exercised: the release build (unsigned, R8 off), ARM hardware, Android versions other than 12 and 14, and real-world PDFs and photos.

## Not reviewed this round (time)
- The full restyle diffs of `JobPanel.kt`, `FileList.kt`, `Common.kt` beyond ChoiceCard, and the Compress, Merge and Add images screens. Hardcoded strings were left to lint, which passed.
- Whether the spike tests still assert behaviour that main no longer has. Unused string resources.
- `encodeJpeg` on CMYK, progressive or large inputs: reasoned about, not run.
- Concurrency of a double save or `onCleared` racing a save: reasoned from the code only.

## Risks and decisions for the owner
- **Appended data (N1/N2a):** now trimmed at the primary image's end marker. Motion photos become still images in the PDF. Confirm with one real motion photo from a phone.
- **Privacy:** N2b is fixed for conforming JPEG headers. Decide whether the residual layouts (N7 metadata between scans, N8 bytes between segments, N9 JFIF thumbnail, N6 concatenated files) need fixing before the Original card's "location and camera details are removed" promise is made. Not checked on real phone photos (Samsung/Pixel motion photos, Apple MPF) or on a device.
- **The request to "ask the compression level":** the screen preselects Original and does not prompt the user. Confirm that this is what you want.
- **Before distribution:** B1, M6 (including the icon licence), release signing, R8, and a device test pass covering the new features and real SAF providers.

## Final review: process isolation (FR)

Independent review of `main` at `b0a3ba4` ("Merge PI3"), which includes PI1, PI2, PI3 and SF6. I wrote none of this code. I changed no app source code and no scripts. The hostile-file test in Part C was a throwaway instrumented test and was not committed.

### A. Gates

I ran all three myself, one after another, with 60 s between the emulator scripts. All passed.

```
verify.sh:            aapt2 dump permissions (debug/release): package: app.venkat.offkit  (none)
                      exported components: app.venkat.offkit.MainActivity
                      EngineService process/exported (debug, release): :engine false
                      VERIFY OK
emulator-smoke.sh:    tests=30 failures=0 errors=0
                      SMOKE OK
isolation-smoke.sh:   :engine process not running
                      ISOLATION SMOKE OK
```

### B. Code review of `engine/remote/**` and its callers

Layering checks: no file in `engine/` outside `engine/remote/` imports `android.*`, and no file outside `engine/` imports `com.artifex`. Both confirmed with grep.

Things I checked and found sound:
- **Binder trust.** `EngineService` is `exported=false` and has no intent filter. `EngineServer.run` refuses any caller whose UID is not the app's own (`EngineServer.kt:51`). So another app can neither bind nor run a request.
- **Paths.** Every request path, including a `NormalizeImage` request's `raw` and `destDir`, is canonicalised and must lie strictly inside `cacheDir/jobs`. A normalised image's output name is built from an integer index (`0..MAX_IMAGE_INDEX`). The server checks it again (`checkPrepared`), and the client accepts only `img_NNN.jpg/png` (`EngineWire.kt:256-260`).
- **Request ids.** Each attempt has its own callback binder and filters on its own id (`RemoteEngine.kt:197-205`), so a late callback from an earlier request cannot reach a later one.
- **The one-time resend.** `run` is `oneway`, so `RemoteException` from it means the transaction was never delivered. The resend happens at most once (`call`, `RemoteEngine.kt:183`), so it cannot loop, and an operation cannot run twice.
- **Blocking.** `connect` waits at most 10 s. The wait loop always ends on result, loss, the cancel grace, the stall or total limit, or the memory limit. Binder death during bind makes `linkToDeath` throw, and that is handled.

Findings, most severe first:

| # | Severity | Where | Finding and failure scenario | Suggested fix |
|---|---|---|---|---|
| FR-1 | **Medium** | `RemoteEngine.kt:213-262` (`nanoTime` deadlines). No foreground service anywhere. | **A job that runs while the app is in the background can be killed as "taking too long" when the user returns.** The stall and total limits use `System.nanoTime()` (CLOCK_MONOTONIC). This clock keeps running while a process is frozen. Once the activity is stopped, the UI process is cached, and so is the `:engine` process, because a bound service takes its client's importance. On devices with the cached-apps freezer (on by default on many Android 13+/14+ devices), both processes are frozen within seconds. Scenario: the user starts a 3-minute Compress, switches to another app for 2 minutes, then comes back. Both processes thaw. The UI's poll returns and sees `now - lastActivity >= 90 s` before the engine has sent its next progress event. The engine is killed, and the user sees "This file is taking too long to process… It may be damaged or too complex" for a good file. Without the freezer, the low-memory killer can kill the cached engine instead. The user then sees `EngineCrashed` ("Offkit couldn't process this file. It may be damaged…"), which blames the file. Reasoned from the code and platform behaviour; **not reproduced** (see Not verified). | Run the stall clock only while the app is actually running. One way is to measure stall time as CPU or uptime of the waiting thread, or to restart `lastActivity` after a gap much longer than `pollMs` between two polls (a gap like that means this process was frozen). Or run jobs from a short foreground service (this needs the `FOREGROUND_SERVICE` permission, which conflicts with the "no permissions" claim). At least, say in the error text or docs that leaving the app during a long job may stop it. |
| FR-2 | **Medium** (Low if FR-1's backgrounding is rare) | `EngineServer.kt:36-38, 59-100`; `EngineService.kt` (no `onUnbind`/`onDestroy`) | **When the UI process dies mid-job, the engine keeps running the job with no watchdog, and a new UI can share that process with it.** The watchdog lives in the UI process. If the UI dies (for example the LMK kills it in the background, as in FR-1), the engine learns of it only when its next `callback.progress` throws (`clientGone`). A request stuck inside one MuPDF call (SR3-2 `bigdict`, an SR3-1-class parse, SR3-4's save) runs to the end with no limit. When the last binding goes away, the service is destroyed, but its daemon worker thread and the process stay alive (cached). When the user reopens the app, `PdfJobs.get` wipes `cacheDir/jobs` under the orphan. `bindService` then reaches the **same** process and creates a second `EngineServer` with a second worker and a second `MuPdfEngine`. Two MuPDF jobs then run at once in one process. The new job's RSS limit counts the orphan's memory, so the new job can fail with `OutOfMemory` (or `EngineCrashed` after an LMK kill) through no fault of its own. Reasoned from the code; not reproduced. | In `EngineService.onUnbind` (or `onDestroy`), if a request is still running, kill the process (`Process.killProcess(Process.myPid())`). The engine holds no state worth keeping. Alternatively, link to the death of the client's callback binder and kill there. |
| FR-3 | Low | `ui/components/Format.kt:18`, `strings.xml:40` | **`EngineCrashed` always blames the file.** The engine also dies from causes that have nothing to do with the file: the LMK in the background (FR-1), a system kill, or the 10 s bind timeout in `connect` (for example while the system delays restarting a service that keeps crashing). All of these show "Offkit couldn't process this file. It may be damaged or too complex." | Give a neutral message ("Processing stopped unexpectedly. Try again.") at least when the app was backgrounded or binding failed. Binding failure and death are both mapped to `EngineCrashed` today (`RemoteEngine.kt:188`). |
| FR-4 | Low | `EngineConnector.kt:373, 407`; `RemoteEngine.drop` | **The death recipient is never unlinked.** Each bind links a new `DeathRecipient` that captures the `Session`. `disconnect` unbinds but never calls `unlinkToDeath`. The proxy and recipient stay referenced until the binder proxy is collected, so there is a small leak per engine restart and no functional effect. `onLost` of an old session only marks that old session, so it is harmless. | Keep the binder and call `unlinkToDeath` in `disconnect`. |
| FR-5 | Info | `RemoteEngine.kt:183, 206-211` | **Resend and misclassification of a transaction that is too large.** A `RemoteException` from the oneway `run` can also be `TransactionTooLargeException` (a huge Bundle; not reachable with today's path lists). It is then retried once on a freshly bound engine (unbinding a healthy one) and reported as `EngineCrashed`. It is bounded and cannot loop. | None needed now; map it to `Io` if path lists ever grow. |
| FR-6 | Info | `EngineConnector.kt:424-426`, `RemoteEngine.killAndWait` | **Kill by a stale pid.** The pid is read once at bind time. The kernel allows `killProcess` only for the same UID, and the UI's own pid is excluded. The only same-UID process a stale or recycled pid could hit is another engine of this app. One cannot be bound while the call holds `RemoteEngine.lock`, but AMS can restart a crashed bound service on its own. Pid reuse within that window is practically impossible. A double kill (the cancel grace and the watchdog) cannot happen, because each path returns right after `killAndWait`. | None. |
| FR-7 | Info | `EngineServer.kt:47, 55, 126-143` | `pid()` and `cancel()` have no caller UID check, and `checkPaths` checks the canonical path but then opens the original string (a TOCTOU window). Neither is exploitable. The service is unexported, and only same-UID code can create symlinks in `cacheDir/jobs`, which is already fully trusted because the engine is not `isolatedProcess`. | None; noted for completeness. |
| FR-8 | Info | `PdfUtilApp.kt` | `PdfUtilApp` is created in both processes and is inert, as its KDoc requires. `engine` throws in the engine process, and `PdfJobs.get` refuses to run (and wipe) there. `engineLimits()` makes one binder call to `ActivityManager` on first use only. | None. |
| FR-9 | Low | `RemoteEngine.kt:182` (`synchronized(lock)` for the whole wait), `PdfJobs.kt:186-192` (image preparation on the IO dispatcher, not the engine dispatcher) | **Image preparation waits on the lock of a running job, and its cancel goes unseen while it waits.** `call` holds one lock for the whole request. Jobs run one at a time on the engine dispatcher, but `prepareImage` is called while inputs are copied, outside that dispatcher. A second job's image preparation, started from another screen while a first job runs, blocks on the lock for up to the first job's 15-minute limit, and its own cancel is not observed until it gets the lock. Reasoned only. | Prepare images on the engine dispatcher too, or use a lock that can be interrupted or cancelled. |

### D. Claims check (README, PRIVACY, Play listing, platform audit)

Holds, checked myself:
- **Zero permissions:** `aapt2 dump permissions` lists none for the debug or release APK (from the `verify.sh` output). The only exported component is `MainActivity`. `EngineService` is `:engine`, `exported=false`.
- **No network:** no `INTERNET` permission.
- **Photo metadata stripping:** unchanged by PI. Normalisation is the same code (`ImageNormalizer.normalizeFile`), now run in the engine.
- **"UI decodes no untrusted image":** the only `BitmapFactory`/`ImageDecoder`/`ExifInterface` user is `core/ImageNormalizer.kt`. Its only production caller is `InProcessImagePreparer`, and that is used only by `EngineServer`. `PdfJobs` hands raw bytes to the engine (`PdfJobs.kt:183-192`).

Sentences that are now inaccurate or incomplete:

| Where | Sentence | Correction |
|---|---|---|
| `docs/play-listing.md:37` | "Merging keeps page content and web links." | Since SF6, Merge copies at most 1,000 external links per page, and every operation refuses a page with more than 10,000 annotations as too large. Suggested wording: "Merging keeps page content and web links (up to 1,000 per page). Form fields, comments and bookmarks are not carried over." No real document is affected, so this is optional. |
| `docs/play-listing.md` "GOOD TO KNOW" | (missing) | Suggest adding: "Very large or unusually complex files are stopped after a time or memory limit." The watchdog (90 s stall, 15 min total, RSS limit) can now end a legitimate but huge job, and FR-1 makes this likelier when the app is left mid-job. |
| `security/platform-audit.md`, Process isolation, "What runs where" | "It also decodes no untrusted image: `scripts/isolation-smoke.sh` checks that the UI process maps no `libmupdf` after launch." | The cited check proves only that the UI process maps no MuPDF. Image decoding uses platform codecs, which a `libmupdf` check cannot see. Cite the code path instead: the only decoder caller is `ImageNormalizer`, reached only from `EngineServer` through `InProcessImagePreparer`. |
| `security/platform-audit.md`, "What it does not protect against" | (missing) | Add FR-1: the stall and total limits count time while the app is frozen in the background, so a good job can end as `TimedOut` (or `EngineCrashed` after an LMK kill) when the user leaves the app mid-job. Add FR-2: if the UI process dies, the engine continues the current call with no watchdog, and a newly started UI can share that process with the orphan job. |
| `security/platform-audit.md`, "Low-memory ordering" | "the engine is killed first" (with the activity in front) | This is correct as measured with the activity in front, but the text should say that in the background both processes are cached, and either may be killed or frozen (FR-1). |
| `security/platform-audit.md`, Watchdog | "(787 MiB on this 2 GB emulator)" | Correct: Part C logged `memoryBytes=825530760` (787 MiB). No change needed. |
| `README.md`, `PRIVACY.md` | none | No inaccurate sentence found. PRIVACY's "Metadata inside the pages of PDFs you give to Merge may also remain" is still true. |

### C. Hostile files through the real app path (FR retry, 2026-11-24)

**Setup.**
- Device: the `pdfutils-smoke` AVD, API 31, x86_64, 2 GB (`MemTotal` 2,015,456 kB). It was started the way the scripts start it (KVM; `sg kvm` fallback). `cached_apps_freezer`: `null` (not set).
- Test: the throwaway instrumented `FrHostileOnDeviceTest`, not committed and removed after the run. Each method was run with `am instrument -e class …#method`.
- Path: each file was copied in through `uiAutomation.executeShellCommand("cat …")`, then run through `PdfJobs.run` with the app's own engine. The log shows `engine=…remote.RemoteEngine` and `limits=EngineLimits(stallMs=90000, totalMs=900000, imageTotalMs=30000, memoryBytes=825530760, memorySampleMs=1000)`, which is 787 MiB and confirms the figure in `platform-audit.md`.
- Compress used HIGH. Merge used the file plus a one-page text PDF.

**Columns.**
- "Peak RSS" is the highest engine `VmHWM`, sampled every 250 ms by the test process. A short spike just before a kill could be missed.
- "UI survived": the test process (pid unchanged, test reported OK).
- "Next job": an ordinary one-page Merge right after the hostile job, with its time and engine pid. In every row `cacheDir/jobs` was empty afterwards.

| File (size) | Op | JobOutcome | Wall time | Peak RSS | UI survived | Next job |
|---|---|---|---|---|---|---|
| `bigarr-20m-1.pdf` (175 KB) | Compress | `Failed(OutOfMemory)` (refused before open; same engine pid) | 0.76 s | 125 MiB | yes | Ready, 61 ms, same engine |
| `bigarr-20m-1.pdf` | Merge | `Failed(OutOfMemory)` | 0.03 s | 138 MiB | yes | Ready, 12 ms |
| `annots-50k-1.pdf` (1.1 KB) | Compress | `Failed(OutOfMemory)` (page over 10,000 annotations refused) | 0.56 s | 137 MiB | yes | Ready, 26 ms |
| `annots-50k-1.pdf` | Merge | `Failed(OutOfMemory)` | 0.03 s | 142 MiB | yes | Ready, 11 ms |
| `wf-20k.pdf` (2.4 MB) | Compress | `Ready` | 3.8 s | 197 MiB | yes | Ready, 18 ms |
| `wf-20k.pdf` | Merge (with a 1-page PDF, **not** with itself as in SR3-4) | `Ready` | 4.2 s | 212 MiB | yes | Ready, 8 ms |
| `bigarr-60m-1.pdf` (525 KB) | Compress | `Failed(OutOfMemory)` (refused before open) | 0.45 s | 121 MiB | yes | Ready, 44 ms |
| `bigarr-60m-1.pdf` | Merge | `Failed(OutOfMemory)` | 0.02 s | 138 MiB | yes | Ready, 12 ms |
| `bigdict-400k.pdf` (945 KB) | Compress | **`Failed(TimedOut)`**: the stall watchdog killed `:engine` (pid 1800, "has died" in the ActivityManager log) | **90.5 s** | 162 MiB | yes | Ready, 427 ms, **new** engine (pid 1820) |
| `bigdict-400k.pdf` | Merge | `Ready` (the unreferenced dictionary is never parsed) | 0.1 s | 138 MiB | yes | Ready, 10 ms |

**Reading.**
- SR3-1 (`bigarr-*`) and SR3-3 (`annots-50k`) are now refused in under 1 s, at about the idle RSS of the engine, without any watchdog action. They are refused by SF6's pre-checks, not by process isolation.
- SR3-2 (`bigdict-400k`) is still uncapped inside the engine. The 90 s stall watchdog ended it exactly as designed, and the next job ran in a new engine.
- The refusals surface as `OutOfMemory` ("Not enough memory. Try fewer or smaller files."). That wording is slightly misleading for a 1 KB file, but it fails safe.
- I did not re-run SR3-4's worst case (`wf-20k` merged with itself, or `wf-50k`). The 4.2 s Merge above does not exercise the merged-save dedup the way SR3 did.

### Not verified
- **Part C limits:** only five SR3 inputs and only Compress and Merge (not Add images), on x86_64 with 2 GB. SR3-4's worst case (`wf-20k` merged with itself, `wf-50k`) was not run. Peak RSS was sampled every 250 ms.
- **FR-1 and FR-2:** reasoned from the code and platform behaviour. I did not reproduce the background freeze, the LMK kill or the orphan engine.
- **FR-9:** reasoned only.
- **Hardware and data:** no real phone, no ARM device, no Android version other than the API 31 emulator image used by the scripts, no real-world large PDFs or camera photos, no low-RAM (2-3 GB) phone under memory pressure.
- **Freezer settings:** whether the cached-apps freezer is on in the scripts' emulator image or on target devices.

### Verdict (FR)
All three gates pass. The process-isolation design is sound for binder trust, paths, request ids and the resend, and I found no High defect in `engine/remote/**`. The build is fit for a Play **closed** test. Before a **public** release, fix FR-1 (the watchdog counts frozen background time, so a good job can end as "taking too long" when the user leaves the app) and FR-2 (an orphan engine job runs with no limit after the UI dies, and shares its process with the next one). Part C on the emulator (done in the FR retry) found no new problem: every SR3 input either failed safely or completed, and in every case the UI survived and the next job succeeded. Repeat it on at least one real ARM phone. Apply the doc corrections in Part D.

### FR follow-up (PI4)

- **FR-1 fixed.** The wait loop discounts gaps of more than 2 s between wake-ups (a frozen app) from the stall, total, image and cancel deadlines, up to 1 hour per call; the clock stays `CLOCK_MONOTONIC` (stops in deep sleep), documented in `RemoteEngine`. JVM tests with an injected clock. Not reproduced with a real freezer.
- **FR-2 fixed.** The engine kills itself (after deleting pending outputs) when a request's callback binder dies or the service is unbound or destroyed while busy; one `EngineServer` per process. JVM tests and an on-device unbind test.
- **FR-3 fixed.** New `PdfError.EngineUnavailable` ("Processing was interrupted. Please try again.") for bind failure or timeout and for a death after a freeze or while the app is cached.
- **FR-4 fixed.** `BoundServiceConnector.disconnect` unlinks the death recipient.
- **FR-5, FR-6, FR-7, FR-8:** not changed (Info, no fix needed).
- **FR-9 fixed.** The request lock is taken with `tryLock(pollMs)`; a waiting call returns `Cancelled` when its job is cancelled.
- **Part D fixed:** Play listing bullets, platform-audit process-isolation text.

### PI4 review (PI4R)

An independent review of the PI4 fixes was done before the redesign review.

- **Verdict:** sound to ship. No Critical, High or Medium findings.
- **FR-1, FR-2, FR-3, FR-4, FR-9:** all confirmed fixed. FR-1 and the real client-death path of FR-2 were tested on the JVM only. Nothing was run against a real freezer.

| Id | Severity | Where | Finding |
|---|---|---|---|
| PI4R-1 | Low | `RemoteEngine.kt:300-316` | Any scheduling gap over 2 s is treated as a freeze. An engine death within 5 s of such a gap is therefore reported as `EngineUnavailable` instead of `EngineCrashed`, and the RSS sample is postponed. Inferred, not reproduced. Suggested fix: require corroboration, such as `inBackground()` or a gap of about 10 s, and do not postpone the RSS sample. |
| PI4R-2 | Info | `EngineServer.kt:136-139` | Any `RemoteException` from `callback.progress` kills the engine. |
| PI4R-3 | Info | `EngineServer.kt:76-87,103-106` | `out` is registered as pending before the path check. Not exploitable: the service is not exported. |
| PI4R-4 | Info | `EngineServer.kt:126-160` | An `Error` thrown before the inner `try` skips `finished(id)`. |

None of these is fixed. They await the owner's decision.

## Redesign review (RD)

**Scope.** `git diff main...redesign` (131 files, +7944/-3540): T1–T11 and one integration fix, fast-forwarded into this worktree. This is a review only; no code was changed. Read in full: `core/JobViewModel.kt`, the `PdfJobs.kt` diff, `ui/components/JobFlow.kt`, `JobState.kt`, `Pickers.kt`, `Selection.kt`, `MergeScreen.kt`, and the ImagesToPdf selection code. Read in part: `JobFlowScreens.kt`, `Reorder.kt`, `HomeScreen.kt`, `Format.kt`, `DisplayNames.kt`, and the About, Home, Support and Onboarding diffs. Also checked: the manifest, `share_paths.xml`, the notices, and strings (main vs redesign vs the pack's `strings.xml`).

### What was checked (evidence)

- **Untouched areas.** `git diff --stat main...redesign -- app/src/main/java/app/venkat/offkit/engine '*.gradle*' gradle/libs.versions.toml scripts` prints nothing.
- **Manifest.**
  - No `uses-permission` is added; the only one is the existing `tools:node="remove"`.
  - `MainActivity` is the only exported component.
  - The provider is `androidx.core.content.FileProvider`, authority `${applicationId}.share`, `exported="false"`, `grantUriPermissions="true"`.
  - `share_paths.xml` has a single `<cache-path name="shared" path="shared/"/>`.
- **Share path containment.**
  - `prepareShare` writes to `File(sharedRoot, UUID)/shareFileName(name)`.
  - `sanitizeDisplayName` (`DisplayNames.kt:58`) replaces `/`, `\` and `:`, and removes control and format characters.
  - `..` becomes `...pdf`, a plain file name.
  - `FileProvider.getUriForFile` refuses anything outside `shared/`; the `IllegalArgumentException` is mapped to `IOException`.
  - The grant is read only (`FLAG_GRANT_READ_URI_PERMISSION`), covers one URI in `EXTRA_STREAM` and `ClipData`, and goes to the app chosen in the chooser (`Pickers.kt:49-57`).
  - No path to a file outside `cacheDir/shared/` was found.
- **Code scans.**
  - No `com.artifex` import outside `engine/`.
  - No `BitmapFactory`, `ImageDecoder`, `ImageBitmap` or `loadThumbnail` in `ui/**` or `feature/**`.
  - The only `takePersistableUriPermission` is the existing one at `core/RecentFiles.kt:64`.
  - No network APIs.
  - No new native libraries: the Gradle files are unchanged and only `.ttf` fonts and XML were added.
- **About.**
  - It uses exactly the same set of string resources as `main`: AGPL text row, `about_mupdf`, `source_code_url`, notices, privacy.
  - The two `THIRD_PARTY_NOTICES.md` files are byte-identical (`cmp`), and both contain Manrope 4.505 under the OFL 1.1 with the full licence text.
- **Errors.** `Format.kt:11-53` is an exhaustive `when` (no `else`) for message, title and body, covering `EngineCrashed`, `TimedOut`, `EngineUnavailable` and `Io`. No string resource is empty except `support_donate_url`, which was already empty on `main`.
- **Defaults.** Compress `CompressionLevel.MEDIUM` (`CompressViewModel.kt:27`). Images `PageSize.FIT_IMAGE` and quality `null` = Original (`ImagesToPdfViewModel.kt:96,101`).
- **Strings.** All 93 pack strings the redesign added match the pack text exactly. None is missing. No existing string changed except to the pack's wording.
- **Links.** The support, home and onboarding diffs add no new intents or URLs. `privacy_policy_url`, `source_code_url` and `support_issues_url` are unchanged.
- **Accessibility.**
  - Icon-only buttons (About, recent Share and Remove) have content descriptions.
  - Tiles and rows are `selectable` and use `cd_selected`.
  - Reordering works through Move up/down/earlier/later buttons.
  - Buttons and chips use `heightIn(min = 48dp)` or larger.
  - Hard-coded colours appear only in `ui/theme`.
  - The only `fontSize` override is the dp-sized decorative onboarding pill (`OnboardingIllustrations.kt:351`).
- **Temp files.**
  - Output is discarded on reset, invalidate, a new job and `onCleared`. During a save, `onCleared` defers the discard to the save's end; this is tested in `JobViewModelTest.clearingDuringSaveStillCompletesTheFile` and `clearingDuringFailingSaveDiscardsOutput`.
  - Leftovers from process death are wiped by the first `PdfJobs.get`, which is lazy: it runs when a tool view model is first created, not at process start. A receiving app that opens a share URI after Offkit was killed therefore does not trigger a wipe.

### Findings (most severe first)

| Id | Severity | Where | What is wrong | Scenario | Confidence | Suggested fix |
|---|---|---|---|---|---|---|
| RD-1 | Medium | `Pickers.kt:83-86`, `JobViewModel.kt:238-241`, `JobState.kt:57` | `Done.shared` is set when the chooser **launches**, not when anything is shared. | Result screen, tap Share, back out of the share sheet without picking an app. The status line now reads `result_shared_only`, Discard has become Done, and Done/Close/Back leave without the SPEC §5 dialog, dropping the unsaved result. | High, from reading. `shareButtonRequestsShare` asserts exactly this (shared after launch only). | Owner decision: either learn the chosen target (`createChooser` with an `IntentSender` to a non-exported receiver, which needs a manifest edit), or keep asking until saved (only `savedUri` suppresses the dialog) and word the status as "Share sheet opened". |
| RD-2 | Low | `PdfJobs.kt` `prepareShare`/`shareFileName`, `DisplayNames.kt:4` | The share name is capped at 120 code points, not bytes. A name of more than about 85 CJK characters (3 bytes each in UTF-8), or of 4-byte emoji, exceeds the 255-byte file-name limit, so `FileOutputStream` fails (ENAMETOOLONG) and Share always shows `result_share_failed`. | Compress (or Add images) a PDF with a long Chinese or Japanese name, then tap Share. | Medium. Confirmed `errno 36 File name too long` for a 125-character CJK name (345 bytes) on the host's Linux filesystem. Not run on a device. | Cap `shareFileName` at about 200 UTF-8 bytes (keeping `.pdf`), or use a short fixed name in the UUID directory and put the display name in the `ClipData` label. |
| RD-3 | Low | `JobViewModel.kt:202-231`, `PdfJobs.prepareShare` | Every Share tap makes a new full copy in `shared/<uuid>/`. Copies are removed only by the next job or the next `PdfJobs.get` in a new process. | A 300 MB Merge result shared 5 times leaves 1.5 GB in the cache until the next job. | High, from reading. | Reuse one copy per output: remember the `ShareRequest` for the current `pending` and return it again. |
| RD-4 | Low | `JobViewModel.kt:157-158`, `JobFlow.kt:135` | A destination that arrives while saving is ignored and its newly created, empty document is not deleted. | Double-tap Save fast enough to start two create-document dialogs, then confirm both. The second one either leaves a 0-byte PDF (first save still running) or silently saves a second copy. | Low, inferred. Not reproduced. | In `save`, call `deleteDestinationIfEmpty(dest)` when ignoring a `dest`, and have the flow ignore Save taps while the saver is open. |
| RD-5 | Low | `strings_design.xml:16` (`recent_empty`) vs `JobViewModel.kt:181` | Pack wording "Files you save or share show up here." does not match the behaviour: only saves are recorded in Recent; shares are not. | Share a result without saving it. It never appears in Recent. | High. | Owner decision: change the wording (it was "Files you save will appear here" on `main`) or record shares. |
| RD-6 | Info | `JobViewModel.kt:111` | `shared/` is wiped at the start of every job (SPEC §5: "until the next launch or job"). A receiving app that reads the URI lazily (a background upload, a Bluetooth transfer) loses it if the user starts another job first. | Share to an uploader, return, run another job at once. | Inferred; matches SPEC. | Accept, or wipe only copies older than a few minutes. |
| RD-7 | Info | `JobFlowScreens.kt:296` | The saved row falls back to `suggestedName` when the provider reports no display name; SPEC §5 says not to guess. | A provider returns no `DISPLAY_NAME`. | High, from reading. | Show a generic `result_saved` line without a name. |
| RD-8 | Info | `ImagesToPdfScreenTest.shareButtonRequestsShare` | Relies on a 5 s wall-clock wait across a real IO copy and recomposition, so it can time out under load. | A loaded CI machine. | Inferred: 3 out of 3 reruns passed in 0.15 s. | Use an injected test dispatcher for `PdfJobs` IO in screen tests, or a longer timeout. |

No Critical or High findings.

### Strings added by the redesign that are not in the pack (owner approval needed)

`addimages_section_count` "Images · %1$d"; `imagestopdf_section_count` "Images · %1$d"; `merge_section_count` "PDFs · %1$d"; `cd_close` "Close"; `cd_list_separator` ", "; `error_engine_crashed_title` "Couldn't process this file"; `error_engine_unavailable_title` "Processing was interrupted"; `error_engine_unavailable_body` "Please try again."; `error_image_too_large_title` "Image is too large"; `error_out_of_memory_title` "Too large or too complex"; `error_out_of_memory_body` "Offkit ran out of memory on this file. Try fewer or smaller files."; `error_timed_out_title` "Taking too long"; `error_unsupported_image_title` "Image format not supported"; `home_wordmark_dot` "."; `processing_add_pages` "Adding pages…"; `recent_meta_two` "%1$s · %2$s"; `result_share_failed` "Could not prepare the file for sharing. Please try again."

### Checks run

- `./gradlew :app:testDebugUnitTest`: BUILD SUCCESSFUL in 3m 20s. 51 suites, 501 tests, 0 failures, 0 errors, 0 skipped (summed from the result XMLs).
- `./gradlew :app:lintDebug :app:compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL in 1m 41s. Lint: 0 errors, 83 warnings (`lint-results-debug.xml`). The warnings were not compared with `main`.
- `./gradlew :app:testDebugUnitTest --tests '*ImagesToPdfScreenTest' --rerun-tasks`, three times: exit 0 each time. The last run had 26 tests, 0 failures, and `shareButtonRequestsShare` took 0.153 s. The test did not reproduce as flaky here. A plausible cause of the one reported timeout (inferred): Share copies the file on the real `Dispatchers.IO`, the first `FileProvider.getUriForFile` parses the provider XML, and the chooser launches from a `LaunchedEffect` after recomposition. All of this has to fit inside the 5 s wall-clock `idleUntil`, which a loaded machine (for example, several Gradle builds in parallel) can exceed.
- `./scripts/verify.sh`: `VERIFY OK`. The APK's native libraries are only `libmupdf_java.so` and `libandroidx.graphics.path.so` for arm64-v8a and x86_64.

### Test gaps

- No test of `shareFileName` with hostile or very long names (`..`, separators, long CJK).
- No test that dismissing the share sheet (as opposed to "no app") keeps the discard dialog (RD-1).
- No test of a second `save(dest)` arriving while saving (RD-4).
- No test of repeated Share disk use (RD-3).
- No rotation or recreation test on the Result screen with the discard dialog open.

### Not checked

- 200% font scale, TalkBack on a device, and comparison with `screens/*.png`.
- SPEC §2–4 and §6, and the bodies of the Compress and Add images screens (only defaults and string usage were checked).
- Onboarding and Support layouts beyond the string and intent diff.
- Nothing was run on the emulator (another worker was using it).
- `PdfJobs.run`'s cancel-versus-completion race was not re-reviewed; it was not changed by the redesign.

### Verdict (RD)

Fit to merge into `main`. The constraints all hold, the gates pass, and there is no Critical or High finding. RD-1 (a dismissed share sheet counts as shared and drops the result without asking) should be decided by the owner before release. RD-2 to RD-5 are small fixes.

## After the redesign review (RD follow-up, PR, T15–T19)

Written 2026-11-25 by the conductor from the workers' reports. This section records what changed after the RD review above; it is a record, not a new review.

### RD findings: what happened

| Id | Status | Note |
|---|---|---|
| RD-1 | **Open (owner decision)** | A dismissed share sheet still counts as shared, so leaving does not ask. |
| RD-2 | Fixed | The share file name is capped at 200 UTF-8 bytes. |
| RD-3 | Fixed | One shared copy per result is reused. |
| RD-4 | Fixed | The flow ignores Save taps while the save dialog is open; an extra empty destination is deleted. |
| RD-5 | **Open (owner decision)** | `recent_empty` says "save or share"; only saves are listed. |
| RD-6 | **Open (accepted so far)** | Shared copies are deleted when the next job starts, as the spec says. |
| RD-7 | Fixed (see PR-1) | The saved row shows a name only when the provider reports one; otherwise "Saved". |
| RD-8 | Fixed | The flake was `FileProvider`'s static cache across Robolectric tests; tests now clear it in set-up. |

### Second review pass (PR)

A worker reviewed the RD fixes and the licence-text reflow. Findings:

| Id | Severity | What | Status |
|---|---|---|---|
| PR-1 | Medium | `savedName` fell back to the last path segment of the Uri ("Saved as 1234") when the provider reported no display name. | Fixed: `PickedFile.nameReported`; a name is shown only when `DISPLAY_NAME` was reported. |
| PR-2 | Low | The licence reflow joined section headings into the next paragraph. | Fixed: headings and numbered headings stay on their own line. |
| PR-3 | Low | The reflow joined copyright lines and C-comment blocks. | Fixed: lines starting with "Copyright"/"©" and comment blocks are kept line by line. |
| PR-4 | Low | A stale shared copy's folder stayed until the next wipe when a copy was recreated. | Fixed: the old folder is deleted first. |
| PR-5 | Info | Deleting an extra destination during a save would also delete an existing 0-byte file the user chose to overwrite. Nearly unreachable behind the UI guard. | Left. |
| PR-6 | Info | The 200-byte name cut is not grapheme-aware (can split a flag pair or Hangul jamo). The result is still a valid file name. | Left. |
| PR-7 | Info | Hints show with zero items and refer to items not yet there (matches design 07c). No test that a disabled button has no icon. | Left. |

### Changes that have had no independent review

These rest on their own tests and on the gates only:

| Task | Change |
|---|---|
| T15 | Pack wording for save failure; separate "no app" messages for Open and Share; share test flake fix. |
| T16 | The PR-1 to PR-4 fixes. |
| T17 | Screen tests save to a destination that reports a display name; a "no name → Saved" test. |
| T18 | Visible Add controls (owner request after a phone test): tonal header pill, a dashed Add tile after the last image, a dashed "Add PDFs" row after the last PDF, scroll to the new item. |
| T19 | Image Add opens the system file picker (`OpenMultipleDocuments`, `image/*`), not the photo picker (owner request). `PRIVACY.md`, its in-app copy, `docs/privacy/index.html` and `docs/play-listing.md` no longer mention the photo picker. |

### Gates on `main` at `c8e0f22` (2026-11-25)

`VERIFY OK`; emulator smoke `tests=31 failures=0 errors=0`, `SMOKE OK`; `ISOLATION SMOKE OK`; `RELEASE OK`; `RELEASE SMOKE OK`.

### Still not checked

- TalkBack on a real phone. The emulator's accessibility dump clips the bottom 126 px of every screen (root bounds 2274 of 2400 px), so focus on the bottom-bar buttons is unconfirmed.
- A receiving app actually reading a shared file.
- The real system save dialog (the test image has none; a stub app stood in for the screenshots).
- The Processing and Error screens as rendered pictures, and content behind the system bars.
- PI4R-1 (Low, `engine/remote`): a scheduling gap over 2 seconds can report a real engine crash as `EngineUnavailable`. Not fixed; the engine was frozen for the redesign.
