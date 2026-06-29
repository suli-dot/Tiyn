package kz.sultan.spendlimit.ui.voice.wake

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Лениво распаковывает офлайн-модель Vosk из assets в filesDir и кэширует [Model].
 *
 * Vosk не умеет читать модель напрямую из сжатого asset — нужна реальная папка на диске.
 * [StorageService.unpack] копирует один раз (помечает распаковку маркером) и на главном
 * потоке отдаёт готовую [Model]. Модель тяжёлая (~45 MB), поэтому держим один экземпляр на процесс.
 *
 * ПОДГОТОВКА: скачать `vosk-model-small-ru-0.22`, распаковать и положить содержимое в
 * `app/src/main/assets/vosk-model-small-ru/`. Без этого [load] вернёт ошибку (приложение не упадёт).
 */
object VoskModelProvider {

    private const val TAG = "WakeWord"

    /** Папка модели внутри assets. */
    private const val ASSET_DIR = "vosk-model-small-ru"

    /** Имя целевой папки в filesDir после распаковки. */
    private const val TARGET_DIR = "vosk-ru"

    @Volatile
    private var cached: Model? = null

    /** Колбэки приходят на главном потоке (так устроен [StorageService]). */
    fun load(context: Context, onReady: (Model) -> Unit, onError: (Exception) -> Unit) {
        cached?.let { onReady(it); return }
        StorageService.unpack(
            context, ASSET_DIR, TARGET_DIR,
            { model ->
                cached = model
                Log.i(TAG, "Vosk-модель распакована и загружена")
                onReady(model)
            },
            { e: IOException ->
                Log.e(TAG, "Не удалось распаковать модель Vosk (положили ли её в assets/$ASSET_DIR?)", e)
                onError(e)
            }
        )
    }
}
