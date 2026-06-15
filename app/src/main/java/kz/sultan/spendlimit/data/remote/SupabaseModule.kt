package kz.sultan.spendlimit.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import kz.sultan.spendlimit.BuildConfig

/**
 * Ленивая инициализация Supabase-клиента.
 *
 * Ключи берутся из BuildConfig (проброшены из local.properties), не из кода.
 * Если ключи не заданы — [isConfigured] == false, и слой синхронизации просто
 * не активничает (приложение остаётся полностью рабочим оффлайн).
 *
 * ПРИМЕЧАНИЕ: API supabase-kt зависит от версии. При первом Gradle sync
 * сверь имена плагинов (Auth/Postgrest) с подключённой версией ${BuildConfig.SUPABASE_URL}.
 */
object SupabaseModule {

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val client: SupabaseClient by lazy {
        check(isConfigured) { "Supabase не настроен: заполни SUPABASE_URL/ANON_KEY в local.properties" }
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
        }
    }
}
