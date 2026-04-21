package dev.seekerzero.app.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.seekerzero.app.ssh.KnownHostDao
import dev.seekerzero.app.ssh.KnownHostEntity

@Database(
    entities = [ChatMessageEntity::class, KnownHostEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun knownHostDao(): KnownHostDao

    companion object {
        private const val NAME = "chat.db"

        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun get(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
