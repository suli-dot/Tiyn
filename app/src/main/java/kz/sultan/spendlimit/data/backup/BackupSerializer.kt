package kz.sultan.spendlimit.data.backup

import kotlinx.serialization.json.Json

/**
 * Чистое преобразование [BackupFile] ↔ JSON-строка. Без Android-зависимостей —
 * полностью покрывается JVM-юнит-тестом (round-trip).
 *
 * [encodeDefaults] = true: schemaVersion и пустые списки должны попадать в файл явно,
 * чтобы импорт старого бэкапа не падал на отсутствующих полях.
 * [ignoreUnknownKeys] = true: файл, сделанный более новой версией приложения, всё ещё
 * читается старой (лишние поля игнорируются).
 */
object BackupSerializer {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** @throws kotlinx.serialization.SerializationException если файл повреждён/не тот формат. */
    fun decode(text: String): BackupFile = json.decodeFromString(BackupFile.serializer(), text)
}
