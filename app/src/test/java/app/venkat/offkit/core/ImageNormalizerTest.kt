package app.venkat.offkit.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.PdfUtilApp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImageNormalizerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var outDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        (context as PdfUtilApp).engine = FakePdfEngine()
        outDir = tmp.newFolder("out")
    }

    private fun webp(width: Int, height: Int, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val bytes = ByteArrayOutputStream()
        assertTrue(bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, bytes))
        bmp.recycle()
        return bytes.toByteArray()
    }

    private fun assertJpegMagic(file: File) {
        val head = file.readBytes().copyOf(3)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), head)
    }

    @Test
    fun jpegPassesThroughByteIdentical() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) + Random(1).nextBytes(5000)
        val uri = CoreTestSupport.contentUri(context, "a.webp", bytes) // name is irrelevant: content is sniffed
        val out = ImageNormalizer.normalize(context, uri, outDir, 3)
        assertEquals("img_003.jpg", out.name)
        assertArrayEquals(bytes, out.readBytes())
        assertEquals(listOf(out), outDir.listFiles()!!.toList())
    }

    @Test
    fun pngPassesThroughByteIdentical() {
        val magic = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() }.toByteArray()
        val bytes = magic + Random(2).nextBytes(3000)
        val out = ImageNormalizer.normalize(context, CoreTestSupport.contentUri(context, "b", bytes), outDir, 0)
        assertEquals("img_000.png", out.name)
        assertArrayEquals(bytes, out.readBytes())
    }

    @Test
    fun randomBytesThrowUnsupportedImage() = assertJunkRejected()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun randomBytesThrowUnsupportedImageWithNativeDecoder() = assertJunkRejected()

    private fun assertJunkRejected() {
        // Leading bytes match no image signature (note: random data starting with 0x00 can be a valid WBMP).
        val bytes = "NOT AN IMAGE".toByteArray() + Random(3).nextBytes(4096)
        try {
            ImageNormalizer.normalize(context, CoreTestSupport.contentUri(context, "junk", bytes), outDir, 0)
            fail("expected UnsupportedImageException")
        } catch (e: UnsupportedImageException) {
            // expected
        }
        assertTrue(outDir.listFiles()!!.isEmpty())
    }

    @Test
    fun targetSizeCapsLongEdge() {
        assertEquals(4096 to 2731, ImageNormalizer.targetSize(6000, 4000))
        assertEquals(2731 to 4096, ImageNormalizer.targetSize(4000, 6000))
        assertEquals(4096 to 4096, ImageNormalizer.targetSize(4096, 4096))
        assertEquals(10 to 20, ImageNormalizer.targetSize(10, 20))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun webpIsTranscodedToJpegWithSameDimensions() {
        val uri = CoreTestSupport.contentUri(context, "c.webp", webp(320, 200, Color.rgb(200, 30, 30)))
        val out = ImageNormalizer.normalize(context, uri, outDir, 1)
        assertEquals("img_001.jpg", out.name)
        assertJpegMagic(out)
        val decoded = BitmapFactory.decodeFile(out.absolutePath)
        assertEquals(320, decoded.width)
        assertEquals(200, decoded.height)
        assertEquals(listOf(out), outDir.listFiles()!!.toList())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun largeWebpIsDownsampled() {
        val uri = CoreTestSupport.contentUri(context, "big.webp", webp(6000, 4000, Color.BLUE))
        val out = ImageNormalizer.normalize(context, uri, outDir, 2)
        assertJpegMagic(out)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.absolutePath, bounds)
        assertTrue("long edge ${bounds.outWidth}", maxOf(bounds.outWidth, bounds.outHeight) <= 4096)
        assertEquals(4096, bounds.outWidth)
        assertEquals(2731, bounds.outHeight)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun transparentWebpIsFlattenedOntoWhite() {
        val uri = CoreTestSupport.contentUri(context, "t.webp", webp(64, 64, Color.TRANSPARENT))
        val out = ImageNormalizer.normalize(context, uri, outDir, 0)
        val pixel = BitmapFactory.decodeFile(out.absolutePath).getPixel(32, 32)
        assertTrue("pixel ${Integer.toHexString(pixel)} should be white",
            Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun bitmapFactoryPathSubsamplesLargeImages() {
        val src = tmp.newFile("big.webp").apply { writeBytes(webp(6000, 4000, Color.GREEN)) }
        val bmp = ImageNormalizer.decodeWithBitmapFactory(src)
        try {
            assertTrue(maxOf(bmp.width, bmp.height) <= 4096)
            assertEquals(3000, bmp.width)
            assertTrue(bmp.isMutable)
        } finally {
            bmp.recycle()
        }
        val junk = tmp.newFile("junk").apply { writeBytes(ByteArray(100) { 7 }) }
        try {
            ImageNormalizer.decodeWithBitmapFactory(junk)
            fail("expected UnsupportedImageException")
        } catch (e: UnsupportedImageException) {
            // expected
        }
    }
}
