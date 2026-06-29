package kz.sultan.spendlimit.ui.voice.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kz.sultan.spendlimit.R
import kz.sultan.spendlimit.SpendLimitApp
import kz.sultan.spendlimit.domain.voice.IntentResult
import kz.sultan.spendlimit.domain.voice.VoiceOutcome
import kz.sultan.spendlimit.ui.voice.SpeechController
import kz.sultan.spendlimit.ui.voice.SpeechSpeaker

/**
 * Foreground-сервис фоновой голосовой активации.
 *
 * Жизненный цикл одного «оборота»:
 *  1. [WakeWordDetector] (офлайн Vosk) ловит слово-триггер на постоянном потоке с микрофона.
 *  2. [onWake]: глушим детектор (освобождаем mic), даём бип+вибро, через паузу запускаем
 *     системный STT на саму команду/сумму.
 *  3. Распознанный текст → существующий pipeline (intentResolver → voiceCommandHandler).
 *  4. Ответ озвучиваем (TTS). Микрофон возвращаем детектору ТОЛЬКО после окончания реплики —
 *     ответ может содержать слово «лимит», иначе эхо само себя триггерит.
 *
 * Ограничение Android 12+/14: mic-FGS стартует только из видимой Activity (см. [start]); после
 * kill сами не воскресаем (START_NOT_STICKY) — переарм пользователем.
 */
class WakeWordService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val container get() = (application as SpendLimitApp).container

    private var detector: WakeWordDetector? = null
    private var amountStt: SpeechController? = null
    private var speaker: SpeechSpeaker? = null

    /** true пока идёт захват суммы — детектор в это время заглушён, повторный [onWake] игнорим. */
    private var capturing = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        speaker = SpeechSpeaker(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startListening()
        if (detector == null) {
            detector = WakeWordDetector(
                context = applicationContext,
                onWake = { onWake() },
                onFailure = { msg -> Log.e(TAG, "Детектор: $msg") }
            ).also { it.start() }
        }
        return START_NOT_STICKY
    }

    // --- Оборот: триггер → захват суммы → pipeline → ответ → возврат детектора ---

    private fun onWake() {
        if (capturing) return
        capturing = true
        Log.i(TAG, "Триггер пойман → захват суммы")
        detector?.stop()            // освободить микрофон для системного STT
        beepAndVibrate()
        // Даём аудиостеку отпустить mic, затем запускаем системное распознавание команды.
        mainHandler.postDelayed({ startAmountCapture() }, HANDOFF_DELAY_MS)
    }

    private fun startAmountCapture() {
        val stt = amountStt ?: SpeechController(
            context = applicationContext,
            onText = { text -> handleCommand(text) },
            onError = { msg -> speakThenResume(msg) },
            onStateChange = { /* состояние mic в сервисе не нужно */ }
        ).also { amountStt = it }
        stt.start()
    }

    private fun handleCommand(text: String) {
        serviceScope.launch {
            val outcome = when (val r = container.intentResolver.resolve(text)) {
                is IntentResult.Failure -> VoiceOutcome.Failed(r.reason)
                is IntentResult.Resolved -> container.voiceCommandHandler.handle(r.intent)
            }
            val msg = outcome.toMessage()
            Log.i(TAG, "Ответ: $msg")
            speakThenResume(msg)
        }
    }

    /** Озвучивает ответ и лишь ПОСЛЕ окончания реплики возвращает микрофон детектору. */
    private fun speakThenResume(msg: String) {
        val sp = speaker
        if (sp == null) {
            resumeWake()
            return
        }
        var resumed = false
        val resumeOnce = {
            if (!resumed) {
                resumed = true
                resumeWake()
            }
        }
        // Подстраховка: если TTS не отдаст onDone (движок не поднялся) — вернём детектор по таймауту.
        mainHandler.postDelayed({ resumeOnce() }, SPEAK_WATCHDOG_MS)
        sp.speak(msg) { resumeOnce() }
    }

    private fun resumeWake() {
        capturing = false
        detector?.start()
        Log.i(TAG, "Снова слушаю слово-триггер")
    }

    private fun VoiceOutcome.toMessage(): String = when (this) {
        is VoiceOutcome.Recorded -> message
        is VoiceOutcome.Answer -> message
        is VoiceOutcome.NeedClarify -> question
        is VoiceOutcome.Failed -> message
    }

    private fun beepAndVibrate() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
            mainHandler.postDelayed({ runCatching { tone.release() } }, 300)
        }
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            }
            vib?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // --- Foreground / нотификация ---

    private fun startListening() {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Голосовая активация включена")
            .setContentText("Слушаю слово «${WakeWordDetector.DEFAULT_KEYWORD}»")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Выключить", stopPi)
            .build()

        // type=microphone обязателен на Android 14; на <Q (API 29) тип игнорируется.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        amountStt?.destroy()
        amountStt = null
        speaker?.shutdown()
        speaker = null
        detector?.stop()
        detector = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WakeWord"
        private const val CHANNEL_ID = "wake_listen"
        private const val NOTIF_ID = 3001
        const val ACTION_STOP = "kz.sultan.spendlimit.wake.STOP"

        /** Пауза между остановкой Vosk и стартом системного STT — дать аудиостеку отпустить mic. */
        private const val HANDOFF_DELAY_MS = 250L
        /** Если TTS не сообщит об окончании — вернуть детектор по этому таймауту. */
        private const val SPEAK_WATCHDOG_MS = 8000L
        private const val BEEP_VOLUME = 80
        private const val BEEP_MS = 150

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Голосовая активация",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Постоянное уведомление, пока приложение слушает слово-триггер" }
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
            }
        }

        /** ВАЖНО: вызывать только из видимой Activity — ограничение mic-FGS на API 31+. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WakeWordService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
