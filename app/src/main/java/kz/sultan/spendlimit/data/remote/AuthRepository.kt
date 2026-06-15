package kz.sultan.spendlimit.data.remote

import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Тонкая обёртка над Supabase Auth (gotrue). Сессию SDK хранит и восстанавливает сам.
 *
 * Если Supabase не настроен (нет ключей) — [isConfigured] == false, [userEmail] всегда null,
 * приложение остаётся рабочим оффлайн.
 */
class AuthRepository {

    val isConfigured: Boolean get() = SupabaseModule.isConfigured

    /** Email вошедшего пользователя, реактивно (null = не вошёл / не настроено). */
    val userEmail: Flow<String?> =
        if (!isConfigured) flowOf(null)
        else SupabaseModule.client.auth.sessionStatus.map { status ->
            (status as? SessionStatus.Authenticated)?.session?.user?.email
        }

    suspend fun signIn(email: String, password: String) {
        SupabaseModule.client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signUp(email: String, password: String) {
        SupabaseModule.client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signOut() {
        SupabaseModule.client.auth.signOut()
    }
}
