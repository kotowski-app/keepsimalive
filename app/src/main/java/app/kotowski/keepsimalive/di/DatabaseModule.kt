package app.kotowski.keepsimalive.di

import android.content.Context
import androidx.room.Room
import app.kotowski.keepsimalive.data.KeepaliveDatabase
import app.kotowski.keepsimalive.data.KeepaliveDatabaseCallback
import app.kotowski.keepsimalive.data.SimConfigDao
import app.kotowski.keepsimalive.data.SimHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideKeepaliveDatabase(
        @ApplicationContext context: Context,
    ): KeepaliveDatabase =
        Room
            .databaseBuilder(context, KeepaliveDatabase::class.java, KeepaliveDatabase.NAME)
            .addCallback(KeepaliveDatabaseCallback)
            .build()

    @Provides
    fun provideSimConfigDao(database: KeepaliveDatabase): SimConfigDao = database.simConfigDao()

    @Provides
    fun provideSimHistoryDao(database: KeepaliveDatabase): SimHistoryDao = database.simHistoryDao()
}
