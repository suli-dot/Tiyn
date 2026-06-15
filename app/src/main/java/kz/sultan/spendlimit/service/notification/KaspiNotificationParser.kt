package kz.sultan.spendlimit.service.notification

import kz.sultan.spendlimit.domain.model.ParsedTransaction
import kz.sultan.spendlimit.domain.model.TransactionType
import kz.sultan.spendlimit.util.Money

/**
 * Разбор текста push-уведомлений Kaspi.
 *
 * ВНИМАНИЕ: точные формулировки пушей Kaspi не документированы и со временем меняются.
 * Паттерны ниже основаны на типовых форматах и сознательно сделаны гибкими.
 * Перед релизом форматы НУЖНО сверить на реальном устройстве: включить
 * [SpendNotificationListenerService], собрать таблицу raw_notifications и
 * прогнать её через этот парсер (для того сырые тексты и хранятся целиком).
 *
 * Покрываемые сценарии:
 *  - Покупка / оплата:        "Покупка 4 990 ₸, Magnum"
 *  - Оплата по QR:            "Оплата по QR 1 200 ₸, Wolt"
 *  - Исходящий перевод:       "Перевод 10 000 ₸ Айгуль К."
 *  - Входящий перевод/доход:  "Пополнение 50 000 ₸" / "Вы получили перевод 5 000 ₸ от Аскар А."
 */
object KaspiNotificationParser : BankNotificationParser {

    const val KASPI_PACKAGE = "kz.kaspi.mobile"

    /** Валюта Kaspi — тенге. Когда появятся банки в других валютах, каждый укажет свою. */
    private const val CURRENCY_KZT = "KZT"

    override fun supports(packageName: String): Boolean = packageName == KASPI_PACKAGE

    /** Сумма + валюта. Разрешаем неразрывные пробелы как разделители разрядов и копейки. */
    private val AMOUNT = Regex(
        "(\\d[\\d\\u00A0\\u202F ]*(?:[.,]\\d{1,2})?)\\s*(?:₸|тг\\.?|тенге|kzt)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Контрагент после предлога "от" (для входящих переводов).
     * Якорь по началу строки/пробелу, а НЕ через \b: в Java-regex по умолчанию \w/\b
     * не считают кириллицу словом, поэтому "\bот" перед русским именем не срабатывает.
     */
    private val FROM_PARTY = Regex("(?:^|\\s)от\\s+([^.,\\n]+)", RegexOption.IGNORE_CASE)

    private val INCOME_MARKERS = listOf(
        "пополнение", "поступление", "зачисление", "возврат",
        "вы получили", "получен перевод", "перевод от"
    )
    private val TRANSFER_MARKERS = listOf(
        "перевод", "вы перевели", "отправили", "p2p"
    )

    /** После этих слов идёт служебная информация (остаток), а не мерчант — обрезаем. */
    private val MERCHANT_CUT_MARKERS = listOf("доступно", "остаток", "баланс", "на счете", "на счёте")

    /**
     * @return [ParsedTransaction] либо null, если уведомление не финансовое
     *         (нет суммы) или не от Kaspi.
     */
    override fun parse(packageName: String, title: String?, text: String?): ParsedTransaction? {
        if (packageName != KASPI_PACKAGE) return null

        val combined = listOfNotNull(title, text).joinToString("\n").trim()
        if (combined.isEmpty()) return null

        val amountMatch = AMOUNT.find(combined) ?: return null
        val amountTiyn = Money.parseToTiyn(amountMatch.groupValues[1]) ?: return null
        if (amountTiyn <= 0) return null

        val type = detectType(combined)
        val merchant = extractMerchant(combined, amountMatch.range.last + 1, type)

        return ParsedTransaction(
            amountTiyn = amountTiyn,
            type = type,
            merchant = merchant,
            currency = CURRENCY_KZT
        )
    }

    private fun detectType(text: String): TransactionType {
        val lower = text.lowercase()
        // Доход проверяем первым: "перевод от ..." — это доход, хотя содержит слово "перевод".
        if (INCOME_MARKERS.any { lower.contains(it) }) return TransactionType.INCOME
        if (TRANSFER_MARKERS.any { lower.contains(it) }) return TransactionType.TRANSFER
        return TransactionType.PURCHASE
    }

    private fun extractMerchant(text: String, afterAmountIndex: Int, type: TransactionType): String? {
        if (type == TransactionType.INCOME) {
            FROM_PARTY.find(text)?.let { return cleanup(it.groupValues[1]) }
            return null
        }
        // Покупка/перевод: контрагент обычно идёт сразу после суммы.
        val tail = if (afterAmountIndex < text.length) text.substring(afterAmountIndex) else ""
        return cleanup(tail)
    }

    private fun cleanup(raw: String): String? {
        var s = raw.trim()
            .trimStart(',', '.', ':', ';', '-', '—', '–', ' ')
            .removePrefix("в магазине ")
            .removePrefix("в ")
            .trim()

        // Обрезаем хвост со служебной информацией об остатке.
        val lower = s.lowercase()
        var end = s.length
        for (marker in MERCHANT_CUT_MARKERS) {
            val i = lower.indexOf(marker)
            if (i in 0 until end) end = i
        }
        s = s.substring(0, end).trim().trimEnd('.', ',', ';', ' ')

        return s.ifBlank { null }
    }
}
