package kz.sultan.spendlimit.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Состояние микрофона для UI. NLU-фаза (запрос к модели) отслеживается отдельно во ViewModel. */
enum class MicState { IDLE, LISTENING }

/**
 * Один сеанс распознавания речи (RU) поверх системного [SpeechRecognizer].
 *
 * Только голос→текст: распознанную фразу отдаёт в [onText], ошибки — в [onError] (готовым
 * русским текстом), смену состояния — в [onStateChange]. Что делать с текстом (NLU, запись)
 * решает вызывающий — здесь этого нет. Объект привязан к экрану и уничтожается в [destroy].
 */
class SpeechController internal constructor(
    context: Context,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onStateChange: (MicState) -> Unit
) : RecognitionListener {

    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context))
            SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(this) }
        else null

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }

    /** Начинает слушать. Вызывать на главном потоке (из Compose) и только при выданном RECORD_AUDIO. */
    fun start() {
        val r = recognizer ?: run {
            onError("Распознавание речи недоступно на устройстве")
            return
        }
        onStateChange(MicState.LISTENING)
        r.startListening(intent)
    }

    fun destroy() = recognizer?.destroy() ?: Unit

    override fun onResults(results: Bundle?) {
        onStateChange(MicState.IDLE)
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim()
        if (text.isNullOrEmpty()) onError("Не расслышал — повтори?") else onText(text)
    }

    override fun onError(error: Int) {
        onStateChange(MicState.IDLE)
        onError(errorText(error))
    }

    override fun onEndOfSpeech() { onStateChange(MicState.IDLE) }

    // Остальные коллбэки слушателя не нужны для разовой команды.
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал — повтори?"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания речи"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято — повтори"
        SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи звука"
        else -> "Ошибка распознавания ($code)"
    }
}

/** Создаёт [SpeechController], привязанный к жизни Composable: уничтожается при уходе с экрана. */
@Composable
fun rememberSpeechController(
    onText: (String) -> Unit,
    onError: (String) -> Unit,
    onStateChange: (MicState) -> Unit
): SpeechController {
    val context = LocalContext.current
    val controller = remember {
        SpeechController(context.applicationContext, onText, onError, onStateChange)
    }
    DisposableEffect(Unit) { onDispose { controller.destroy() } }
    return controller
}
