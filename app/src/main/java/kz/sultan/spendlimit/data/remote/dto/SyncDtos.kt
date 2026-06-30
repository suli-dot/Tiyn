package kz.sultan.spendlimit.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kz.sultan.spendlimit.data.local.entity.RawNotificationEntity
import kz.sultan.spendlimit.data.local.entity.TransactionEntity
import java.time.Instant

/**
 * DTO для выгрузки в Supabase. Имена полей — как столбцы в PostgreSQL (см. docs/supabase_schema.sql).
 *
 * Модель: облако — полный архив, локальная БД — текущий период. Идемпотентность —
 * по паре (user_id, client_id): client_id это локальный id из Room, upsert по нему
 * перезаписывает строку (правки/удаления не дублируются).
 *
 * Время передаём ISO-8601 строкой (epoch millis → Instant.toString()), Postgres
 * принимает её в timestamptz. id и synced_at заполняет сервер (не шлём).
 */

private fun Long.toIso(): String = Instant.ofEpochMilli(this).toString()
private fun String.fromIso(): Long = Instant.parse(this).toEpochMilli()

@Serializable
data class RawNotificationDto(
    @SerialName("user_id") val userId: String,
    @SerialName("client_id") val clientId: Long,
    @SerialName("package_name") val packageName: String,
    val title: String?,
    val text: String,
    @SerialName("posted_at") val postedAt: String
)

fun RawNotificationEntity.toDto(userId: String) = RawNotificationDto(
    userId = userId,
    clientId = id,
    packageName = packageName,
    title = title,
    text = text,
    postedAt = postedAt.toIso()
)

/**
 * Облако → локальная сущность (восстановление). client_id облака — это локальный id
 * (REPLACE по нему). synced = true: запись пришла из облака, повторно выгружать нечего.
 * dedup_key не восстанавливаем (null) — восстановленная запись не из живого пуша.
 */
fun RawNotificationDto.toEntity() = RawNotificationEntity(
    id = clientId,
    packageName = packageName,
    title = title,
    text = text,
    postedAt = postedAt.fromIso(),
    synced = true,
    dedupKey = null
)

@Serializable
data class TransactionDto(
    @SerialName("user_id") val userId: String,
    @SerialName("client_id") val clientId: Long,
    // Локальный id связанного raw_notifications (коррелирует с raw_notifications.client_id).
    @SerialName("raw_id") val rawId: Long?,
    val amount: Long,
    val type: String,
    val merchant: String?,
    val category: String?,
    val currency: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("edited_at") val editedAt: String?,
    @SerialName("deleted_at") val deletedAt: String?
)

fun TransactionEntity.toDto(userId: String) = TransactionDto(
    userId = userId,
    clientId = id,
    rawId = rawId,
    amount = amount,
    type = type,
    merchant = merchant,
    category = category,
    currency = currency,
    createdAt = createdAt.toIso(),
    editedAt = editedAt?.toIso(),
    deletedAt = deletedAt?.toIso()
)

/**
 * Облако → локальная сущность (восстановление). Сохраняем edited_at/deleted_at как есть
 * (soft-deleted строки восстанавливаются удалёнными — в выборки не попадут, повторный синк
 * их не воскресит). synced = true: запись из облака, выгружать повторно нечего.
 */
fun TransactionDto.toEntity() = TransactionEntity(
    id = clientId,
    rawId = rawId,
    amount = amount,
    type = type,
    merchant = merchant,
    category = category,
    currency = currency,
    createdAt = createdAt.fromIso(),
    editedAt = editedAt?.fromIso(),
    deletedAt = deletedAt?.fromIso(),
    synced = true
)
