package app.venkat.offkit.ui

import android.content.Intent
import app.venkat.offkit.ui.components.IMAGE_PICKER_MIME_TYPES
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Asserts that [intent] is the image picker (T19): the system file picker (`ACTION_OPEN_DOCUMENT`,
 * so any folder or document provider can be browsed, not only the photo picker), multiple
 * selection, and exactly [IMAGE_PICKER_MIME_TYPES] as the MIME filter (`EXTRA_MIME_TYPES`; the
 * androidx contract sets the intent type itself to all types, as for the PDF picker).
 */
fun assertOpensImageDocumentPicker(intent: Intent?, what: String) {
    assertNotNull("$what opened no picker", intent)
    intent!!
    assertEquals(what, Intent.ACTION_OPEN_DOCUMENT, intent.action)
    assertTrue(what, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    assertArrayEquals(what, IMAGE_PICKER_MIME_TYPES.toTypedArray(), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
}
