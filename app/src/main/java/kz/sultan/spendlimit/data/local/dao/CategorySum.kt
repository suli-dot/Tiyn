package kz.sultan.spendlimit.data.local.dao

/**
 * Проекция «категория → сумма трат» (результат GROUP BY в [TransactionDao.observeCategorySums]).
 * [category] может быть null — это записи без определённой категории.
 */
data class CategorySum(
    val category: String?,
    val total: Long
)
