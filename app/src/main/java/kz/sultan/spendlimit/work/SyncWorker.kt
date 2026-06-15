package kz.sultan.spendlimit.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kz.sultan.spendlimit.data.local.AppDatabase
import kz.sultan.spendlimit.data.remote.RemoteSyncSource
import kz.sultan.spendlimit.data.remote.SupabaseRemoteSyncSource
import kz.sultan.spendlimit.data.remote.SupabaseModule

/**
 * Фоновая выгрузка локальной очереди (raw_notifications + transactions) в Supabase.
 * После успешной отправки записи помечаются synced (локально не удаляем сразу —
 * сырые уведомления нужны для возможного перепарсинга).
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val db = AppDatabase.get(context)
    private val remote: RemoteSyncSource = SupabaseRemoteSyncSource()

    override suspend fun doWork(): Result {
        // Supabase не настроен (нет ключей) — оффлайн-режим, синкать нечего.
        if (!SupabaseModule.isConfigured) return Result.success()
        return try {
            // Нет сессии (юзер ещё не вошёл) — не падаем, повторим позже.
            if (!remote.isAuthenticated()) return Result.retry()

            val rawDao = db.rawNotificationDao()
            val txDao = db.transactionDao()

            // unsynced включает удалённые (deleted_at) — факт удаления тоже должен уехать.
            val rawBatch = rawDao.unsynced()
            if (rawBatch.isNotEmpty()) {
                val syncedRawIds = remote.pushRawNotifications(rawBatch)
                if (syncedRawIds.isNotEmpty()) rawDao.markSynced(syncedRawIds)
            }

            val txBatch = txDao.unsynced()
            if (txBatch.isNotEmpty()) {
                val syncedTxIds = remote.pushTransactions(txBatch)
                if (syncedTxIds.isNotEmpty()) txDao.markSynced(syncedTxIds)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Синхронизация не удалась, повтор позже", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
    }
}
