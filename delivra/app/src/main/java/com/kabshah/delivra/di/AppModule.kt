package com.kabshah.delivra.di

import android.content.Context
import androidx.room.Room
import com.kabshah.delivra.data.AppDatabase
import com.kabshah.delivra.data.ScheduledMessageDao
import com.kabshah.delivra.diagnostics.EventRingBuffer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "delivra_db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideDao(db: AppDatabase): ScheduledMessageDao = db.scheduledMessageDao()

    @Provides
    @Singleton
    fun provideEventRingBuffer(): EventRingBuffer = EventRingBuffer(capacity = 50)
}
