package app.venkat.offkit.reader

import app.venkat.offkit.feature.reader.GoToPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The input rules of the Go to page dialog. */
class GoToPageTest {
    private val n = 12

    @Test
    fun emptyOrBlankIsNoPage() {
        assertNull(GoToPage.parse("", n))
        assertNull(GoToPage.parse("   ", n))
    }

    @Test
    fun zeroIsNoPage() {
        assertNull(GoToPage.parse("0", n))
        assertNull(GoToPage.parse("000", n))
    }

    @Test
    fun firstAndLastPagesAreAccepted() {
        assertEquals(1, GoToPage.parse("1", n))
        assertEquals(n, GoToPage.parse("12", n))
        assertEquals(7, GoToPage.parse(" 7 ", n))
    }

    @Test
    fun pastTheLastPageIsNoPage() {
        assertNull(GoToPage.parse("13", n))
        assertNull(GoToPage.parse("2", 1).also { assertEquals(1, GoToPage.parse("1", 1)) })
    }

    @Test
    fun hugeNumbersDoNotOverflow() {
        assertNull(GoToPage.parse("2147483648", n))
        assertNull(GoToPage.parse("99999999999999999999999999", n))
        assertNull(GoToPage.parse("999999999", n))
        assertEquals(5000, GoToPage.parse("5000", 5000))
    }

    @Test
    fun nonDigitsAreNoPage() {
        assertNull(GoToPage.parse("abc", n))
        assertNull(GoToPage.parse("3a", n))
        assertNull(GoToPage.parse("-3", n))
        assertNull(GoToPage.parse("+3", n))
        assertNull(GoToPage.parse("3.0", n))
        assertNull(GoToPage.parse("٣", n))
    }

    @Test
    fun leadingZerosAreIgnored() {
        assertEquals(3, GoToPage.parse("03", n))
        assertEquals(12, GoToPage.parse("0000000000000012", n))
    }

    @Test
    fun theFieldKeepsOnlyDigitsAndAtMostNine() {
        assertEquals("12", GoToPage.clean("p. 1-2"))
        assertEquals("", GoToPage.clean("abc"))
        assertEquals("123456789", GoToPage.clean("12345678901234"))
        assertEquals("007", GoToPage.clean("007"))
        assertEquals("", GoToPage.clean("٣"))
    }
}
