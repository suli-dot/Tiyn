package kz.sultan.spendlimit.data.backup

import kotlinx.serialization.Serializable
import kz.sultan.spendlimit.data.local.entity.CategoryBudgetEntity
import kz.sultan.spendlimit.data.local.entity.MerchantRuleEntity
import kz.sultan.spendlimit.data.local.entity.RawNotificationEntity
import kz.sultan.spendlimit.data.local.entity.TransactionEntity

/**
 * Формат локального файла резервной копии (Канал A — независимый от облака бэкап).
 *
 * Самодостаточный снимок ВСЕХ данных приложения: четыре таблицы Room + пользовательские
 * настройки из DataStore. Восстанавливается без интернета и аккаунта.
 *
 * [schemaVersion] — версия формата файла (НЕ версия БД). Растёт, когда меняется набор
 * полей бэкапа; импорт старого файла должен оставаться возможным после миграций схемы БД.
 * Сейчас совпадает по смыслу с version=5 AppDatabase, но живёт отдельно.
 */
@Serializable
data class BackupFile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** Время создания бэкапа, epoch millis (UTC). Информационное поле. */
    val exportedAt: Long,
    val transactions: List<TransactionDto> = emptyList(),
    val rawNotifications: List<RawNotificationDto> = emptyList(),
    val merchantRules: List<MerchantRuleDto> = emptyList(),
    val categoryBudgets: List<CategoryBudgetDto> = emptyList(),
    val settings: SettingsDto
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 5
    }
}

/**
 * Пользовательские настройки расчёта лимита. Алерт-дедуп (alert_day/level, category_alerts)
 * НАМЕРЕННО не входит в бэкап — это транзиентное состояние конкретного устройства.
 */
@Serializable
data class SettingsDto(
    val balanceTiyn: Long,
    val obligatoryTiyn: Long,
    /** Дата следующего дохода, epochDay. null = не задана. */
    val nextIncomeEpochDay: Long?,
    /** Имя [kz.sultan.spendlimit.data.prefs.ThemeMode]. */
    val themeMode: String
)

@Serializable
data class TransactionDto(
    val id: Long,
    val rawId: Long?,
    val amount: Long,
    val type: String,
    val merchant: String?,
    val category: String?,
    val currency: String,
    val createdAt: Long,
    val editedAt: Long?,
    val deletedAt: Long?,
    val synced: Boolean
)

@Serializable
data class RawNotificationDto(
    val id: Long,
    val packageName: String,
    val title: String?,
    val text: String,
    val postedAt: Long,
    val synced: Boolean
)

@Serializable
data class MerchantRuleDto(
    val id: Long,
    val merchantNorm: String,
    val category: String,
    val createdAt: Long
)

@Serializable
data class CategoryBudgetDto(
    val category: String,
    val limitDayTiyn: Long?,
    val limitWeekTiyn: Long?,
    val limitMonthTiyn: Long?,
    val updatedAt: Long
)

// ---- Мапперы entity <-> dto (чистые, без Android-зависимостей) ----

fun TransactionEntity.toDto() = TransactionDto(
    id, rawId, amount, type, merchant, category, currency, createdAt, editedAt, deletedAt, synced
)

fun TransactionDto.toEntity() = TransactionEntity(
    id = id,
    rawId = rawId,
    amount = amount,
    type = type,
    merchant = merchant,
    category = category,
    currency = currency,
    createdAt = createdAt,
    editedAt = editedAt,
    deletedAt = deletedAt,
    synced = synced
)

fun RawNotificationEntity.toDto() = RawNotificationDto(id, packageName, title, text, postedAt, synced)

fun RawNotificationDto.toEntity() = RawNotificationEntity(
    id = id,
    packageName = packageName,
    title = title,
    text = text,
    postedAt = postedAt,
    synced = synced
)

fun MerchantRuleEntity.toDto() = MerchantRuleDto(id, merchantNorm, category, createdAt)

fun MerchantRuleDto.toEntity() = MerchantRuleEntity(
    id = id,
    merchantNorm = merchantNorm,
    category = category,
    createdAt = createdAt
)

fun CategoryBudgetEntity.toDto() =
    CategoryBudgetDto(category, limitDayTiyn, limitWeekTiyn, limitMonthTiyn, updatedAt)

fun CategoryBudgetDto.toEntity() = CategoryBudgetEntity(
    category = category,
    limitDayTiyn = limitDayTiyn,
    limitWeekTiyn = limitWeekTiyn,
    limitMonthTiyn = limitMonthTiyn,
    updatedAt = updatedAt
)
