# Offkit engine security audit: untrusted-input path (SA)

Auditor: SA, independent review of `app/src/main/java/app/venkat/offkit/engine/**`, `core/ImageNormalizer.kt`,
`core/PdfJobs.kt` and `core/UriIo.kt` at `main` (merged ff-only), 2026-11-23. Host MuPDF 1.28.5 (x86_64 JNI).

## Summary
**Verdict: acceptable for a public release with the listed fixes; SA-11, SA-2, SA-6 and SA-1/SA-3 must be fixed before release.**
About 77,000 recorded fuzz executions found no memory-safety problem, but one crafted input does crash the process: a PDF with a 20,000-link chain of indirect objects overflows MuPDF's recursive save-time marking and kills the JVM with SIGSEGV in Compress and Add images (SA-11; a native stack overflow, not an exploitable write). Otherwise: no `hs_err` file, no use-after-free or double-destroy pattern in our code, and none of the hazardous binding calls (`PDFObject.push(PDFObject)`, `addObject`). Every failure was a clean `Err`, and no `Err` left an output file behind. The serious class is **resource exhaustion from tiny files**. Nothing caps the declared pixel count of a picked JPEG/PNG, and Compress's 100 MP cap trusts the PDF dictionary. A 1.6 KB progressive JPEG, or a 2 KB PDF that contains one, makes libjpeg allocate memory without bound: on the audit host it drove the JVM to 9 GB RSS and into the kernel OOM killer twice. On a phone the app is killed, and because the image estimate runs automatically, picking the file is enough (SA-2, SA-6; fix: check the declared dimensions before decoding, SA-1/SA-3). Compress and Add images also return `Ok` with an output that cannot be opened for 13–17% of the fuzzed damaged inputs that reached `Ok` (SA-5; fix: validate the output after saving). Metadata survives at Original quality only in rare layouts (SA-7, plus the known N6–N9). A PDF's own `/Info` is kept by Compress and Add images (SA-8, by design).

## Method
- Read the code as an attacker: `engine/ImagePages.kt`, `engine/PdfInputs.kt`, `engine/MuPdfEngine.kt`,
  `engine/ops/{ImagesToPdfOp,CompressOp,MergeOp,AppendImagesOp}.kt`, `core/{ImageNormalizer,PdfJobs,UriIo}.kt`.
  Also read the MuPDF 1.28.5 JNI (`platform/java/jni/pdfobject.c` readRawStream/readStream, `wrap.c`) and
  the MuPDF limits (`fitz/pixmap.c` FZ_MAX_SAMPLES, `fitz/load-png.c`, `pdf/pdf-xref.c` PDF_MAX_OBJECT_NUMBER).
- Fuzz harness: a throwaway `probe/Fuzz.kt` (not committed). Each target runs in a separate child JVM
  (`-Xmx256m -Xss1m`, `-XX:ErrorFile=…/hs_err_%p.log`, `timeout`) with the real host MuPDF
  (`~/mupdf-host/libmupdf_java64.so`), so a native abort shows up as a JVM death with an hs_err file.
  Before every call the child writes the input to `current-<target>.bin`.
  Oracle: `Ok` → reopen the output, check the page count, load every page; `Err` → `out` must be absent, and
  the cause of `Err(Io)` must be a plain MuPDF `RuntimeException` (any other class is a finding). No stray
  files may be left in the work dir. A single call taking more than 10 s is recorded.
- Targets: `jpeg` and `png` (each iteration: `estimateImage`, `imagesToPdf` at a random level of null/LOW/HIGH,
  `appendImages`); `merge`, `compress` and `append` over mutated PDFs.
  - JPEG mutations (structure-aware): truncation inside the header; segment length set to 0/1/2/3/0xFFFF/random;
    repeated SOI; stray EOI; missing EOI; `FF` fill runs; SOF dims set to 0/1/2000/65500/0xFFFF; component count
    0/2/4/5/255; extra SOS; segment running past EOF; random byte flips.
    Seeds: baseline, EXIF/XMP/MPF/IPTC/JFXX/COM-laden, Adobe+ICC, gray, EXIF thumbnail, and progressive (ImageIO).
  - PNG mutations: truncation; chunk length 0/1/0x7FFFFFFF/0xFFFFFFFF/random; IHDR byte fields (depth, colour type,
    interlace) and W/H set to 0/1/30000/0x7FFFFFFF/0xFFFFFFFF; bad CRC; duplicated chunk header; byte flips.
    Seeds: RGB, RGBA, tEXt/iTXt/eXIf, 16-bit RGB, interlaced RGB, palette with an oversized tRNS, 1-bit interlaced
    gray, and 16-bit RGBA interlaced.
  - PDF mutations: 1–10 random byte flips; truncation; token-level replacement of the value after
    `/Width /Height /BitsPerComponent /Filter /ColorSpace /N /Length /Count /Size /MediaBox /Kids /Parent /Type /Root
    /Prev /First /Subtype /Annots /URI /Rect /SMask /Decode /W` with hostile values (0, −1, 2^31−1, 1e38, self/cyclic
    references, wrong filters, CMYK, missing ICC, degenerate boxes).
    Seeds: `Fixtures.photoPdf`, `textPdf`, `linkPdf`, `mixedImagesPdf`, and **uncompressed re-saves** of three of them,
    so that flips hit structure rather than zlib.
- After the bombs took down the host, all later runs were capped with `ulimit -v 3000000` (3 GB), plus small JVM
  reservations, so a bomb fails `malloc` instead of OOM-killing the machine. Bomb sizes were then measured again at a
  5.5 GB cap to show that the peak tracks the cap.
- Iterations and time (fixed seeds 1, 7 and 20261123; one child JVM per run):

  | Target | Iterations | Wall time | Peak RSS of the fuzz JVM | hs_err / JVM death |
  |---|---|---|---|---|
  | jpeg | 4,460 + ~9,500 + 53,252 = ~67,200 | ~8 s + ~170 s + 120 s | 1,578 MB (capped) | none; one uncapped run OOM-killed by the kernel (SA-2) |
  | png | ~1,000 + 1,501 | ~8 s + 120 s | 1,476 MB (capped) | none |
  | compress | 2,500 | 120 s | 348 MB | none |
  | merge | 191 + 2,806 | ~8 s + 100 s | 334 MB | none |
  | append | 2,786 | 100 s | 357 MB | none |

- Crafted inputs (`sec-engine/crafted/*.pdf`, generated by a Python script): DCT dimension lie, 99.6 MP DCT,
  100 MP Flate bomb, short Flate data, `/Size 8388607`, 200,000-deep nesting, page-tree cycle, `/Count` lie,
  self-SMask, lying ICC `/N`, 10,000 pages, and huge/zero/inverted/overflowing MediaBox.
- Metadata: an independent probe (`MetaMain`, not the repo's fixtures for JPEG) plants the marker `ZQXSECRET` in
  EXIF/GPS, XMP, IPTC, COM, the Adobe APP14 tail, the ICC_PROFILE payload, the JFIF tail, FF fill before APP1,
  PNG iCCP and PNG tEXt/iTXt/eXIf, and in a PDF `/Info`. It runs Images to PDF at Original/Low/Medium/High,
  Add images, Compress (3 levels) and Merge. The search covers raw bytes plus every inflated stream.
- Scratch artefacts: `/tmp/claude-1000/-home-ubuntu-code-pdf-util/a7d6466d-cdd4-4a97-9575-67ba874d248d/scratchpad/sec-engine/`
  (`case-*.bin`, `crafted/`, `log-*.txt`).

## Findings (sorted by severity)

| ID | Sev | Where | Description | Trigger | Impact on a phone | How confirmed | Fix |
|---|---|---|---|---|---|---|---|
| SA-11 | **Medium (process crash, native)** | `engine/ops/CompressOp.kt:265` and `AppendImagesOp.kt:206` (`doc.save(..., "garbage=deduplicate,...")` on the INPUT document); MuPDF `source/pdf/pdf-write.c:191` `markobj` (recursive) | **A picked PDF crashes the process with SIGSEGV (native stack overflow).** Garbage collection during save marks reachable objects recursively. A chain of indirect objects (`4 0 obj <</Next 5 0 R>>`, `5 0 obj <</Next 6 0 R>>`, …) hung off a page's `/Resources` recurses once per link and overflows the thread's native stack. This is not catchable: the JVM dies with exit 139 and no `hs_err` file (the overflow happens in native code past the guard page). Merge is not affected (grafting a page copies only what the page references, and it returned `Ok` for the same file). The 200k-deep *direct* nesting test did not find this, because the parser caps direct nesting; indirect chains are not capped. | `sec-engine/crafted/chain-20000.pdf` (about 1.1 MB, 20,000 chained dicts): `compress` -> **SIGSEGV (rc 139)**, `appendImages` -> **SIGSEGV**. Same for `chain-50000` and `chain-100000` (5.9 MB). `chain-5000`: `Ok` for both. Thread stack 1 MB (`-Xss1m`; the Java `main` thread runs on a launcher thread of that size). | The app crashes ("Offkit keeps stopping") whenever the user compresses or adds images to such a PDF; repeatable. The Android engine thread (`Executors.newSingleThreadExecutor` -> a default Java thread, about 1 MB of stack on ART) is no larger, so the threshold on a phone is likely similar or lower. A stack overflow hits the guard page, so this is a denial-of-service crash, not an exploitable write. | Ran (crafted; 6 crashing runs, 2 sizes plus 100k; merge control `Ok`) | Run the engine thread with a large explicit stack: `Thread(null, r, "pdf-engine", 256L shl 20)` in `PdfJobs.ENGINE_DISPATCHER`. Virtual memory is only committed as used, and this raises the threshold about 250x. Additionally/alternatively, for Compress/Add images save without `garbage` (or with `garbage=compact`, if it does not mark: check `pdf-write.c`), or report upstream to Artifex (iterative marking). Add a regression test with a 20k chain. |
| SA-2 | **Medium (top priority)** | same places as SA-1; the allocation itself is in libjpeg's progressive coefficient buffer, reached via MuPDF `filter-dct.c` from `encodeJpeg` → `DrawDevice.fillImage` | **Picking a 1.6 KB JPEG exhausts all memory.** A progressive (SOF2) JPEG declaring 65500×65500×3 makes libjpeg allocate whole-image coefficient arrays: about 65500²×3×2 bytes ≈ 25 GB, in chunks. So peak memory is limited only by what `malloc` will give. A baseline (SOF0) JPEG patched to 65500x65500 (`crafted/baseline-bomb.jpg`, 29 KB) also peaked at about 1,050 MB under the 3 GB cap (`estimate`, 2.2 s), so baseline files are not safe either. The progressive case is just the worst. Nothing in our code bounds it (see SA-1). | `sec-engine/case-jpeg-kill.bin` (1,667 bytes; a fuzz mutant of `Fixtures.jpegWithMetadata`: SOF2 65500×65500, nc=3, several SOS headers, ends `FF D9`). Measured in a child JVM with `-Xmx256m` and the address space capped by `ulimit -v`. At a 3 GB cap: `estimateImage` peaked at 1,050 MB RSS (2.1 s) and `imagesToPdf(HIGH)` at 1,024 MB. At a 5.5 GB cap: `estimateImage` peaked at **3,911 MB** RSS (7.7 s) and `imagesToPdf(HIGH)` at 3,885 MB. Both still returned `Ok`, because libjpeg's allocation failure is absorbed. With no cap, the fuzz JVM was **killed by the kernel OOM killer at 9.1 GB anon RSS** (dmesg: `Killed process … (java) anon-rss:9183912kB`), twice, with this file as the running input. That also took down the audit session. `imagesToPdf(null)` (no re-encode) is unaffected: 68 MB. | Picking the image is enough: `PdfJobs.estimateImage` runs `estimate` automatically on pick and decodes the image at three levels. Android has no address-space cap and overcommits memory, so the low-memory killer kills the app (the user sees it vanish with no message). On low-RAM phones it may also kill other apps in the background. One file is enough, and it can be sent by chat or email. Denial of service only: no memory corruption was observed (no hs_err, no SIGSEGV). | Ran (fuzz + isolated repro, 4 runs) | Same fix as SA-1. Reject before any decode when the SOFn/IHDR declares `w*h` over a cap. Progressive and multi-scan JPEGs need a stricter cap, because libjpeg buffers the whole image: Real phone photos go up to 108-200 MP, so a flat 50 MP cap would reject genuine images. Better: cap progressive files hard (their coefficient buffer is about 6 bytes per pixel), and for baseline files set a cap near the largest real sensor (about 200 MP), but decode them with MuPDF's DCT downscaling only. Note that `encodeJpeg` already draws at a reduced size, yet still peaked at about 1 GB. Optionally also call MuPDF's `fz_set_user_context`/a custom allocator limit (not exposed by the Java binding). |
| SA-6 | Medium | `engine/ops/CompressOp.kt:305-317` (`MAX_PIXELS` checks the dictionary's `/Width`×`/Height`, then `doc.loadImage` + `fillImage`) | **The Compress pixel cap is bypassable.** For a `DCTDecode` image MuPDF's decoder (libjpeg) sizes its buffers from the JPEG's own SOF, not from the PDF dictionary. A dictionary saying 8×8 over a progressive JPEG stream that declares 65500×65500 passes the 100 MP check, and decoding then allocates as in SA-2. Separately, 100 MP itself (`MAX_PIXELS`) means a decode of about 300 MB+ native for an RGB image, which is too much for a phone. | `sec-engine/crafted/dct-lie.pdf` (2,298 B: one page, image XObject `/Width 8 /Height 8 /DeviceRGB /DCTDecode`, stream = the 1,667-byte `case-jpeg-kill.bin`). `compress(HIGH)`: peak RSS **1,022 MB** at a 3 GB address-space cap and **3,882 MB** at a 5.5 GB cap (4.0 s), then `Ok`. The peak tracks the cap, so the allocation is unbounded. `crafted/dct-big.pdf` (dict 65500×1520 = 99.6 MP, under the cap): same. `merge` of the same file: 67 MB (no decode). | Compressing a 2 KB PDF gets the app killed by the low-memory killer. | Ran | Before `loadImage`, for DCT streams parse the SOF from the first bytes of `readRawStream` (or skip DCT images whose SOF dimensions differ from the dictionary). Skip progressive DCT above a small cap. Lower `MAX_PIXELS` to about 25–40 MP. |
| SA-1 | Medium | `engine/ImagePages.kt:340-360` (`encodeJpeg`), `:419-436` (`estimate`), `:442-457`; `core/ImageNormalizer.kt:201-219` (JPEG/PNG passed through without a size cap) | Image decompression bomb. JPEG and PNG files go to MuPDF unchanged; `open` only checks `width > 0 && height > 0`, and nothing caps the declared pixel count. `estimate` (which runs automatically for every picked image) and every compressed job decode the full image via `fillImage`. | `sec-engine/case-jpeg-0.bin` (3,515 bytes): a progressive JPEG whose SOF2 declares 65500×10955×3, followed by almost no scan data. The walker accepts it (EOI present), and MuPDF decodes 717 M pixels of filler. One fuzz iteration (`estimateImage` + `imagesToPdf` + `appendImages`) took **19.2 s** on the host. The child JVM reached a VmHWM of 980 MB in a short smoke run (256 MB Java heap, so roughly 700 MB native). | CPU stays busy for tens of seconds per image on the single engine thread; every other job and estimate waits. Large native allocations can get the process killed by the low-memory killer (crash with no error message). | Ran (fuzz) | In `open`, read the declared dimensions (JPEG SOFn / PNG IHDR, already walked) and reject `w*h` above a cap, for example 50 MP, as `UnsupportedImage` before any decode. |
| SA-3 | Medium | `engine/ImagePages.kt:69-109` (`open`: PNG accepted on `pngEnd` + `width>0`), `:369-411` (`originalEmbeddedSize` → `scratch.addImage` decodes the full PNG; `readRawStream` copies the raw pixels to the Java heap) | **PNG decompression bomb.** A small PNG declaring a huge size is fully decoded by `estimate`, and by `imagesToPdf`/`appendImages` at every quality level, including Original, because `addImage` decodes PNGs to raw pixels. MuPDF's only guards are integer overflow and `FZ_MAX_SAMPLES`. | Fuzz mutants of the seed PNGs (IHDR fields only): `case-png-2.bin` (365 B, 17×8,323,081 RGB, interlaced): `estimate` peaked at **888 MB RSS** and ended in a Java-heap OOM. `case-png-1.bin` (116 B, 2,147,483,647×1, 1-bit gray): **12.5 s** of CPU before "Overly large image". `case-png-0/3/4`: 12–20 s each. **Valid IDAT bomb** `crafted/idat-12000.png` (420 KB, 12000x12000 RGB of zeros): `estimate` -> 889 MB RSS then Java OOM (`Err(Io)`); `imagesToPdf(null)` (Original) -> **888 MB RSS**, 3.8 s, `Ok`. `idat-8000.png` (187 KB): 432-435 MB. During the fuzz run, native `malloc` requests of 1.0–2.0 GB failed only because of the 3 GB `ulimit -v`. | Same as SA-2: the app is killed by the low-memory killer when it picks or converts the image, or the engine thread is blocked for 10–40 s. Denial of service only. | Ran (fuzz + isolated repro) | Cap the declared `w*h` from IHDR in `open`, as for SA-1. Additionally compute `originalEmbeddedSize` for a PNG without materialising the raw stream on the Java heap (N5). |
| SA-9 | Medium (user-driven) | `core/UriIo.kt:130-142` (`pump`), `core/PdfJobs.kt:165-178`, `engine/ops/ImagesToPdfOp.kt:452-462`, `CompressOp.kt:321` | **No input-size, page-count, image-count or job-memory limits.** A picked file of any size is copied to `cacheDir/jobs` (bounded only by free storage; ENOSPC -> `Io`, the partial file is deleted). Images to PDF keeps every embedded image stream in the in-memory `PDFDocument` until the final save. That is native memory of about the sum of the image sizes (200 x 5 MB photos = about 1 GB), plus at Original quality one transient Java-heap copy per image (N5 update). Compress copies each image's raw stream onto the Java heap just to read its length (`readRawStream().size`). A single 300 MB image stream therefore exceeds a typical 256-512 MB app heap -> OOM -> reported as `Io` (SA-4). 10,000 pages: merge 3.5 s, 80 MB, fine. | 200 large photos in one Images to PDF job; a 500 MB scanned PDF whose pages are single huge image streams. | Process killed by the low-memory killer, or `Err` with a misleading message. User-driven, not an attack. | Read; 10k pages ran | Cap the image count per job (or its total bytes) and the input size (for example 1 GB); get the stream length without copying (`/Length`, or a native length call). For very large jobs, write incrementally (save, reopen, append). |
| SA-5 | Low | `engine/ops/CompressOp.kt:252-273` (no validation of the saved output); MuPDF `pdf_save_document` with `garbage=deduplicate` | **Compress reports success for an output that cannot be opened.** With a damaged input (bad xref offsets, odd free-list entries), MuPDF repairs the input and its pages load. But the document it saves can have a cyclic or malformed page tree, and `CompressOp` returns `Ok` without ever loading a page. Merge of the same input succeeds. | 2,500 compress iterations gave 1,381 `Ok`, and **238 of those outputs failed to load a page**: 198× "cycle in page tree", 37× "malformed page tree", 3× page count 0 vs 1. Example `sec-engine/case-compress-0.bin` (909 B, `Fixtures.linkPdf` with xref entry 1 = `00000F0042` and gen-1 free entries): `merge` → Ok and readable; `compress` → Ok (423 B), output throws "cycle in page tree" on `loadPage(0)`. Not root-caused inside MuPDF; the fault is in MuPDF's repair + garbage-collect save, not in a misuse of the binding. **Add images has the same problem**: 2,786 append iterations → 1,490 `Ok`, of which 188 have "cycle in page tree" and 9 have a page count that differs from `Ok.pages`. Crafted `crafted/cycle.pdf` (`/Kids[3 0 R 2 0 R]`, `/Count 3`): `compress` → `Ok(pages=3)` with an unusable output, while `merge` → `Err`. | The user is told the PDF was compressed and saves a file that viewers cannot open. The original is untouched (SAF save to a new file), so this is wrong output only, with no data exposure and no memory issue. | Ran | After `save`, reopen `out` and `loadPage` every page (cheap compared with the image work). On failure, fall back to the byte copy of the input or return `Err(Corrupt)`. The same check is a cheap safety net for Merge and Add images. |
| SA-7 | Low | `engine/ImagePages.kt:295-300` (`isJpegMetadata` keeps a segment whole when its payload *starts with* `JFIF\0`, `ICC_PROFILE\0` or `Adobe`) | **Metadata in kept segments survives at Original quality and in Add images.** This extends N9 to APP14 and APP2/ICC. Arbitrary bytes after the 12-byte Adobe header, and any text inside an ICC profile (the profile `desc`, `cprt` and device-model tags), are embedded unchanged. Add images always embeds at Original quality, so it always keeps them. At Low/Medium/High the bytes also survive whenever the re-encoded JPEG is not smaller than the original (`addImageObject` falls back to `doc.addImage(img)` from the same kept bytes). | Independent probe: SOI + APP14 `Adobe` + 7-byte header + `ZQXSECRET`; SOI + APP2 `ICC_PROFILE\0 01 01 descZQXSECRET`; SOI + JFIF APP0 + `ZQXSECRET`. Each gives `ZQXSECRET` in the PDF from `imagesToPdf(null)` and `appendImages`, and is clean at LOW/MEDIUM/HIGH for this 200x150 image. Clean everywhere: standard EXIF with GPS, XMP, IPTC, COM, FF fill before APP1, PNG iCCP (only an invalid planted profile was tested; a valid profile with text tags is untested), and PNG tEXt/iTXt/eXIf (PNG is always decoded to pixels). | Data exposure in rare layouts. Camera ICC profiles can carry a device model string; arbitrary data can be hidden in these tails by a tool, but phones do not normally write it. | Ran | Rewrite kept segments: JFIF -> bare 16-byte header; Adobe -> exactly 12 bytes; ICC -> keep, but document it (or drop it and accept the colour change). Make the Original-quality privacy string less absolute. |
| SA-4 | Low | `engine/MuPdfEngine.kt:148-164`, `:123-131`; JNI `fz_throw_java`/`jni_rethrow` | **Misclassified failures.** (a) A Java `OutOfMemoryError` raised inside a JNI call (for example `NewByteArray` in `readRawStream`) comes back as a plain `RuntimeException("java.lang.OutOfMemoryError: Java heap space")`, so the user sees `Io`, not `OutOfMemory`. (b) A PNG with a corrupt IDAT (bad zlib, "ran out of output before input") passes `pngEnd` and fails later in `addImage`; it is reported as `Io`, not `UnsupportedImage`. Native `malloc … failed` also comes back as `Io`. (c) A PDF whose page tree is broken passes `PdfInputs.open` (only `countPages` is checked) and then fails inside `graftPage` with "malformed page tree", "source object number out of range", "cannot find page N in page tree" or "cycle in page tree". These are reported as `Io`, not `Corrupt` (merge fuzz: 28 of 2,806). | `case-png-2.bin` gives (a); about 20% of PNG fuzz mutants give (b) (for example 134× "ran out of output before input"). | Wrong error message only ("could not read/write file" instead of "image not supported" / "too large"). | Ran | In `guardedRun`/`estimateImage`, map a `RuntimeException` whose message contains `OutOfMemoryError` or starts with `malloc (` to `OutOfMemory`. Wrap `addImageObject` failures as `UnsupportedImageFileException`. |
| SA-8 | Low / Info | `engine/ops/CompressOp.kt:265`, `AppendImagesOp.kt:206` (full save of the input document); `CompressOp.kt:266-268` (byte copy of the input when not smaller) | **Document metadata of a PDF is kept by Compress and Add images** (by design: they edit the document). This covers `/Info` (Author, Producer...), the XMP `/Metadata` stream, embedded files, JavaScript, and EXIF inside DCT image streams that are skipped or not made smaller. When nothing gets smaller, the output is the input byte for byte. Merge starts a new document and grafts only pages, so `/Info` is dropped there; page-level and image-level metadata still survives. | Ran for `/Info` only: `/Info /Author (author-ZQXSECRET)` gives compress LOW/MEDIUM/HIGH -> present, appendImages -> present, merge -> clean. XMP, embedded files, JS and DCT EXIF: by reading. | Users may expect "compress" to clean the file; it does not. No new exposure beyond the input the user already has. | Ran | Document it. Optionally drop `/Info` and `/Metadata` in Compress, as a deliberate product decision. |

## Confirmed safe
- `readRawStream`/`readStream` JNI: `(jsize)len` for a buffer of 2 GiB or more gives a negative size, so `NewByteArray` throws, or it wraps to a smaller positive size, so `SetByteArrayRegion` copies a prefix. Both are memory safe; the second gives wrong data (read).
- MuPDF pixmap allocation is capped by `FZ_MAX_SAMPLES` ("Overly large image" seen in the PNG fuzz as a clean `RuntimeException`) (ran).
- Smoke fuzz (seed 1, ~8 s each): jpeg 4,460 iterations, png ~1,000, merge 191. No crash, no escaped exception type, no out file left behind after an `Err`.

- Crafted PDFs (`sec-engine/crafted/`, each through `compress` and `merge`; all ≤ 120 MB RSS unless noted, no crash):
  - Flate bomb, 10000×10000 RGB in 292 KB (exactly `MAX_PIXELS`): compress 0.8 s, 79 MB. MuPDF subsamples while it decodes.
  - Flate stream far shorter than W×H: `Ok`.
  - Trailer `/Size 8388607` in a 434-byte file: compress 56 ms, 67 MB. MuPDF sizes the xref from the real objects; merge gives `Err`.
  - Resources nested 200,000 arrays deep: `Ok`, MuPDF's parser limits nesting. No stack overflow, even with `-Xss1m`.
  - `/Count 2000000000`: `Corrupt`.
  - Image whose `/SMask` is itself: `Ok`, skipped.
  - `ICCBased` with a lying `/N 3` over 1-component data: `Ok`.
  - 10,000 pages (1.2 MB): compress 0.2 s, merge 3.5 s, about 80 MB.
- Add images onto a last page with MediaBox `[0 0 1e38 1e38]`, `[0 0 0 0]`, inverted, or `1e400`: MuPDF normalises the invalid page bounds before `pageSizeOf` sees them (it falls back to 612×792), so no `NaN`/`Infinity`/zero `cm` reaches the content stream (ran).

- **Memory safety of our binding use** (read every MuPDF call in scope): every `Image`, `Pixmap`, `DrawDevice`, `Buffer`, `PDFObject`, `Page`, `Link`, graft map and `Document` is destroyed at most once (no double-destroy found). One minor leak path: at `ImagePages.kt:523-525` `ref` leaks to the finalizer if `newDictionary()` throws. `CompressOp.Scope` destroys in reverse and skips `PDFObject.Null`. `resolve()` always returns a new kept wrapper (`jni/pdfobject.c:415-436`), so `s.add(x.resolve())` never double-frees, and `destroy()` zeroes the pointer. There is no `push(PDFObject)` and no `addObject`. `isStream`/`readRawStream` are only called on indirect refs. Colour-space singletons are never destroyed. A `DrawDevice` whose `fillImage` throws is destroyed without `close()`; MuPDF only warns. (Read.)
- **Thread confinement**: all engine calls, including `estimateImage`, run on the single `pdf-engine` executor (`core/PdfJobs.kt:335-337`). No MuPDF class is used outside `engine/` (read; grep).
- **JPEG walker** (`jpegScan`): lengths are `Int` from 2 bytes (max 65535), payload is a `Long`, and `skip` is bounded by EOF. It always terminates (every iteration consumes at least 1 byte). `readKept` spans are ascending and disjoint, with `toInt()` guarded by the `Int.MAX_VALUE - 16` check. No exception other than `IOException` escapes (about 67k runs).
- **PNG walker** (`pngEnd`): the length is read as an unsigned `Long`; `next = pos + 12 + len` always advances; 0x7FFFFFFF/0xFFFFFFFF lengths give `null` (ran).
- **JNI byte arrays**: `readRawStream`/`readStream` cast `size_t` to `jsize`. At 2 GiB or more this gives a negative size (`NewByteArray` throws) or a wrapped smaller size (`SetByteArrayRegion` copies a prefix). Both are memory safe; the second gives wrong data, which is unreachable on a phone (read).
- **Output hygiene**: no `Err` left `out` behind, no stray files appeared in the work dir in about 85k runs, and no file appeared in the probe's work directory other than `out` (other locations were checked by reading only: MuPDF's non-incremental save writes `out` directly). Cancellation returns before `save`. `guardedRun` deletes `out` on every `Err`, and no uncaught exception escaped the engine.
- **Temp files**: the job dir is `cacheDir/jobs/<random UUID>`, inputs are `in_%03d.pdf` / `img_%03d.{raw,jpg,png}`, and the output is `output.pdf`. **No name is derived from a display name**, so a provider `DISPLAY_NAME` of `../../x` or with NUL or newline is irrelevant (`PickedFile.name` is UI only). `cacheDir` is app-private. Files are deleted on failure, cancel and estimate (`finally` + `NonCancellable`), inputs on success, the output on save/discard, and everything on the next process start (`PdfJobs.get` -> `wipeAll`), which also covers an LMK kill (SA-2). Nothing is written to external storage (no storage permission; output only through SAF `openOutputStream`). `allowBackup=false` (read).
- **Non-JPEG/PNG images** never reach MuPDF raw: `ImageNormalizer` transcodes them through `Bitmap.compress` (no EXIF) with a 4096 px cap (`setTargetSize`/`inSampleSize`) (read). N10 remains unreachable.
- **Encryption**: `PdfInputs.open` rejects `needsPassword`, a crypt handler and a trailer `/Encrypt` before any page access (read; existing tests cover AES-256 user and owner-only).
- **Direct nesting and page-tree cycles**: 200k-deep direct arrays, a `/Kids` self-cycle and `/Count` lies give no overflow and no hang (ran). **Indirect** object chains DO crash Compress/Add images (SA-11).
- **Xref repair alone does not trigger SA-5**: `Fixtures.textPdf` with a broken `startxref` gives compress `Ok`, and that output merges fine (ran).
- **Crafted Add images placement**: an invalid last-page MediaBox never yields `NaN`/`Infinity`/zero `cm` (ran, see above).

## Not covered
- **No device run.** The bombs were measured on x86_64 Linux. That the app is LMK-killed on Android is reasoned from Android's overcommit and LMK behaviour, not observed. The arm64 `libmupdf_java.so` was not exercised.
- **Root cause of SA-5 inside MuPDF** (repair + `garbage=deduplicate` producing a cyclic page tree) was not bisected; minimal inputs are saved in `sec-engine/case-compress-*.bin` and `case-append-*.bin`.
- **Encrypted variants** were checked by reading only, not fuzzed (rejection happens before any page content is parsed).
- **JBIG2, JPX and CCITT streams** inside PDFs were not fuzzed (Compress skips them; Merge and Add images graft without decoding).
- **Fuzz depth**: a few minutes per target with random plus token-level mutation; no coverage-guided fuzzing and no ASan build of MuPDF. A heap overflow that does not crash would not be seen.
- **Cancellation during a native call**: cancellation is polled only between units, so a bomb decode cannot be cancelled. Reasoned, not run.
- **True CMYK (Adobe transform 0/2) JPEG** as an independent seed, and **exhaustive per-byte header truncation** (truncation was random, inside the first 800 bytes).
- **Android decoders** used by `ImageNormalizer` (HEIC/WebP/GIF via `ImageDecoder`/`BitmapFactory`) are platform code and were not fuzzed.
- **500 MB PDF / 200-image jobs** were reasoned from code (SA-9), not measured.

## Re-check after fixes (SR)

Re-checker: SR, independent of the fix authors. Code at `main` `ca22d9a` (merged ff-only), compiled in this worktree (`:app:compileDebugKotlin`). The tests used a throwaway standalone probe (`scratchpad/sr/src/SR.java`, not committed), with one JVM per case, `-Xmx256m -XX:+UseSerialGC`, address space capped by `ulimit -v 3000000` and host MuPDF 1.28.5. Peak RSS comes from `/usr/bin/time %M`.

- Each image case runs `estimateImage`, then `imagesToPdf(HIGH)`, then `imagesToPdf(null)`, then `appendImages`.
- Each PDF case runs `compress(HIGH)`, `merge([f,f])` and `appendImages(f,[small png])`, with the audit's oracle: an `Ok` output must reopen with every page loading, and an `Err` must leave no output.
- Crafted inputs come from `scratchpad/sr/gen.py` and are saved in `scratchpad/sr/c/`.
- Timing caveat: an orphaned `gen2.py` from an earlier session was using about 1 CPU core throughout, and I was not permitted to kill it.

### Status of the original findings

| ID | Status | Evidence (what I ran) |
|---|---|---|
| SA-2 | **Fixed** for picked images (residual: SR-2) | `case-jpeg-kill.bin`: all four calls return `Err(ImageTooLarge)` in 0-30 ms, peak RSS 72 MB (the audit measured 1,050-3,911 MB). |
| SA-1 | **Fixed** | `case-jpeg-0.bin`: `ImageTooLarge`, 72 MB. Gate bypass attempts (table below): every hostile header is rejected or bounded, except for the engine-direct non-JPEG/PNG route (SR-4). |
| SA-3 | **Fixed** | `case-png-0..4.bin`: all `ImageTooLarge`, 71-72 MB, under 60 ms (the audit measured 12-20 s and up to 888 MB). |
| SA-6 | **Not fixed: bypassable** (SR-1) | `dct-lie.pdf` and `dct-big.pdf` compress: `Ok`, 73 MB (image skipped). The same stream with `/Filter /DCT` (MuPDF's abbreviation) instead of `/DCTDecode` gets past the gate and peaks at **1,048 MB** at the 3 GB cap. |
| SA-5 | **Fixed** | `case-compress-0.bin` compress, `cycle.pdf` compress, merge and append, `case-append-0.bin` append: all now `Err(Corrupt)`, with no output left. |
| SA-4 | Fixed for the cases I saw | The `ImageTooLarge`/`UnsupportedImage`/`Corrupt` mapping is correct in every run above. OOM mapping: read only (`isOutOfMemoryFailure`). |
| SA-7 | Fixed by reading; colour regression check queued (see Regression) | `jpegScan` now rewrites JFIF to its bare 16 bytes and Adobe to its 12 bytes. ICC is kept, and PRIVACY.md now says so. |
| SA-8 | Accepted risk (documented in PRIVACY.md) | Not re-run. |
| SA-9 | Partly fixed | Limits exist: 500 images and 1 GiB per input PDF, and Compress takes the stream length from `/Length` instead of `readRawStream`. CPU is not bounded: see SR-3. |

### Image-gate bypass attempts (each in its own capped JVM)

| Input (scratchpad/sr/c/) | Result | Peak RSS | Time |
|---|---|---|---|
| SOS before the SOF (`j-early-sos`) | UnsupportedImage | 72 MB | <0.1 s |
| SOF0 inside an APP1 payload, real SOF2 65500² (`j-sof-in-app-small`) | ImageTooLarge (the walker skips APPn payloads) | 72 MB | <0.1 s |
| EXIF APP1 carrying a whole progressive bomb as thumbnail | Ok, thumbnail never decoded | 76 MB | 0.1 s |
| Bomb appended after EOI (MPF-style second image) | Ok, never handed to MuPDF | 76 MB | 0.1 s |
| Two SOFs (small then huge, and huge then small) | UnsupportedImage (frameCount ≠ 1) | 72 MB | <0.1 s |
| SOF after the first scan | UnsupportedImage | 72 MB | <0.1 s |
| DNL (height 0 in the SOF) | UnsupportedImage | 71 MB | <0.1 s |
| Progressive 7000×7000, 3 components, tiny data | Ok (under the 50 MP cap) | **409 MB** | 1.9 s |
| Progressive 7000×7000, **4 components** (with or without Adobe) | Ok | **520 MB** (SR-2) | 2.5 s |
| Progressive 4 components with 4×4 sampling on component 1 | Ok | 250 MB | 1.0 s |
| Baseline 16000×15560 (249 MP), interleaved, tiny data | Ok (streamed) | 150 MB | 1.1 s |
| Same, non-interleaved first scan | ImageTooLarge (counted as buffered) | 72 MB | <0.1 s |
| Same, 4 components (CMYK) | Ok | 200 MB | 1.4 s |
| Same, SOS spectral selection 1..5 | Ok | 149 MB | 1.1 s |
| Same, two interleaved scans | Ok | 149 MB | 1.1 s |
| SOF1 249 MP | Ok | 148 MB | 1.1 s |
| SOF9 (arithmetic) 49 MP / 51.8 MP | Ok / ImageTooLarge | 140 / 72 MB | 0.8 s |
| SOF3 (lossless) 49 MP | UnsupportedImage (MuPDF) | 73 MB | <0.1 s |
| `FF D8 00` + the kill JPEG (`j-ffd8-nonff`, engine direct) | **Ok after decoding the bomb**: routed as `Format.OTHER`, which has no gate | **1,079 MB** (tracks the cap) | 3.0 s (SR-4) |
| GIF / BMP / TIFF / PSD headers declaring 30000²-65535² with a .jpg/.png name, engine direct | UnsupportedImage (MuPDF rejects them) | 71-75 MB | <0.1 s |
| APNG: IHDR 100², fcTL 60000² | Ok, MuPDF ignores the APNG chunks | 76 MB | 0.1 s |
| Two IHDRs (100² then 60000²) | Ok at 100² | 76 MB | 0.1 s |
| Two IHDRs (8-bit gray, then 16-bit RGBA, same size) | UnsupportedImage | 90 MB | 0.2 s |
| IHDR not first; CgBI | UnsupportedImage | 71 MB | <0.1 s |
| Adam7 4472² RGB (at the cap), all-zero data | Ok | 340 MB | 4.5 s (all four calls) |
| 4472² RGB, maximal-ratio zlib | Ok | 340 MB | 3.7 s |
| 64² PNG with an iCCP zlib bomb of 1 GB / 8 GB | Ok; MuPDF bails out early (time does not scale) | 204 / 226 MB | 2.6 / 2.8 s |

### Compress / Merge / Add images on bomb PDFs

| Input | compress | merge | append |
|---|---|---|---|
| `/Filter /DCT` lie (`c-dct-abbrev`) | Ok, **1,048 MB** (decoded; at a 2 GB cap the first malloc fails and it ends at 73 MB, so the request exceeds about 2 GB) | Ok 71 MB | Ok 74 MB |
| `/Filter [/DCT]` | Ok, **1,049 MB** | Ok 71 MB | Ok 74 MB |
| `/DCT` image inside a Form XObject | Ok, **1,048 MB** | 70 MB | 75 MB |
| `/DCT` lie used as an SMask | Ok, 73 MB (masks are skipped) | 71 MB | 75 MB |
| Inline image `BI … /F /DCT ID <bomb> EI` | Ok 72 MB (never decoded) | 73 MB | 73 MB |
| Flate 100×100 RGB with `/DecodeParms` Predictor 15, Colors 32, BPC 16, Columns 4,194,000 | Ok, **332 MB** from 759 bytes (SR-5) | 72 MB | 73 MB |
| JPX declaring 60000² | Ok 74 MB (skipped) | 70 MB | 75 MB |
| One image drawn 10,000 times | Ok 74 MB | 74 MB | 75 MB |
| 5,000 distinct 32×32 images (16 MB) | Ok, 1.6 s, 107 MB | Ok **9.9 s**, 106 MB | Ok 2.0 s, 78 MB |
| Content stream that is a 200 MB Flate bomb | Ok 72 MB | 74 MB | 75 MB |
| 1,000,000 nested `q` | Ok 74 MB | 77 MB | 75 MB |
| `dct-lie.pdf` merged with itself 50 times | Ok (50 pages), 72 MB, 61 ms | | |
| `loadPage` alone on the 200 MB content bomb / nested-q page | 71 / 72 MB, 28 ms | | |

Output validation (`loadPage` of every page) decodes no images and runs no content streams, so it cannot be turned into the bomb.

Merge time grows superlinearly with the number of distinct images (SR-3):

| Distinct images | Merge time | Peak RSS |
|---|---|---|
| 5,000 | 2.3 s | |
| 10,000 | 9.9 s | |
| 20,000 (65 MB file) | **57.5 s** | 149 MB |

### New findings

| ID | Sev | Where | Description | Trigger | Impact on a phone | How confirmed | Fix |
|---|---|---|---|---|---|---|---|
| SR-1 | **Medium** | `engine/ops/CompressOp.kt` `recompress`: `if ("DCTDecode" in filters)` | **The SA-6 gate is bypassed by the standard abbreviation `/DCT`.** MuPDF treats `/DCT` as DCTDecode for image streams. `filtersOf` returns `["DCT"]`, so the SOF check is skipped. The "last line of defence" `img.width != w` compares against the dictionary, which is exactly what MuPDF reports for a PDF image, so it never fires. libjpeg then sizes its buffers from the stream's SOF2 65500², as in SA-6. Works with `[/DCT]` and inside Form XObjects. | `scratchpad/sr/c/c-dct-abbrev.pdf` (2,298 B: `dct-lie.pdf` with `/DCTDecode` → `/DCT      `) | Compressing a 2 KB PDF gets the app killed by the low-memory killer (same as SA-6). Merge and Add images are unaffected. | Ran: 1,048 MB at the 3 GB cap; at a 2 GB cap the allocation fails up front | Normalise the filter names (`DCT`→`DCTDecode`, and also `Fl`, `AHx`, `A85`, `LZW`, `RL`, `CCF`), or simply decide by "is any filter DCT-like". Better: also run the SOF check whenever MuPDF's `Image` reports a JPEG, whatever the dictionary says. Add a test with `/DCT`. |
| SR-2 | Low | `engine/ImageLimits.kt` `MAX_BUFFERED_JPEG_PIXELS` (50 MP, whatever the component count) | **The progressive cap is about 1.7× more generous than its KDoc says.** A 1.2 KB hostile progressive header just under 50 MP peaks at 409 MB with 3 components and **520 MB with 4 components** (CMYK/YCCK), against the documented "50 MP progressive 238 MB" and "under ~400 MB". The cap counts pixels, not samples. | `scratchpad/sr/c/j-prog4-49mp.jpg` | Picking one file (the estimate runs automatically) costs about 0.5 GB native: survivable on most phones, but can trigger the LMK on 2-3 GB devices. | Ran | Budget samples rather than pixels (for example 150 M samples: a 4-component image capped at 37.5 MP), or lower the buffered cap. Re-measure with hostile tiny-data headers, not only real images. |
| SR-3 | Low | `PdfInputs.SAVE_OPTIONS` `garbage=deduplicate` (Merge; Compress and Add images save the same way) | **Save time grows superlinearly with the number of distinct small streams.** The save is a single native call, so it cannot be cancelled. | `c-5000-imgs.pdf` merged ×4 (20,000 images, 65 MB): 57.5 s. A single hostile PDF with more images scales worse. | The engine thread is blocked for minutes and other jobs wait; no memory growth. | Ran (2.3 / 9.9 / 57.5 s at 5k / 10k / 20k) | Accept and document, or use `garbage=compact` for Merge, or cap the object count per input. |
| SR-4 | Low (defence in depth) | `engine/ImagePages.kt` `open`: `Format.OTHER -> Verdict.OK` | **Anything that is not `FF D8 FF` / `\x89PNG` gets no gate**, but MuPDF recognises a JPEG from `FF D8` alone. `FF D8 00` + a progressive bomb is decoded unbounded through the engine API. In the app this is not reachable: `ImageNormalizer.sniff` also requires `FF D8 FF`, so such a file goes to Android's `ImageDecoder` instead (platform decoder, not tested here). GIF/BMP/TIFF/PSD bombs are rejected by MuPDF. | `scratchpad/sr/c/j-ffd8-nonff.jpg` via `estimateImage`/`imagesToPdf` directly | None today. The engine's safety depends on its caller's normaliser (the code comment says so). | Ran: 1,079 MB, tracking the cap | Reject `Format.OTHER` in the engine (`UnsupportedImageFileException`), since the app never sends it anything else. |
| SR-5 | Low | MuPDF predictor filter, reached from `CompressOp` (decode of a Flate image under 40 MP) | **`/DecodeParms /Columns` inflates the predictor's row buffers** independently of `/Width`: about 268 MB per buffer. | `scratchpad/sr/c/c-predictor.pdf` (759 B) | About 330 MB native per such image during Compress; bounded by MuPDF's column check. | Ran: 332 MB | Skip images whose `DecodeParms` `Columns`/`Colors`/`BitsPerComponent` disagree with `Width`/colour space/BPC. |

### Regression of legitimate inputs (capped JVM; est + i2p HIGH + i2p Original + append, all `Ok`)

| Input | Peak RSS | Time (all four calls) |
|---|---|---|
| 12 MP baseline JPEG (4000×3000) | 130 MB | 0.7 s |
| 48 MP baseline JPEG (8000×6000) | 134 MB | 0.7 s |
| 12 MP progressive JPEG | 154 MB | 0.8 s |
| 2000×1500 gray JPEG | 82 MB | 0.2 s |
| 800×600 CMYK JPEG | 78 MB | 0.2 s |
| 800×600 JPEG + ICC + EXIF | 78 MB | 0.2 s |
| 12 MP RGB PNG | 209 MB | 2.0 s |
| 10 MP 16-bit RGB PNG | 241 MB | 2.4 s |
| 15 MP RGBA PNG | 323 MB | 4.0 s |

- **Colour checks.** These compare page-0 renders of `imagesToPdf(null)` outputs.
  - JPEG+ICC+EXIF (EXIF stripped through the `readKept` path) against the same JPEG+ICC without EXIF: **max diff 0**.
  - CMYK: max diff 0, but the result is **inconclusive**. MuPDF's JPEG writer emitted no Adobe APP14, so the generated file never exercised the Adobe rewrite. The ICC profile used (MuPDF `rgb.icc`, effectively sRGB) also renders the same as no profile.
  - So "ICC kept, colours unchanged" holds for the strip path. The Adobe-rewrite colour equivalence is **not checked** with a real Adobe-marked CMYK file.
- **30-page mixed PDF.** The PDF had text on every page, an image on every 3rd page and a URI link on page 1.
  - compress → 30 pages, "Page 17 marker" text, link kept.
  - merge(f,f) → 60 pages, text and link kept.
  - append → 31 pages, text and link kept.

### Short re-fuzz
Seed 4242 (the audit used 1, 7 and 20261123), 170 s per target, one capped child JVM per target (`ulimit -v 3000000`, `-Xmx256m`), audit oracle plus "no call > 10 s".

| Target | Iterations | Ok | Violations | Child peak RSS |
|---|---|---|---|---|
| jpeg | 2,195 | 914 | 0 (1,280 UnsupportedImage, 1 ImageTooLarge) | 326 MB |
| png | 182,110 | 15,943 | 0 (152,804 UnsupportedImage, 13,363 ImageTooLarge) | 221 MB |
| compress | 14,067 | 1,855 | 0 per-call (12,212 Corrupt); **child peak RSS 1,287 MB > the 600 MB oracle** | 1,287 MB |
| merge, append | not recorded in time (still running when the session ended) | | | |

No hs_err file, no JVM death, no escaped exception and no `Err` with output left behind. The compress child breached the 600 MB RSS criterion once. The probe does not record RSS per iteration, so the input was not isolated. The seeds included `dct-lie.pdf`, so a mutant reaching the decoder by another filter spelling (as in SR-1) or a predictor/Width mismatch (SR-5) is the likely cause. Treat this as unexplained until it is reproduced, for example by bisecting with per-iteration VmHWM.

### Updated verdict (engine)
**Not yet acceptable for public release: one Medium remains.** The image gate holds against every structural trick I tried on picked JPEG/PNG files (SA-1/2/3 fixed), and output validation fixes SA-5 without itself becoming a bomb. But the Compress DCT gate (SA-6) is bypassed by the `/DCT` filter abbreviation (SR-1), so a 2 KB PDF still drives a Compress job to an unbounded allocation. This is a one-line class of fix; with SR-1 fixed and tested, I would rate the engine acceptable, with SR-2 to SR-5 as Low follow-ups.

### Not checked (SR)
- No device or arm64 run.
- Android `ImageDecoder` on bombs that start `FF D8` without a following `FF` (the SR-4 route).
- Type3-glyph images: covered by reading only (Compress enumerates every image object by number, as the Form-XObject case shows).
- `/Length` lying low, indirect or absent: read only.
- JBIG2 bombs: not built (skipped by filter name; `JBIG2Decode` has no abbreviation).
