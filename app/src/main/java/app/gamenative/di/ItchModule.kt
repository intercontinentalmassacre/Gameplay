package app.gamenative.di

import app.gamenative.itch.ItchApiClient
import app.gamenative.itch.ItchConfig
import app.gamenative.utils.Net
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the itch.io API client; everything else is constructor-injected. */
@Module
@InstallIn(SingletonComponent::class)
object ItchModule {
    @Provides
    @Singleton
    fun provideItchApiClient(): ItchApiClient = ItchApiClient(
        http = Net.http,
        apiBaseUrl = ItchConfig.apiBaseUrl,
        authorizeBaseUrl = ItchConfig.authorizeBaseUrl,
    )
}
