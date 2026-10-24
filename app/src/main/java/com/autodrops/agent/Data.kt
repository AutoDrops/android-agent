package com.autodrops.agent

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity
data class Conversation(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val model: String, val updatedAt: Long)

@Entity(indices = [Index("convId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val convId: Long,
    val role: String,            // system|user|assistant|tool|error|info
    val content: String,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val ts: Long = System.currentTimeMillis()
)

@Entity
data class Recipe(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val prompt: String)

@Dao
interface ChatDao {
    @Query("SELECT * FROM Recipe ORDER BY id DESC") fun recipes(): Flow<List<Recipe>>
    @Insert suspend fun insert(r: Recipe): Long
    @Query("DELETE FROM Recipe WHERE id = :id") suspend fun deleteRecipe(id: Long)
    @Query("SELECT * FROM Conversation ORDER BY updatedAt DESC") fun conversations(): Flow<List<Conversation>>
    @Query("SELECT * FROM MessageEntity WHERE convId = :id ORDER BY id") suspend fun messages(id: Long): List<MessageEntity>
    @Insert suspend fun insert(c: Conversation): Long
    @Insert suspend fun insert(m: MessageEntity): Long
    @Query("UPDATE Conversation SET updatedAt = :t, title = :title WHERE id = :id") suspend fun touch(id: Long, title: String, t: Long = System.currentTimeMillis())
    @Query("DELETE FROM MessageEntity WHERE convId = :id") suspend fun deleteMessages(id: Long)
    @Query("DELETE FROM Conversation WHERE id = :id") suspend fun deleteConv(id: Long)
    @Query("DELETE FROM MessageEntity") suspend fun wipeMessages()
    @Query("DELETE FROM Conversation") suspend fun wipeConvs()
}

@Database(entities = [Conversation::class, MessageEntity::class, Recipe::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ChatDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context) = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "agent.db").fallbackToDestructiveMigration().build().also { inst = it }
        }
    }
}
