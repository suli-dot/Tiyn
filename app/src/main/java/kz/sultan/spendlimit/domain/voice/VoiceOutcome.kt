package kz.sultan.spendlimit.domain.voice

/**
 * Итог обработки голосовой команды — то, что показываем/озвучиваем пользователю.
 * UI-слой (кнопка-микрофон + ViewModel) маппит это в snackbar/TTS.
 */
sealed interface VoiceOutcome {

    /** Операция записана/изменена. [message] — подтверждение («Записал расход 1 500 ₸ — Кафе»). */
    data class Recorded(val message: String) : VoiceOutcome

    /** Ответ на запрос (query_*). [message] — готовая фраза с числом. */
    data class Answer(val message: String) : VoiceOutcome

    /** Не хватает данных или сработала санити-проверка — нужен переспрос. */
    data class NeedClarify(val question: String) : VoiceOutcome

    /** Техническая ошибка либо команда пока не поддержана. */
    data class Failed(val message: String) : VoiceOutcome
}
