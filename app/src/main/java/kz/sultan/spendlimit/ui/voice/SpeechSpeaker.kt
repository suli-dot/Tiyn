package kz.sultan.spendlimit.ui.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Озвучивание ответов (TTS, RU). Замыкает голосовой дуплекс: пользователь сказал —
 * приложение ответило голосом. Инициализация движка асинхронная; реплики, пришедшие до
 * готовности, складываются в [pendingText] и проговариваются, как только движок поднялся.
 *
 * [speak] принимает необязательный `onDone` — он вызывается на главном потоке по завершении
 * реплики (или сразу, если движок недоступен/текст пуст). Это нужно фоновой голосовой
 * активации: микрофон возвращаем детектору только после ответа, иначе TTS, содержащий слово
 * «лимит», сам себя триггерит через эхо.
 */
class SpeechSpeaker(context: Context) {

    private var ready = false
    private var pendingText: String? = null
    private var pendingDone: (() -> Unit)? = null
    private var onDone: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    // Коллбэк инициализации срабатывает асинхронно — к этому моменту val engine уже присвоен.
    // Тип указан явно: иначе вывод типа уходит в рекурсию (engine ссылается на себя в лямбде).
    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            engine.language = Locale("ru")
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { main.post { fireDone() } }
                @Deprecated("Старый коллбэк до API 21")
                override fun onError(utteranceId: String?) { main.post { fireDone() } }
                override fun onError(utteranceId: String?, errorCode: Int) { main.post { fireDone() } }
            })
            ready = true
            val t = pendingText
            val d = pendingDone
            pendingText = null
            pendingDone = null
            t?.let { speak(it, d) }
        }
    }

    private fun fireDone() {
        val d = onDone
        onDone = null
        d?.invoke()
    }

    /** Проговаривает фразу; [onDone] — по завершении (или сразу, если текст пуст). */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        if (ready) {
            this.onDone = onDone
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } else {
            pendingText = text
            pendingDone = onDone
        }
    }

    fun shutdown() {
        engine.stop()
        engine.shutdown()
    }

    private companion object {
        const val UTTERANCE_ID = "voice_reply"
    }
}

/** [SpeechSpeaker], живущий вместе с экраном: движок освобождается при уходе с экрана. */
@Composable
fun rememberSpeechSpeaker(): SpeechSpeaker {
    val context = LocalContext.current
    val speaker = remember { SpeechSpeaker(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    return speaker
}
