package app.venkat.offkit.engine.ops

/**
 * Default-deny decision on which image streams Compress may decode (SR-1).
 *
 * MuPDF 1.28.5 chooses the decoder ONLY from the stream dictionary's filter names
 * (`source/pdf/pdf-stream.c` `build_compression_params` / `build_filter`): the last filter of the
 * chain picks the image decoder, earlier ones are generic decoders. It reads the filter from
 * `/Filter` or, if absent, `/F`, and the parameters from `/DecodeParms` or `/DP`
 * (`pdf_dict_geta`), and accepts the abbreviations in [CANONICAL]. An unknown name is ignored with
 * a warning (identity), and a non-name array element too. So the dictionary must be read exactly
 * the way MuPDF reads it, and everything that is not a positively recognised, explicitly allowed
 * chain is left alone.
 */
internal object ImageFilters {
    /** Every filter name MuPDF 1.28.5 accepts, mapped to its canonical name (pdf-stream.c `build_filter`). */
    val CANONICAL: Map<String, String> = mapOf(
        "ASCIIHexDecode" to "ASCIIHexDecode", "AHx" to "ASCIIHexDecode",
        "ASCII85Decode" to "ASCII85Decode", "A85" to "ASCII85Decode",
        "LZWDecode" to "LZWDecode", "LZW" to "LZWDecode",
        "FlateDecode" to "FlateDecode", "Fl" to "FlateDecode",
        "RunLengthDecode" to "RunLengthDecode", "RL" to "RunLengthDecode",
        "CCITTFaxDecode" to "CCITTFaxDecode", "CCF" to "CCITTFaxDecode",
        "DCTDecode" to "DCTDecode", "DCT" to "DCTDecode",
        "BrotliDecode" to "BrotliDecode", "Br" to "BrotliDecode",
        // No abbreviations exist for these three.
        "JBIG2Decode" to "JBIG2Decode",
        "JPXDecode" to "JPXDecode",
        "Crypt" to "Crypt",
    )

    /** What Compress may do with an image stream. */
    enum class Chain { RAW, FLATE, DCT, SKIP }

    /**
     * Stream-dictionary keys whose presence makes Compress skip the image: `/F` (MuPDF reads it as
     * `/Filter` when that is absent; it is also the external-file key), `/FFilter`/`/FDecodeParms`
     * (external file), `/DP` (abbreviated `/DecodeParms`) and the abbreviated image keys that
     * `pdf_load_image` honours when the long key is absent (`W H BPC CS IM D I`).
     */
    val SKIP_KEYS = listOf("F", "FFilter", "FDecodeParms", "DP", "W", "H", "BPC", "CS", "IM", "D", "I")

    /**
     * The decision for a filter entry described as: [present] false when there is no `/Filter`;
     * [names] the entry's names when it is a single name (one element) or an array (one per
     * element, null for an element that is not a name); [names] null when the entry is present but
     * neither a name nor an array (e.g. a number, or an unresolvable reference).
     *
     * Allowed: no filter / empty array → [Chain.RAW]; exactly `[FlateDecode]` → [Chain.FLATE];
     * exactly `[DCTDecode]` → [Chain.DCT] (abbreviations normalised). Everything else is
     * [Chain.SKIP]: unknown or non-name entries, chains of two or more, LZW, RunLength, ASCII,
     * Brotli, CCITT, JBIG2, JPX, Crypt.
     */
    fun decide(present: Boolean, names: List<String?>?): Chain {
        if (!present) return Chain.RAW
        if (names == null) return Chain.SKIP
        if (names.isEmpty()) return Chain.RAW
        if (names.size != 1) return Chain.SKIP
        return when (names[0]?.let { CANONICAL[it] }) {
            "FlateDecode" -> Chain.FLATE
            "DCTDecode" -> Chain.DCT
            else -> Chain.SKIP
        }
    }

    /**
     * Flate predictor parameters (SR-5). MuPDF sizes the predictor's row buffers from
     * `/Columns × /Colors × /BitsPerComponent` of `/DecodeParms`, independently of `/Width`. A
     * predictor is accepted only when it describes exactly the image: [columns] == [width],
     * [colors] == [components], [bpc] == 8 (the only depth Compress decodes). Values are those in
     * `/DecodeParms` (null = absent, MuPDF defaults: Predictor 1, Columns 1, Colors 1, BPC 8);
     * [predictor] null or 1 means no predictor, and then the other keys are not used by MuPDF.
     */
    fun flateParamsOk(predictor: Int?, columns: Int?, colors: Int?, bpc: Int?, width: Int, components: Int): Boolean {
        val p = predictor ?: 1
        if (p <= 1) return true
        if (p != 2 && p !in 10..15) return false
        return (columns ?: 1) == width && (colors ?: 1) == components && (bpc ?: 8) == 8
    }
}
