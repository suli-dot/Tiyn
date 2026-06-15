package kz.sultan.spendlimit

import kz.sultan.spendlimit.domain.SpendingLimitCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SpendingLimitCalculatorTest {

    private val today = LocalDate.of(2026, 6, 11)

    @Test
    fun basic_dailyLimit() {
        // 100 000 ₸ остаток, без обязательных, поступление через 10 дней.
        val r = SpendingLimitCalculator.compute(
            balanceTiyn = 10_000_000L,
            obligatoryTiyn = 0L,
            nextIncomeDate = today.plusDays(10),
            spentTodayTiyn = 0L,
            today = today
        )
        assertEquals(10, r.daysToCover)
        assertEquals(1_000_000L, r.dailyLimitTiyn) // 10 000 ₸/день
        assertEquals(1_000_000L, r.remainingTodayTiyn)
        assertFalse(r.isExceeded)
    }

    @Test
    fun obligatory_reducesDisposable() {
        // (100 000 − 20 000) / 8 = 10 000 ₸/день
        val r = SpendingLimitCalculator.compute(
            balanceTiyn = 10_000_000L,
            obligatoryTiyn = 2_000_000L,
            nextIncomeDate = today.plusDays(8),
            spentTodayTiyn = 0L,
            today = today
        )
        assertEquals(1_000_000L, r.dailyLimitTiyn)
    }

    @Test
    fun exceeded_whenSpentOverLimit() {
        val r = SpendingLimitCalculator.compute(
            balanceTiyn = 10_000_000L,
            obligatoryTiyn = 0L,
            nextIncomeDate = today.plusDays(10),
            spentTodayTiyn = 1_500_000L, // потратил 15 000 при лимите 10 000
            today = today
        )
        assertTrue(r.isExceeded)
        assertEquals(-500_000L, r.remainingTodayTiyn)
    }

    @Test
    fun incomeDateInPast_doesNotDivideByZero() {
        val r = SpendingLimitCalculator.compute(
            balanceTiyn = 5_000_000L,
            obligatoryTiyn = 0L,
            nextIncomeDate = today.minusDays(3),
            spentTodayTiyn = 0L,
            today = today
        )
        assertEquals(1, r.daysToCover)
        assertEquals(5_000_000L, r.dailyLimitTiyn)
    }
}
