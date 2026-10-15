package com.notesplus.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun kindToString(k: NoteKind): String = k.name
    @TypeConverter fun stringToKind(s: String): NoteKind = NoteKind.valueOf(s)
}

@Database(entities = [NoteEntity::class, PendingDeleteEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "notesplus.db")
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
