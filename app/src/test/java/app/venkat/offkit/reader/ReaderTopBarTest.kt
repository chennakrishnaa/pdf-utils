package app.venkat.offkit.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.venkat.offkit.MainActivity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.venkat.offkit.feature.reader.IndicatorFit
import app.venkat.offkit.feature.reader.ReaderTopBarButtons
import app.venkat.offkit.feature.reader.TAG_READER_SEARCH
import app.venkat.offkit.feature.reader.TAG_READER_SHARE
import app.venkat.offkit.feature.reader.indicatorFit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The reader top bar's fixed buttons and the narrow-width rule of its page indicator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderTopBarTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun shareIsDisabledAndNotClickableWhileACopyIsPrepared() {
        var shares = 0
        var searches = 0
        var enabled by mutableStateOf(false)
        // The manifest declares no bare ComponentActivity: replace MainActivity's content instead.
        rule.runOnUiThread {
            rule.activity.setContent { Row { ReaderTopBarButtons(onSearch = { searches++ }, onShare = { shares++ }, shareEnabled = enabled) } }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_READER_SHARE).assertIsNotEnabled().performClick()
        rule.onNodeWithTag(TAG_READER_SEARCH).assertIsEnabled().performClick()
        assertEquals(0, shares)
        assertEquals(1, searches)
        enabled = true
        rule.onNodeWithTag(TAG_READER_SHARE).assertIsEnabled().performClick()
        assertEquals(1, shares)
    }

    @Test
    fun compressIsDisabledWhileAShareCopyIsPrepared() {
        var items: List<app.venkat.offkit.feature.reader.ReaderMenuItem> = emptyList()
        var sharing by mutableStateOf(true)
        rule.runOnUiThread {
            rule.activity.setContent {
                items = app.venkat.offkit.feature.reader.readerMenuItems(
                    onGoToPage = {},
                    onOpenAnother = {},
                    onCompress = {},
                    compressEnabled = !sharing,
                )
            }
        }
        rule.waitForIdle()
        assertEquals(false, items.single { it.tag == app.venkat.offkit.feature.reader.TAG_READER_COMPRESS }.enabled)
        sharing = false
        rule.waitForIdle()
        assertEquals(true, items.single { it.tag == app.venkat.offkit.feature.reader.TAG_READER_COMPRESS }.enabled)
    }

    @Test
    fun theIndicatorDropsItsTotalThenHidesBeforeTheTitleGoesUnder72dp() {
        assertEquals(IndicatorFit.FULL, indicatorFit(free = 140.dp, full = 60.dp, pageOnly = 20.dp))
        assertEquals(IndicatorFit.PAGE_ONLY, indicatorFit(free = 100.dp, full = 60.dp, pageOnly = 20.dp))
        assertEquals(IndicatorFit.NONE, indicatorFit(free = 80.dp, full = 60.dp, pageOnly = 20.dp))
    }
}
