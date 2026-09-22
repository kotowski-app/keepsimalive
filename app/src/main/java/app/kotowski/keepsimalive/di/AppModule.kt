package app.kotowski.keepsimalive.di

import android.content.Context
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.PermissionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideAppPrefs(
        @ApplicationContext context: Context,
    ): AppPrefs = AppPrefs(context)

    @Provides
    @Singleton
    fun providePermissionManager(
        @ApplicationContext context: Context,
    ): PermissionManager = PermissionManager(context)

    // Display hold for the SENDING state: tests inject a shorter value.
    @Provides
    @Singleton
    @Named("sending_hold_millis")
    fun provideSendingHoldMillis(): Long = AppConfig.DEFAULT_SENDING_HOLD_MS
}
