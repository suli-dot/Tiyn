package kz.sultan.spendlimit.ui.voice.wake

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Постоянный офлайн-споттинг одного слова-триггера на потоке с микрофона (Vosk).
 *
 * Распознаватель ограничен грамматикой `["<слово>", "[unk]"]`: всё, кроме ключевого слова,
 * схлопывается в `[unk]`. Это резко снижает ложные срабатывания и нагрузку против free-form STT.
 * Поймав слово — дёргает [onWake] (с дебаунсом, чтобы один «хлопок» не сработал многократно).
 *
 * Микрофон один на приложение: на время захвата суммы вызывающий обязан [stop], а потом [start]
 * заново (Vosk и системный SpeechRecognizer не делят mic). В шаге 1 [onWake] только логирует.
 *
 * Создавать и вызывать на потоке с Looper (главный поток сервиса): так устроены колбэки Vosk.
 */
class WakeWordDetector(
    private val context: Context,
    private val keyword: String = DEFAULT_KEYWORD,
    private val onWake: () -> Unit,
    private val onFailure: (String) -> Unit
) : RecognitionListener {

    private var speechService: SpeechService? = null
    private var lastWakeAt = 0L

    /** Грузит модель (если ещё нет) и начинает слушать. Повторный вызов при активном сеансе игнорируется. */
    fun start() {
        if (speechService != null) return
        VoskModelProvider.load(
            context,
            onReady = { model ->
                // Грамматика ограничивает словарь распознавателя.
                val grammar = "[\"$keyword\", \"[unk]\"]"
                val recognizer = Recognizer(model, SAMPLE_RATE, grammar).apply {
                    // Пословная разбивка с conf в JSON результата — нужна для порога уверенности.
                    setWords(true)
                }
                val service = SpeechService(recognizer, SAMPLE_RATE)
                speechService = service
                service.startListening(this)
                Log.i(TAG, "Wake-word слушает слово «$keyword»")
            },
            onError = { e -> onFailure("Модель Vosk не загрузилась: ${e.message}") }
        )
    }

    /** Останавливает сеанс и освобождает микрофон. Модель остаётся в кэше провайдера. */
    fun stop() {
        speechService?.stop()
        speechService = null
    }

    /**
     * Решение по финальной гипотезе. Партиалы игнорируем — они без conf и «прыгают»
     * (главный источник ложных). Срабатываем, только если распознано РОВНО слово-триггер
     * и минимальная пословная уверенность ≥ [CONF_THRESHOLD].
     */
    private fun onSpeech(json: String?) {
        val obj = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val text = obj.optString("text").trim()
        if (!text.equals(keyword, ignoreCase = true)) return

        val conf = minConfidence(obj)
        if (conf < CONF_THRESHOLD) {
            // Видно в Logcat, где «садится» уверенность ложных против настоящего слова — по этому тюним порог.
            Log.d(TAG, "Отклонил «$text»: conf=$conf < $CONF_THRESHOLD")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < DEBOUNCE_MS) return
        lastWakeAt = now
        Log.i(TAG, "WAKE: «$keyword» conf=$conf")
        onWake()
    }

    /** Минимальная уверенность среди слов результата (setWords(true) кладёт массив "result"). */
    private fun minConfidence(obj: JSONObject): Double {
        val arr = obj.optJSONArray("result") ?: return 1.0
        var min = 1.0
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i)?.optDouble("conf", 1.0) ?: 1.0
            if (c < min) min = c
        }
        return min
    }

    override fun onPartialResult(hypothesis: String?) { /* партиалы не триггерят — слишком шумно */ }
    override fun onResult(hypothesis: String?) = onSpeech(hypothesis)
    override fun onFinalResult(hypothesis: String?) = onSpeech(hypothesis)
    override fun onError(exception: Exception?) = onFailure("Ошибка Vosk: ${exception?.message}")
    override fun onTimeout() { /* SpeechService продолжает читать поток — перезапуск не нужен */ }

    companion object {
        private const val TAG = "WakeWord"
        const val DEFAULT_KEYWORD = "лимит"
        private const val SAMPLE_RATE = 16000.0f
        /** Минимальная пословная уверенность Vosk для приёма триггера (0..1). Крутить тут. */
        private const val CONF_THRESHOLD = 0.85
        /** Окно подавления повторных срабатываний на одно слово. */
        private const val DEBOUNCE_MS = 1500L
    }
}
