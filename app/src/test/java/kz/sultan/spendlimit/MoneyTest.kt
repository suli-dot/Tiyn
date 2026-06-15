package kz.sultan.spendlimit

import kz.sultan.spendlimit.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun parse_whole() {
        assertEquals(500000L, Money.parseToTiyn("5 000"))
    }

    @Test
    fun parse_withTiyn() {
        assertEquals(500050L, Money.parseToTiyn("5 000,50"))
        assertEquals(129990L, Money.parseToTiyn("1 299,9"))
    }

    @Test
    fun parse_nonBreakingSpace() {
        assertEquals(1_000_000L, Money.parseToTiyn("10 000"))
    }

    @Test
    fun parse_garbage_returnsNull() {
        assertNull(Money.parseToTiyn("abc"))
        assertNull(Money.parseToTiyn(""))
    }

    @Test
    fun format_whole() {
        assertEquals("12 500 ₸", Money.formatTiyn(1_250_000L))
    }

    @Test
    fun format_withTiyn() {
        assertEquals("12 500,50 ₸", Money.formatTiyn(1_250_050L))
    }
}
