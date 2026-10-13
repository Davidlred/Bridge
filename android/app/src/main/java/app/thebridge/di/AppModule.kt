package app.thebridge.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.session.KeystoreTokenVault
import app.thebridge.data.session.TokenVault
import app.thebridge.data.sync.RemoteSyncApi
import app.thebridge.data.sync.SyncApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.sessionStore by preferencesDataStore("session")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): BridgeDb = Room.databaseBuilder(ctx, BridgeDb::class.java, "bridge.db").build()

    @Provides @Singleton
    fun http(): OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    @Provides @Singleton
    fun store(@ApplicationContext ctx: Context): DataStore<Preferences> = ctx.sessionStore
}

@Module
@InstallIn(SingletonComponent::class)
interface Bindings {
    @Binds fun vault(impl: KeystoreTokenVault): TokenVault
    @Binds fun syncApi(impl: RemoteSyncApi): SyncApi
}
