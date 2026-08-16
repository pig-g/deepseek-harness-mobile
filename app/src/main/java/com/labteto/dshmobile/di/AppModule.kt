package com.labteto.dshmobile.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.labteto.dshmobile.core.wire.WireJson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "dsh_mobile")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.appDataStore

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // The events.mux downlink is read-only and carries no traffic while idle, so this ping is
        // the app's only way to notice a silently dropped socket. A dead link otherwise leaves the
        // app CONNECTED-but-dead: Send still reaches the harness over HTTP but nothing streams back
        // until the socket finally fails — the "nothing reacts, then suddenly all normal" symptom.
        // A tight interval bounds that dead stretch (20s here felt unresponsive on a remote link).
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideWireJson(): Json = WireJson

    @Provides
    fun provideIoDispatcher(): kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
}
