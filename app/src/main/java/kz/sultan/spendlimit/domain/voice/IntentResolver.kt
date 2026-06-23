package kz.sultan.spendlimit.domain.voice

/**
 * Превращает распознанную фразу пользователя в [IntentResult].
 *
 * Транспорт за интерфейсом: сегодня — прямой вызов Anthropic
 * ([kz.sultan.spendlimit.data.remote.nlu.AnthropicIntentResolver]); если приложение
 * перестанет быть «только для себя», эту реализацию меняют на «через прокси», а ядро
 * (handler, парсинг, валидация) не трогают. Так же подключается и будущий оффлайн-движок.
 */
interface IntentResolver {
    suspend fun resolve(text: String): IntentResult
}
