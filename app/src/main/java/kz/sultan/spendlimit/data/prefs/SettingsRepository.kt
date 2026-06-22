package kz.sultan.spendlimit.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kz.sultan.spendlimit.data.backup.SettingsDto
import java.time.LocalDate

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Пользовательские параметры расчёта лимита.
 * Деньги — в тиынах (Long). Дата — epochDay (Long).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val BALANCE = longPreferencesKey("balance_tiyn")
        val OBLIGATORY = longPreferencesKey("obligatory_tiyn")
        val NEXT_INCOME = longPreferencesKey("next_income_epoch_day")
        val THEME = stringPreferencesKey("theme_mode")
        val ALERT_DAY = longPreferencesKey("alert_day_epoch")
        val ALERT_LEVEL = intPreferencesKey("alert_level")
        val CATEGORY_ALERTS = stringSetPreferencesKey("category_alerts")
    }

    /**
     * Множество уже показанных уведомлений о превышении лимита категории.
     * Ключ — "slug|PERIOD|bucket" (bucket: день yyyy-MM-dd, неделя — дата понедельника,
     * месяц yyyy-MM), поэтому каждая пара категория+период алертится раз на свой отрезок.
     */
    val categoryAlerts: Flow<Set<String>> = context.dataStore.data.map { p ->
        p[Keys.CATEGORY_ALERTS] ?: emptySet()
    }

    /**
     * Помечает пару категория+период как оповещённую в текущем отрезке. Заодно вычищает
     * протухшие ключи: оставляем только те, чей bucket входит в [currentBuckets]
     * (текущий день/неделя/месяц), чтобы множество не росло бесконечно.
     */
    suspend fun addCategoryAlert(key: String, currentBuckets: Set<String>) {
        context.dataStore.edit { p ->
            val kept = (p[Keys.CATEGORY_ALERTS] ?: emptySet())
                .filterTo(mutableSetOf()) { existing -> currentBuckets.any { existing.endsWith("|$it") } }
            kept.add(key)
            p[Keys.CATEGORY_ALERTS] = kept
        }
    }

    /**
     * Какой порог дневного лимита уже отображён сегодня (анти-спам уведомлений).
     * [level]: 0 — ничего, 80 — предупреждение, 100 — превышение.
     * Сброс делать не нужно: при смене дня [dayEpoch] перестаёт совпадать с сегодняшним.
     */
    val alertState: Flow<AlertState> = context.dataStore.data.map { p ->
        AlertState(dayEpoch = p[Keys.ALERT_DAY] ?: 0L, level = p[Keys.ALERT_LEVEL] ?: 0)
    }

    suspend fun setAlertState(dayEpoch: Long, level: Int) {
        context.dataStore.edit { p ->
            p[Keys.ALERT_DAY] = dayEpoch
            p[Keys.ALERT_LEVEL] = level
        }
    }

    /** Выбранный режим темы; по умолчанию следуем системе. */
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { p ->
        p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { p -> p[Keys.THEME] = mode.name }
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            balanceTiyn = p[Keys.BALANCE] ?: 0L,
            obligatoryTiyn = p[Keys.OBLIGATORY] ?: 0L,
            nextIncomeDate = p[Keys.NEXT_INCOME]?.let { LocalDate.ofEpochDay(it) }
        )
    }

    suspend fun update(balanceTiyn: Long, obligatoryTiyn: Long, nextIncomeDate: LocalDate) {
        context.dataStore.edit { p ->
            p[Keys.BALANCE] = balanceTiyn
            p[Keys.OBLIGATORY] = obligatoryTiyn
            p[Keys.NEXT_INCOME] = nextIncomeDate.toEpochDay()
        }
    }

    /**
     * Атомарно сдвигает остаток на дельту (тиыны, может быть отрицательной).
     * `DataStore.edit` сериализует операции read-modify-write, поэтому два
     * параллельных вызова не затрут друг друга (защита от гонки/двойного счёта).
     */
    suspend fun applyBalanceDelta(deltaTiyn: Long) {
        if (deltaTiyn == 0L) return
        context.dataStore.edit { p ->
            p[Keys.BALANCE] = (p[Keys.BALANCE] ?: 0L) + deltaTiyn
        }
    }

    /**
     * Прямая установка остатка (ручная сверка с реальным балансом карты).
     * Пишет в тот же ключ [Keys.BALANCE] — единый источник правды; это НЕ транзакция,
     * записей в transactions не создаёт.
     */
    suspend fun setBalance(balanceTiyn: Long) {
        context.dataStore.edit { p -> p[Keys.BALANCE] = balanceTiyn }
    }

    /**
     * Снимок настроек для бэкапа. Алерт-дедуп (ALERT_DAY/LEVEL, CATEGORY_ALERTS) НЕ включаем —
     * это транзиентное состояние устройства, не пользовательские данные.
     */
    suspend fun exportSettings(): SettingsDto {
        val p = context.dataStore.data.first()
        return SettingsDto(
            balanceTiyn = p[Keys.BALANCE] ?: 0L,
            obligatoryTiyn = p[Keys.OBLIGATORY] ?: 0L,
            nextIncomeEpochDay = p[Keys.NEXT_INCOME],
            themeMode = p[Keys.THEME] ?: ThemeMode.SYSTEM.name
        )
    }

    /** Восстановление настроек из бэкапа. Перезаписывает баланс/обязательные/дату дохода/тему. */
    suspend fun importSettings(dto: SettingsDto) {
        context.dataStore.edit { p ->
            p[Keys.BALANCE] = dto.balanceTiyn
            p[Keys.OBLIGATORY] = dto.obligatoryTiyn
            if (dto.nextIncomeEpochDay != null) {
                p[Keys.NEXT_INCOME] = dto.nextIncomeEpochDay
            } else {
                p.remove(Keys.NEXT_INCOME)
            }
            // Валидируем имя темы — мусор из чужого файла не должен ломать чтение.
            val theme = runCatching { ThemeMode.valueOf(dto.themeMode) }.getOrNull() ?: ThemeMode.SYSTEM
            p[Keys.THEME] = theme.name
        }
    }
}

/** Режим оформления приложения. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Последний показанный сегодня порог лимита (для дедупликации уведомлений). */
data class AlertState(val dayEpoch: Long, val level: Int)

data class UserSettings(
    val balanceTiyn: Long,
    val obligatoryTiyn: Long,
    val nextIncomeDate: LocalDate?
) {
    val isConfigured: Boolean get() = nextIncomeDate != null && balanceTiyn > 0
}
