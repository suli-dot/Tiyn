package kz.sultan.spendlimit.data.remote.nlu

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kz.sultan.spendlimit.domain.voice.IntentResolver
import kz.sultan.spendlimit.domain.voice.IntentResult
import java.time.LocalDate

/**
 * Реализация [IntentResolver] поверх Anthropic Messages API (прямой вызов с устройства).
 *
 * Ключ берётся из BuildConfig (заполняется из local.properties, в git не попадает).
 * Это уровень защиты «только у меня»: APK с телефона никуда не уходит, исходник чист.
 * Когда приложение станет раздаваться — эту реализацию заменяют на «через прокси»,
 * остальной код (handler/парсер/валидация) не меняется.
 *
 * tool_choice=any → модель обязана вернуть вызов инструмента, а не свободный текст.
 * Системный блок помечен cache_control: префикс кешируется, дальше — копейки за запрос.
 */
class AnthropicIntentResolver(
    private val apiKey: String,
    private val model: String,
    private val clock: () -> LocalDate = { LocalDate.now() },
    private val http: HttpClient = HttpClient(OkHttp)
) : IntentResolver {

    override suspend fun resolve(text: String): IntentResult {
        if (apiKey.isBlank()) return IntentResult.Failure("Ключ Anthropic не задан (local.properties)")
        val clean = text.trim()
        if (clean.isEmpty()) return IntentResult.Failure("Пустая фраза")

        val body = buildRequest(clean, clock())

        val response = withContext(Dispatchers.IO) {
            runCatching {
                http.post(ENDPOINT) {
                    header("x-api-key", apiKey)
                    header("anthropic-version", ANTHROPIC_VERSION)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        }.getOrElse { return IntentResult.Failure("Сеть недоступна: ${it.message ?: "ошибка соединения"}") }

        val raw = runCatching { response.bodyAsText() }
            .getOrElse { return IntentResult.Failure("Не удалось прочитать ответ") }

        if (!response.status.isSuccess()) {
            return IntentResult.Failure("API ${response.status.value}: ${brief(raw)}")
        }
        return IntentParser.fromResponse(raw)
    }

    private fun buildRequest(text: String, today: LocalDate): String {
        val obj = buildJsonObject {
            put("model", model)
            put("max_tokens", 512)
            putJsonArray("system") {
                addJsonObject {
                    put("type", "text")
                    put("text", AnthropicPrompt.system(today))
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                }
            }
            put("tools", AnthropicPrompt.toolsElement)
            putJsonObject("tool_choice") { put("type", "any") }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    }
                }
            }
        }
        return JSON.encodeToString(JsonObject.serializer(), obj)
    }

    /** Короткая выжимка тела ошибки для лога/сообщения (без утечки лишнего). */
    private fun brief(raw: String): String = raw.take(160)

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private val JSON = Json { encodeDefaults = true }
    }
}
