package kz.sultan.spendlimit.service.notification

import kz.sultan.spendlimit.domain.model.ParsedTransaction

/**
 * Парсер уведомлений конкретного банка. Задел под мультибанковость:
 * добавить банк = дописать одну реализацию (свой пакет + регулярки) и
 * зарегистрировать её в [BankParsers]. Остальной код не меняется.
 */
interface BankNotificationParser {

    /** Относится ли уведомление этого пакета к данному банку. */
    fun supports(packageName: String): Boolean

    /** @return разобранная транзакция или null, если уведомление не финансовое. */
    fun parse(packageName: String, title: String?, text: String?): ParsedTransaction?
}

/**
 * Реестр парсеров банков. Listener обращается сюда, не зная про конкретные банки.
 */
object BankParsers {

    private val parsers: List<BankNotificationParser> = listOf(
        KaspiNotificationParser
        // сюда добавляются другие банки: MBankParser, OptimaParser, …
    )

    fun supports(packageName: String): Boolean =
        parsers.any { it.supports(packageName) }

    fun parse(packageName: String, title: String?, text: String?): ParsedTransaction? =
        parsers.firstOrNull { it.supports(packageName) }
            ?.parse(packageName, title, text)
}
