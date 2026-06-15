package kz.sultan.spendlimit.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kz.sultan.spendlimit.data.local.entity.MerchantRuleEntity

@Dao
interface MerchantRuleDao {

    /** Снимок всех пользовательских правил — категоризатор грузит их в память. */
    @Query("SELECT * FROM merchant_rules")
    suspend fun all(): List<MerchantRuleEntity>

    /** Реактивно — чтобы UI/категоризатор обновлялись при добавлении правил. */
    @Query("SELECT * FROM merchant_rules")
    fun observeAll(): Flow<List<MerchantRuleEntity>>

    /** Точное правило по нормализованному мерчанту. */
    @Query("SELECT * FROM merchant_rules WHERE merchant_norm = :merchantNorm LIMIT 1")
    suspend fun findByMerchant(merchantNorm: String): MerchantRuleEntity?

    /**
     * Upsert: ручная поправка категории затирает прежнее правило по тому же мерчанту
     * (конфликт по уникальному индексу merchant_norm → REPLACE).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: MerchantRuleEntity)
}
