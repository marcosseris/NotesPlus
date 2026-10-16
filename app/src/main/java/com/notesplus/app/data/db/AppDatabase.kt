package com.notesplus.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun providerToString(p: Provider): String = p.name
    @TypeConverter fun stringToProvider(s: String): Provider = Provider.valueOf(s)
}

@Database(entities = [NoteEntity::class, CloudItemEntity::class, FolderStateEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun cloud(): CloudDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "notesplus.db")
                // Pre-release schema; the cache rebuilds itself and annotations live on the drive.
                .fallbackToDestructiveMigration()
                .build()
    }
}
