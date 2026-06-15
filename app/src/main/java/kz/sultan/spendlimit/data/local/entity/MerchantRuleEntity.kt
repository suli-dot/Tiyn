package kz.sultan.spendlimit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Правило автокатегоризации: нормализованное имя мерчанта → slug категории.
 *
 * Источник правил двухслойный:
 *  - встроенный словарь в коде (см. [kz.sultan.spendlimit.domain.category.Categorizer]);
 *  - пользовательские поправки — копятся здесь, когда юзер вручную меняет
 *    категорию записи. Пользовательское правило важнее встроенного.
 */
@Entity(
    tableName = "merchant_rules",
    indices = [Index(value = ["merchant_norm"], unique = true)]
)
data class MerchantRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Нормализованное имя мерчанта (lowercase + trim) — ключ сопоставления. */
    @ColumnInfo(name = "merchant_norm")
    val merchantNorm: String,

    /** Slug категории. */
    val category: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
