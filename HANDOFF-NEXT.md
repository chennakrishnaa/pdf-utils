# Handoff for the next session: donation link and next features

Written 2026-11-27. Nothing here is implemented. The owner asked for analysis and a handoff only, and will start a new session for the work. Read `HANDOFF.md` first for the state of the app, the invariants and the gates; `conductor.md` for how the owner wants work run.

State at the time of writing: `main` and `pdf-reader` at `d2a2316`, pushed. Signed 1.0.0 (versionCode 1) built from `4f542db`, in `dist/release/` and `s3://offkit-artifacts-656490711426/releases/1.0.0/`. Nothing has been uploaded to Play.

## Part 1: "Buy Me a Coffee" link in the app

### What exists today

- The link lives in `README.md` (`https://www.buymeacoffee.com/neelima`) and `.github/FUNDING.yml`.
- The app already has the plumbing. The Support page (`ui/support/SupportScreen.kt`) has a "Donate" row (`support_donate_title`, `support_donate_detail`) that is shown only when `support_donate_url` in `res/values/strings_support.xml` is not empty. It is empty today. Links open outside the app with a plain `ACTION_VIEW` (`openLink` in `ui/about/AboutScreen.kt`), so the app stays at zero permissions and makes no network request itself.
- So the code change is one string, plus tests and docs. The question is only whether Play allows it.

### What Google Play's rules say (read 2026-11-27)

- Payments policy, section 4: apps "may not lead users to a payment method other than Google Play's billing system", including through "in-app webviews, buttons, links". The exceptions are section 3 and the regional programmes in sections 8 and 9.
- Section 3 exception: Play billing must not be used for "peer-to-peer payments, online auctions, and tax exempt donations".
- FAQ ("Understanding Google Play's Payments policy"): a tip from a user to a creator counts as a peer-to-peer payment, with no Play billing needed, only when "100% of the tip or contribution from a user goes to the creator" and the payment "does not grant access to any digital content or services (including stickers, badges, special emojis etc.)". If either is not true, Play billing must be used.
- There is no FAQ entry that plainly covers a donation to an app's developer.
- "Tax exempt donations" has, in enforcement, meant validated charities (US 501(c)(3) or local equivalent). An individual developer does not qualify.

### How it has been enforced

- AnkiDroid, 2026: Google rejected updates over an Open Collective donation link, did not accept a 501(c)(6) letter, and scheduled removal from Play. AnkiDroid removed the link from its Play build and was back on Play on 2026-10-24.
- StreetComplete (2022) and a group of open-source apps (2019) had the same thing happen.
- Other small apps do ship a donation link on Play today without trouble. Enforcement is uneven and tends to arrive with an update review.

### Assessment

A Buy Me a Coffee link inside the Play build is a real risk, not a clear violation and not clearly allowed.

- **For it:** it is a voluntary tip, unlocks nothing, opens in the browser.
- **Against it:**
  - Buy Me a Coffee keeps a platform fee (5% at last knowledge; check), so "100% goes to the creator" is arguably false.
  - A Buy Me a Coffee page can sell memberships and extras. If the page offers any, the "no digital content" condition fails.
  - The account name on the page (`neelima`) differs from the Play developer name. A reviewer could read that as a payment to a third party. The owner should decide whether that matters.
  - Google's reviewers have treated developer donation links as "directing users to payment methods other than the Google Play billing system" regardless of the FAQ.
- **Cost if it goes wrong:** a rejected update or a removal notice with a deadline, on a brand-new developer account. Fixing it means shipping a build without the link.

### Options

| Option | Risk | Notes |
| --- | --- | --- |
| A. Keep the link out of the Play build; keep it in the README, the GitHub repo and the store listing's website/GitHub page | None | Recommended for 1.0.0. The Support page already links to the source code on GitHub, where the coffee link is. |
| B. Play build without the link; a second build flavour (GitHub releases, F-Droid) with it | None on Play | What AnkiDroid ended up doing. Needs a product flavour: Gradle files are frozen for workers, so the conductor or owner must unfreeze them for this task. |
| C. Ship the link in the Play build | Medium | Before doing it: ask Play Console support in writing; make sure the page has no memberships, extras or rewards; keep the wording to "tip", promise nothing in return; have option A ready as the fallback build. |
| D. Tips through Play Billing (one-time in-app products) | None on policy | Breaks the app's promises: needs the Play Billing library, the `com.android.vending.BILLING` permission and network use by Play services. Google keeps 15%. Not recommended for this app. |

Recommendation: A now, B if the owner wants in-app tips for people who install from GitHub, and C only after a written answer from Play support.

### If the owner chooses C (or B's non-Play flavour)

- Set `support_donate_url`; check the row's wording; add or adjust the Support screen test that asserts the row appears and opens the link through `ACTION_VIEW`.
- `PRIVACY.md`, its asset copy and `docs/privacy/index.html`: say the app has a link that opens a third-party page in the browser, and that the app itself sends nothing.
- `docs/play-listing.md`: do not mention donations in the listing text (section 4 also covers the listing).
- Do not put the link on the home screen card (`HomeSupportCards.kt`) in the first version; keep it on the Support page only.
- Invariants unchanged: zero permissions, no network, only `MainActivity` exported.

## Part 2: features users want that other apps lock or do badly

### Evidence and its limits

No ranked survey of mobile PDF needs was found. The picture below comes from store reviews of the big apps, review sites, the feature lists of the few apps that advertise "offline, no subscription", and one office survey (ABBYY, 500+ employees: adding, deleting and reordering pages was among the five most common PDF tasks). Treat the ranking as informed judgement. The Reddit searches returned nothing usable.

What reviewers complain about most in iLovePDF, Adobe Acrobat, Xodo and similar:

- Features that were free moved behind a subscription after an update (merge, images to PDF, batch work).
- Daily limits, watermarks on exported files, account required to export.
- Files uploaded to a server to be processed.
- Ads, and requests for full storage access.

Offkit already answers the second half of that list by design. The gap is the tool list: today it has Images to PDF, Compress, Merge, Add images and the reader.

### Candidates, ranked

Fit means how well it sits with the existing design: MuPDF 1.28.5 in the `:engine` process, system file picker only, zero permissions, no network.

| # | Feature | Demand | Elsewhere | Fit and effort |
| --- | --- | --- | --- | --- |
| 1 | Organise pages: split, extract a range, delete, rotate, reorder | High | Often paywalled or limited on mobile | Good. Page operations are native to MuPDF; the reader already renders pages for thumbnails. Medium. One new engine op plus a page-grid screen. |
| 2 | Sign: draw a signature and place it on a page | High | Free in Adobe with an account push; watermarked or capped in others | Good. Stamp an image or ink onto a page. Medium. The owner has a document "Drawn Signatures: Requirements Spec" (2026-11-23) in their artifacts: read it first, it may be for this. Call it "sign by drawing", never a digital or certified signature. |
| 3 | Protect with a password, and remove a password you know | Medium to high | Commonly paywalled | Very good. Removing is already built (`DecryptCopyOp`, used for printing); adding is a MuPDF save option. Small to medium. The other tools still refuse encrypted PDFs; this gives users the way through. |
| 4 | PDF to images (JPG or PNG per page) | Medium | Usually free but capped or watermarked | Very good. Rendering exists. Small. Needs output limits like the other ops. |
| 5 | Scan to PDF with the camera | High | Core of CamScanner-type apps, with watermarks and subscriptions | Mixed. The `CAMERA` permission breaks "zero permissions". `ACTION_IMAGE_CAPTURE` needs none and feeds the existing Images to PDF, but gives no edge detection or perspective fix, which is the "sloppy" version users complain about. Google's ML Kit document scanner needs Play services and is closed source. Decide the trade-off before building. |
| 6 | Fill in forms | Medium to high | Free in Adobe; uneven elsewhere | MuPDF supports form widgets, but a good editing UI is large. Large. Do after 1 to 3. |
| 7 | Reader comforts: remember the last page, night mode, outline/bookmarks, page thumbnails | Medium | Free everywhere; Offkit's reader lacks them | Good. Small each. Makes the reader worth choosing as the default PDF app. |
| 8 | Highlight and annotate | Medium | Free in Xodo and Adobe | Large: editing model, undo, saving back into the file. Later. |
| 9 | Page numbers, text watermark | Low to medium | Often paywalled | Small to medium. Later. |
| 10 | OCR (searchable scans) | Medium | Paywalled almost everywhere | Poor for now: needs a bundled recognition model (large APK) and heavy processing. Not without a clear plan. |
| 11 | PDF to Word, edit existing text | High in reviews | Done badly by nearly everyone | Do not attempt. No offline engine does this well; a poor result costs more trust than it earns. |

### Suggested order

1. **Organise pages** (1). Largest gap, best fit.
2. **Password protect / remove** (3) and **PDF to images** (4). Cheap, reuse what exists.
3. **Sign by drawing** (2).
4. Reader comforts (7) as fillers between the larger items.
5. Decide scanning (5) and forms (6) after those ship.

### Release question for the owner

Either ship 1.0.0 as built and add features in 1.1, or hold 1.0.0 for item 1. Shipping first is recommended: the signed build is tested, a new developer account benefits from an early clean review, and every item above is an addition that changes no existing behaviour.

### Notes for whoever builds these

- Every new tool is a new `EngineRequest` and op under `engine/ops/`, checked by `EngineServer.checkPaths`, with limits in `EngineLimits` and a watchdog time. Follow `MergeOp` and `DecryptCopyOp` (including the output size watch from P2/Y2).
- New ops that write a PDF from an untrusted one need the same refusals the existing tools apply, or a stated reason why not. "Remove password" is the one tool that must accept encrypted input.
- Every new `PdfError` needs a title and body in `ui/components/Format.kt`.
- New icons: the owner draws them in their design tool; do not add Material icons (see `THIRD_PARTY_NOTICES.md` section 3).
- Home screen: the tool grid is 2 columns of 4 cards plus the reader. Seven or more tools needs a layout decision from the owner's design.
- The privacy policy and the store listing must be updated for each feature that changes what the app does with files (for example an unprotected copy from "remove password").
- Gates before any merge to `main`: `./scripts/verify.sh`, `sg kvm -c './scripts/emulator-smoke.sh'`, `sg kvm -c './scripts/isolation-smoke.sh'`; for a release also `release.sh` and `release-smoke.sh`. Bump `versionCode` once 1.0.0 is on Play.

## Decisions the owner needs to make

1. Donation link: option A, B, C or D above.
2. Ship 1.0.0 now, or hold it for "organise pages".
3. Which features, in which order.
4. Scanning: accept the `CAMERA` permission, use the no-permission camera intent, or leave scanning out.
5. Whether "Drawn Signatures: Requirements Spec" is the spec for the signing feature.

## Sources

- Google Play Payments policy: https://support.google.com/googleplay/android-developer/answer/9858738
- Understanding Google Play's Payments policy (FAQ): https://support.google.com/googleplay/android-developer/answer/10281818
- US policy update (external links, alternative billing): https://support.google.com/googleplay/android-developer/answer/15582165
- AnkiDroid donation link rejection: https://github.com/ankidroid/Anki-Android/issues/21656
- Open-source apps removed over donation links (2019): https://news.ycombinator.com/item?id=21268389
- iLovePDF review summaries: https://appgrooves.com/android/com.ilovepdf.www/ilovepdf-pdf-editor-and-reader/ilovepdf/negative and https://www.g2.com/products/ilovepdf/reviews?qs=pros-and-cons
- ABBYY survey on PDF use: https://pdf.abbyy.com/blog/who-uses-pdf-and-why/
- Adobe mobile research: https://www.adobe.com/acrobat/resources/mobile-tech-troubles-research.html
- Offline, no-subscription competitors: https://play.google.com/store/apps/details?id=com.noadspdf and https://f-droid.org/en/packages/org.fossify.documents/
