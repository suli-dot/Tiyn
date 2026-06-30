package kz.sultan.spendlimit.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * Узкий контракт чтения пользовательских настроек. Нужен потребителям, которым
 * достаточно реактивного потока [UserSettings] и не нужен весь [SettingsRepository]
 * (с DataStore/Context). Позволяет внедрять настройки без Android-зависимостей —
 * например, в юнит-тестах [kz.sultan.spendlimit.domain.voice.VoiceCommandHandler].
 */
interface SettingsReader {
    /** Текущие параметры расчёта лимита, реактивно. */
    val settings: Flow<UserSettings>
}
