# Handoff: Offkit after the UI redesign

Written 2026-11-25 at the end of the UI redesign. The redesign is merged, built, signed and uploaded. What remains is the owner's decisions below, a real-phone check and the Play Console work. Updated 2026-11-26: the reader on `pdf-reader` gained a menu, Go to page, password-protected PDFs, search, share and print (K1 to K5, merged at `f016e77`); their review (K7) found them sound for phone testing, with a merge blocker (K7-1) since fixed in M1; M2 is in progress (see "PDF reader").

## State of the project

- **App:** Offkit, package `app.venkat.offkit`. Offline Android PDF utility with four tools on `main`: images to PDF, compress, merge, add images to a PDF. A fifth, an in-app PDF reader, is on branch `pdf-reader` (see "PDF reader" below).
- **Version:** 1.0.0, version code 1. **Not yet uploaded to Google Play.** Do not bump the version before the first upload.
- **Branch:** `main` contains the redesign (merge commit `c8e0f22`) and the follow-up fixes made the same day: share-sheet fix, engine watchdog fix, dependency and unused-resource removal, wording. `dist/release/BUILD_INFO.txt` names the commit the signed build was made from.
- **Branch `pdf-reader`:** the PDF reader, in the worktree `.claude/worktrees/pdf-reader`. **Not merged to `main`** and not in the signed 1.0.0 build.
- **Release artefacts:** signed APK and bundle are in `dist/release/` (untracked) and in `s3://offkit-artifacts-656490711426/releases/1.0.0/`, with `SHA256SUMS` and `BUILD_INFO.txt`. The bucket is versioned; the pre-redesign build is an earlier version of the same keys.
- **Store assets:** the new app icon and feature graphic (from the design pack) are in `dist/icon/` and under `releases/1.0.0/store/`. The developer icon and header in `dist/developer/` and `store/developer/` are unchanged. `dist/icon/preview-*.png` still show the old icon. **Phone screenshots have not been made.**
- **Design pack:** `offkit-handoff/` (untracked). `SPEC.md` is the source of truth for the screens.
- **Branch builds:** debug APKs of the `redesign` branch are under `s3://offkit-artifacts-656490711426/branches/redesign/<commit>/`. They can be deleted once the release is on Play. Debug builds of `pdf-reader` are under `s3://offkit-artifacts-656490711426/branches/pdf-reader/<commit>/` (the first one: `8658d50`).
- **Left on disk:** the `redesign` branch and its worktree at `.claude/worktrees/redesign`, plus workers' `worktree-agent-*` branches. All are merged and can be removed.

## What the redesign changed

- **Look:** design pack "Option B Paper light". `OffkitTheme`, light only, bundled Manrope font, no dynamic colour. Every screen is rebuilt or restyled, including Onboarding, About, Licence text and Support.
- **Result flow:** shared Processing, Result and Error screens (`ui/components/JobFlow.kt`, `JobFlowScreens.kt`, `JobState.kt`). Save as, Save another copy, Share, Open, and a discard dialog. The job output is kept after a save until the result is left or the next job starts.
- **Share:** one **non-exported** `FileProvider` (authority `${applicationId}.share`) that exposes only `cache/shared/`. `PdfJobs.prepareShare` copies the output there; the folder is wiped at the next job start and at the first `PdfJobs.get`. The owner approved this provider.
- **Add controls (owner request after a phone test):** tonal "Add" pill in the section header, a dashed Add tile after the last image, a dashed "Add PDFs" row after the last PDF.
- **Image picker (owner request):** the system file picker (`OpenMultipleDocuments`, `image/*`), so any folder can be browsed. The photo picker is no longer used. The file picker does not promise selection order.
- **Thumbnails are placeholders.** The UI process decodes no images; a real preview would mean decoding untrusted images outside the `:engine` process.
- **Core changes the owner approved:** `core/JobViewModel.kt`, `core/PdfJobs.kt`, `core/UriIo.kt`, and the manifest's provider entry. The redesign left `engine/**`, `scripts/**` and all Gradle files unchanged. Branch `pdf-reader` does change `engine/` (new render ops, the image gate, the scan memo, reader requests in `engine/remote/`) and `core/` (`PdfReader.kt` new; `PdfJobs.kt`, `UriIo.kt`); `scripts/**` and Gradle files are still unchanged there.

## How the owner wants work done

`conductor.md` is the working agreement. In short: the main session is the conductor. It owns the plan, architecture and acceptance criteria and writes no implementation code. It splits work into independent tasks (goal, files in scope, how to verify), launches each with the `worker` sub-agent (`.claude/agents/worker.md`), runs independent tasks in parallel, checks each report against the criteria, sends failures back with specific feedback (maximum two retries, then flag to the owner), runs one review task at the end, and reports what was done, what was not, and what the owner must decide.

Rules that went into every worker brief:

- Never read `/home/ubuntu/keys/` or any `keystore.properties`. Do not run `scripts/release.sh`; the conductor runs it on `main`.
- Start with `git merge --ff-only <integration branch>`. Commit after each item. Report the branch name and paste the verification output.
- Stay inside the files in scope. Build files are frozen; a worker that needs a new dependency stops and reports.
- Workers stop at 40 turns. Resume one by messaging its agent id; that counts as a retry.
- The conductor does not `git push`. The owner pushes with `! git push origin main`.

## Constraints that still hold

The owner's standing requirement: the app is secure, works offline, and requests the absolute minimum permissions. In practice:

- **Zero permissions.** `scripts/verify.sh` fails the build if the APK declares any `uses-permission`. This rules out a foreground service, notifications that need a permission, network access and analytics.
- **No network.** No fonts, images or data fetched at run time. Bundle every asset; check the licence of any font or icon set and add it to `THIRD_PARTY_NOTICES.md`.
- **Only `MainActivity` is exported.** The share `FileProvider` is not exported and must stay limited to `cache/shared/`. No other new components. On `pdf-reader`, `MainActivity` also has a `VIEW` filter for `content:` `application/pdf` ("Open with") and is `singleTask`; the SB-13 intent scrub still runs before `super.onCreate` (`security/platform-audit.md`, "Reader 'Open with' addendum").
- **System pickers only**, with transient grants. Never `takePersistableUriPermission` (the one in `core/RecentFiles.kt` predates this rule and is left).
- **MuPDF never loads in the UI process.** The PDF engine runs in a separate `:engine` process. No code outside `engine/` may import `com.artifex.mupdf`; `verify.sh` checks this and `scripts/isolation-smoke.sh` checks the running app.
- **No image decoding in the UI process.** The reader (`pdf-reader` branch) keeps to this: MuPDF renders each page in the `:engine` process and the UI process only copies the raw pixels it sends into a bitmap.
- **Reader copy lifetime (`pdf-reader` branch).** The picked PDF, or one opened from another app, is read once with its transient grant and copied to `cacheDir/jobs/reader-<uuid>/`; the Uri is not kept. The copy stays while the reader is open, also in the background, and is deleted when the reader is closed, or when another PDF opened in it has opened or failed. While a newly picked PDF waits for its password, the old one stays open with its copy until the new one opens or fails or the prompt is cancelled; the waiting PDF's copy is deleted on cancel. A copy left by a killed process is deleted by the first `PdfJobs.get` in the next process (the first tool or reader opened). `PRIVACY.md` describes this.
- **AGPL.** The About screen must keep the licence text, the MuPDF credit and the source link.
- **App size and start-up:** no new native libraries; 64-bit ABIs only.

Every `PdfError` needs a title and body on screen. The mapping is `pdfErrorTitle` / `pdfErrorBody` in `ui/components/Format.kt`.

Screen tests are Robolectric tests using `FakePdfEngine` (`app/src/sharedTest`). They find elements mostly by test tag; keep the tags where a control survives a change, and update the tests in the same task.

## Gates

Run on `main` after every merge, in this order:

| Script | Passes when it prints |
|---|---|
| `./scripts/verify.sh` | `VERIFY OK` |
| `sg kvm -c './scripts/emulator-smoke.sh'` | `tests=31 failures=0 errors=0` and `SMOKE OK` on `main`; `tests=43` on `pdf-reader` at `f016e77` (the count rises if tests are added) |
| `sg kvm -c './scripts/isolation-smoke.sh'` | `ISOLATION SMOKE OK` |
| `./scripts/release.sh` | `RELEASE OK` (conductor only; needs `keystore.properties`) |
| `sg kvm -c './scripts/release-smoke.sh'` | `RELEASE SMOKE OK` |

Set `export ANDROID_HOME=$HOME/Android/Sdk` first.

## Quirks of this machine

- 11.6 GB of RAM and no swap. `/tmp` is RAM-backed, so large scratch files there use memory.
- Run at most two Gradle builds at once. Run emulator scripts one at a time with about a minute between them.
- Before an emulator run, stop idle Gradle daemons with `./gradlew --stop`.
- The emulator must start under `sg kvm -c '...'`.
- The emulator is x86_64 on Android 12. Its image (`pdfutils-smoke`) has no SystemUI and no system file picker, so the real picker and save dialog cannot be exercised there. A throwaway stub picker app (`stub.docs`) and two sample PDFs in Downloads were left on the image after the screenshot runs.
- A session working inside a git worktree must run plain, separate `git` commands (no `git -C`, no variables in the command).
- S3 uploads need a current AWS SSO login; it expires within hours. Ask the owner to run `! aws sso login --use-device-code` if a copy fails.

## Decisions waiting on the owner

1. **Icon licence (blocks the Play upload).** `THIRD_PARTY_NOTICES.md` still credits Material Symbols for icons the design pack replaced. The pack icons' origin and licence are needed, and the Manrope version line ("4.505") should be confirmed.
2. **Dismissed share sheet (RD-1): fixed.** Only a save protects a result now; an unsaved result always asks before it is discarded, whether or not Share was used. The discard dialog reads "It hasn't been saved. Your original file isn't affected."
3. **Recent wording (RD-5): decided.** The empty state now reads "Files you save show up here."
4. **Shared copies (RD-6).** They are deleted when the next job starts, as the spec says; an app that reads the file late could lose it.
5. **PI4R-1: fixed and reviewed (sound to ship).** An engine death is reported as "interrupted" only when the app is in the background or within 5 seconds of a pause of at least 10 seconds. The follow-up T22R-1 is fixed too: a pause over 2 seconds that began while the app was in the background also counts as a freeze. Tested on the JVM only, never against a real device freeze.
6. **Thumbnails.** Keep placeholders, or choose a route for real previews that keeps decoding in the `:engine` process.
7. **Unused dependency: removed.** `material-icons-core` is gone from the Gradle files and from the notices.
8. **Wording not in the pack.** 21 strings were added; the list is in `REVIEW.md` under "Strings added by the redesign that are not in the pack".
9. **Deviations from the pictures.** Merge shows no page counts before the job; the onboarding hero lost its blue background, arrow and label animation; the licence text is not monospace and is reflowed for display; quality rows show nothing while an estimate loads; a Compress result with no size gain cannot be saved; the Result screen returns to Home on Done, Close and Discard.
10. **Add images with no PDF chosen** shows two dashed boxes (the PDF slot and the Add tile). Say if that looks cluttered.
11. **Reader: first upload or later.** Ship the reader in the first Play upload or in a later version. The signed 1.0.0 build and `main` do not contain it, but `docs/play-listing.md` on `pdf-reader` already describes it; the listing must match whichever build is uploaded.
12. **Reader: Home entry.** It is a fifth, full-width card under the 2x2 tool grid. Confirm or choose another place.
13. **Reader: Recents and Open.** Recents and the Result screen's Open still hand the file to an external viewer. Switch them to the in-app reader or not.
14. **Reader: "Open with Offkit": reversed by the owner and built (H1).** `MainActivity` has a `VIEW` filter for `content:` `application/pdf` and is `singleTask`. Two consequences for the owner to review: (a) Back from the reader goes to Offkit's previous screen (Home, or the screen that was open), not to the app that sent the PDF; (b) Offkit is now one of the apps that can answer its own Open on a saved result and in Recents, which then opens the in-app reader.
15. **Reader: password-protected PDFs: built (K1, K3), reader only.** The tools still refuse every encrypted PDF. Keep passwords reader-only, or extend them to the tools.
16. **Reader: cancelled picker on the error screen.** Cancelling the picker opened from the reader's error screen stays on the error screen.
17. **Reader wording.** `res/values/strings_reader.xml` and `strings_reader_zoom.xml`.
18. **Reader: review of F4.** F4 reworked the image gate's walk after the second review and has had no independent review. Decide whether it needs one before release.
19. **Reader: no-copy and no-print bits.** A protected PDF's permission bits (`canPrint`, `canCopy`) are read but not enforced: text can be copied, the file shared and an owner-password-only PDF printed. Enforce them or not.
20. **Reader: `FLAG_SECURE` for protected PDFs.** Not set, so the password prompt and the pages of a protected PDF can appear in screenshots and the Recents thumbnail.
21. **Reader: printing protected PDFs.** A PDF opened with a password cannot be printed (a message says so), because the system print spooler renders the file without the password. Supporting it would need a decrypt step in the engine.
22. **Reader: owner-password-only printing.** A PDF with only an owner password opens without a prompt and is allowed to print. Unverified on a device. Keep or refuse.
23. **Reader: Offkit in its own share chooser.** Decide whether Offkit may appear in the chooser of its own Share or should be excluded. Share sends `ACTION_SEND` and the manifest has only a `VIEW` filter, so it is not expected to appear today; confirm on a phone (Open on a result and in Recents can offer Offkit, decision 14).
24. **Reader: image gate: keep patching or switch to a conservative design** (see `REVIEW.md`, "Review (K1–K5)", Pattern). Three reviews in a row found a way past the gate after a rework; M2's assessment is recorded there: a conservative design would remove the class of bug at the cost of refusing a few percent of scanned books with shared resources.

## Open items

- **Real-phone checks.** The owner has tried the debug build on a phone and asked for the Add controls and the folder picker. Still unconfirmed: TalkBack focus on the bottom-bar buttons (the emulator's accessibility dump clips the bottom 126 px, probably the test image), a receiving app reading a shared file, the real save dialog end to end, content behind the system bars, the Processing and Error screens, and the notices screen's opening time (about 2 seconds on the emulator).
- **The signed release build has only been launched, never used.** `release-smoke.sh` starts the app and checks it stays up; no gate runs a PDF job in the release build.
- **No independent review** of the last changes: the saved-name rule, the licence reflow rules, the Add controls and the picker change. See `REVIEW.md`, "After the redesign review".
- **Watchdog limits** (`engine/remote/EngineLimits.kt`): 90 seconds without progress, 15 minutes total, memory cap of 1.5 GB or 40% of RAM; on `pdf-reader`, 30 seconds per page render, 30 seconds per page text and 60 seconds to open a document in the reader. Unproven against very large legitimate files on slow phones.
- **Merge output size:** duplicate removal is skipped above 5,000 objects; the size effect on ordinary merges is unmeasured.
- **Leaving the app during a long job** can interrupt it, because keeping it alive would need a permission. The Processing screen says to keep the app open.
- **Two hostile-file cases not run on a device:** the forms file merged with itself, and Add images with the hostile set. `security/engine-audit.md` describes each file well enough to regenerate it.
- **Unused resources: removed.** 69 unused pack resources were deleted; lint now flags only `font/manrope.xml`, kept on purpose.
- **Play Console:** developer account, listing text (`docs/play-listing.md`), phone screenshots, privacy policy URL, data safety form, then internal, closed (12 testers for 14 days) and production tracks.

## PDF reader

Built on branch `pdf-reader` after the 1.0.0 build; not merged. Reviewed twice; the fixes F1 to F4 are merged on the branch, and the last of them (F4) has had no independent review. K1 to K5 (menu and Go to page, passwords, password prompt, search, share and print) are merged at `f016e77`; emulator smoke there: `tests=43`. Review K7 is done (see below). At `6adc734` `VERIFY OK`, the emulator smoke (`tests=32`) and `ISOLATION SMOKE OK` pass; two on-device reader tests against the real engine were added afterwards (`tests=34`). H1 (Open with), H2 (engine page text) and H3 (selection and copy) are merged at `561c0e6`; emulator smoke there: `tests=43`. **It has never been tried on a real phone.**

- **What it does:** a fifth Home card, "Read a PDF", below the tool grid. It opens the system file picker for one PDF and shows its pages in a continuous vertical list with pinch-to-zoom (1x to 5x), double-tap zoom, a page indicator and "Open another PDF". Read-only: nothing is changed or saved, and the file is not added to Recents. A PDF can also be opened from another app ("Open with", H1). Long-press selects a word, handles extend the selection, and a contextual top bar offers Close, Select all and Copy; plain text goes to the system clipboard, one page at a time, and a page with no text shows a message. An accessibility action "Copy page text" copies a whole page (H3). A top-bar overflow menu holds the reader's actions; Go to page (also opened by tapping the page indicator) jumps to a page (K2). A password-protected PDF asks for its password, with unlimited attempts; a PDF with only an owner password opens without a prompt (K1, K3). Search finds text case-insensitively, whitespace matching line breaks, scanning from the current page and wrapping, up to 10,000 hits (K4). Share sends the original file (still protected if it was) through the share sheet; Print hands it to Android's print system; a PDF opened with a password cannot be printed yet (K5).
- **How:** MuPDF renders pages in the `:engine` process (`engine/PdfPageRenderer.kt`, `engine/ops/RenderPageOp.kt`, new calls in `engine/remote/`) and sends raw pixels to the UI. `core/PdfReader.kt` holds the private copy and runs reader calls on `PdfJobs`' engine thread. `feature/reader/` has the screen, `ReaderViewModel` and the render planner; `ui/reader/` has the zoomable list and its maths. Page text comes from `PdfPageRenderer.pageText` (`engine/ops/PageTextOp.kt`, file format "OKTX" in `engine/PageText.kt`, `EngineRequest.PageText`, `PdfReader.text`), after the same pre-checks as a render including the image gate, capped at 200,000 UTF-16 units per page (marked truncated), 30 s per request; the view model keeps at most 8 pages / 400,000 characters of text (`ReaderSelection.kt`). A reader restored after process death without its document goes Home instead of opening the picker (`SavedStateHandle`). Passwords (K1, K3): `PdfInputs.openForReader` in the engine, `PdfError.WrongPassword`, `ObjStmScan` pass 2 on decrypted object streams, `ScanMemo` keyed also on a password hash; the password (at most 127 bytes) travels in the request Bundle, a deliberate exception to "only paths travel", and is kept in memory in the open `ReaderDocument` only, never in saved state; `feature/reader/PasswordPrompt.kt`, `ReaderDocState.Locked`. Menu and Go to page: `feature/reader/ReaderMenu.kt`, `GoToPage.kt` (K2). Search: `feature/reader/ReaderSearch.kt`, `ReaderSearchBar.kt`; runs in the UI process over page text, the query never reaches `:engine` and is not stored (K4). Share and print: `feature/reader/ReaderShare.kt` copies to `cache/shared/<uuid>/<name>` for the existing share provider (deleted at the next job start or app start, as RD-6); `ReaderPrint.kt` gives the private copy's bytes to a `PrintDocumentAdapter`, and the system spooler renders it (K5). No new permission, component or network access. PDFs over 1 GiB are refused before or during the copy, as in the tools.
- **Image gate:** before a page is drawn, `engine/ops/RenderImageGate.kt` checks the images it can draw (through forms, patterns, soft masks, Type 3 fonts and annotation appearances). A page is refused as "too large or complex" when an image is over 256 MiB decoded, a JPX image over 64 M samples, a JPEG or JPX stream over 32 MiB or with an unreadable header, a JPEG behind any filter but one Flate, or the walk over its budget (20,000 visits, 2 s); the full list is in `REVIEW.md`.
- **Speed:** `ScanMemo` (`engine/PdfInputs.kt`) remembers the passed pre-scan and image-gate pages of the open document, so each tile does not re-scan the whole file. The viewport is reported 120 ms after it settles. Wide items get a sharper detail render at zoom 1.
- **Odd page sizes:** page sizes are clamped to 1 to 2,880,000 pt; items are at most 60,000 px tall and 8,191 px wide, and a longer page is shrunk into its item.
- **New icons:** `ic_tool_read.xml`, `ic_tool_read_badge.xml`, `ic_folder_open.xml`, `ic_refresh.xml`, drawn for the project, and from K2 and K4 `ic_more_vert.xml`, `ic_search_previous.xml`, `ic_search_next.xml`; `THIRD_PARTY_NOTICES.md` names them all as original. `ic_folder_open.xml` is unused since K2.
- **Review of H1 to H3 (`REVIEW.md`, "Review (H1–H3)"):** sound for phone testing; the merge blocker H5-1 was fixed afterwards in J1 (with H5-6 and H5-7); the J1 gate change has had no independent review. J2 fixed H5-2 (copy capped at 100,000 characters, a refused clipboard shows a message, highlight rectangles bounded), H5-3 (no provider call on the main thread: an untyped `VIEW` has its type checked off the main thread, 3 s limit, before any copy), H5-4 (a `VIEW` relaunched from Recents is ignored) and H5-9 (the flaky `reopeningTheSameFileShowsItAgain`: folder deletion only on the engine thread). H5-5, H5-8 and H5-10 are open as notes.
- **Open items (K1 to K5):** not tried on a phone. Phone checklist: open a PDF with "Open with" from a file manager; select and copy text; search a long PDF; Go to page; a protected PDF with a wrong, then the right password; Share; Print to "Save as PDF" with a page subset; cancel a print. Also: the search typing delay and TalkBack on search are untested; the password dialog's busy state, keyboard and Back are untested on a device; search measured about 26 ms per page on the build machine (300 pages about 8 s; `ReaderSearchCostTest` is ignored); printing is JVM-tested only; hint text may be cut off on narrow screens; a page-indicator quirk on a short last page in Go to page. K1 gaps: an object stream whose header number differs from the xref can be loaded by pass 2's lookup before it is measured; a damaged owner-password-only PDF is repaired inside `openDocument` before pass 2; non-BMP characters in AES-256 passwords may not match. Owner decisions 15 and 19 to 24 above.
- **Review K7 of K1 to K5 (`REVIEW.md`, "Review (K1–K5)"):** sound for phone testing; not to be merged to `main` until K7-1 (image gate bypass through a visited set shared across roles) is fixed. K7-1 and K7-2 (`ObjStmScan` ignored `/F`) are fixed in M1, and K7-3 is mitigated there. M2 is in progress for two further gaps (the gate trusting `/Subtype`; `ObjStmScan` pass 1 not tracking nesting). Neither M1 nor M2 has had an independent review. M2 fixed the two further gaps (the gate trusting `/Subtype`; `ObjStmScan` pass 1 ignoring dictionary nesting); M1 and M2 have had no independent review.
- **L1:** a race in `OpenWithIntentTest.unreadableDocumentShowsTheReadersErrorScreen` (one failure at `f016e77`) fixed in the test; the product is unchanged.
- **Open items (H1 to H3):** not tried on a phone; the "Copied" message shown below Android 13 is untested on a device; a selection handle can overhang into the next page; selection across pages not built; search was added in K4.
- **Open items (earlier):** the owner's decisions 11 to 18 above; the open findings in `REVIEW.md` (RDR-6 copy progress, RDR-7 tests, RF-4, RF-6 remainder, RF-8); not tried on a real phone (pinch and pan feel, performance on large PDFs, low memory, content behind the system bars, TalkBack, the real picker, text without embedded fonts including CJK); a very tall strip may draw blank on a GPU with a smaller texture limit; the render path has not been run against the hostile-file set.

## Where to read more

- `REVIEW.md`: review history, including "Redesign review (RD)", "After the redesign review" and, on `pdf-reader`, "PDF reader review (RDR, RF)" "Reader: open with and copy text (H1–H3)" and "Reader: menu, go to page, passwords, search, share, print (K1–K5)" at the end.
- `security/engine-audit.md`, `security/platform-audit.md`: what was audited and what remains. The platform audit has a redesign addendum for the share provider and the picker change. On `pdf-reader` the engine audit ends with "Reader render path", "Reader text path" and "Reader password addendum", and the platform audit with a "Reader 'Open with' addendum" and "Reader share, print and password prompt".
- `RELEASING.md`: the release procedure.
- `docs/play-listing.md`: store text, including the limits shown to users.
- `offkit-handoff/SPEC.md`: the design spec.
