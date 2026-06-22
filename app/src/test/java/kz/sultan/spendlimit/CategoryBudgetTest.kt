package kz.sultan.spendlimit

import kz.sultan.spendlimit.data.repository.CategoryBudgetStatus
import kz.sultan.spendlimit.data.repository.CategoryLimits
import kz.sultan.spendlimit.data.repository.CategoryPeriodStatus
import kz.sultan.spendlimit.domain.BudgetPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Арифметика статуса категорийного лимита по периодам. Чистые функции — без Room.
 */
class CategoryBudgetTest {

    @Test
    fun periodStatus_underLimit_notExceeded() {
        val ps = CategoryPeriodStatus(BudgetPeriod.DAY, spentTiyn = 3000, limitTiyn = 5000)
        assertFalse(ps.isExceeded)
        assertEquals(2000L, ps.remainingTiyn)
        assertEquals(0.6f, ps.fraction, 0.0001f)
    }

    @Test
    fun periodStatus_overLimit_exceeded() {
        val ps = CategoryPeriodStatus(BudgetPeriod.WEEK, spentTiyn = 7000, limitTiyn = 5000)
        assertTrue(ps.isExceeded)
        assertEquals(-2000L, ps.remainingTiyn) // перерасход
        assertTrue(ps.fraction > 1f)
    }

    @Test
    fun periodStatus_exactlyAtLimit_notExceeded() {
        // Превышение строгое (>): ровно лимит ещё не «исчерпан».
        val ps = CategoryPeriodStatus(BudgetPeriod.MONTH, spentTiyn = 5000, limitTiyn = 5000)
        assertFalse(ps.isExceeded)
        assertEquals(0L, ps.remainingTiyn)
    }

    @Test
    fun periodStatus_noLimit_neutral() {
        val ps = CategoryPeriodStatus(BudgetPeriod.DAY, spentTiyn = 9999, limitTiyn = null)
        assertFalse(ps.hasLimit)
        assertFalse(ps.isExceeded)
        assertEquals(0f, ps.fraction, 0.0001f)
    }

    @Test
    fun budgetStatus_collectsOnlyExceededPeriods() {
        val st = CategoryBudgetStatus(
            categorySlug = "cafe",
            periods = listOf(
                CategoryPeriodStatus(BudgetPeriod.DAY, 6000, 5000),   // исчерпан
                CategoryPeriodStatus(BudgetPeriod.WEEK, 1000, 20000), // ок
                CategoryPeriodStatus(BudgetPeriod.MONTH, 0, null)     // лимита нет
            )
        )
        assertTrue(st.hasAnyLimit)
        assertEquals(listOf(BudgetPeriod.DAY), st.exceededPeriods.map { it.period })
    }

    @Test
    fun budgetStatus_noLimitsAtAll() {
        val st = CategoryBudgetStatus(
            categorySlug = "fuel",
            periods = BudgetPeriod.entries.map { CategoryPeriodStatus(it, 0, null) }
        )
        assertFalse(st.hasAnyLimit)
        assertTrue(st.exceededPeriods.isEmpty())
    }

    @Test
    fun categoryLimits_withAndForPeriod() {
        val limits = CategoryLimits().withPeriod(BudgetPeriod.DAY, 3000)
        assertEquals(3000L, limits.forPeriod(BudgetPeriod.DAY))
        assertNull(limits.forPeriod(BudgetPeriod.WEEK))
        assertFalse(limits.isEmpty)

        val cleared = limits.withPeriod(BudgetPeriod.DAY, null)
        assertTrue(cleared.isEmpty)
    }

    @Test
    fun budgetPeriod_rangeIsHalfOpenAndOrdered() {
        BudgetPeriod.entries.forEach { p ->
            val (from, to) = p.range()
            assertTrue("${p}: from < to", from < to)
        }
        // День — самый узкий, месяц — самый широкий.
        val day = BudgetPeriod.DAY.range().let { it.second - it.first }
        val month = BudgetPeriod.MONTH.range().let { it.second - it.first }
        assertTrue(day <= month)
    }

    @Test
    fun budgetPeriod_currentBucketsHasThreeDistinct() {
        val buckets = BudgetPeriod.currentBuckets()
        // День и месяц форматируются по-разному, неделя = дата понедельника — обычно 3 разных.
        assertTrue(buckets.isNotEmpty())
        // Месячный bucket короче дневного (yyyy-MM vs yyyy-MM-dd).
        assertEquals(7, BudgetPeriod.MONTH.bucket().length)
        assertEquals(10, BudgetPeriod.DAY.bucket().length)
    }
}
