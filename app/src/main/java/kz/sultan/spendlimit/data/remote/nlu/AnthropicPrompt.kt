package kz.sultan.spendlimit.data.remote.nlu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate

/**
 * Статическая часть запроса к модели: определения инструментов (`tools`) и системный
 * промпт (`system`). Промпт описывает ТОЛЬКО распознавание; вся проверка и исполнение —
 * в [kz.sultan.spendlimit.domain.voice.VoiceCommandHandler]. {{TODAY}} подставляется
 * перед запросом, чтобы модель считала относительные даты («вчера») верно.
 */
object AnthropicPrompt {

    /** Восемь «форм» намерений. Модель (tool_choice=any) обязана выбрать ровно одну и заполнить слоты. */
    private val TOOLS_JSON = """
[
  {
    "name": "add_expense",
    "description": "Пользователь сообщает о потраченных деньгах (трата, покупка, оплата).",
    "input_schema": {
      "type": "object",
      "properties": {
        "amount": { "type": "number", "description": "Сумма в тенге (KZT). Разворачивай разговорное: 'штука'/'косарь'=1000, 'стольник'=100, 'пятихатка'=500. Если обозначение двусмысленно — НЕ угадывай, используй clarify." },
        "category": { "type": "string", "description": "Короткое русское слово-кандидат категории (еда, такси, кафе, продукты, связь...). Можно null, если не названа явно — финальный маппинг не твоя забота." },
        "note": { "type": "string", "description": "Свободное уточнение, если есть ('подарок маме'). Иначе null." },
        "date": { "type": "string", "description": "Дата траты в формате YYYY-MM-DD. Считай от TODAY. 'вчера'→TODAY-1, 'позавчера'→TODAY-2. Если время не названо — null (значит сегодня)." }
      },
      "required": ["amount"]
    }
  },
  {
    "name": "add_income",
    "description": "Пользователь сообщает о поступлении денег (зарплата, перевод, возврат).",
    "input_schema": {
      "type": "object",
      "properties": {
        "amount": { "type": "number" },
        "note": { "type": "string", "description": "Источник, если назван ('зарплата'). Иначе null." },
        "date": { "type": "string", "description": "YYYY-MM-DD от TODAY, либо null = сегодня." }
      },
      "required": ["amount"]
    }
  },
  {
    "name": "set_limit",
    "description": "Пользователь задаёт лимит расходов на период.",
    "input_schema": {
      "type": "object",
      "properties": {
        "amount": { "type": "number" },
        "period": { "type": "string", "enum": ["day", "week", "month"] },
        "category": { "type": "string", "description": "Категория-кандидат или null = общий лимит." }
      },
      "required": ["amount", "period"]
    }
  },
  {
    "name": "query_spent",
    "description": "Пользователь спрашивает, сколько потрачено.",
    "input_schema": {
      "type": "object",
      "properties": {
        "period": { "type": "string", "enum": ["today", "yesterday", "week", "month", "all"] },
        "category": { "type": "string", "description": "Категория-кандидат или null = все категории." }
      },
      "required": ["period"]
    }
  },
  {
    "name": "query_balance",
    "description": "Пользователь спрашивает остаток / сколько осталось до лимита.",
    "input_schema": {
      "type": "object",
      "properties": {
        "period": { "type": "string", "enum": ["day", "week", "month"], "description": "Относительно какого лимита, если уточнён. Иначе null." }
      }
    }
  },
  {
    "name": "can_i_spend",
    "description": "Пользователь спрашивает, может ли он позволить себе трату ('можно ли потратить N', 'потяну ли покупку на N', 'не уйду ли в минус, если потрачу N'). Это ВОПРОС-прогноз, НЕ факт траты — отличай от add_expense (там трата уже совершена).",
    "input_schema": {
      "type": "object",
      "properties": {
        "amount": { "type": "number", "description": "Предполагаемая сумма траты в тенге (KZT)." }
      },
      "required": ["amount"]
    }
  },
  {
    "name": "correct_last",
    "description": "Пользователь правит или отменяет ПОСЛЕДНЮЮ операцию ('нет, это была еда', 'отмени', 'сумма не та, 3000').",
    "input_schema": {
      "type": "object",
      "properties": {
        "new_amount": { "type": "number", "description": "Новая сумма или null, если не меняется." },
        "new_category": { "type": "string", "description": "Новая категория-кандидат или null." },
        "delete": { "type": "boolean", "description": "true, если операцию надо отменить целиком." }
      }
    }
  },
  {
    "name": "clarify",
    "description": "Используй, КОГДА не хватает обязательного слота или фраза двусмысленна. Не угадывай.",
    "input_schema": {
      "type": "object",
      "properties": {
        "missing": { "type": "string", "description": "Чего конкретно не хватает ('сумма', 'категория')." },
        "question": { "type": "string", "description": "Короткий вопрос пользователю на русском." }
      },
      "required": ["missing", "question"]
    }
  }
]
""".trimIndent()

    private const val SYSTEM_TEMPLATE = """Ты — парсер намерений для финансового приложения. Твоя единственная задача:
превратить одну фразу пользователя в ровно ОДИН вызов инструмента.

Ты НЕ исполняешь операции, НЕ считаешь баланс, лимиты или итоги, НЕ хранишь
состояние между запросами. Ты только распознаёшь смысл и заполняешь слоты.
Решение, что делать с результатом, принимает приложение — не ты.

Правила:
- Всегда вызывай ровно один инструмент. Никакого текста вне вызова.
- Никогда не выдумывай суммы и категории. Нет обязательного слота или
  двусмысленно — вызывай clarify.
- Суммы — в тенге, числом, в человеческих единицах (не в копейках/тийынах).
- Язык пользователя — русский, возможны казахские слова, сленг, падежи,
  разговорный счёт. Разбирай их, но при двусмысленности — clarify.
- Относительные даты считай от сегодняшней: TODAY = {{TODAY}}.
- Категорию возвращай как короткое слово-кандидат; точный маппинг сделает приложение.

Примеры:
"закинул штуку за обед"        → add_expense(amount=1000, category="еда")
"потратил 3000"                → clarify(missing="категория", question="На что 3000?")
"сколько ушло на такси за неделю" → query_spent(period="week", category="такси")
"зарплата пришла, 400 тысяч"   → add_income(amount=400000, note="зарплата")
"лимит на еду 60 тысяч в месяц" → set_limit(amount=60000, period="month", category="еда")
"нет, это была не такси а кафе" → correct_last(new_category="кафе")
"отмени последнее"             → correct_last(delete=true)
"можно ли потратить 15 тысяч"  → can_i_spend(amount=15000)"""

    private val json = Json { ignoreUnknownKeys = true }

    /** Разобранный массив инструментов — один раз. */
    val toolsElement: JsonElement by lazy { json.parseToJsonElement(TOOLS_JSON) }

    /** Системный промпт с подставленной сегодняшней датой. */
    fun system(today: LocalDate): String = SYSTEM_TEMPLATE.replace("{{TODAY}}", today.toString())
}
