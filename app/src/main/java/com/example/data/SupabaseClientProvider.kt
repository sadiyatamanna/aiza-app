package com.example.data

import com.example.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Thread-safe singleton provider for the Supabase client instance.
 * Configured with Auth, PostgREST, Realtime, and Storage plugins using the Ktor OkHttp engine
 * (which provides WebSocketCapability required by Supabase Realtime).
 */
object SupabaseClientProvider {

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
        ) {
            defaultLogLevel = LogLevel.NONE
            httpEngine = OkHttp.create()

            install(Auth) {
                scheme = "aegis"
                host = "auth-callback"
            }
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }
}
