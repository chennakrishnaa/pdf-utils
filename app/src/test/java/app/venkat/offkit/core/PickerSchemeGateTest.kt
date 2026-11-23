package app.venkat.offkit.core

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.feature.addimages.AddImagesViewModel
import app.venkat.offkit.feature.compress.CompressViewModel
import app.venkat.offkit.feature.imagestopdf.ImagesToPdfViewModel
import app.venkat.offkit.feature.merge.MergeViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * SB-14: the feature ViewModels accept only `content:` Uris from the pickers; `file:` and other
 * schemes are ignored (never described, read or listed) without a crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PickerSchemeGateTest {
    private lateinit var app: Application
    private lateinit var privateFile: Uri
    private val others = listOf(
        Uri.parse("android.resource://app.venkat.offkit/raw/x"),
        Uri.parse("relative/path.pdf"),
        Uri.parse("http://example.invalid/x.pdf"),
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        (app as PdfUtilApp).engine = FakePdfEngine()
        // An app-private file a hostile picker answer could point at.
        privateFile = Uri.fromFile(File(app.filesDir, "secret.pdf").apply { writeText("%PDF-1.7 secret") })
    }

    private fun content(name: String) = CoreTestSupport.contentUri(app, name, "%PDF-1.7 $name".toByteArray())

    private fun settle() {
        repeat(5) { shadowOf(android.os.Looper.getMainLooper()).idle() }
        Thread.sleep(50)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun mergeIgnoresNonContentUris() {
        val vm = MergeViewModel(app)
        val ok = content("a.pdf")
        vm.add(listOf(privateFile) + others + ok)
        CoreTestSupport.idleUntil(message = "merge item") { vm.items.value.isNotEmpty() }
        assertEquals(listOf(ok), vm.items.value.map { it.file.uri })
        vm.add(listOf(privateFile))
        settle()
        assertEquals(listOf(ok), vm.items.value.map { it.file.uri })
    }

    @Test
    fun compressIgnoresNonContentUri() {
        val vm = CompressViewModel(app)
        vm.choose(privateFile)
        others.forEach(vm::choose)
        settle()
        assertNull(vm.file.value)
        val ok = content("b.pdf")
        vm.choose(ok)
        CoreTestSupport.idleUntil(message = "compress file") { vm.file.value != null }
        assertEquals(ok, vm.file.value?.uri)
    }

    @Test
    fun addImagesIgnoresNonContentUris() {
        val vm = AddImagesViewModel(app)
        vm.choosePdf(listOf(privateFile))
        vm.addImages(listOf(privateFile) + others)
        settle()
        assertNull(vm.pdf.value)
        assertEquals(0, vm.images.value.size)
    }

    @Test
    fun imagesToPdfIgnoresNonContentUris() {
        val vm = ImagesToPdfViewModel(app)
        vm.add(listOf(privateFile) + others)
        settle()
        assertEquals(0, vm.images.value.size)
    }
}
