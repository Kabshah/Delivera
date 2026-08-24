package com.kabshah.delivra.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ScheduledMessage::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduledMessageDao(): ScheduledMessageDao
}
