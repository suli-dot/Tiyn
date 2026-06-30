package kz.sultan.spendlimit

import kz.sultan.spendlimit.domain.ForecastEngine
import kz.sultan.spendlimit.domain.ForecastEngine.Risk
import kz.sultan.spendlimit.domain.SpendingLimitCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Прогнозное ядро [ForecastEngine]: дожитие до поступления по темпу трат + сценарий «что-если».
 * Деньги в тиынах (1 ₸ = 100). today зафиксирован.
 */
class ForecastEngineTest {

    private val today = LocalDate.of(2026, 6, 30)

    // 100 000 ₸ остаток, поступление через 10 дней, без обязательных → безопасный дневной 10 000 ₸.
    private fun base(
        avgDailyTiyn: Long,
        balanceTiyn: Long = 10_000_000L,
        obligatoryTiyn: Long = 0L,
        spentTodayTiyn: Long = 0L,
        days: Int = 10
    ) = ForecastEngine.project(
        balanceTiyn = balanceTiyn,
        obligatoryTiyn = obligatoryTiyn,
        nextIncomeDate = today.plusDays(days.toLong()),
        spentTodayTiyn = spentTodayTiyn,
        avgDailySpendTiyn = avgDailyTiyn,
        today = today
    )

    @Test
    fun survives_whenPaceUnderSafeDaily() {
        val f = base(avgDailyTiyn = 800_000L) // 8 000 ₸/день < 10 000 безопасных
        assertEquals(1_000_000L, f.safeDailyTiyn)
        assertTrue(f.willSurvive)
        assertNull(f.runOutDate)
        assertEquals(0L, f.shortfallPerDayTiyn)
        assertEquals(Risk.LOW, f.risk)
    }

    @Test
    fun runsOut_mediumRisk_inSecondHalf() {
        val f = base(avgDailyTiyn = 1_250_000L) // 12 500 ₸/день > 10 000
        assertFalse(f.willSurvive)
        assertEquals(8, f.daysMoneyLasts)               // 100 000 / 12 500 = 8 дней
        assertEquals(today.plusDays(8), f.runOutDate)
        assertEquals(250_000L, f.shortfallPerDayTiyn)   // режь 2 500 ₸/день
        assertEquals(Risk.MEDIUM, f.risk)
    }

    @Test
    fun runsOut_highRisk_inFirstHalf() {
        val f = base(avgDailyTiyn = 2_500_000L) // 25 000 ₸/день
        assertFalse(f.willSurvive)
        assertEquals(4, f.daysMoneyLasts)               // минус на 4-й день из 10 — первая половина
        assertEquals(today.plusDays(4), f.runOutDate)
        assertEquals(1_500_000L, f.shortfallPerDayTiyn)
        assertEquals(Risk.HIGH, f.risk)
    }

    @Test
    fun negativeDisposable_isHighRisk_runsOutToday() {
        val f = base(avgDailyTiyn = 500_000L, balanceTiyn = 5_000_000L, obligatoryTiyn = 6_000_000L)
        assertEquals(0, f.daysMoneyLasts)
        assertEquals(today, f.runOutDate)
        assertEquals(Risk.HIGH, f.risk)
    }

    @Test
    fun zeroPace_survivesWholePeriod() {
        val f = base(avgDailyTiyn = 0L)
        assertTrue(f.willSurvive)
        assertEquals(10, f.daysMoneyLasts)
        assertEquals(Risk.LOW, f.risk)
    }

    @Test
    fun safeDaily_matchesSpendingLimitCalculator() {
        // Прогноз без гипотетической траты обязан давать тот же дневной лимит, что и калькулятор.
        val income = today.plusDays(10)
        val f = ForecastEngine.project(
            balanceTiyn = 8_500_000L, obligatoryTiyn = 0L, nextIncomeDate = income,
            spentTodayTiyn = 1_500_000L, avgDailySpendTiyn = 700_000L, today = today
        )
        val calc = SpendingLimitCalculator.compute(
            balanceTiyn = 8_500_000L, obligatoryTiyn = 0L, nextIncomeDate = income,
            spentTodayTiyn = 1_500_000L, today = today
        )
        assertEquals(calc.dailyLimitTiyn, f.safeDailyTiyn)
    }

    @Test
    fun incomeDateInPast_coversOneDay() {
        val f = ForecastEngine.project(
            balanceTiyn = 5_000_000L, obligatoryTiyn = 0L, nextIncomeDate = today.minusDays(3),
            spentTodayTiyn = 0L, avgDailySpendTiyn = 0L, today = today
        )
        assertEquals(1, f.daysToCover)
        assertEquals(5_000_000L, f.safeDailyTiyn)
    }

    // ---- Что-если ----

    @Test
    fun whatIf_affordable_dropsDailyButStaysSafe() {
        val w = ForecastEngine.whatIf(
            balanceTiyn = 10_000_000L, obligatoryTiyn = 0L, nextIncomeDate = today.plusDays(10),
            spentTodayTiyn = 0L, avgDailySpendTiyn = 800_000L, spendTiyn = 2_000_000L, today = today
        )
        assertEquals(1_000_000L, w.before.safeDailyTiyn)
        assertEquals(800_000L, w.after.safeDailyTiyn)   // (100 000 − 20 000) / 10
        assertEquals(200_000L, w.dailyDropTiyn)
        assertTrue(w.affordable)
        assertEquals(Risk.LOW, w.after.risk)
    }

    @Test
    fun whatIf_notAffordable_pushesToHighRisk() {
        val w = ForecastEngine.whatIf(
            balanceTiyn = 10_000_000L, obligatoryTiyn = 0L, nextIncomeDate = today.plusDays(10),
            spentTodayTiyn = 0L, avgDailySpendTiyn = 800_000L, spendTiyn = 7_000_000L, today = today
        )
        assertEquals(300_000L, w.after.safeDailyTiyn)   // (100 000 − 70 000) / 10
        assertEquals(700_000L, w.dailyDropTiyn)
        assertFalse(w.affordable)
        assertEquals(Risk.HIGH, w.after.risk)
    }
}
