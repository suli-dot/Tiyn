package kz.sultan.spendlimit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Распарсенная транзакция.
 *
 * ВАЖНО: [amount] — в ТИЫНАХ (1 тг = 100 тиын), integer. Никаких float для денег.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = RawNotificationEntity::class,
            parentColumns = ["id"],
            childColumns = ["raw_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("raw_id"), Index("created_at"), Index("synced")]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Ссылка на сырое уведомление, из которого получена транзакция. Nullable — на случай ручного ввода. */
    @ColumnInfo(name = "raw_id")
    val rawId: Long?,

    /** Сумма в тиынах (положительная; знак определяется типом). */
    val amount: Long,

    /** Имя [TransactionType]. */
    val type: String,

    val merchant: String?,

    /** Категория — slug из каталога [kz.sultan.spendlimit.domain.category.Category]. null = не определена. */
    val category: String? = null,

    /**
     * Валюта операции (ISO 4217: KZT, KGS, USD…). Задел под мультивалютность.
     * defaultValue 'KZT' — существующие записи трактуются как тенге. Значение
     * обязано совпадать с DEFAULT в миграции 2→3, иначе Room ругнётся на schema mismatch.
     */
    @ColumnInfo(name = "currency", defaultValue = "KZT")
    val currency: String = "KZT",

    /** Время транзакции, epoch millis (UTC). */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** Время ручной правки, epoch millis (UTC). null = запись не редактировалась. */
    @ColumnInfo(name = "edited_at")
    val editedAt: Long? = null,

    /**
     * Soft delete: время удаления, epoch millis (UTC). null = запись активна.
     * Жёсткий DELETE не используем — иначе удаление не доедет до облака и запись
     * вернётся при восстановлении. Удалённые строки участвуют в синхронизации
     * (synced сбрасывается), но исключаются из всех пользовательских выборок.
     */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "synced")
    val synced: Boolean = false
)
