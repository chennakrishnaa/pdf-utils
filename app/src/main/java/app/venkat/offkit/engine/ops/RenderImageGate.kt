package app.venkat.offkit.engine.ops

import app.venkat.offkit.engine.ImageLimits
import app.venkat.offkit.engine.ImagePages
import app.venkat.offkit.engine.ObjectGraph
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfInputException
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.BitSet
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * RDR-3: pre-decode checks of the images one page can draw, run by [RenderPageOp] before
 * `Page.run` (which hands every image to `DrawDevice` with no bound but the engine's RSS watchdog).
 *
 * **Polarity.** Compress's gates ([ImageFilters], [ColorSpaceGate]) are default-DENY because
 * Compress re-encodes: anything it cannot prove simple is left untouched. A reader must draw
 * ordinary documents (CMYK, Indexed, ICC, CCITT/JBIG2/JPX scans, 1/16-bit, masks), so this gate
 * refuses a page (`PdfInputException(OutOfMemory)`) only when an image is PROVEN over a limit, or
 * when its size cannot be established for a decoder that sizes itself from the stream (DCT, JPX).
 *
 * **What is checked, per image XObject** (keys read as MuPDF reads them, long key then
 * abbreviation: `Width|W`, `Height|H`, `BitsPerComponent|BPC`, `ImageMask|IM`, `ColorSpace|CS`,
 * `Filter|F`, `DecodeParms|DP`):
 * - declared size: each edge ≤ [ImageLimits.MAX_EDGE]; decoded bytes
 *   `w × h × components × (2 if bpc > 8 else 1)` ≤ [MAX_IMAGE_DECODED_BYTES]. MuPDF decodes to an
 *   8-bit-per-component tile (1-bit scans are unpacked to a byte per pixel), and the compressed
 *   buffer it keeps is bounded by `w × h × n × bpc / 8`; so this is the largest buffer the image
 *   can cost when a tile shows it at full resolution;
 * - the sum of that cost over the distinct images of the page ≤ [MAX_PAGE_DECODED_BYTES];
 * - DCT (the last filter): the stream's own frame header (SOF), which is what libjpeg sizes its
 *   buffers from (SA-6), must pass [ImageLimits.jpeg] (progressive/multi-scan JPEGs capped in
 *   samples, the SA-2 bomb) and its `w × h × components` must also fit [MAX_IMAGE_DECODED_BYTES].
 *   The header is read from the raw stream (≤ [MAX_INSPECT_BYTES]); a `[/FlateDecode /DCTDecode]`
 *   chain is inflated only as far as the header (≤ [MAX_JPEG_HEADER_BYTES]). Any other filter in
 *   front of DCT (ASCIIHex, ASCII85, LZW, RunLength, Brotli, two Flates...), a missing or
 *   unparsable header, or a larger stream is refused: those are the bomb spellings of
 *   `CompressFilterGateTest` and the header cannot be reached cheaply;
 * - JPX: MuPDF ignores the dictionary size and openjpeg allocates from the codestream's SIZ
 *   marker (32-bit samples): SIZ must be found and `w × h × components ≤` [MAX_JPX_SAMPLES];
 *   JPX behind any other filter is refused (MuPDF itself only honours a JPX stream on its own);
 * - Flate/LZW predictors: `/Columns × /Colors × /BitsPerComponent / 8` ≤ [MAX_PREDICTOR_ROW_BYTES]
 *   (MuPDF sizes its row buffers from these alone, SR-5); CCITT `/Columns` ≤ [ImageLimits.MAX_EDGE];
 * - `[/ICCBased s]` colour space (also inside Indexed): profile raw `/Length` ≤
 *   [ColorSpaceGate.MAX_PROFILE_RAW_BYTES] and, if Flate, inflated size ≤
 *   [ColorSpaceGate.MAX_PROFILE_DECODED_BYTES] (MuPDF inflates a profile without bound); other
 *   filters on a profile are refused. Other colour-space families are accepted (they are bounded by
 *   MuPDF: an Indexed lookup is read for `(hival + 1) × n` bytes only);
 * - the image's `/SMask` and `/Mask` streams, checked the same way.
 *
 * **What is walked** (iteratively, a visited set by object number, so shared objects are checked
 * once and cycles end; at most [MAX_NESTING] nested resource levels and [MAX_VISITS] visited
 * entries in [MAX_WALK_MILLIS], else refused): the page's `/Resources` (inherited), its
 * `/XObject` entries, form XObjects' `/Resources` (a form without them uses its parent's, already
 * walked), tiling patterns' `/Resources` (`/Pattern`), soft-mask groups (`/ExtGState` → `/SMask`
 * → `/G`), Type 3 fonts' `/Resources` (`/Font`), and every annotation's appearance streams
 * (`/AP` `/N` `/R` `/D`, a stream or a dictionary of states). When a resource dictionary has more
 * than [NARROW_ABOVE] `/XObject` entries (generators that share one resource dictionary across
 * all pages), only the entries whose name occurs as a `/Name` token in the content stream(s) that
 * use it are checked, provided those streams are unfiltered or plain Flate and inflate within
 * [MAX_CONTENT_SCAN_BYTES]; otherwise every entry is checked. Any `/` token counts, so the
 * candidate set is a superset of what the interpreter can look up.
 *
 * **Not covered** (the RSS watchdog stays the backstop): inline images (`BI … ID … EI` inside a
 * content stream, including an inline DCT image whose SOF lies); JBIG2 internals (jbig2dec sizes
 * its page from the stream's page-information segment, not the dictionary; the dictionary size
 * is still checked) and `/JBIG2Globals`; DeviceN/Separation tint functions (sampled-function
 * streams); shading patterns and `sh` (no images, but large function streams); fonts other than
 * Type 3 (embedded font programs); `/MK` icons of widgets that have no appearance stream yet; an
 * image's `/Alternates`; images drawn through the appearance MuPDF synthesises; colour-space
 * resource lookups for inline images.
 */
internal object RenderImageGate {
    /** 256 MiB per image: 4000×6000 CMYK JPEG 96 MB, 600 dpi A4 4960×7016 gray 35 MB / RGB 104 MB. */
    const val MAX_IMAGE_DECODED_BYTES = 256L shl 20

    /** 1 GiB over the distinct images of a page (decode work; MuPDF's store evicts between images). */
    const val MAX_PAGE_DECODED_BYTES = 1L shl 30

    /** JPX samples (openjpeg holds 32-bit samples: 64 M samples = 256 MiB; 21 MP RGB, 64 MP gray). */
    const val MAX_JPX_SAMPLES = 64L shl 20

    /** Largest raw DCT/JPX stream read to find its header (the binding copies the whole stream). */
    const val MAX_INSPECT_BYTES = 32 shl 20

    /** Total raw bytes read for headers in one page check. */
    const val MAX_PAGE_INSPECT_BYTES = 256L shl 20

    /** Inflated bytes of a `[/Fl /DCT]` stream read to reach its SOF (ICC + EXIF segments ≲ 1 MB). */
    const val MAX_JPEG_HEADER_BYTES = 4 shl 20

    /** Predictor row buffer (65,500 px × 4 colours × 16 bit = 524,000 B). */
    const val MAX_PREDICTOR_ROW_BYTES = 1L shl 20

    /** Nested resource levels (page → form → form …); real documents use a handful. */
    const val MAX_NESTING = 32

    /** Entries (resource entries, objects, annotations) one page check may visit. */
    const val MAX_VISITS = 20_000

    const val MAX_WALK_MILLIS = 2_000L

    /** Resource dictionaries with more `/XObject` entries than this are narrowed to the names used. */
    const val NARROW_ABOVE = 32

    /** Largest decoded content stream scanned for names when narrowing. */
    const val MAX_CONTENT_SCAN_BYTES = 16 shl 20

    /** Content bytes one page check scans for names; beyond, entries are checked without narrowing. */
    const val MAX_PAGE_SCAN_BYTES = 64L shl 20

    /** Longest dictionary key narrowing can match ([plainKey]). */
    const val MAX_KEY_CHARS = 127

    private fun refuse(): Nothing = throw PdfInputException(PdfError.OutOfMemory)

    /**
     * Checks page [index] of [doc] (before `loadPage`). Returns normally when every image it can
     * see is within the limits; throws `PdfInputException(OutOfMemory)` otherwise.
     */
    fun checkPage(doc: PDFDocument, index: Int, isCancelled: () -> Boolean = { false }) {
        Walk(doc, isCancelled).use { it.run(index) }
    }

    private class Walk(private val doc: PDFDocument, private val isCancelled: () -> Boolean) : AutoCloseable {
        private val s = Scope()
        private val count = doc.countObjects()
        private val visited = BitSet(count)
        private val budget = ObjectGraph.Budget(MAX_VISITS, MAX_WALK_MILLIS)
        private var pageCost = 0L
        private var inspected = 0L
        private var scanned = 0L
        private var nextDirectId = -1L

        /**
         * One resource dictionary, listed once per page check: its `/XObject` keys, whether they may
         * be narrowed ([plainKey]), which entries were already checked, and whether the other
         * sections (patterns, soft masks, Type 3 fonts, colour spaces) were walked.
         */
        private class ResInfo(val dict: PDFObject, val xobject: PDFObject?, val keys: List<String>, val narrowable: Boolean) {
            val keySet: Set<String> by lazy { keys.toHashSet() }
            val checked = HashSet<String>()
            var allChecked = xobject == null
            var othersDone = false
        }

        private val infos = HashMap<Long, ResInfo>()

        /** RF-2: (resource-less form/pattern/group/font object, parent resources id) pairs walked. */
        private val pairs = HashSet<Pair<Int, Long>>()

        /** A resource dictionary ([id] into [infos]) to walk with the streams that may narrow it. */
        private class Item(val id: Long, val contents: List<PDFObject>?, val level: Int)

        private val stack = ArrayList<Item>()

        override fun close() = s.close()

        /** Charges [n] budget units (0: only the clock) and polls cancellation. */
        private fun tick(n: Int = 1) {
            if (isCancelled()) throw PdfInputException(PdfError.Cancelled)
            budget.spend(n)
        }

        fun run(index: Int) {
            val page = s.add(doc.findPage(index))
            val pageRes = s.add(page.getInheritable("Resources"))
            val pageId = idOf(pageRes)
            push(pageId, pageRes, streamsOf(s.add(page.get("Contents"))), 1)
            annotations(s.add(page.get("Annots")), pageId, pageRes)
            while (stack.isNotEmpty()) resources(stack.removeAt(stack.size - 1))
        }

        /** Marks [o]'s object number visited; false if it was already (or is out of range). */
        private fun firstVisit(o: PDFObject): Boolean {
            if (!o.isIndirect) return true
            val num = o.asIndirect()
            if (num <= 0 || num >= count || visited.get(num)) return false
            visited.set(num)
            return true
        }

        /**
         * Id of a resource dictionary: its object number, or a fresh negative id for a direct one
         * (a direct dictionary is reached only through its owner, which is itself deduplicated).
         */
        private fun idOf(res: PDFObject): Long = if (res.isIndirect) res.asIndirect().toLong() else nextDirectId--

        private fun push(id: Long, res: PDFObject, contents: List<PDFObject>?, level: Int) {
            tick()
            if (res.isNull || !res.isDictionary) return
            if (level > MAX_NESTING) refuse()
            val info = infos.getOrPut(id) {
                val xo = s.add(res.get("XObject")).let { if (it.isIndirect) s.add(it.resolve()) else it }
                val keys = if (xo.isDictionary) keys(xo) else emptyList()
                tick(keys.size / 64)
                ResInfo(res, if (xo.isDictionary) xo else null, keys, keys.size > NARROW_ABOVE && keys.all(::plainKey))
            }
            if (info.allChecked && info.othersDone) return
            stack.add(Item(id, contents, level))
        }

        /**
         * A form, tiling pattern, soft-mask group or Type 3 font [obj] reached from resources
         * [parentId]: with its own `/Resources` it is walked once (by object number); without, MuPDF
         * runs it with its caller's resources, so the parent is walked again with [contents] as
         * the names, once per (object, parent) pair (RF-2).
         */
        private fun child(obj: PDFObject, contents: List<PDFObject>?, level: Int, parentId: Long, parentRes: PDFObject) {
            val own = s.add(obj.get("Resources"))
            if (own.isNull) {
                if (!obj.isIndirect || pairs.add(obj.asIndirect() to parentId)) push(parentId, parentRes, contents, level)
            } else if (firstVisit(obj)) {
                push(idOf(own), own, contents, level)
            }
        }

        private fun annotations(annots: PDFObject, pageId: Long, pageRes: PDFObject) {
            if (!annots.isArray) return
            for (i in 0 until annots.size()) {
                tick()
                val a = s.add(annots.get(i))
                if (!firstVisit(a) || !a.isDictionary) continue
                val ap = s.add(a.get("AP"))
                if (!ap.isDictionary) continue
                for (key in APPEARANCE_KEYS) {
                    val v = s.add(ap.get(key))
                    if (v.isStream) xobject(v, 1, pageId, pageRes)
                    else if (v.isDictionary && firstVisit(v)) for (k in keys(v)) xobject(s.add(v.get(k)), 1, pageId, pageRes)
                }
            }
        }

        private fun resources(item: Item) {
            val info = infos.getValue(item.id)
            val res = info.dict
            val xo = info.xobject
            if (xo != null && !info.allChecked) {
                val wanted = if (info.narrowable) item.contents?.let { usedKeys(it, info.keySet) } else null
                for (name in wanted ?: info.keys) {
                    if (!info.checked.add(name)) continue
                    tick()
                    xobject(s.add(xo.get(name)), item.level, item.id, res)
                }
                if (wanted == null) info.allChecked = true
            }
            if (info.othersDone) return
            info.othersDone = true
            val pat = s.add(res.get("Pattern"))
            if (pat.isDictionary) for (k in keys(pat)) {
                tick()
                val p = s.add(pat.get(k))
                if (p.isStream) child(p, listOf(p), item.level + 1, item.id, res)
            }
            val gs = s.add(res.get("ExtGState"))
            if (gs.isDictionary) for (k in keys(gs)) {
                tick()
                val g = s.add(gs.get(k))
                if (!g.isDictionary) continue
                val sm = s.add(g.get("SMask"))
                if (sm.isDictionary) xobject(s.add(sm.get("G")), item.level, item.id, res)
            }
            val fonts = s.add(res.get("Font"))
            if (fonts.isDictionary) for (k in keys(fonts)) {
                tick()
                val f = s.add(fonts.get(k))
                if (!f.isDictionary || nameOf(s.add(f.get("Subtype"))) != "Type3") continue
                // Glyph procedures are not scanned for names: their resources are walked in full.
                child(f, null, item.level + 1, item.id, res)
            }
            // RF-6: ICC profiles (also under Indexed) of the colour-space resources.
            val cs = s.add(res.get("ColorSpace"))
            if (cs.isDictionary) for (k in keys(cs)) {
                tick()
                Scope().use { t -> components(t, t.add(cs.get(k))) }
            }
        }

        /** An XObject reference found in resources [parentId]: an image is checked, a form is queued. */
        private fun xobject(v: PDFObject, level: Int, parentId: Long, parentRes: PDFObject) {
            tick()
            if (!v.isStream) return
            when (nameOf(s.add(v.get("Subtype")))) {
                "Image" -> if (firstVisit(v)) image(v, mask = false)
                "Form" -> child(v, listOf(v), level + 1, parentId, parentRes)
            }
        }

        private fun image(img: PDFObject, mask: Boolean) {
            Scope().use { t -> checkImage(t, img) }
            if (mask) return
            for (k in MASK_KEYS) {
                val m = s.add(img.get(k))
                tick()
                if (m.isStream && firstVisit(m)) image(m, mask = true)
            }
        }

        private fun checkImage(t: Scope, img: PDFObject) {
            val w = intOf(t, geta(t, img, "Width", "W"))
            val h = intOf(t, geta(t, img, "Height", "H"))
            val imageMask = t.add(geta(t, img, "ImageMask", "IM")).let { it.isBoolean && it.asBoolean() }
            val bpc = if (imageMask) 1 else intOf(t, geta(t, img, "BitsPerComponent", "BPC")).let { if (it == 0) 8 else it }
            val filters = filterNames(t, img) ?: refuse() // neither a name nor an array: cannot tell the decoder
            if ("JPXDecode" in filters) {
                if (filters.size != 1) refuse()
                jpx(img)
                return
            }
            // MuPDF rejects w/h <= 0 itself ("image width is zero"); nothing is decoded.
            if (w <= 0 || h <= 0 || bpc !in 1..16) return
            if (w > ImageLimits.MAX_EDGE || h > ImageLimits.MAX_EDGE) refuse()
            val n = if (imageMask) 1 else components(t, geta(t, img, "ColorSpace", "CS"))
            charge(w.toLong() * h * n * (if (bpc > 8) 2 else 1))
            decodeParms(t, img, filters)
            // libjpeg sizes itself from the SOF wherever DCT sits in the chain (also as a generic filter).
            if ("DCTDecode" in filters) dct(img, filters)
        }

        private fun charge(cost: Long) {
            if (cost > MAX_IMAGE_DECODED_BYTES) refuse()
            pageCost += cost
            if (pageCost > MAX_PAGE_DECODED_BYTES) refuse()
        }

        /** Raw bytes of an image stream for header inspection, under the per-image and per-page caps. */
        private fun rawForInspection(img: PDFObject): ByteArray {
            val len = Scope().use { t -> intOf(t, t.add(img.get("Length"))) }
            if (len > MAX_INSPECT_BYTES) refuse()
            val raw = img.readRawStream()
            if (raw.size > MAX_INSPECT_BYTES) refuse()
            inspected += raw.size
            if (inspected > MAX_PAGE_INSPECT_BYTES) refuse()
            return raw
        }

        private fun dct(img: PDFObject, filters: List<String>) {
            val raw = rawForInspection(img)
            if (filters.last() != "DCTDecode") refuse()
            val input: InputStream = when (filters.size) {
                1 -> ByteArrayInputStream(raw)
                2 -> if (filters[0] == "FlateDecode") {
                    Capped(InflaterInputStream(ByteArrayInputStream(raw), Inflater()), MAX_JPEG_HEADER_BYTES.toLong())
                } else refuse()
                else -> refuse()
            }
            val layout = try {
                input.use { ImagePages.jpegScan(it, headerOnly = true) }
            } catch (e: IOException) {
                null
            } ?: refuse()
            val frame = layout.frame ?: refuse()
            if (ImageLimits.jpeg(frame, layout.frameCount, layout.firstScanComponents) != ImageLimits.Verdict.OK) refuse()
            if (frame.width.toLong() * frame.height * frame.components > MAX_IMAGE_DECODED_BYTES) refuse()
        }

        private fun jpx(img: PDFObject) {
            val raw = rawForInspection(img)
            val siz = jpxSize(raw) ?: refuse()
            val (w, h, c) = siz
            if (w <= 0 || h <= 0 || c <= 0) refuse()
            if (w > ImageLimits.MAX_EDGE || h > ImageLimits.MAX_EDGE) refuse()
            val samples = w * h * c
            if (samples > MAX_JPX_SAMPLES) refuse()
            charge(w * h * minOf(c, 4L))
        }

        /** Predictor and CCITT parameters of each filter in the chain. */
        private fun decodeParms(t: Scope, img: PDFObject, filters: List<String>) {
            val dp = t.add(geta(t, img, "DecodeParms", "DP")).let { if (it.isIndirect) t.add(it.resolve()) else it }
            for ((i, f) in filters.withIndex()) {
                // An array holds one entry per filter; a single dictionary is checked against every filter.
                val p = if (dp.isArray) t.add(dp.get(i)).let { if (it.isIndirect) t.add(it.resolve()) else it } else dp
                if (!p.isDictionary) continue
                when (f) {
                    "FlateDecode", "LZWDecode" -> {
                        val predictor = intOf(t, t.add(p.get("Predictor")))
                        if (predictor > 1) {
                            val columns = maxOf(1, intOf(t, t.add(p.get("Columns")))).toLong()
                            val colors = maxOf(1, intOf(t, t.add(p.get("Colors")))).toLong()
                            val bits = intOf(t, t.add(p.get("BitsPerComponent"))).let { if (it <= 0) 8 else it }.toLong()
                            if ((columns * colors * bits + 7) / 8 > MAX_PREDICTOR_ROW_BYTES) refuse()
                        }
                    }
                    "CCITTFaxDecode" -> if (intOf(t, t.add(p.get("Columns"))) > ImageLimits.MAX_EDGE) refuse()
                }
            }
        }

        /**
         * Components of the tile MuPDF decodes for colour space [cs] (owned by the caller's scope).
         * Unknown or unresolvable spaces count 4. An ICC profile is vetted ([iccOk]).
         */
        private fun components(t: Scope, csIn: PDFObject, depth: Int = 0): Long {
            val cs = if (csIn.isIndirect) t.add(csIn.resolve()) else csIn
            if (cs.isNull) return 1
            if (cs.isName) return when (cs.asName()) {
                "DeviceGray", "G", "CalGray" -> 1
                "DeviceRGB", "RGB", "CalRGB", "Lab" -> 3
                else -> 4
            }
            if (cs.isStream) { iccOk(csIn); return 4 } // MuPDF treats a bare stream as ICC
            if (!cs.isArray || cs.size() == 0) return 4
            return when (nameOf(t.add(cs.get(0)))) {
                "DeviceGray", "G", "CalGray", "Separation" -> 1
                "DeviceRGB", "RGB", "CalRGB", "Lab" -> 3
                "ICCBased" -> {
                    val ref = t.add(cs.get(1))
                    iccOk(ref)
                    val nObj = t.add(ref.get("N"))
                    val n = intOf(t, nObj)
                    if (n in 1..32) n.toLong() else 4
                }
                // The decoded tile is 1 component; MuPDF expands it to the base space when drawing.
                "Indexed", "I" -> if (depth < 2) maxOf(1, components(t, t.add(cs.get(1)), depth + 1)) else 4
                "DeviceN" -> t.add(cs.get(1)).let { if (it.isArray) it.size().toLong().coerceIn(1, 32) else 4 }
                else -> 4
            }
        }

        /** SR2-1 bounds on an ICC profile stream [ref] (checked once per object). */
        private fun iccOk(ref: PDFObject) {
            if (!ref.isStream || !firstVisit(ref)) return
            Scope().use { t ->
                val len = intOf(t, t.add(ref.get("Length")))
                if (len < 0 || len > ColorSpaceGate.MAX_PROFILE_RAW_BYTES) refuse()
                val filters = filterNames(t, ref) ?: refuse()
                val flate = when {
                    filters.isEmpty() -> false
                    filters.size == 1 && filters[0] == "FlateDecode" -> true
                    else -> refuse()
                }
                val raw = ref.readRawStream()
                if (raw.size > ColorSpaceGate.MAX_PROFILE_RAW_BYTES) refuse()
                val ok = if (flate) ColorSpaceGate.decodedSizeWithin(raw, ColorSpaceGate.MAX_PROFILE_DECODED_BYTES)
                else raw.size <= ColorSpaceGate.MAX_PROFILE_DECODED_BYTES
                if (!ok) refuse()
            }
        }

        /**
         * The keys of [keySet] that occur as name tokens in [streams] (RF-1/RF-5), or null when a
         * stream cannot be scanned (not raw or plain Flate, larger than [MAX_CONTENT_SCAN_BYTES]
         * decoded, or the page's [MAX_PAGE_SCAN_BYTES] are spent): the caller then checks every
         * entry. Each stream object is scanned once per call; only matched keys are kept.
         */
        private fun usedKeys(streams: List<PDFObject>, keySet: Set<String>): Set<String>? {
            val found = HashSet<String>()
            val seen = HashSet<Int>()
            for (st in streams) {
                tick()
                if (!st.isStream) return null
                if (st.isIndirect && !seen.add(st.asIndirect())) continue
                val data = Scope().use { t ->
                    val filters = filterNames(t, st) ?: return null
                    if (!t.add(geta(t, st, "DecodeParms", "DP")).isNull) return null
                    val len = intOf(t, t.add(st.get("Length")))
                    if (len < 0 || len > MAX_CONTENT_SCAN_BYTES || scanned + len > MAX_PAGE_SCAN_BYTES) return null
                    val raw = st.readRawStream()
                    when {
                        filters.isEmpty() -> raw
                        filters.size == 1 && filters[0] == "FlateDecode" -> inflateWithin(raw, MAX_CONTENT_SCAN_BYTES) ?: return null
                        else -> return null
                    }
                }
                scanned += data.size
                if (scanned > MAX_PAGE_SCAN_BYTES) return null
                matchNames(data, keySet, found) { tick(0) }
                if (found.size == keySet.size) break
            }
            return found
        }

        private fun streamsOf(contents: PDFObject): List<PDFObject>? = when {
            contents.isStream -> listOf(contents)
            contents.isArray -> (0 until minOf(contents.size(), MAX_VISITS)).map { tick(); s.add(contents.get(it)) }
            else -> null
        }

        private fun keys(dict: PDFObject): List<String> {
            val out = ArrayList<String>()
            val d = if (dict.isIndirect) s.add(dict.resolve()) else dict
            for (k in d) {
                if (k.isName) out.add(k.asName())
                if (k !== PDFObject.Null) k.destroy()
            }
            return out
        }
    }

    /** [dict]`[long]`, or `[short]` when the long key is absent (`pdf_dict_geta`). */
    private fun geta(t: Scope, dict: PDFObject, long: String, short: String): PDFObject {
        val v = t.add(dict.get(long))
        return if (v.isNull) t.add(dict.get(short)) else v
    }

    private fun intOf(t: Scope, o: PDFObject): Int {
        val v = if (o.isIndirect) t.add(o.resolve()) else o
        return if (v.isInteger) v.asInteger() else if (v.isNumber) v.asFloat().toInt() else 0
    }

    private fun nameOf(o: PDFObject): String? = if (o.isName) o.asName() else null

    /**
     * Canonical filter names (`Filter|F`) as MuPDF reads them: empty list if absent; an unknown
     * name is kept as-is; null when the entry is neither a name nor an array of names.
     */
    private fun filterNames(t: Scope, dict: PDFObject): List<String>? {
        val raw = geta(t, dict, "Filter", "F")
        if (raw.isNull) return emptyList()
        val f = if (raw.isIndirect) t.add(raw.resolve()) else raw
        return when {
            f.isName -> listOf(canonical(f.asName()))
            f.isArray -> (0 until f.size()).map { i ->
                val e = t.add(f.get(i)).let { if (it.isIndirect) t.add(it.resolve()) else it }
                if (e.isName) canonical(e.asName()) else null
            }.filterNotNull() // MuPDF ignores a non-name element (identity filter)
            else -> null
        }
    }

    private fun canonical(name: String): String = ImageFilters.CANONICAL[name] ?: name

    /** Inflates [raw] completely, or null if it is corrupt or exceeds [limit] bytes. */
    private fun inflateWithin(raw: ByteArray, limit: Int): ByteArray? {
        val inflater = Inflater()
        try {
            inflater.setInput(raw)
            val out = java.io.ByteArrayOutputStream(minOf(limit, raw.size * 4 + 64))
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val got = try { inflater.inflate(buf) } catch (e: java.util.zip.DataFormatException) { return null }
                out.write(buf, 0, got)
                if (out.size() > limit) return null
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /**
     * RF-1: a dictionary key that name narrowing can match exactly: 1..[MAX_KEY_CHARS] characters,
     * each printable ASCII (0x21-0x7E), no `#`. For such a key, a content token denotes it in
     * MuPDF's lexer (`pdf-lex.c` `lex_name`) iff [matchNames] decodes it to the same bytes. Any
     * other key (control or non-ASCII bytes, which `asName` returns UTF-8 decoded; a literal `#`,
     * e.g. MuPDF's undecoded `#00`; names MuPDF truncates at 4,095 bytes) disables narrowing.
     */
    internal fun plainKey(k: String): Boolean =
        k.length in 1..MAX_KEY_CHARS && k.all { it in '!'..'~' && it != '#' }

    /**
     * Adds to [out] every key of [keys] (all [plainKey]s) that occurs as a name token in content
     * bytes [data], tokenised as MuPDF does: a name runs from `/` to NUL, TAB, LF, FF, CR, space,
     * a delimiter `()<>[]{}/%` or the end; `#hh` is one byte except `#00`, and a `#` not followed
     * by two hex digits (or by `00`) is kept literally. Tokens that cannot equal a plain key are
     * dropped as soon as that is clear; every `/` in the data starts a token, so names in strings
     * or comments are included too (a superset). [onMiB] runs after each MiB scanned.
     */
    internal fun matchNames(data: ByteArray, keys: Set<String>, out: MutableSet<String>, onMiB: () -> Unit = {}) {
        val buf = ByteArray(MAX_KEY_CHARS)
        var i = 0
        var nextTick = 1 shl 20
        while (i < data.size) {
            if (i >= nextTick) {
                onMiB()
                nextTick += 1 shl 20
            }
            if (data[i] != '/'.code.toByte()) { i++; continue }
            i++
            var len = 0
            var ok = true
            while (i < data.size) {
                val c = data[i].toInt() and 0xFF
                if (c in WHITE || c in DELIMITERS) break
                var b = c
                if (c == '#'.code) {
                    val h1 = hex(data.getOrNull(i + 1))
                    val h2 = hex(data.getOrNull(i + 2))
                    if (h1 >= 0 && h2 >= 0 && (h1 != 0 || h2 != 0)) {
                        b = (h1 shl 4) or h2
                        i += 3
                    } else {
                        i++ // MuPDF keeps the '#' and re-reads what follows
                    }
                } else {
                    i++
                }
                if (b < 0x21 || b > 0x7E || b == '#'.code || len == MAX_KEY_CHARS) ok = false
                if (ok) buf[len++] = b.toByte()
            }
            if (ok && len > 0) {
                val name = String(buf, 0, len, Charsets.ISO_8859_1)
                if (name in keys) out.add(name)
            }
        }
    }

    private fun hex(b: Byte?): Int {
        val c = (b ?: return -1).toInt() and 0xFF
        return when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }

    private val WHITE = setOf(0x00, 0x09, 0x0A, 0x0C, 0x0D, 0x20)
    private val DELIMITERS = "()<>[]{}/%".map { it.code }.toSet()

    /**
     * Width, height and component count from a JPEG 2000 codestream's SIZ marker (`FF 4F FF 51`,
     * found anywhere in [raw], so inside a JP2 `jp2c` box too), as Longs; null if absent/truncated.
     */
    internal fun jpxSize(raw: ByteArray): Triple<Long, Long, Long>? {
        var i = 0
        while (i + 4 <= raw.size) {
            if (raw[i] == 0xFF.toByte() && raw[i + 1] == 0x4F.toByte() && raw[i + 2] == 0xFF.toByte() && raw[i + 3] == 0x51.toByte()) {
                val p = i + 4 // Lsiz
                if (p + 38 > raw.size) return null
                fun u32(o: Int) = ((raw[o].toLong() and 0xFF) shl 24) or ((raw[o + 1].toLong() and 0xFF) shl 16) or
                    ((raw[o + 2].toLong() and 0xFF) shl 8) or (raw[o + 3].toLong() and 0xFF)
                val xsiz = u32(p + 4)
                val ysiz = u32(p + 8)
                val xo = u32(p + 12)
                val yo = u32(p + 16)
                val csiz = ((raw[p + 36].toLong() and 0xFF) shl 8) or (raw[p + 37].toLong() and 0xFF)
                return Triple(xsiz - xo, ysiz - yo, csiz)
            }
            i++
        }
        return null
    }

    /** Ends the stream (EOF) after [limit] bytes. */
    private class Capped(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun skip(n: Long): Long {
            if (left <= 0) return 0
            val k = super.skip(minOf(n, left))
            left -= k
            return k
        }
    }

    private val APPEARANCE_KEYS = listOf("N", "R", "D")
    private val MASK_KEYS = listOf("SMask", "Mask")

    private class Scope : AutoCloseable {
        private val items = ArrayList<PDFObject>()
        fun add(o: PDFObject): PDFObject { items.add(o); return o }
        override fun close() {
            for (i in items.indices.reversed()) items[i].let { if (it !== PDFObject.Null) it.destroy() }
            items.clear()
        }
    }
}
