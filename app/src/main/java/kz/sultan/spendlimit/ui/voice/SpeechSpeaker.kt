package kz.sultan.spendlimit.ui.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Озвучивание ответов (TTS, RU). Замыкает голосовой дуплекс: пользователь сказал —
 * приложение ответило голосом. Инициализация движка асинхронная; реплики, пришедшие до
 * готовности, складываются в [pending] и проговариваются, как только движок поднялся.
 */
class SpeechSpeaker(context: Context) {

    private var ready = false
    private var pending: String? = null

    // Коллбэк инициализации срабатывает асинхронно — к этому моменту val engine уже присвоен.
    // Тип указан явно: иначе вывод типа уходит в рекурсию (engine ссылается на себя в лямбде).
    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            engine.language = Locale("ru")
            ready = true
            pending?.let { speak(it); pending = null }
        }
    }

    /** Проговаривает фразу. До готовности движка запоминает последнюю реплику. */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (ready) engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_reply")
        else pending = text
    }

    fun shutdown() {
        engine.stop()
        engine.shutdown()
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
