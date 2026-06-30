package kz.sultan.spendlimit.data.remote

import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.from
import kz.sultan.spendlimit.data.local.entity.RawNotificationEntity
import kz.sultan.spendlimit.data.local.entity.TransactionEntity
import kz.sultan.spendlimit.data.remote.dto.RawNotificationDto
import kz.sultan.spendlimit.data.remote.dto.TransactionDto
import kz.sultan.spendlimit.data.remote.dto.toDto
import kz.sultan.spendlimit.data.remote.dto.toEntity

/**
 * Supabase-реализация выгрузки через Postgrest.
 *
 * Upsert по уникальной паре (user_id, client_id): правки и soft-delete перезаписывают
 * существующую строку в облаке, а не плодят дубли. В каждую строку проставляем
 * user_id текущей сессии — RLS-политики разрешают писать только свои строки.
 *
 * Локальные записи после выгрузки НЕ удаляются (облако — архив, локально — текущий период).
 */
class SupabaseRemoteSyncSource : RemoteSyncSource {

    private val client get() = SupabaseModule.client

    override suspend fun isAuthenticated(): Boolean =
        SupabaseModule.isConfigured && client.auth.currentSessionOrNull() != null

    override suspend fun pushRawNotifications(items: List<RawNotificationEntity>): List<Long> {
        if (items.isEmpty()) return emptyList()
        val userId = client.auth.currentUserOrNull()?.id ?: return emptyList()
        val dtos = items.map { it.toDto(userId) }
        // Мёрж по составному первичному ключу (user_id, client_id) — задан в схеме Supabase.
        client.from("raw_notifications").upsert(dtos)
        return items.map { it.id }
    }

    override suspend fun pushTransactions(items: List<TransactionEntity>): List<Long> {
        if (items.isEmpty()) return emptyList()
        val userId = client.auth.currentUserOrNull()?.id ?: return emptyList()
        val dtos = items.map { it.toDto(userId) }
        // Мёрж по составному первичному ключу (user_id, client_id) — задан в схеме Supabase.
        client.from("transactions").upsert(dtos)
        return items.map { it.id }
    }

    override suspend fun pullRawNotifications(): List<RawNotificationEntity> {
        if (!isAuthenticated()) return emptyList()
        // RLS-политики ограничивают выборку строками текущего пользователя — фильтр не нужен.
        return client.from("raw_notifications").select().decodeList<RawNotificationDto>()
            .map { it.toEntity() }
    }

    override suspend fun pullTransactions(): List<TransactionEntity> {
        if (!isAuthenticated()) return emptyList()
        return client.from("transactions").select().decodeList<TransactionDto>()
            .map { it.toEntity() }
    }
}
