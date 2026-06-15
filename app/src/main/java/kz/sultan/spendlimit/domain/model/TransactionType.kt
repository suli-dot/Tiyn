package kz.sultan.spendlimit.domain.model

/**
 * Тип транзакции, распознанный из уведомления.
 * Хранится в БД как строковое имя (см. [name]) — устойчиво к изменению порядка enum.
 */
enum class TransactionType {
    /** Покупка / оплата (в т.ч. QR) — уменьшает баланс. */
    PURCHASE,

    /** Исходящий перевод другому лицу — уменьшает баланс. */
    TRANSFER,

    /** Поступление (пополнение, входящий перевод, зарплата) — увеличивает баланс. */
    INCOME;

    /** Уменьшает ли транзакция доступный остаток (учитывается в дневных тратах). */
    val isOutgoing: Boolean
        get() = this == PURCHASE || this == TRANSFER

    companion object {
        fun fromName(value: String?): TransactionType =
            entries.firstOrNull { it.name == value } ?: PURCHASE
    }
}
