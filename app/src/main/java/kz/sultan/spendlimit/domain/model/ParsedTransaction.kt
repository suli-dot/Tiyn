package kz.sultan.spendlimit.domain.model

/**
 * Результат разбора текста уведомления.
 *
 * @param amountTiyn сумма в минорных единицах валюты (1 ед. = 100 минорных), всегда > 0.
 * @param type тип операции.
 * @param merchant продавец/контрагент, если удалось вытащить.
 * @param currency валюта операции (ISO 4217). Парсер банка определяет её по символу/тексту.
 */
data class ParsedTransaction(
    val amountTiyn: Long,
    val type: TransactionType,
    val merchant: String?,
    val currency: String = "KZT"
)
