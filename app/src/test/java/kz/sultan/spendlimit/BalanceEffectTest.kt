package kz.sultan.spendlimit

import kz.sultan.spendlimit.domain.BalanceEffect
import kz.sultan.spendlimit.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Проверяет арифметику автосписания с остатка. Чистые функции — без Room/DataStore
 * (полная интеграция с реальной БД проверяется на устройстве).
 */
class BalanceEffectTest {

    @Test
    fun purchase_reduces_balance() {
        assertEquals(-5000L, BalanceEffect.ofNew(TransactionType.PURCHASE, 5000L))
    }

    @Test
    fun transfer_reduces_balance() {
        assertEquals(-5000L, BalanceEffect.ofNew(TransactionType.TRANSFER, 5000L))
    }

    @Test
    fun income_increases_balance() {
        assertEquals(5000L, BalanceEffect.ofNew(TransactionType.INCOME, 5000L))
    }

    @Test
    fun edit_amount_adjusts_by_difference() {
        // Покупка 1000 → 1500: остаток должен дополнительно уменьшиться на 500.
        val delta = BalanceEffect.ofEdit(
            oldType = TransactionType.PURCHASE, oldAmountTiyn = 1000L,
            newType = TransactionType.PURCHASE, newAmountTiyn = 1500L
        )
        assertEquals(-500L, delta)
    }

    @Test
    fun edit_type_purchase_to_income_flips_effect() {
        // Было −1000 (покупка), стало +1000 (пополнение) → сдвиг +2000.
        val delta = BalanceEffect.ofEdit(
            oldType = TransactionType.PURCHASE, oldAmountTiyn = 1000L,
            newType = TransactionType.INCOME, newAmountTiyn = 1000L
        )
        assertEquals(2000L, delta)
    }

    @Test
    fun delete_purchase_returns_money() {
        // Удалили покупку 5000 → остаток вырастет на 5000.
        assertEquals(5000L, BalanceEffect.ofDelete(TransactionType.PURCHASE, 5000L))
    }

    @Test
    fun delete_income_removes_money() {
        // Удалили пополнение 5000 → остаток уменьшится на 5000.
        assertEquals(-5000L, BalanceEffect.ofDelete(TransactionType.INCOME, 5000L))
    }

    @Test
    fun new_effect_is_source_agnostic() {
        // Ручная и распознанная операция считаются одинаково — функция не знает об источнике.
        assertEquals(
            BalanceEffect.ofNew(TransactionType.PURCHASE, 2500L),
            BalanceEffect.ofNew(TransactionType.PURCHASE, 2500L)
        )
    }
}
