package kz.sultan.spendlimit.domain.model

/**
 * Доменная модель транзакции (для UI и расчётов). Деньги — в тиынах.
 */
data class Transaction(
    val id: Long,
    val amountTiyn: Long,
    val type: TransactionType,
    val merchant: String?,
    val category: String?,
    val createdAt: Long,
    /** Валюта операции (ISO 4217). */
    val currency: String = "KZT",
    /** Время ручной правки (null = запись не редактировалась). */
    val editedAt: Long? = null,
    /** Запись добавлена вручную (нет сырого уведомления, raw_id IS NULL). */
    val isManual: Boolean = false
)
