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

@Entity
data class RagDoc(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val embedding: String,            // comma-separated L2-normalized floats
    val ts: Long = System.currentTimeMillis()
)

@Entity
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val trigger: String,              // wifi_ssid | charging | interval
    val param: String = "",           // e.g. SSID to watch
    val intervalMinutes: Int = 60,
    val action: String = "notify",    // notify | run_prompt
    val allowedPrompt: String = "",   // pre-granted prompt for run_prompt (still risk-gated in-app)
    val enabled: Boolean = true,
    val ts: Long = System.currentTimeMillis()
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("id", id).put("name", name).put("trigger", trigger).put("param", param)
        .put("intervalMinutes", intervalMinutes).put("action", action)
        .put("allowedPrompt", allowedPrompt).put("enabled", enabled)
    companion object {
        fun fromJson(o: org.json.JSONObject) = Rule(
            id = o.optLong("id"), name = o.optString("name"), trigger = o.optString("trigger"),
            param = o.optString("param"), intervalMinutes = o.optInt("intervalMinutes", 60),
            action = o.optString("action", "notify"), allowedPrompt = o.optString("allowedPrompt"),
            enabled = o.optBoolean("enabled", true))
    }
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM Recipe ORDER BY id DESC") fun recipes(): Flow<List<Recipe>>
    @Insert suspend fun insert(r: Recipe): Long
    @Query("DELETE FROM Recipe WHERE id = :id") suspend fun deleteRecipe(id: Long)
    @Query("SELECT * FROM RagDoc ORDER BY id DESC") fun ragDocsFlow(): Flow<List<RagDoc>>
    @Query("SELECT * FROM RagDoc") suspend fun ragDocs(): List<RagDoc>
    @Insert suspend fun insert(d: RagDoc): Long
    @Query("DELETE FROM RagDoc WHERE id = :id") suspend fun deleteRag(id: Long)
    @Query("SELECT * FROM Rule ORDER BY id DESC") fun rulesFlow(): Flow<List<Rule>>
    @Query("SELECT * FROM Rule WHERE enabled = 1") suspend fun enabledRules(): List<Rule>
    @Insert suspend fun insert(r: Rule): Long
    @Query("DELETE FROM Rule WHERE id = :id") suspend fun deleteRule(id: Long)
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

@Database(entities = [Conversation::class, MessageEntity::class, Recipe::class, RagDoc::class, Rule::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ChatDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context) = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "agent.db").fallbackToDestructiveMigration().build().also { inst = it }
        }
    }
}
