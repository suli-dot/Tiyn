package kz.sultan.spendlimit.domain

import kz.sultan.spendlimit.domain.model.TransactionType

/**
 * Как операция двигает доступный остаток (тиыны). Чистая арифметика, без хранилищ —
 * вынесена отдельно, чтобы покрыть юнит-тестами без Room/DataStore.
 *
 * Знак: исходящая (покупка/перевод) уменьшает остаток, доход — увеличивает.
 */
object BalanceEffect {

    /** Эффект новой операции: исходящая → −сумма, доход → +сумма. */
    fun ofNew(type: TransactionType, amountTiyn: Long): Long =
        if (type.isOutgoing) -amountTiyn else amountTiyn

    /**
     * Корректировка при правке записи: новый эффект минус старый.
     * Текущий остаток уже содержит старый эффект, поэтому применяем разницу.
     */
    fun ofEdit(
        oldType: TransactionType,
        oldAmountTiyn: Long,
        newType: TransactionType,
        newAmountTiyn: Long
    ): Long = ofNew(newType, newAmountTiyn) - ofNew(oldType, oldAmountTiyn)

    /** Возврат при удалении: откат эффекта операции. */
    fun ofDelete(type: TransactionType, amountTiyn: Long): Long =
        -ofNew(type, amountTiyn)
}
