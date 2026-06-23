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
        // Начало дня 100 000 ₸; потрачено 15 000 → живой остаток 85 000. Лимит 10 000/день.
        val r = SpendingLimitCalculator.compute(
            balanceTiyn = 8_500_000L,        // живой остаток (старт − траты сегодня)
            obligatoryTiyn = 0L,
            nextIncomeDate = today.plusDays(10),
            spentTodayTiyn = 1_500_000L,     // потратил 15 000 при лимите 10 000
            today = today
        )
        assertEquals(1_000_000L, r.dailyLimitTiyn) // лимит НЕ просел от траты
        assertTrue(r.isExceeded)
        assertEquals(-500_000L, r.remainingTodayTiyn)
    }

    @Test
    fun dailyLimit_stableAfterIntradaySpend() {
        // Регресс на двойной счёт: трата 3 000 ₸ за день не должна менять сам daily.
        // Начало дня 100 000 / 10 дней = 10 000/день; после траты живой остаток 97 000.
        val r = SpendingLimitCalculator.compute(
            balanceTiyn = 9_700_000L,        // 100 000 − 3 000
            obligatoryTiyn = 0L,
            nextIncomeDate = today.plusDays(10),
            spentTodayTiyn = 300_000L,       // 3 000 ₸
            today = today
        )
        assertEquals(1_000_000L, r.dailyLimitTiyn)       // лимит стабилен
        assertEquals(700_000L, r.remainingTodayTiyn)     // осталось 10 000 − 3 000 = 7 000
        assertFalse(r.isExceeded)
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
