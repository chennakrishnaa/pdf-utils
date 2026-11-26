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

## Second re-check (SR2)

Re-checker: SR2, independent of the fix authors. Code at `main` `eda2751` (merged ff-only). Throwaway harness and inputs in `scratchpad/sr2/` (not committed).

### Status
- **SR-1 (Compress filter gate): Holds for the filter chain; default-deny is bypassed one level further, through the colour space (SR2-1, Medium).** Every spelling, indirection, duplicate-key and xref trick on `/Filter` was refused (section A), but `ICCBased` images are decoded and `loadImage` reads the whole ICC profile stream without a bound.
- **SA-11 (deep-object guard): Holds on the 128 MB `EngineStack` thread** (490k-link chains, the whole `MAX_OBJECTS` range, run without a crash). **The guard alone does not hold on a 256 KB stack** (a 2,990-link chain it accepts kills the process); its margin on 1 MB is about 4-5x (SR2-4, see C). The guard walk itself is a new memory/CPU bomb (SR2-3).

### A. Compress filter gate attempts
Inputs from `scratchpad/sr2/gena.py` (one page, image XObject 4 drawn by the page; "kill" = the 1,667-byte SOF2 65500² stream `case-jpeg-kill.bin` under an 8×8 DeviceRGB dictionary). Op `compress(HIGH)`, capped child JVM as in B.

| Input (`scratchpad/sr2/c/`) | Result | Time | Peak RSS |
|---|---|---|---|
| `/#46ilter/DCT` (hex-escaped key) | Ok, image skipped (the parser decodes the key; SOF check refuses) | 0.27 s | 74 MB |
| `/Filter/DCT#44ecode`, `/Filter/#44CT` | Ok, skipped | 0.27 s | 73-74 MB |
| Duplicate keys `/Filter/FlateDecode/Filter/DCT` and reverse | Ok, skipped / Ok (the parser keeps only the last occurrence, so the binding and the decoder see the same dictionary) | 0.27-0.29 s | 73 MB |
| `/Filter 6 0 R` → `/DCT`; `/Filter[6 0 R]`; `/Filter 6 0 R` → `[7 0 R]` → `/DCT` | Ok, skipped (indirect names and arrays are resolved before the decision) | 0.27 s | 73-74 MB |
| `/Filter null` over the kill stream | Ok (raw 8×8 decode, 192 bytes) | 0.26 s | 75 MB |
| Incremental update redefining object 4 from a harmless Flate image to the `/DCT` lie | Ok, skipped (gate and decoder both see the newest definition) | 0.28 s | 73 MB |
| Object 4 defined twice, no xref (forced repair; the `/DCT` definition last) | Ok, skipped | 0.26 s | 73 MB |
| Flate image 100×100 whose data inflates to 1 GB | Ok (MuPDF reads only `w*h*c` bytes) | 0.25 s | 73 MB |
| Flate 3000×3000 with a matching Predictor 15 `/DecodeParms` over the same 1 GB data | Ok | 0.44 s | 87 MB |
| Progressive DCT, dictionary and SOF both 6324² (40 MP), 3 components, tiny data | Ok | 0.68 s | 316 MB |
| Progressive DCT 5477² (30 MP), 4 components, under a `/DeviceRGB` dictionary | Ok | 0.64 s | 314 MB |
| **8×8 raw RGB image with `/ColorSpace[/ICCBased 6 0 R]`, profile stream = 1 GB of zeros in a 1 MB Flate stream** (`a-icc-bomb.pdf`) | **Err(Io: `realloc (1371016084 bytes) failed`)** after reading the whole profile | 2.5 s | **963 MB** at the 3 GB cap (SR2-1) |
| Same via `/Alternate[/ICCBased 7 0 R]` of a small profile (`a-icc-bomb-alt.pdf`) | same | 2.6 s | **963 MB** |
| Same profile Flate-compressed twice (`/Filter[/FlateDecode/FlateDecode]`, 2.7 KB file) | Ok | 0.56 s | 189 MB |
| `a-icc-bomb` merge / page render (render is not an app operation) | Ok / - | 0.24 / 2.5 s | 72 / 961 MB |

By reading: Compress enumerates images by object number over the whole xref (`scanImages`: 1 until `countObjects()`), so images reached only through Forms, Patterns, Type3, `/Thumb`, `/Alternates`, `/OPI`, soft-mask groups or annotation appearances are gated the same way, and an object stream entry is reached through the same `newIndirect`. The `/Subtype /Image` check is on the decoded name, so `/#49mage` is the same; a stream without `/Subtype /Image` is never decoded by Compress. Inline images are never decoded. `pdf-write.c` only deflates unfiltered streams (`is_image_filter` keeps DCT/JPX/JBIG2/CCITT as they are; there is no `decompress`/`compress-images` in `SAVE_OPTIONS`), and the merged ICC bomb above saved at 72 MB, so the save decodes nothing. Output validation (`loadPage`) does not load images (SR measured this; the 963 MB above is from `loadImage` in `recompress`, not from the save).

**Result: the filter gate itself holds** against every key/value spelling, indirection and xref trick I tried. But default-deny stops at the filter chain: `classify` accepts `ICCBased` from `/N` alone, and `loadImage` then reads the whole ICC profile stream into memory without any bound (SR2-1). This is the same impact as SR-1 by another route.

### B. Deep-object guard attempts
Harness: `scratchpad/sr2/src/SR.java` (copy of SR's, plus `rawop` = the internal op class run directly on a thread of a given stack size with no `EngineStack`, and `resave`), one child JVM per run, `-Xmx256m`, `ulimit -v 3000000`, classes compiled from this worktree. Generators: `scratchpad/sr2/gen2.py`; inputs in `scratchpad/sr2/c/`. Peak RSS is `/usr/bin/time %M`.

| Input | Op | Result | Time | Peak RSS |
|---|---|---|---|---|
| `shortcut-20000` (catalog `/A` lists every chain node in chain order, page hangs the 20k chain) | compress / merge / append | Corrupt (walker depth 20,003: DFS enters node 1 first and follows the chain) | 0.3 s | 74 MB |
| `shortcut-rev-20000` (same, array in REVERSE order; walker depth 6) | compress / merge / append | Ok (markobj visits in the same order as the walker, so it is shallow too) | 0.7-0.95 s | 90-137 MB |
| same, `rawop` on a 256 KB and a 1 MB thread (no EngineStack) | all three | Ok | 0.7-0.8 s | 88-110 MB |
| `shortcut-rev-200000` | merge / compress | Ok / Ok | 5.9 s / **20.4 s** | 348 / 263 MB |
| `shortcut-rev-490000` (35 MB, just under `MAX_OBJECTS`) | merge / compress | Ok / Ok | 14.0 s / **113.5 s** | **488 / 471 MB** |
| `pagetree-10000` (10,000-deep `/Kids`) | all three | Corrupt | 0.3 s | 73 MB |
| `wrapped-2900` (2,900 indirect links, each wrapped in 50 direct arrays; walker depth 147,906) | all three | Corrupt | 0.25 s | 72 MB |
| `outline-20000` (`/Outlines` `/First` chain) | all three | Corrupt | 0.3 s | 74 MB |
| `chain-2990` (accepted by the guard), **`rawop` on a 256 KB thread** | compress / append | **SIGSEGV (rc 139)** | 0.3 s | 71 MB |
| same | merge | Ok | 0.4 s | 77 MB |
| `chain-3000`, `rawop` 256 KB and 1 MB | all three | Corrupt (guard) | 0.3 s | 72 MB |
| Unguarded `garbage=deduplicate` save (`rawsave`), 256 KB thread | - | 2,000 / 2,200 / 2,500 / 2,800 links save; **3,000 crash** (SIGSEGV) | | |
| Unguarded save, 1 MB thread | - | 12,000 links save; **15,000 crash** | | |
| `dense-20000x1000` (pseudo-random refs) | compress / merge | Corrupt (DFS gets deep fast) | 1.4 s | 190 MB |
| `bip-20000x1000` (202 MB, 20M refs, depth 6): guard alone (`depth`) | - | depth 6 | **19.4 s** | **1,137 MB** |
| same | compress / merge | Ok / Err(OutOfMemory) | 35 s / 34.5 s | **1,442 / 1,135 MB** |
| `bipseq-objstm` (**2.0 MB**: 20,000 identical 1,000-ref arrays in Flate object streams): guard alone | - | depth 6 | **18.6 s** | **1,092 MB** |
| same | compress / merge / append | Err(OutOfMemory) / Ok / Ok | 26.8 s / **72 s** / 23 s | **1,114 / 1,130 / 1,094 MB** |
| same, open + `countPages` + `loadPage` only (no walk) | - | Ok | 0.25 s | 69 MB |

Observations:
- The walker's depth is order-dependent (DFS with a visited set), but it walks from the trailer in the same dictionary/array index order as `markobj`, so for the save it measures the depth `markobj` actually reaches. I found no ordering that makes the walker shallow and `markobj` deep. Merge grafts from the page, not the trailer, but in every run the graft did not recurse deep enough to matter: Merge outputs were 1.5 KB even for chain inputs (the chain is not in the merged output; not root-caused).
- **The guard alone is not enough on a 256 KB stack**: a 2,990-link chain passes the guard and Compress/Add images then crash the process. Measured crash depth of the unguarded save: between 2,800 and 3,000 links on 256 KB, and between 12,000 and 15,000 on 1 MB. Margin of `MAX_DEPTH` 3,000: about **4-5x on a 1 MB stack, below 1x on 256 KB**. On the 128 MB `EngineStack` thread the whole `MAX_OBJECTS` range (490k links) is safe, so the defence relies on the stack size being honoured (section C).
- **The guard itself is a memory and CPU bomb** (SR2-1): the walk resolves every reachable object, and MuPDF keeps each parsed object cached, so a 2 MB file of object streams costs 1.1 GB and about 19 s before any operation starts. Pre-fix, Merge never touched objects its pages do not reference; now every Merge input is fully walked.
- A 490k-link chain hidden behind the reverse shortcut is accepted (depth 6) and costs 113 s in Compress (single uncancellable `save`, SR2-2).

### C. On-device (emulator)
I wrote a throwaway instrumented test (`Sr2ThrowawayTest`, deleted afterwards and never committed) and ran it with `./scripts/emulator-smoke.sh`. The AVD is `pdfutils-smoke`, Android 12 (API 31) `aosp_atd` x86_64, and it booted on the first attempt. The test reported through a deliberate assertion failure; the other 19 instrumented tests passed.

- **Stack actually obtained.** I counted Java recursion depth until `StackOverflowError` (a trivial recursive Kotlin function) on threads of different sizes.

  | Thread | Frames before overflow |
  |---|---|
  | `Thread(null, r, name, 128 MB)` (the `EngineStack` shape) | **2,656,932** |
  | Explicit 1 MB | 43,684 |
  | Explicit 256 KB | 27,300 |
  | Plain `Thread(r)` | 1,204 |

  - The plain `Thread(r)` figure was measured first, while the function was probably still interpreted, so it is not comparable with the others.
  - The 128 MB thread goes about 61x deeper than the explicit 1 MB thread. **ART honours the requested stack size**, at least on this x86_64 API 31 image.
- **Chain PDFs generated on the device, run through the real `MuPdfEngine`.** There was no crash in any of these runs.
  - 2,900 links: compress `Ok` (151 ms), merge `Ok` (315 ms), append `Ok` (92 ms).
  - 3,100 links: all three `Err(Corrupt)` (78-79 ms).
  - 20,000 links: all three `Err(Corrupt)` (72-95 ms).
- **Alias-DCT bomb.** The `/Filter/DCT` lie over the 1,667-byte SOF2 65500² stream gave compress `Ok` in **3 ms**: the image was skipped.
- **Not measured on the device:**
  - native depth on a small stack;
  - arm64;
  - other API levels or vendor ART builds;
  - RSS.

### D. Re-fuzz (merge, append)
Seed 9001, 150 s per target, one capped child JVM per target (`ulimit -v 3000000`, `-Xmx256m`). The pool was 26 seeds: 12 `case-compress-1*`, 8 `case-append-1*`, `dct-lie`, `c-inline-dct`, `c-jpx`, `chain-2990`, `a-filter-ind` and `a-incr-dct`. The oracle is the audit's plus "no call > 10 s", with per-iteration VmHWM. The emulator was booting on the same host during the merge run.

| Target | Iterations | Ok | Err | Violations | Max per-iteration HWM / child peak RSS |
|---|---|---|---|---|---|
| merge | 8,696 | 6,214 | 2,482 Corrupt | **1 (slow, 17.3 s)** | 334 MB / 292 MB |
| append | 15,091 | 4,762 | 10,328 Corrupt, 1 Io (RuntimeException) | 0 | 349 MB / 355 MB |

There was no JVM death, no hs_err, no escaped exception class and no `Err` with output left behind.

The slow case, `scratchpad/sr2/fuzz/viol-merge-9001-8696.bin`, is a 15.9 MB mutant with no xref and no trailer. In isolation, merge returned `Ok` in **20.9 s** at 80 MB RSS. Each open repairs it by scanning the whole file, which takes about 4.5 s (`open` + `loadPage`), and the guard walk adds 2.3 s. Merge opens each input twice and then validates the output. This is CPU only, in the SR-3 class, and I filed nothing new for it. The single `Io` in append was not isolated.

### New findings (SR2)

| ID | Sev | Where | Description | Trigger | Impact on a phone | How confirmed | Fix |
|---|---|---|---|---|---|---|---|
| SR2-1 | **Medium** | `engine/ops/CompressOp.kt` `classify` (`"ICCBased"` accepted from `/N` alone) → `doc.loadImage` → MuPDF `pdf-colorspace.c:66` `pdf_load_stream` of the profile | **Unbounded read of an ICC profile stream during Compress.** The filter gate checks only the image stream. Loading the image loads its colour space, and MuPDF inflates the whole `/ICCBased` profile stream into memory (growing with `realloc`) before it looks at it. It works directly and via the profile's `/Alternate`. | `scratchpad/sr2/c/a-icc-bomb.pdf` (about 1 MB: an 8×8 raw RGB image, profile stream = 1 GB of zeros in one Flate stream) | Compress of a 1 MB PDF: 963 MB RSS at a 3 GB address-space cap, ending in `realloc (1371016084 bytes) failed` → `Err(Io)`. Without a cap (Android) the allocation succeeds and grows, so the low-memory killer kills the app. Merge and Add images do not load it (72 MB). | Ran (2 variants; the double-Flate variant stayed at 189 MB) | Before `loadImage`, require the `ICCBased` profile stream (and every `/Alternate` it names) to have a direct `/Length` of at most a small budget (for example 4 MB), and no filter except one `FlateDecode`. Better: skip any ICCBased image whose profile is not a direct, unfiltered or Flate stream under that size; or treat ICCBased like DeviceRGB/Gray by replacing the colour space. Add a test with `a-icc-bomb.pdf`. |
| SR2-3 | **Medium** | `engine/ObjectGraph.kt` `depth` (called for every input by `PdfInputs.open`, including each Merge input, twice) | **The deep-object guard is itself a memory and CPU bomb.** The walk resolves every reachable object, and MuPDF keeps each parsed object in its xref cache, so memory is proportional to the number of references, not to the file size. Identical arrays in a Flate object stream compress about 100:1. Before the fix, Merge only touched objects its pages reference. | `scratchpad/sr2/c/bipseq-objstm.pdf` (**2.0 MB**: 20,000 arrays × 1,000 references, depth 6) | Guard alone: 18.6 s and **1,092 MB** RSS. Merge: 72 s, 1,130 MB, `Ok`. Compress: 26.8 s, `Err(OutOfMemory)` at the 3 GB cap. Add images: 23 s, 1,094 MB. Open + `loadPage` alone: 0.25 s, 69 MB. Picking one 2 MB PDF gets the app killed by the low-memory killer. | Ran | Give the walk a reference budget: count every child visited (each `get` in `depth`), and stop at about 2 M with `PdfError.OutOfMemory` ("too large"). The walk resolves objects at about 55 B per reference (1,092 MB / 20 M), so 2 M references stops `bipseq-objstm` at roughly 110 MB and about 2 s. Real documents have far fewer references (the 30-page and 10,000-page fixtures have under 100k). Add a wall-clock budget (for example 3 s) as a second stop. The `MAX_OBJECTS` cap does not help here: the file has only 21,089 objects. |
| SR2-4 | Low (the emulator honours the 128 MB stack, see C) | `engine/ObjectGraph.kt` `MAX_DEPTH = 3_000`; `EngineStack` | **The depth bound is only safe because of the 128 MB thread.** Measured crash depth of a `garbage=deduplicate` save: between 2,800 and 3,000 links on a 256 KB stack, and between 12,000 and 15,000 on 1 MB. A 2,990-link chain that the guard accepts makes Compress and Add images crash the process (SIGSEGV) when run on a 256 KB thread. The KDoc claim "the guard alone protects even a default-size thread" holds for 1 MB (margin 4-5x), not for smaller stacks. | `scratchpad/sr2/c/chain-2990.pdf`, `rawop compress … 256` | None if the 128 MB stack is honoured. Otherwise, on a 1 MB thread, about 4x headroom. | Ran (host) | Keep `EngineStack`, and make the KDoc state the measured margins. Lower `MAX_DEPTH` to about 1,000: legitimate PDFs nest a few dozen levels, and a 3,000-bookmark `/Next` list would still be refused. Or drop `garbage` from Compress/Add images, so the save does not recurse at all (see the note below). |
| SR2-2 | Low | `PdfInputs.SAVE_OPTIONS` save inside Compress (single native call) | **Long uncancellable Compress on an accepted graph.** A 490,000-link chain hidden behind a reverse-order index array passes the guard (depth 6), because the walker and `markobj` take the same shallow path. Compress then spends 113 s in one `save` (`garbage=deduplicate`). Merge of the same file takes 14 s. | `scratchpad/sr2/c/shortcut-rev-490000.pdf` (35 MB) | Engine thread blocked for about 2 minutes, with 471-488 MB RSS. Cancel has no effect until it finishes. | Ran (also 200k: 20 s compress, 6 s merge) | The same reference budget as SR2-3, plus a per-input object budget for Compress (for example 200k), or `garbage=compact` instead of `deduplicate` (deduplication is the quadratic part; not measured separately). |

Note on removing the recursion (by reading `pdf-write.c` 1.28.5): `markobj` (`:191`, recursive) is called only under `if (opts->do_garbage >= 1)` (`:2740-2742`). Without `garbage`, every object is marked used (`:2746-2747`), and no other step of the save follows references. Deduplication and renumbering both need `garbage`. Object serialisation recurses only on direct nesting, which the parser caps. So saving Compress and Add images **without `garbage`** removes the indirect-depth recursion from those two ops, at the cost of keeping unreferenced objects, including the replaced image data, which is overwritten in place, so nothing is orphaned. Merge cannot drop it this way: `pdf_graft_mapped_object` (`pdf-graft.c:94-190`) recurses once per indirect link, through `pdf_resolve_indirect` for dictionaries and arrays. The guard (or the large stack) therefore stays necessary for Merge. In my runs the Merge graft never reached the chain depth (outputs were 1.5 KB; not root-caused), so this is from reading only.

### Verdict (SR2)
**Not yet acceptable for public release: two Mediums remain, both new, and both kill the app on a phone.**
- The SR-1 filter gate holds. Every name spelling, indirection, duplicate-key, incremental-update and repair trick failed to get a DCT stream past it.
- But default-deny does not extend to the colour space. A 1 MB PDF whose ICCBased profile is a Flate bomb drives Compress to an unbounded allocation through `loadImage` (SR2-1: 963 MB at a 3 GB cap).
- The SA-11 fix holds where it matters: the 128 MB stack is honoured on the emulator, chains up to the 500k-object limit run without a crash on the host, and on the device 2,900 links give `Ok` while 3,100 and 20,000 give `Corrupt`.
- But the new guard walk is itself a bomb. A 2 MB object-stream PDF costs about 1.1 GB and about 19 s in `PdfInputs.open`, for every operation including Merge (SR2-3).
- SR2-4 (the guard alone is not enough on a 256 KB stack) is Low, given the device result.
- SR2-2 (a 113 s uncancellable save) is Low.
- With SR2-1 fixed (a size/filter bound on ICC profile streams before `loadImage`) and SR2-3 fixed (a reference budget in the walk of about 2 M, plus a time budget), each with a regression test, I would rate the engine acceptable.

### Not covered (SR2)
- No arm64 device and no API level other than 31. RSS was not measured on the device, and the native recursion depth on Android was not measured (only the Java depth ratio).
- **Merge graft depth.** In every run the merged output was 1.5 KB and did not contain the chain, so the recursion of `pdf_graft_mapped_object` was never exercised deeply. I did not root-cause why. The 50-input Merge of a just-under-3,000 chain was not run.
- **Not built:**
  - JPEG with DNL plus a matching SOF, arithmetic tables, or a second frame inside a matching stream;
  - DeviceN/Separation with a function (Compress skips these colour spaces by reading);
  - Indexed with a huge lookup table (skipped by `classify`);
  - images reached only through Pattern, Type3, `/Thumb`, `/OPI`, annotation appearances or soft-mask groups (covered by reading: the scan is over all object numbers);
  - a generation-number mismatch.
- **Not run:**
  - `garbage=deduplicate` on wide-and-deep objects (`pdf_objcmp`): the guard rejects depth over 3,000, and `pdf_objcmp` does not follow references;
  - a deep content stream through `compress`;
  - a 100 MB xref;
  - the guard on 500,000 objects × 1,000 references (the file would exceed the disk quota; the 20M-reference case already breaches the budget);
  - `a-icc-bomb` through append.
- **Not proven:** that dropping `garbage` from Compress/Add images removes every recursion. This is from reading `pdf-write.c` only.
- **No ASan run and no coverage-guided fuzzing.** The fuzz ran 150 s per target.

## SR3 — re-check of SF5

Re-checker: SR3, independent of the fix authors. Code at `main` `b938777` (merged ff-only), compiled in this worktree (`:app:compileDebugKotlin`). Harness: `scratchpad/sr3/SR.java` (SR2's probe plus `walk objects|pages <file>` = `PdfInputs.open` + one `ObjectGraph` walk alone, and `open`), never committed. Every hostile input ran in its own child JVM: `-Xmx256m -XX:+UseSerialGC`, `ulimit -v 3000000`, `timeout -s KILL`, host MuPDF 1.28.5. Peak RSS is `/usr/bin/time %M`; times are wall-clock for the whole JVM (about 0.25 s of it is JVM start-up). Generators: `scratchpad/sr3/gen3.py`; inputs in `scratchpad/sr3/c/`; raw result lines in `scratchpad/sr3/results.txt`.

### Status of SR2-1 … SR2-4

| ID | Verdict | Evidence (original reproducers, current code) |
|---|---|---|
| SR2-1 (ICC profile bomb in Compress) | **Fixed** for the reproducers (see the new findings for what the gate does not cover) | `a-icc-bomb.pdf` compress: `Ok`, image skipped, **75 MB**, 0.33 s (SR2: 963 MB, `realloc` failure). `a-icc-bomb-alt.pdf`: `Ok`, 72 MB, 0.28 s. Double-Flate `a-icc-bomb2.pdf`: `Ok`, 72 MB. `a-icc-bomb` append: `Ok`, 75 MB. `icc-lie.pdf`: `Ok`, 73 MB. |
| SR2-2 (113 s uncancellable Compress) | **Fixed** | `shortcut-rev-490000` compress: `Err(OutOfMemory)` (object cap) in 0.48 s, 89 MB (SR2: 113.5 s, 471 MB). `shortcut-rev-200000` compress and merge: `Err(OutOfMemory)`, 0.3 s, 77 MB. Just under the cap, `shortcut-rev-190000`: compress `Ok` in 2.5 s, 256 MB; merge `Err(Corrupt)` (nesting) in 0.3 s. |
| SR2-3 (guard walk is a memory/CPU bomb) | **Fixed** (bounded; the bound is about 2.5x looser than documented) | `bipseq-objstm.pdf` (2.0 MB): `checkObjects` alone stops with `OutOfMemory` after 1.76 s at **287 MB** (SR2: 18.6 s, 1,092 MB). Compress and Add images: `Err(OutOfMemory)`, 2.1-2.2 s, 285-286 MB (SR2: 23-27 s, 1.1 GB). Merge: `Ok`, 0.63 s, 79 MB (SR2: 72 s, 1,130 MB; the page does not reach the arrays). The `ObjectGraph` KDoc says 2 M references are "~110 MB of parsed objects"; measured 285 MB, because resolving one object parses its whole object stream before the budget counts anything (see SR3-1). |
| SR2-4 (depth guard only safe on the 128 MB stack) | **Fixed** for Compress and Add images | Internal op class run directly on a **256 KB** thread (`rawop`, no `EngineStack`): `chain-2990` compress/append `Ok` (SR2: SIGSEGV); `chain-20000` compress/append `Ok`, 0.5 s, 89 MB; `chain-100000` compress `Ok` 1.5 s / append `Ok` 1.2 s, 175 MB. No crash: the save no longer recurses over references. Merge on 256 KB: `chain-2990` and `chain-100000` `Err(Corrupt)` (nesting > 200), 0.3 s. |

### New findings (SR3)

| ID | Sev | Where | Description | Trigger | Measured effect | Fix |
|---|---|---|---|---|---|---|
| SR3-1 | **Medium** | `engine/ObjectGraph.kt:86` (`ref.resolve()` before any `budget.spend()` for the object's entries), `:164` (same in `checkPages`), `engine/PdfInputs.kt:68` (`countPages` in `open`); MuPDF `pdf-xref.c:2097` `pdf_load_obj_stm` | **The reference budget is charged after the parse, and one parse can be unbounded.** Resolving any object that lives in an object stream makes MuPDF parse *every* object in that stream (`pdf_load_obj_stm` loops over all `/N` entries) in one native call. A single array object of 20 M `1 0 R` compresses about 700:1. The budget then sees one object with 20 M entries and stops, but only after MuPDF has already built it. Neither the count nor the 3 s deadline can interrupt the call. If the catalog, `/Pages` and the page share that object stream, `PdfInputs.open` pays the cost before any walk runs, so all three operations are hit, including Merge (which opens each input twice). | `scratchpad/sr3/gen/gen3.py bigarr <out> N where`: one Flate object stream whose last member is `[1 0 R 1 0 R …]` with N entries. `where 0`: unreferenced; `where 1`: catalog, Pages and page in the same object stream. `bignull N`: the same with `null` elements. | `bigarr-20m-0.pdf` (**175 KB**): `checkObjects` alone 5.0 s, **902 MB**, then `OutOfMemory`. Compress and Add images: `Err(OutOfMemory)` after 6.8 s at **902 MB**. Merge: `Ok`, 71 MB (the page does not reach it). `bigarr-20m-1.pdf` (175 KB): `PdfInputs.open` alone **5.0 s, 901 MB**. Merge: **29.3 s**, 900 MB, `Ok`. `bigarr-60m-1.pdf` (525 KB): `open` reached **1,502 MB** in 11.5 s and failed with `malloc (24 bytes) failed`, reported as **`Corrupt`**. The peak tracks the 3 GB address-space cap, so the allocation is unbounded. `bignull-100m.pdf` (729 KB): compress 8.3 s, 1,131 MB. On Android, with no cap, running any operation on a sub-1 MB file gets the app killed by the low-memory killer. The cost is linear in the file's inflated size: about 5 KB of file per MB of RSS. | The budget cannot fix this from Kotlin, because the cost happens inside one MuPDF call. Robust fix: run the engine in a separate process (an `android:process=":engine"` service). If that process dies or is killed, report `OutOfMemory`, and the UI process survives every MuPDF bomb class (this one, SR3-2, and any future one). Partial mitigation: before `Document.openDocument`, pre-scan the raw file for streams whose dictionary is `/Type /ObjStm` (names normalised for `#xx` escapes), inflate each with a bounded `Inflater` (as `ColorSpaceGate.decodedSizeWithin` does), and refuse decoded object streams over about 8 MB or with an `/N` over 100k. This is incomplete: repair can find object streams a scan misses. Also map `malloc … failed` during `open` to `OutOfMemory`, not `Corrupt` (SA-4). Correct the "~110 MB" KDoc: the 2 M budget measured **285 MB** on `bipseq-objstm`. |
| SR3-2 | Low (CPU only) | MuPDF `pdf-object.c:2515` (`pdf_dict_get_put`: once a dictionary has more than 100 keys it is kept sorted, and each insert `memmove`s the tail); reached from `ObjectGraph.kt:86` / `:164` and from every load of the object | **Quadratic parse of one large dictionary, outside the walk's time budget.** A dictionary written with its keys in descending order makes every insert during parsing shift the whole array, so the cost grows with the square of the key count. It happens in one `resolve()`, so the 3 s deadline (checked every 1,024 spends, between calls) cannot stop it. | `gen3.py bigdict <out> N`: one dictionary `<< /k00N-1 0 … /k00000000 0 >>` in a Flate object stream, unreferenced | `bigdict-200k.pdf` (472 KB): `checkObjects` **16.2 s**, 111 MB, then `OutOfMemory` (budget). `bigdict-400k.pdf` (945 KB): compress **66.0 s**, 173 MB, `Err(OutOfMemory)`. Time quadruples when the key count doubles. The engine thread is blocked and cancel has no effect until it ends. The same object in the catalog's object stream would charge `open` (by construction, as in SR3-1; not run). | Same process-isolation fix as SR3-1 (a watchdog can then kill the engine process after N seconds). Without it, the raw-file object-stream pre-scan could also refuse a decoded object stream over a size bound, which caps N. Low, because it is CPU only and finishes. |
| SR3-3 | **Medium** | `engine/ops/MergeOp.kt:80-90` (`copyExternalLinks`: `srcPage.links`, then one `createLink` per link, with no count limit and no cancellation check inside the loop); `engine/PdfInputs.kt:154` (`loadPage` of every page in output validation); `engine/ObjectGraph.kt` (`checkPages` follows only the graft keys, so `/Annots` is never charged) | **`/Annots` is outside both walks, and Merge's link copy is quadratic.** A page's `/Annots` array can hold up to about 2 M references to one shared URI Link annotation. `checkObjects` counts the array once, however many pages share it, and `checkPages` does not look at it at all. Merge then loads the source page's links and calls `createLink` for each one on the new page, and each call costs time proportional to the links already on that page. Compress and Add images pay at `loadPage` during output validation: MuPDF builds every annotation of the page. | `gen3.py annots <out> M P`: one `/Annots` array of M `6 0 R` (in a Flate object stream), shared by P pages; object 6 is one Link with a `/URI` action. | **Merge** (input given twice): `annots-5k-1` 0.8 s; `annots-20k-1` 9.5 s; `annots-50k-1.pdf` (**1,123 bytes**) **60.6 s**, 228 MB, `Ok`. Time grows with the square of the link count, so 200k links would take about 16 minutes. `annots-200k-20.pdf` (4.4 KB) was killed by the 120 s timeout. Cancel is only checked between pages, so it has no effect during a page. **Compress / Add images:** `annots-1900k-1.pdf` (**17 KB**): compress `Ok` after 7.8 s at **883 MB** (the `loadPage` of validation; about 460 B per annotation, and within the 2 M budget). `annots-200k-20`: compress 8.0 s, append 8.2 s, 148-155 MB. With P pages sharing the array, the time is P × M annotations: `annots-1900k-100.pdf` (27 KB) would need about 13 minutes (estimated from 7.8 s per page, not run), though Compress can be cancelled between pages. | Merge: cap the links copied per page (for example 1,000; real pages have tens), and check cancellation inside the loop. Better: collect the external URIs and add them in one pass that does not re-scan the page's `/Annots` each time. All ops: count `/Annots` entries per page in the walk (`checkPages`, and a per-page sum for Compress and Add images, charging P × M), and refuse pages with more than about 10,000 annotations as too large. |
| SR3-4 | Low (CPU only; the SR-3 class, still open) | `engine/ops/MergeOp.kt` `dst.save(out, PdfInputs.SAVE_OPTIONS)` (`garbage=deduplicate` on the new document); `ObjectGraph.checkPages` budgets each input separately, never the merged document | **Merge's save of the new document is not budgeted.** Each input passes its own 2 M / 3 s walk, but the grafted copy of every input accumulates in `dst`, and `garbage=deduplicate` then compares many distinct small streams in one native call that cannot be cancelled. | `gen3.py wideforms <out> K`: one page whose `/Resources /XObject` names K distinct 1×1 form XObjects (raw streams, about 90 B each) | `wf-20k.pdf` (2.4 MB), merged with itself: **106.8 s**, 224 MB, `Ok`. Compress of `wf-50k.pdf` (6.1 MB): 3.3 s, 258 MB. Merge of `wf-50k` with itself was stopped unfinished after more than 3 minutes, and its time was not recorded. Its walk had passed. | Charge one shared budget across all Merge inputs (one `Budget` object for the whole job), and cap the objects in `dst` (for example 200k in total). Alternatively, save Merge's `dst` with `garbage=compact` (no dedup) and measure. |
| SR3-5 | Info | `engine/PdfInputs.kt:68` (`countPages` failure → `Corrupt`); `engine/ops/AppendImagesOp.kt` (MuPDF errors at insert time pass through as `Io`) | **Misclassified errors (SA-4 class).** Out-of-memory during `open` (`malloc (24 bytes) failed`, from SR3-1 `bigarr-60m-1`) is reported as `Corrupt`. A `/Parent` cycle above the last page (`gen3.py parentcycle`) gives compress/merge `Corrupt` but append **`Io(RuntimeException: cycle in parent chain)`**. Both fail cleanly, within 0.3 s and at 74 MB, with no hang. | as stated | Wrong message only | Apply `isOutOfMemoryFailure` to the `countPages` catch in `open`; map MuPDF format errors from `appendImagePage`/`pageSizeOf` to `Corrupt`. |

Other results (no finding):
- MuPDF's page-tree and `/Parent` handling is iterative. With no walk on Compress/Add images, the SR2 section-B shapes were run through the op class on a **256 KB** thread: `pagetree-10000`, `outline-20000`, `wrapped-2900` and `shortcut-20000`. Every run was `Ok`, at 0.6-1.0 s and 88-105 MB, with no crash. Merge refused `wrapped-2900` and `shortcut-20000` as `Corrupt` (nesting), and returned `Ok` for `pagetree-10000` and `outline-20000`, which the graft does not follow.
- `/Parent` cycle (`parentcycle.pdf`): no hang in any op (see SR3-5).

### Effect of the `:engine` process (merged to `main` after this re-check's base `b938777`; not re-measured here)
Since this base commit, the engine runs in a separate `:engine` process. On a crash or out-of-memory kill the app reports an error instead of dying, and cancel kills the process after about 2 s. I read the description only; I did not test it.
- **What it mitigates in principle:** SR3-1 can no longer take the UI process down: an LMK or `malloc` death of `:engine` is contained, and so is any future native crash. SR3-2, SR3-3 and SR3-4 become hard-cancellable: the user's cancel kills the process within about 2 s, even inside a native call or inside the per-link loop of SR3-3.
- **What still needs an engine-side fix:**
  - (a) **Memory pressure.** SR3-1 still allocates about 900 MB (or more: the size is bounded only by the device) before it dies. On a 2-3 GB phone the LMK may kill other background apps first, and the engine process is chosen as the victim only if its `oom_adj` ranks it so. The pre-scan or bound remains necessary.
  - (b) **Jobs nobody cancels.** A Merge or Compress that just runs for minutes still blocks the job queue until the user notices: 60 s for a 1 KB file (SR3-3), 107 s (SR3-4), 66 s (SR3-2). Engine-side caps (links per page, annotations per page, a shared Merge budget, an object-stream size bound) or an automatic watchdog timeout are still needed.
  - (c) **Error messages.** The error must say "too large" rather than "corrupt". This depends on how the parent process maps a dead `:engine`; not checked.

### Not covered (SR3)
- **Colour-space gate bypass attempts:** only the SR2 reproducers were run (`a-icc-bomb`, `-alt`, `-bomb2`, `icc-lie`: all skipped or bounded). These were reviewed by reading `ColorSpaceGate.kt` and `CompressOp.classify`, but no input was built or run:
  - indirect or nested colour-space arrays;
  - Indexed, Separation, DeviceN and Pattern bases (all `SKIP` by reading: only two-element `CalRGB`/`CalGray`/`ICCBased` arrays and four device names are accepted);
  - name aliases;
  - reference loops (the `/ColorSpace` value is resolved once, and the `/Alternate` only once, so there is no loop by reading);
  - a colour space reached through an SMask or Mask (an image carrying either key is skipped, and mask targets are never decoded, by reading);
  - an ICC stream with chained filters (`SKIP` unless the chain is RAW or exactly one Flate filter, by reading);
  - a lying `/N` together with lcms2 parsing of a hostile profile of up to 4 MiB;
  - a profile whose direct `/Length` lies low;
  - many images sharing one 4 MiB profile (the gate inflates the profile once per image; the cost is bounded but repeated).
- **Stale `countObjects()`:** whether MuPDF's lazy xref repair, triggered during a walk, raises the object count above the value both walks read once at the start, so that objects above it are skipped by `checkPages` and the 200,000 cap is checked on the pre-repair count. Not tested.
- **Deadline granularity:** slow-per-spend objects (for example one object per object stream, or long `/Parent` chains under `getInheritable`) were not built. SR3-1 and SR3-2 already show that a single native call overruns the 3 s deadline.
- **Merge-specific inputs:** `mergeN 5` was not run, and `wf-50k` merge was not finished. Forms nested inside forms about 100k deep were not run (by reading, `checkPages` refuses nesting over 200).
- **Regression on legitimate files:** not measured. I did not check real large PDFs for object count or walk `spent`, and did not generate a legitimate file of more than 200,000 objects. A real document with more than 200k objects, or more than 2 M entries (for example a large scanned atlas or a CAD export), is now refused as "too large". No concrete example was built.
- No device or arm64 run, no ASan build, and no re-fuzz.

### Verdict (SR3)
**SR2-1 to SR2-4 are fixed as reproduced.** But the walk budget is charged after MuPDF's parse, so a 175 KB file still costs about 900 MB (SR3-1, Medium, unbounded). `/Annots` is outside both walks, and a 1 KB file blocks Merge for 60 s (SR3-3, Medium). The new `:engine` process contains the crash and makes these cancellable. With it, I would rate SR3-1 and SR3-3 acceptable for release only if, in addition, the link copy is capped (SR3-3) and an automatic engine timeout or object-stream bound is added (SR3-1/2/4).

## SF6 / PI status

Written by the FR final review (`REVIEW.md`, "Final review: process isolation (FR)"). **[FR device]** marks my own evidence: FR Part C (retry), the API 31 x86_64 2 GB emulator, `PdfJobs.run` with the production `RemoteEngine` and limits (787 MiB). Compress was HIGH, and Merge used the file plus a one-page PDF. The other labels: **[author]** means SF6/PI3 commit messages, unit tests and `platform-audit.md`; **[SR3]** means this file's SR3 section, measured before SF6 on the host. Code statements marked **[FR code read]** are my own reading of the code, not measurements.

| Item | Status | Evidence | Remaining exposure |
|---|---|---|---|
| SR3-1 (object-stream parse before budget; 175 KB → 900 MB) | **Mitigated** | [author] `ObjStmScan` pre-scans raw object streams with a bounded `Inflater`. It refuses more than 16 MiB for one stream or 64 MiB for all of them, and non-Flate or chained filters on such a stream. [author] PI3 memory watchdog: min(1536 MiB, 40 % RAM), sampled about every 1 s. [FR code read] the scan runs before `openDocument`, and the engine is a separate process. [FR device] `bigarr-20m-1`: Compress `Failed(OutOfMemory)` in 0.76 s, Merge in 0.03 s. `bigarr-60m-1`: 0.45 s and 0.02 s. Peak engine RSS was 121-138 MiB (about idle); the same engine served the next job, which succeeded. | Object streams the scan misses (MuPDF repair, by the author's own note). Memory can still rise to the RSS limit plus one sampling interval. The engine is not `isolatedProcess`. |
| SR3-2 (quadratic dictionary parse; `bigdict-400k` 66 s on host) | **Mitigated** (not fixed in the engine; SF6 does not list it) | [author] PI3 stall watchdog: 90 s without progress gives `TimedOut`, and a user cancel kills the engine within about 2 s. A decoded stream under 16 MiB (about 5 MB for 400k keys) passes `ObjStmScan`, so the parse itself is unchanged. [FR device] `bigdict-400k` Compress: **`Failed(TimedOut)` after 90.5 s**, with the engine killed (peak 162 MiB) and the next job `Ready` in a new engine. Merge: `Ok` in 0.1 s (the dictionary is not reached). | Up to 90 s of CPU per job. FR-1: the same stall limit can misfire on good files after a background freeze. |
| SR3-3 (`/Annots` outside walks; quadratic Merge link copy) | **Fixed** (as reported) | [author] each page's `/Annots` length is charged to the walk budget (P × M), a page over 10,000 annotations is refused before `loadPage`, Merge copies at most 1,000 links per page, and the copy loop polls cancel. [FR code read] `MergeOp.kt:86-133` has the cap and the poll. [FR device] `annots-50k-1` (1.1 KB): Compress `Failed(OutOfMemory)` in 0.56 s, Merge in 0.03 s (SR3: Merge 60.6 s), with the next job `Ready`. | Up to 1,000 `createLink` calls per page (still quadratic, but bounded). |
| SR3-4 (Merge's save of the merged document not budgeted; `wf-20k` 107 s) | **Mitigated** | [author] one reference budget for all Merge inputs. `garbage=deduplicate` applies only up to 5,000 objects, and `compact` above that. Backstop: the stall watchdog. [FR device] `wf-20k` Compress `Ready` in 3.8 s (197 MiB). Merge with a one-page PDF `Ready` in 4.2 s (212 MiB). The SR3 case (merged with itself) was **not** re-run, so the dedup cutoff is unverified by me. | The time budget still restarts per input. The save remains one call that cannot be cancelled, and it is bounded only by the watchdog. |
| SR3-5 (allocation failure reported as `Corrupt`; append `/Parent` cycle reported as `Io`) | **Fixed** (as reported) | [author] commit "Map allocation failures in open to OutOfMemory, /Parent cycles to Corrupt (SR3-5)". Not checked by me. | Message accuracy only. |

New items from FR that touch engine robustness: FR-1 (the watchdog counts frozen background time) and FR-2 (after the UI dies, an orphan engine job runs with no watchdog and shares its process with the next one). See `REVIEW.md`.

## Reader render path (branch `pdf-reader`, 2026-11-25)

Addendum written from the code at `6adc734` and the reader reviews (`REVIEW.md`, "PDF reader review (RDR, RF)"). Not merged to `main`. Nothing here was measured: no hostile input was built or run for this addendum.

### What is new

- Two read-only engine requests: `PageInfo` (open, count pages, read every page's size) and `RenderPage` (draw one tile of one page into a file of raw RGBA).
- **Before this branch the engine never rasterised a page.** `RenderPageOp` calls `Page.run` with a `DrawDevice`, which decodes every image, font, shading and pattern the page draws, and its annotations and form widgets. This is MuPDF's largest decoder surface, reached from any picked PDF.

### Bounds that apply

- **Paths.** Both paths of a request must resolve (canonical) strictly inside `cacheDir/jobs`, as for the other requests. A render's output file is deleted on every outcome other than `Ok`.
- **`RenderLimits`** (checked when the request is decoded and again against the page's size): at most 8,000,000 pixels per tile, scale in (0, 16], a rectangle inside the scaled page; at most 50,000 pages.
- **Annotations.** All pages' `/Annots` lengths are charged to the walk budget in `PageInfo`; a page over 10,000 annotations is refused before `loadPage` in both ops.
- **`PdfInputs.open`** pre-checks on every call: 1 GiB file cap, `ObjStmScan`, encryption, object count. `ScanMemo` remembers only a passed pre-scan and passed image-gate pages for one document (key: canonical path, length, mtime, CRC-32 of the first and last 64 KiB); a failure is never remembered.
- **`RenderImageGate`** before `Page.run`: per image 256 MiB decoded and 65,500 px per edge; 1 GiB per page; JPEG SOF and JPX SIZ read from the stream (32 MiB per stream, 256 MiB per page); JPX 64 M samples; predictor rows 1 MiB; ICC profiles 1 MiB raw / 4 MiB inflated; walk budget 32 levels, 20,000 visits, 2 s; cancellation polled. Over a limit is `OutOfMemory`; an unparsable object is `Corrupt`. Details and the walk's reach are in the gate's KDoc.
- **Cancel.** A watcher thread polls every 50 ms during `Page.run` and calls `Cookie.abort()`; MuPDF checks it only between content-stream operators, so one expensive operator is not interrupted.
- **Client watchdog** (`RemoteEngine`, `EngineLimits`): 30 s total per render, 60 s per page-info request, no stall limit for either (they report no progress); the RSS cap (min(1.5 GiB, 40 % of RAM)) kills the engine with `OutOfMemory`. A request is re-sent only if it never reached the engine.
- **No automatic retry.** A failed page is not requested again until the user taps Retry.
- **UI-side checks of replies.** Page sizes must be finite and > 0, at most 50,000 pages, then clamped to [1, 2,880,000] pt. A tile file must hold exactly `width x height x 4` bytes before a buffer is allocated. Item sizes are capped (60,000 x 8,191 px).

### Not covered

- From the gate's KDoc: inline images (including an inline DCT whose SOF lies); JBIG2 internals and `/JBIG2Globals` (the dictionary size is checked); DeviceN/Separation tint functions; shading patterns and `sh`; fonts other than Type 3; `/MK` icons of widgets without an appearance stream; an image's `/Alternates`; appearances MuPDF synthesises; colour-space lookups for inline images.
- RF-4: a stream longer than its `/Length` is read to `endstream` before the size checks.
- RF-6 remainder: ICC profiles of `/Shading`, `/Group /CS`, DeviceN/Separation alternates and an ICC stream's own `/Alternate`.
- RF-8 (open): an xref repair during `Page.run` might re-point an image the gate passed to a later duplicate. The lazy-repair mechanism is confirmed in the MuPDF source; an exploit is unproven.
- H5-1 (fixed): MuPDF's `pdf_lookup_resource` searches the whole resource stack, so a form with its own `/Resources` (a pattern or soft-mask group runs on the stack where it is painted) could draw an image named only in an ancestor's narrowed `/XObject` dictionary, which the gate never checked. The gate now narrows page-wide: every name in every content stream it walks is checked in every narrowable dictionary. Narrowing is given up for the page on an unscannable stream, more than 65,536 distinct names, or a Type 3 font without `/Resources`. Only `/XObject` is narrowed; the other resource categories are walked in full. Regression tests are in `RenderImageGateTest` (`h5_1_*`).
- F4, the current form of the gate's walk, **has not been independently reviewed**.
- **None of the render path has been run against this audit's hostile-file set on a device**, or on the host. The SR/SR2/SR3 reproducers were built for Compress, Merge and Add images.

## Reader text path (branch `pdf-reader`, 2026-11-26)

Addendum written from the code at `561c0e6` (H2, page text for H3's selection and copy). Not merged to `main`. Nothing here was measured or run for this addendum; no hostile input was built for the text path.

### What is new

- One read-only engine request, `PageText` (`EngineRequest.PageText`, `PdfPageRenderer.pageText`, `engine/ops/PageTextOp.kt`): the text of one page with the box of every character, written by the `:engine` process to a file in the document's `cacheDir/jobs/reader-<uuid>/` directory. The UI calls it through `PdfReader.text`, on the same engine thread as renders, only when the user long-presses a page or uses "Copy page text".
- `PageTextOp` runs the page into a `DisplayList` with `Page.run` (with a `Cookie`), then `DisplayList.toStructuredText("")` (default options: no images kept, no dehyphenation) and walks the result.

### Bounds that apply

- **Paths.** Both paths (`pdf`, `out`) are in `EngineRequest.PageText.paths`, so `EngineServer.checkPaths` requires them to resolve strictly inside `cacheDir/jobs`, as for the other requests. On every `Err` the output file is deleted (in `PageTextOp.run`, in `EngineServer`, and again in `RemoteEngine.pageText`); `PdfReader.text` deletes it after parsing in every case.
- **Pre-checks, in the order of `RenderPageOp`:** `PdfInputs.open` with the `ScanMemo` key (1 GiB cap, `ObjStmScan`, encryption, object count), the page index, `checkPageAnnots` (10,000 annotations), then `RenderImageGate.checkPage`, memoised per page and shared with renders.
- **Why the image gate is needed.** The structured-text device keeps no image (`preserve-images` is not set), but `Page.run` into any device other than one that skips image decoding makes the PDF interpreter load every image it reaches (`pdf_load_image`: the whole stream is read, a JPX header parsed, colour spaces and ICC profiles loaded). Without the gate, a text request would reach the image surface that the gate keeps from renders. The gate's limits and gaps are the same as in "Reader render path" above.
- **Caps on the output.** At most 200,000 UTF-16 units per page (`PageTextFormat.MAX_UNITS`); text past that is dropped and the reply is marked `truncated`. Invalid code points become U+FFFD; non-finite boxes become zero; every coordinate is clamped to ±4,000,000 pt (`MAX_COORD`). This bounds the file and the UI's memory. **It does not bound what MuPDF builds inside the engine** (the display list and the structured text of a huge page): that is left to the time limit and the RSS cap.
- **Output file validated in the UI process.** `RemoteEngine.pageText` accepts an `Ok` only if its unit count is in 0..200,000 and the file exists, is at least the 24-byte header and at most `PageTextFormat.MAX_FILE_BYTES` (about 5.2 MB). `PageTextFormat.read` (format "OKTX", version 1, big-endian) then checks the magic, version, flags, unit/line/block counts against the caps, the exact file length implied by the counts (before allocating), every box (finite, within `MAX_COORD`, `x0 <= x1`, `y0 <= y1`), lines (first at 0, strictly increasing, inside the text), block numbers (from 0, steps of 0 or 1, last equals the count) and no trailing bytes. `PdfReader.text` also requires the parsed length and `truncated` flag to equal the reply's. Any failure is `Err(Io)` and the user sees "Could not read the text on this page."
- **Time limit.** `EngineLimits.textTotalMs`, 30 s total per request, with no stall limit (the request reports no progress), and the RSS cap (min(1.5 GiB, 40 % of RAM)). Breaking either kills the engine (`TimedOut` / `OutOfMemory`); the next call binds a fresh one.
- **Cancel.** Asked for only when the document is closed (`PdfReader` passes `document.closed` as `isCancelled`). A watcher thread polls every 50 ms during `Page.run` and calls `Cookie.abort()`, as in renders, so one expensive operator is not interrupted. `toStructuredText` and the walk take no cookie: they are checked for cancel only after they return, and are bounded by the time limit and the RSS cap.
- **No automatic retry.** A failed text request shows a message; it is asked again only on the user's next long-press or copy.

### Not covered

- Everything listed under "Not covered" in "Reader render path" applies here too (the same gate and `Page.run`).
- Font loading during text extraction is MuPDF's, unbounded except by time and memory, as for renders.
- The text path has not been run against the hostile-file set, on a device or on the host. On the host, `PageTextOpTest` runs the op with host MuPDF on ordinary PDFs and on a few small refused ones (a 60,000 x 60,000 px image refused by the gate, too many annotations, encrypted, corrupt), and `PageTextFormatTest` feeds malformed files to the parser.

## Reader password addendum (K1)

The reader (`pageInfo`, `renderPage`, `pageText`) now opens encrypted PDFs (`PdfInputs.openForReader`); the tools still refuse every encrypted PDF (`PdfInputs.open`, unchanged). The password travels in the request Bundle (documented exception to "only paths"), capped at 127 bytes of modified UTF-8 and free of U+0000 (`ReaderPassword`, checked by `RemoteEngine`, `EngineWire.decodeRequest` and `openForReader`; MuPDF truncates R5/R6 passwords at 127 bytes). It is never in a file, a log, an exception message or a `toString`.

Pre-checks on an encrypted input, before and after:

- **Size / object count** (`PdfInputs`): file length and xref length; independent of encryption. Unchanged.
- **`ObjStmScan`** (raw file bytes): limits are per object stream 16 MiB decoded and 16 MiB consumed, 64 MiB decoded and consumed for all object streams together; filter none or Flate alone, anything else refused. On an encrypted file the data is ciphertext (not valid zlib: previously ignored "so encrypted files reach the Encrypted check"). Now pass 1 records every Flate candidate with its `num gen obj` header; after `authenticatePassword` and before `countPages`, pass 2 (`ObjStmScan.checkDecrypted`) reads each through MuPDF `readRawStream` (crypt applied, no other filter) and enforces the same limits on the plaintext (budget continues from the unfiltered bytes of pass 1). Refused: a candidate without a header, more than 10,000 candidates, an object that does not resolve to a stream, a stream-level `/Crypt` or any other filter, raw over 16 MiB.
- **`checkPageAnnots` / `ObjectGraph.checkAnnots`**: MuPDF objects, decrypted after authentication. Effective as before.
- **`RenderImageGate`**: dictionaries via MuPDF; JPEG SOF, JPX SIZ, Flate-before-DCT and ICC readers use `readRawStream`, which `pdf_open_raw_filter` (pdf-stream.c) decrypts unless the stream names its own `/Crypt` filter; such a stream has an extra filter, which every raw reader refuses. A reader that cannot parse what it gets refuses.
- **`ScanMemo`**: key now includes `auth`, 8 bytes of SHA-256(per-process salt || password), 0 for none; verdicts are recorded only after MuPDF accepted the password.

Crypt handler: `pdf_new_crypt` throws on unknown `/Filter`, `/V`, `/R`, missing `/O` `/U` (`/OE` `/UE` for R6), invalid key `/Length` and unknown `/CF` crypt filters; MuPDF reads only fixed-size key material. R6 key derivation (`pdf_compute_hardened_hash_r6`) runs at most 64 + 255 + 32 rounds over at most (127 + 64 + 48) x 64 bytes: bounded.

Residual: (1) as for every pre-check, `ObjStmScan` is a mitigation: an object stream whose header number differs from MuPDF's xref view can be loaded by pass 2's own resolution before it is measured. (2) A PDF with an empty user password whose xref is broken is repaired inside `Document.openDocument`, after MuPDF authenticated the empty password; repair loads object streams before pass 2 can run (this also holds for the tools, which refuse encrypted files only after `openDocument`). The `:engine` process remains the backstop. (3) Non-BMP characters in an AES-256 password reach MuPDF as modified UTF-8 (CESU), not UTF-8, so such a password may not match.

## Reader pre-check fixes (K7, branch `pdf-reader`, 2026-11-26)

- **K7-1 (fixed):** `RenderImageGate` deduplicated objects with one `visited` set shared by every role, so the role that reached an object first hid another role's checks (a form first seen as an image's `/SMask` never had its `/Resources` walked; an image first seen as a mask never had its own masks checked; the same for ICC streams and `/Annots` entries). Visits are now tracked per role (image checks, a drawn image's masks, form/pattern/group resources, Type 3 resources, ICC profile, annotation, appearance-state dictionary). Image checks still run and are charged once per object. Each object is handled at most once per role (7 roles); the 20,000-unit budget still counts entries and objects visited, so the work stays bounded. Tests: `RenderImageGateTest.k7_1_*`.
- **K7-2 (fixed):** `ObjStmScan` read only `/Filter`, but MuPDF's `pdf_open_filter` takes `Filter|F`, so a `/F /FlateDecode` object stream was counted as raw bytes and inflated without bound at open (tools and reader). Both passes now use `/F` when `/Filter` is absent. A file-specification `/F` (a dictionary, string or reference) is refused: MuPDF 1.28.5 has no external-file streams (`/FFilter` is not read) and would read the data raw. `/DecodeParms|DP` is not needed: predictors do not expand output. Tests: `ObjStmScanTest.k7_2_*`, `EncryptedReaderTest.objectStreamBombWithAbbreviatedFilterKeyIsRefusedAfterDecryption`.
- **K7-3 (mitigated):** pass 2 (`checkDecrypted`) now refuses the document if `PDFDocument.wasRepaired()` turns true while it runs (a header that does not match the xref makes MuPDF repair, which loads every object stream). The cost of that one repair is already paid when it is detected, so residual (1) above is narrowed, not closed. Residual (2), a repair inside `openDocument`, is unchanged. Test: `EncryptedReaderTest.repairDuringObjectStreamPass2IsRefused`.
- **M2-1 (fixed):** `RenderImageGate` chose image or form from `/Subtype` at every site, but only `Do` (`pdf_process_Do`) reads it. MuPDF runs a soft mask's `/G` (`begin_softmask` → `pdf_run_xobject`) and an annotation appearance (`pdf_process_annot` → `op_Do_form`, the `/AP` `/N|/R|/D` stream or the state chosen by `/AS`) as a form whatever its `/Subtype` (none, `/Image`, `/PS`), so a bomb behind such a stream's `/Resources` was never walked. Those two sites now walk the stream as a form regardless and also check it as an image when it has `/Subtype /Image` or `Width|W`. At `Do`, `/Subtype /Form /Subtype2 /Image` is loaded by MuPDF as an image (`pdf_load_image`) and is now checked as one too. Other sites checked in MuPDF 1.28.5 and unchanged: tiling patterns (MuPDF requires `/PatternType 1`; the gate walks every `/Pattern` stream, a superset); Type 3 glyphs (run on the font's `/Resources`, a CharProc's own `/Resources` is ignored); now also walked for a font `pdf_load_font` GUESSES is Type 3 (`/CharProcs` with no `/Subtype` or one other than Type0/Type1/MMType1/TrueType) and for a font set by an ExtGState `/Font [font size]` (`pdf_process_extgstate`), both previously skipped; page `/Contents` (lexed as content on the page's resources whatever the stream's `/Subtype` or own `/Resources`; already scanned so); an image's `/SMask`/`/Mask` (always loaded as an image; already so); `Do` with no `/Subtype` (MuPDF throws) or `/PS`/unknown (ignored). Shading functions and transparency-group colour spaces run no content. Appearances MuPDF synthesises stay out of scope. Tests: `RenderImageGateTest.m2_1_*`.
- **M2-2 (fixed):** `ObjStmScan` pass 1 did not track dictionary nesting, so a nested dictionary's `/Filter` or `/F` replaced the outer one: `/Filter/FlateDecode/X<</Filter[]>>` read as unfiltered and was counted as raw bytes while MuPDF inflated it (tools; and on the reader path an encrypted one was never recorded for pass 2). Pass 1 now tracks `<<`/`>>` and `[`/`]` (strings and comments were already skipped as MuPDF lexes them) and takes `/Filter|F` only from the top level of the stream dictionary. Because `pdf_parse_dict` takes ONE token as a null value after a key in `/K >>`, `/K 1 2 >>` and `/K 1 <<` (so `/X<</Y>>/Filter[]>>` nests `/Filter` for MuPDF and `/Y 1<</Filter/FlateDecode/Z>>` does not), a candidate is refused when a `/Filter` or `/F` name occurs nested or in an array, occurs twice at the top level, when a `>>` closes nothing, when the nesting is unbalanced at `stream`, or when its dictionary held a string or comment cut at 64 KiB. `/DecodeParms<</Columns 4/Predictor 12>>` and other nested dictionaries without those keys still pass. Reverse confusion: `/N` and `/ObjStm` still make a candidate wherever they occur (a superset); `/Type`, `/Length` and `/First` are not read by pass 1 (raw extent runs to `endstream`). Pass 2's `streamFilter` reads the top-level key through MuPDF objects and is unaffected. Tests: `ObjStmScanTest.m2_2_*`, `EncryptedReaderTest.objectStreamBombWithNestedFilterKeyIsRefusedAfterDecryption`.
- **Q1 (fixed; M2-2 regression):** M2-2 refused unmodified image-heavy PDFs (a real 33 MB file, and 16 of about 26 encrypted or object-stream variants in a corpus check) on the tool and reader paths. Cause: pass 1 tokenised the whole file, binary stream data included, and reset its state only at `obj`/`endobj`/`stream`/`endstream` keywords it lexed. A stray `(`, `<` or `%` in image data or ciphertext opened a string, hex string or comment that swallowed the next object's `endstream endobj N 0 obj` header, so `[`/`<<` counts and a 64 KiB cut from earlier data reached a later object's `stream` keyword (in the real file: object 2542 judged with depth state from data after object 2523). Swallowing a header could also hide that object stream completely, so a bomb went unmeasured. Fix: pass 1 now byte-searches for every `obj` keyword in the file, including ones inside strings and stream data, and parses each object on its own with fresh state. It starts only at `<<`, stops at the first `stream`/`endstream`/`obj`/`endobj` keyword, and skips strings and comments to their real end (over 64 KiB still refuses a candidate). It never tokenises stream data and does not skip by `/Length`, so a lying `/Length` or a fake header cannot hide a candidate. MuPDF needs an `N G obj` header for every object it loads (`pdf_parse_ind_obj`; repair lexes `obj` too), so the scan finds a superset of the objects MuPDF loads. Headers that are only lookalikes in binary data cost a bounded parse (all parses together: 2 × file size + 64 MiB, then refused). Residual mismatch: the lexer is still an approximation of MuPDF's (shapes known to differ are refused); an unfiltered candidate is measured to the next `endstream` bytes, not `/Length`; a header with a comment between `gen` and `obj` gets no object number, so in an encrypted file it is refused in pass 2 (costly direction). Tests: `ObjStmScanTest.q1_*`, `EncryptedReaderTest.q1_*`.
