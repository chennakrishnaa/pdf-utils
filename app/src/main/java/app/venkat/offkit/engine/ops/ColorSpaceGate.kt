package app.venkat.offkit.engine.ops

import com.artifex.mupdf.fitz.PDFObject
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Default-deny decision on an image's `/ColorSpace` before Compress lets MuPDF load the image
 * (security re-check SR2-1).
 *
 * `PDFDocument.loadImage` → `pdf_load_image_imp` → `pdf_load_colorspace` reads the colour space
 * WITHOUT any bound: an `/ICCBased` profile stream is inflated completely into memory
 * (`pdf-colorspace.c` `load_icc_based` → `pdf_load_stream`), `/Indexed` lookup streams, DeviceN /
 * Separation tint functions (`pdf_load_function`, which may load sampled-function streams) and the
 * profile's `/Alternate` (loaded recursively) are read too. So the colour space is vetted here the
 * same default-deny way as the filter chain ([ImageFilters]), and only these are accepted:
 *
 * - the names `/DeviceRGB`, `/DeviceGray`, and their abbreviations `/RGB`, `/G` (MuPDF's
 *   `pdf_load_colorspace_imp` maps all four to its device singletons for any image, not just
 *   inline ones; no object is read);
 * - `[/CalRGB <<dict>>]`, `[/CalGray <<dict>>]` with a DIRECT dictionary (MuPDF reads only the
 *   numeric arrays `/WhitePoint`, `/BlackPoint`, `/Gamma`, `/Matrix` from it, and falls back to the
 *   device space on any error);
 * - `[/ICCBased ref]` whose profile stream passes [iccProfileOk].
 *
 * The `/ColorSpace` value itself may be an indirect reference (resolved once). Everything else —
 * Indexed, Separation, DeviceN, Lab, Pattern, DeviceCMYK/CalCMYK, a bare stream or dictionary (MuPDF
 * treats that as ICC), a name that would have to be looked up in page `/Resources` (MuPDF does not
 * look up names for XObject images and would throw "unknown colorspace"), arrays of any other
 * length, missing — is [Kind.SKIP]: the image is never passed to `loadImage`.
 */
internal object ColorSpaceGate {
    enum class Kind { RGB, GRAY, SKIP }

    /** Largest raw (still compressed) ICC profile stream read for vetting: 1 MiB. */
    const val MAX_PROFILE_RAW_BYTES = 1 shl 20

    /** Largest decoded ICC profile accepted: 4 MiB (real profiles are a few hundred bytes to ~1 MB). */
    const val MAX_PROFILE_DECODED_BYTES = 4 shl 20

    /**
     * The kind of [colorSpace] (the image's `/ColorSpace` value as returned by `get`, owned by the
     * caller). Objects created here are destroyed here.
     */
    fun classify(colorSpace: PDFObject): Kind = Scope().use { s ->
        val cs = if (colorSpace.isIndirect) s.add(colorSpace.resolve()) else colorSpace
        when {
            cs.isName -> deviceKind(cs.asName())
            cs.isArray && cs.size() == 2 -> {
                val family = s.add(cs.get(0)).let { if (it.isName) it.asName() else "" }
                val sub = s.add(cs.get(1))
                when (family) {
                    "CalRGB" -> if (plainDirectDict(sub)) Kind.RGB else Kind.SKIP
                    "CalGray" -> if (plainDirectDict(sub)) Kind.GRAY else Kind.SKIP
                    "ICCBased" -> iccKind(s, sub)
                    else -> Kind.SKIP
                }
            }
            else -> Kind.SKIP
        }
    }

    private fun deviceKind(name: String): Kind = when (name) {
        "DeviceRGB", "RGB" -> Kind.RGB
        "DeviceGray", "G" -> Kind.GRAY
        else -> Kind.SKIP
    }

    /** A direct dictionary (not a reference, so never a stream) for `CalRGB`/`CalGray`. */
    private fun plainDirectDict(o: PDFObject): Boolean = !o.isIndirect && o.isDictionary && !o.isStream

    /** An `/Alternate` MuPDF may load without reading any stream: device names or Cal forms. */
    private fun alternateOk(s: Scope, alt: PDFObject): Boolean {
        if (alt.isNull) return true
        val a = if (alt.isIndirect) s.add(alt.resolve()) else alt
        return when {
            a.isName -> deviceKind(a.asName()) != Kind.SKIP
            a.isArray && a.size() == 2 -> {
                val family = s.add(a.get(0)).let { if (it.isName) it.asName() else "" }
                (family == "CalRGB" || family == "CalGray") && plainDirectDict(s.add(a.get(1)))
            }
            else -> false
        }
    }

    /**
     * `[/ICCBased ref]`: [ref] must be an indirect reference to a stream whose profile passes every
     * check: `/N` 1 or 3; a direct integer `/Length` ≤ [MAX_PROFILE_RAW_BYTES]; filter chain none
     * or exactly `[FlateDecode]` (normalised by [ImageFilters]) without `/DecodeParms`; no
     * `/F`/`/FFilter`/`/FDecodeParms`/`/DP`; no `/Metadata`; `/Range` absent or a direct array;
     * `/Alternate` absent or plain ([alternateOk]); and the decoded profile, inflated here under a
     * bound ([decodedSizeWithin]), ≤ [MAX_PROFILE_DECODED_BYTES].
     */
    private fun iccKind(s: Scope, ref: PDFObject): Kind {
        if (!ref.isIndirect || !ref.isStream) return Kind.SKIP
        val n = s.add(ref.get("N")).let { if (it.isIndirect) s.add(it.resolve()) else it }
        val kind = when (if (n.isInteger) n.asInteger() else 0) {
            1 -> Kind.GRAY
            3 -> Kind.RGB
            else -> return Kind.SKIP
        }
        val length = s.add(ref.get("Length"))
        if (length.isIndirect || !length.isInteger) return Kind.SKIP
        val len = length.asInteger()
        if (len < 0 || len > MAX_PROFILE_RAW_BYTES) return Kind.SKIP
        for (k in PROFILE_SKIP_KEYS) if (!s.add(ref.get(k)).isNull) return Kind.SKIP
        val range = s.add(ref.get("Range"))
        if (!range.isNull && (range.isIndirect || !range.isArray)) return Kind.SKIP
        if (!alternateOk(s, s.add(ref.get("Alternate")))) return Kind.SKIP

        val flate = when (filterChain(s, ref)) {
            ImageFilters.Chain.RAW -> false
            ImageFilters.Chain.FLATE -> true
            else -> return Kind.SKIP
        }
        // Raw bytes are bounded by the direct /Length check above.
        val raw = ref.readRawStream()
        if (raw.size > MAX_PROFILE_RAW_BYTES) return Kind.SKIP
        val ok = if (flate) decodedSizeWithin(raw, MAX_PROFILE_DECODED_BYTES) else raw.size <= MAX_PROFILE_DECODED_BYTES
        return if (ok) kind else Kind.SKIP
    }

    /** `/Filter` of [dict] read as MuPDF reads it, decided by [ImageFilters.decide]. */
    private fun filterChain(s: Scope, dict: PDFObject): ImageFilters.Chain {
        val raw = s.add(dict.get("Filter"))
        if (raw.isNull) return ImageFilters.decide(present = false, names = null)
        val filter = if (raw.isIndirect) s.add(raw.resolve()) else raw
        val names: List<String?>? = when {
            filter.isName -> listOf(filter.asName())
            filter.isArray -> (0 until filter.size()).map { i ->
                val e = s.add(filter.get(i))
                val r = if (e.isIndirect) s.add(e.resolve()) else e
                if (r.isName) r.asName() else null
            }
            else -> null
        }
        return ImageFilters.decide(present = true, names = names)
    }

    /**
     * True when the zlib stream [compressed] inflates to at most [limit] bytes. Inflates into a
     * fixed 64 KiB scratch buffer, counting output, and stops as soon as the count exceeds [limit],
     * so the work is bounded by `limit` whatever the input. A truncated stream counts what it
     * yields (MuPDF would decode no more); a corrupt one is rejected.
     */
    fun decodedSizeWithin(compressed: ByteArray, limit: Int): Boolean {
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val scratch = ByteArray(64 * 1024)
            var total = 0L
            while (!inflater.finished()) {
                val got = try {
                    inflater.inflate(scratch)
                } catch (e: DataFormatException) {
                    return false
                }
                total += got
                if (total > limit) return false
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            }
            return true
        } finally {
            inflater.end()
        }
    }

    /** Profile-stream keys whose presence makes the image skipped (external file, abbreviated parms, unvetted streams). */
    private val PROFILE_SKIP_KEYS = listOf("F", "FFilter", "FDecodeParms", "DecodeParms", "DP", "Metadata")

    private class Scope : AutoCloseable {
        private val items = ArrayList<PDFObject>()
        fun add(o: PDFObject): PDFObject { items.add(o); return o }
        override fun close() {
            for (i in items.indices.reversed()) items[i].let { if (it !== PDFObject.Null) it.destroy() }
            items.clear()
        }
    }
}
