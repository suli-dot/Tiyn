# Tiyn (Тиын) — трекер финансов с контролем дневного лимита трат

[![Android CI](https://github.com/suli-dot/Tiyn/actions/workflows/android-ci.yml/badge.svg)](https://github.com/suli-dot/Tiyn/actions/workflows/android-ci.yml)

> Название — от тиына, наименьшей денежной единицы Казахстана: все суммы в приложении хранятся в тиынах (целочисленно, без float). Экранное имя приложения — «Лимит», внутреннее имя пакета пока `kz.sultan.spendlimit` (ребрендится отдельно при желании).

Нативное Android-приложение (Kotlin). Перехватывает push-уведомления Kaspi
(`kz.kaspi.mobile`) через `NotificationListenerService`, парсит суммы транзакций
и считает дневной лимит трат. При превышении — локальное уведомление.

## Стек

- Kotlin, minSdk 26, Jetpack Compose (Material 3)
- Room (локальный источник правды), WorkManager (фоновая синхронизация)
- Supabase (PostgreSQL + Auth + RLS) — облачный бэк, опционально
- MVVM + репозиторий-паттерн (источник данных взаимозаменяем)
- Деньги — `Long` в **тиынах** (1 ₸ = 100 тиын), без float

## Быстрый старт

1. Открой проект в Android Studio (Giraffe+), дай Gradle синхронизироваться.
   Из консоли: `./gradlew assembleDebug` (на Windows — `gradlew.bat assembleDebug`).
2. Скопируй `local.properties.example` → `local.properties`, впиши `sdk.dir`.
   Ключи Supabase можно оставить пустыми — приложение работает оффлайн.
3. Запусти на устройстве/эмуляторе (API 26+).
4. На первом экране выдай **доступ к уведомлениям** и отключи **оптимизацию батареи**.
5. Заполни остаток, обязательные платежи и дату следующего поступления.

## Структура

```
app/src/main/java/kz/sultan/spendlimit/
├─ data/
│  ├─ local/         Room: entity, dao, AppDatabase
│  ├─ prefs/         DataStore: настройки лимита
│  ├─ remote/        Supabase-клиент и источник выгрузки
│  └─ repository/    FinanceRepository (интерфейс + Room-реализация)
├─ domain/
│  ├─ model/         Transaction, TransactionType, ParsedTransaction
│  └─ SpendingLimitCalculator   расчёт лимита (чистая логика, покрыта тестами)
├─ service/notification/        NotificationListenerService + парсер Kaspi
├─ work/             SyncWorker + SyncScheduler (WorkManager)
├─ ui/               MainActivity (Compose) + MainViewModel
└─ util/             Money, Time, LimitAlertNotifier, SystemPermissions
```

## Расчёт лимита

```
дневной_лимит = (остаток − обязательные_платежи) / дней_до_поступления
остаток_на_сегодня = дневной_лимит − потрачено_сегодня
```

Перенос недотрат — естественный: расчёт ведётся от текущего остатка, поэтому
нес_потраченные деньги переходят на следующие дни автоматически
(см. `SpendingLimitCalculator`).

## Supabase

Схема таблиц с RLS — в `docs/supabase_schema.sql`. Ключи (URL + anon) пробрасываются
из `local.properties` в `BuildConfig`, в коде не хардкодятся.

## Статус (этап 1, скелет)

Готово: структура, Room-схема, парсер Kaspi (+тесты), сервис перехвата,
калькулятор лимита (+тесты), онбординг разрешений, каркас синхронизации, базовый UI.

Доделать в этапе 1:
- [ ] Реальная вставка в Supabase в `SupabaseRemoteSyncSource` (нужны @Serializable DTO + kotlinx-serialization, авторизация для RLS).
- [ ] Обновление остатка при каждой исходящей транзакции (сейчас остаток задаётся вручную).
- [ ] Экран входа/регистрации Supabase Auth.
- [ ] Сверка реальных форматов пушей Kaspi и расширение `KaspiNotificationParserTest`.

Заложено на будущее (этап 2): виджет (Glance), streak, автокатегоризация,
прогноз «дожития», детектор подписок, конверты, проверка «безопасно ли потратить N».

## Лицензия

[MIT](LICENSE) © 2026 Sultan Tasbulatov
