# Handoff: Offkit UI redesign

Written 2026-11-24 at the end of the security and process-isolation work. The next piece of work is a new user interface, designed by the owner in Claude Design. This file is what a new session needs to start it.

## State of the project

- **App:** Offkit, package `app.venkat.offkit`. Offline Android PDF utility with four tools: images to PDF, compress, merge, add images to a PDF.
- **Version:** 1.0.0, version code 1. **Not yet uploaded to Google Play.** The redesign ships as part of 1.0.0; do not bump the version.
- **Branch:** `main` at merge commit `c6a0890` is the baseline. All gates pass on it.
- **Release artefacts:** signed APK and bundle built from `c6a0890` are in `dist/release/` (untracked) and in `s3://offkit-artifacts-656490711426/releases/1.0.0/`. They must be rebuilt and re-uploaded after the redesign.
- **Store assets:** app icon, feature graphic, developer icon and header are in `dist/` and under `releases/1.0.0/store/` in the same bucket. Phone screenshots have not been made; make them after the redesign.

## How the owner wants work done

`conductor.md` is the working agreement. In short: the main session is the conductor. It owns the plan, architecture and acceptance criteria and writes no implementation code. It splits work into independent tasks (goal, files in scope, how to verify), launches each with the `worker` sub-agent (`.claude/agents/worker.md`), runs independent tasks in parallel, checks each report against the criteria, sends failures back with specific feedback (maximum two retries, then flag to the owner), runs one review task at the end, and reports what was done, what was not, and what the owner must decide.

Rules that went into every worker brief:

- Never read `/home/ubuntu/keys/` or any `keystore.properties`. Do not run `scripts/release.sh`; the conductor runs it on `main`.
- Start with `git merge --ff-only main`. Commit after each item. Report the branch name and paste the verification output.
- Stay inside the files in scope. Build files are frozen; a worker that needs a new dependency stops and reports.
- Workers stop at 40 turns. Resume one by messaging its agent id; that counts as a retry.
- The conductor does not `git push`. The owner pushes with `! git push origin main`.

## Constraints the redesign must not break

The owner's standing requirement: the app is secure, works offline, and requests the absolute minimum permissions. In practice:

- **Zero permissions.** `scripts/verify.sh` fails the build if the APK declares any `uses-permission`. This rules out a foreground service, notifications that need a permission, network access and analytics.
- **No network.** No fonts, images or data fetched at run time. Bundle every asset; check the licence of any font or icon set and add it to `THIRD_PARTY_NOTICES.md`.
- **Only `MainActivity` is exported.** No `FileProvider`, no new exported components.
- **System pickers only**, with transient grants. Never `takePersistableUriPermission`.
- **MuPDF never loads in the UI process.** The PDF engine runs in a separate `:engine` process. No code outside `engine/` may import `com.artifex.mupdf`; `verify.sh` checks this and `scripts/isolation-smoke.sh` checks the running app.
- **AGPL.** The About screen must keep the licence text, the MuPDF credit and the source link.
- **App size and start-up:** no new native libraries; 64-bit ABIs only.

## What the UI is allowed to touch

The redesign should stay in these places:

- `app/src/main/java/app/venkat/offkit/ui/**`: theme, navigation, shared components, home, onboarding, about, support.
- `app/src/main/java/app/venkat/offkit/feature/*/*Screen.kt`: the four tool screens.
- `app/src/main/res/**`: strings, drawables, theme resources.
- The matching tests under `app/src/test/**/ui`, `app/src/test/**/{imagestopdf,compress,merge,addimages}` and `app/src/androidTest/**`.

Treat these as fixed unless the owner agrees otherwise:

- `engine/**` and `engine/remote/**`: the PDF engine, the isolation layer and its limits.
- `core/**`: `PdfJobs`, `ImagePreparer`, `ImageNormalizer`. Screens reach the engine only through the view models and `PdfJobs`.
- The view models' behaviour: copy inputs into the cache, run the job, ask where to save, clean up. A redesign can change how states are shown, not this flow.
- `AndroidManifest.xml`, Gradle files, `scripts/**`.

Every `PdfError` needs a message on screen. The mapping is in `ui/components/Format.kt` with strings in `res/values/strings.xml`. The isolation work added `EngineCrashed`, `TimedOut` and `EngineUnavailable`; a redesigned error state must still show all of them.

Screen tests are Robolectric tests using `FakePdfEngine` (`app/src/sharedTest`). They find elements mostly by test tag (about 370 uses) and sometimes by text (about 30), so keep the tags where a control survives the redesign; where one does not, the test breaks, and updating those tests is part of each task, not a follow-up.

## Gates

Run on `main` after every merge, in this order:

| Script | Passes when it prints |
|---|---|
| `./scripts/verify.sh` | `VERIFY OK` |
| `sg kvm -c './scripts/emulator-smoke.sh'` | `tests=31 failures=0 errors=0` and `SMOKE OK` (the count rises if tests are added) |
| `sg kvm -c './scripts/isolation-smoke.sh'` | `ISOLATION SMOKE OK` |
| `./scripts/release.sh` | `RELEASE OK` (conductor only; needs `keystore.properties`) |
| `sg kvm -c './scripts/release-smoke.sh'` | `RELEASE SMOKE OK` |

Set `export ANDROID_HOME=$HOME/Android/Sdk` first.

## Quirks of this machine

- 11.6 GB of RAM and no swap. `/tmp` is RAM-backed, so large scratch files there use memory.
- Run at most two Gradle builds at once. Run emulator scripts one at a time with about a minute between them.
- Before an emulator run, stop idle Gradle daemons with `./gradlew --stop`. The one emulator crash seen part-way through the tests went away after memory was freed, so suspect low memory first; that cause is inferred, not proven.
- The emulator must start under `sg kvm -c '...'`.
- The emulator is x86_64 on Android 12. Nothing has been run on a real phone or on ARM.
- S3 uploads need a current AWS SSO login; ask the owner to log in if a copy fails.

## Starting the new session

Open with something like: "Read `conductor.md` and `HANDOFF.md`. You are the conductor. Here is the Claude Design output: ..."

## What the new session needs from the owner

- The Claude Design output: a link or exported files. Nothing from it is in this repo.
- Whether the redesign changes any flow (for example new screens or settings) or only the look. Flow changes may need view-model work and wider tests.
- Whether the onboarding and support screens are in scope.

## Open items carried over

Not blocking the redesign, but still open:

- **No real-phone test yet.** All four tools, the welcome flow, returning from the file picker, and leaving the app mid-job.
- **Watchdog limits** (`engine/remote/EngineLimits.kt`): 90 seconds without progress, 15 minutes total, memory cap of 1.5 GB or 40% of RAM. Unproven against very large legitimate files on slow phones.
- **Merge output size:** duplicate removal is skipped above 5,000 objects; the size effect on ordinary merges is unmeasured.
- **Error wording:** refusals of hostile files say "Not enough memory"; "file too complex" would be clearer. The redesign is a natural time to fix this.
- **Leaving the app during a long job** can interrupt it, because keeping it alive would need a permission. The redesign could say so on the progress screen.
- **Two hostile-file cases not run on a device:** the forms file merged with itself, and Add images with the hostile set. The hostile files and their generators were in the previous session's scratch directory (`/tmp/claude-1000/-home-ubuntu-code-pdf-util/a7d6466d-cdd4-4a97-9575-67ba874d248d/scratchpad/sr3/`), which is RAM-backed and may be gone; `security/engine-audit.md` describes each file well enough to regenerate it.
- **The signed release build has only been launched, never used.** `release-smoke.sh` starts the app and checks it stays up; no gate runs a PDF job in the release build. Code shrinking is off, so the risk is small, but the first real job in the signed APK will be on the owner's phone.
- **The last set of fixes (PI4) has had no independent review.** It changed about 350 lines of the isolation layer after the final review ran; it rests on its own tests and the gates. Include it in the redesign's end review, or review it first.
- **Play Console:** developer account, listing text (`docs/play-listing.md`), screenshots, privacy policy URL, data safety form, then internal, closed (12 testers for 14 days) and production tracks.

## Where to read more

- `REVIEW.md`: review history, including "Final review: process isolation (FR)" and "FR follow-up (PI4)".
- `security/engine-audit.md`, `security/platform-audit.md`: what was audited and what remains.
- `RELEASING.md`: the release procedure.
- `docs/play-listing.md`: store text, including the limits shown to users.
