package app.venkat.offkit.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater

/**
 * Minimal PDF writer for hostile test inputs (SR3): plain objects, Flate (or raw) streams, and
 * object streams, written with an xref STREAM so objects can live inside object streams.
 */
internal class HostilePdf {
    private val objs = sortedMapOf<Int, ByteArray>()
    private val inStm = HashMap<Int, Pair<Int, Int>>()

    fun add(num: Int, body: String) = apply { objs[num] = body.toByteArray(Charsets.ISO_8859_1) }

    fun stream(num: Int, dict: String, data: ByteArray, flate: Boolean = true) = apply {
        val d = if (flate) deflate(data) else data
        val f = if (flate) "/Filter/FlateDecode" else ""
        objs[num] = "<<$dict$f/Length ${d.size}>>stream\n".latin1() + d + "\nendstream".latin1()
    }

    /** One object stream [num] holding [members] (object number to body); [dictExtra] is added to its dictionary. */
    fun objStm(num: Int, members: List<Pair<Int, ByteArray>>, dictExtra: String = "/Type/ObjStm", flate: Boolean = true, nKey: String = "N") = apply {
        val header = StringBuilder()
        var off = 0
        for ((n, b) in members) {
            header.append("$n $off ")
            off += b.size + 1
        }
        header.append('\n')
        val body = ByteArrayOutputStream()
        body.write(header.toString().latin1())
        for ((_, b) in members) {
            body.write(b)
            body.write('\n'.code)
        }
        stream(num, "$dictExtra/$nKey ${members.size}/First ${header.length}", body.toByteArray(), flate)
        members.forEachIndexed { i, (n, _) -> inStm[n] = num to i }
    }

    fun bytes(root: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n%âãÏÓ\n".latin1())
        val offs = HashMap<Int, Int>()
        for ((n, b) in objs) {
            offs[n] = out.size()
            out.write("$n 0 obj\n".latin1()); out.write(b); out.write("\nendobj\n".latin1())
        }
        val size = (objs.keys + inStm.keys).max() + 2
        val xn = size - 1
        val xrefAt = out.size()
        val rows = ByteBuffer.allocate(size * 7)
        for (n in 0 until size) {
            val s = inStm[n]
            when {
                n in offs -> rows.put(1).putInt(offs.getValue(n)).putShort(0)
                s != null -> rows.put(2).putInt(s.first).putShort(s.second.toShort())
                n == xn -> rows.put(1).putInt(xrefAt).putShort(0)
                else -> rows.put(0).putInt(0).putShort(if (n == 0) -1 else 0)
            }
        }
        val data = deflate(rows.array())
        out.write("$xn 0 obj\n<</Type/XRef/Size $size/W[1 4 2]/Root $root 0 R/Filter/FlateDecode/Length ${data.size}>>stream\n".latin1())
        out.write(data)
        out.write("\nendstream\nendobj\nstartxref\n$xrefAt\n%%EOF\n".latin1())
        return out.toByteArray()
    }

    companion object {
        fun String.latin1(): ByteArray = toByteArray(Charsets.ISO_8859_1)

        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(9)
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }

        fun repeated(unit: String, count: Int, open: String = "[", close: String = "]"): ByteArray {
            val sb = StringBuilder(open.length + unit.length * count + close.length)
            sb.append(open)
            repeat(count) { sb.append(unit) }
            sb.append(close)
            return sb.toString().latin1()
        }

        /** P pages sharing one `/Annots` array (object 8) of M references to one URI link (object 6). */
        fun sharedAnnots(m: Int, p: Int, linkRect: (Int) -> String = { "0 0 10 10" }): ByteArray {
            val pdf = HostilePdf()
            pdf.add(1, "<</Type/Catalog/Pages 2 0 R>>")
            pdf.add(2, "<</Type/Pages/Kids[" + (0 until p).joinToString(" ") { "${10 + it} 0 R" } + "]/Count $p>>")
            pdf.stream(4, "", "BT ET".latin1())
            pdf.add(6, "<</Type/Annot/Subtype/Link/Rect[${linkRect(0)}]/A<</S/URI/URI(https://example.com/)>>>>")
            pdf.objStm(7, listOf(8 to repeated("6 0 R ", m)))
            for (i in 0 until p) pdf.add(10 + i, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R/Annots 8 0 R>>")
            return pdf.bytes()
        }

        /** One page with [n] DISTINCT URI links (each its own annotation object). */
        fun distinctLinks(n: Int): ByteArray {
            val pdf = HostilePdf()
            pdf.add(1, "<</Type/Catalog/Pages 2 0 R>>")
            pdf.add(2, "<</Type/Pages/Kids[3 0 R]/Count 1>>")
            pdf.add(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 2000 2000]/Contents 4 0 R/Annots[" +
                (0 until n).joinToString(" ") { "${10 + it} 0 R" } + "]>>")
            pdf.stream(4, "", "BT ET".latin1())
            for (i in 0 until n) {
                val x = (i % 100) * 20
                val y = (i / 100) * 20
                pdf.add(10 + i, "<</Type/Annot/Subtype/Link/Rect[$x $y ${x + 10} ${y + 10}]/A<</S/URI/URI(https://example.com/$i)>>>>")
            }
            return pdf.bytes()
        }
    }
}
