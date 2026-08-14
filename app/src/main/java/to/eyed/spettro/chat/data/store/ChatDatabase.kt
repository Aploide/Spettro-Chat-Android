package to.eyed.spettro.chat.data.store

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val preview: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val archived: Boolean,
    /** Active skill for this chat (bundled or user skill id); null = none. */
    val skillId: String? = null,
)

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** The /slash trigger; unique among user skills, [a-z0-9-]. */
    val slug: String,
    val description: String,
    /** Markdown appended to the system prompt while the skill is active. */
    val instructions: String,
    val emoji: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    /** Position within the conversation; @Relation gives no ordering. */
    val ord: Int,
    val role: String,
    val content: String,
    val thinking: String,
    val at: Long,
    /** Serialized List<StoredToolRun>; small (outputs are capped upstream). */
    val toolsJson: String,
    /** Serialized List<StoredFile>; extracted text is capped upstream. */
    val filesJson: String = "",
)

// Image data URLs can run to ~1 MB each; one per row keeps every row well
// under SQLite's ~2 MB CursorWindow limit, which an inline list would break.
@Entity(
    tableName = "message_images",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class MessageImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val ord: Int,
    val dataUrl: String,
)

data class MessageWithImages(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val images: List<MessageImageEntity>,
)

data class ConversationWithMessages(
    @Embedded val conversation: ConversationEntity,
    @Relation(entity = MessageEntity::class, parentColumn = "id", entityColumn = "conversationId")
    val messages: List<MessageWithImages>,
)

@Dao
interface ConversationDao {
    @Transaction
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun loadAll(): List<ConversationWithMessages>

    /** Null when the conversation does not exist locally. */
    @Query("SELECT updatedAt FROM conversations WHERE id = :id")
    suspend fun updatedAt(id: String): Long?

    // Upsert rather than REPLACE: a REPLACE deletes the old row first, which
    // would cascade-delete the messages we are about to rewrite anyway, but
    // makes the transaction's intent depend on FK trigger subtleties.
    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: String)

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert
    suspend fun insertImages(images: List<MessageImageEntity>)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    /** Atomically replaces a conversation's row and its full message list. */
    @Transaction
    suspend fun replace(
        conversation: ConversationEntity,
        messages: List<MessageEntity>,
        imagesPerMessage: List<List<String>>,
    ) {
        upsertConversation(conversation)
        deleteMessages(conversation.id)
        messages.forEachIndexed { i, message ->
            val messageId = insertMessage(message)
            val images = imagesPerMessage[i].mapIndexed { ord, dataUrl ->
                MessageImageEntity(messageId = messageId, ord = ord, dataUrl = dataUrl)
            }
            if (images.isNotEmpty()) insertImages(images)
        }
    }
}

@Entity(tableName = "memories")
data class MemoryEntity(
    /** Stable hash of the normalized text, like the CLI's m-xxxxxx ids. */
    @PrimaryKey val id: String,
    val text: String,
    val addedAt: Long,
    /** Bumped when the same fact is saved again; drives injection order. */
    val usedAt: Long,
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY usedAt DESC, addedAt DESC")
    fun all(): kotlinx.coroutines.flow.Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY usedAt DESC, addedAt DESC")
    suspend fun allOnce(): List<MemoryEntity>

    @Upsert
    suspend fun upsert(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}

/**
 * One embedded chunk of searchable history: either a memory fact or a slice
 * of a past message. Vectors are float32, little-endian. [ownerKey] encodes
 * what the chunk came from and is content-addressed, so re-indexing is a set
 * difference rather than a rebuild.
 */
@Entity(
    tableName = "embeddings",
    indices = [Index("ownerKey", unique = true), Index("conversationId")],
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "memory" or "chat". */
    val kind: String,
    val ownerKey: String,
    val conversationId: String?,
    /** The chunk text itself — what a search hit returns as the snippet. */
    val text: String,
    /** Message timestamp or memory added-date, for dating search hits. */
    val at: Long,
    /** Which embedder produced the vector (e.g. "hash-1", "use-1"). */
    val model: String,
    val vector: ByteArray,
)

@Dao
interface EmbeddingDao {
    @Query("SELECT ownerKey FROM embeddings")
    suspend fun ownerKeys(): List<String>

    @Query("SELECT * FROM embeddings WHERE model = :model")
    suspend fun byModel(model: String): List<EmbeddingEntity>

    @Query("SELECT COUNT(*) FROM embeddings")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM embeddings WHERE model != :model")
    suspend fun countNotFromModel(model: String): Int

    @Insert
    suspend fun insert(embeddings: List<EmbeddingEntity>)

    @Query("DELETE FROM embeddings WHERE ownerKey IN (:ownerKeys)")
    suspend fun deleteByOwnerKeys(ownerKeys: List<String>)

    @Query("DELETE FROM embeddings WHERE model != :model")
    suspend fun deleteNotFromModel(model: String)

    @Query("DELETE FROM embeddings")
    suspend fun deleteAll()
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY name COLLATE NOCASE")
    fun all(): kotlinx.coroutines.flow.Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills ORDER BY name COLLATE NOCASE")
    suspend fun allOnce(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun byId(id: String): SkillEntity?

    @Query("SELECT * FROM skills WHERE slug = :slug")
    suspend fun bySlug(slug: String): SkillEntity?

    @Upsert
    suspend fun upsert(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [
        ConversationEntity::class, MessageEntity::class, MessageImageEntity::class,
        SkillEntity::class, MemoryEntity::class, EmbeddingEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun skills(): SkillDao
    abstract fun memories(): MemoryDao
    abstract fun embeddings(): EmbeddingDao

    companion object {
        // Chats exist only on-device, so migrations must be explicit — a
        // destructive fallback would silently erase the user's history.
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN skillId TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS skills (" +
                        "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, slug TEXT NOT NULL, " +
                        "description TEXT NOT NULL, instructions TEXT NOT NULL, emoji TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
                )
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memories (" +
                        "id TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, " +
                        "addedAt INTEGER NOT NULL, usedAt INTEGER NOT NULL)",
                )
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN filesJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS embeddings (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, " +
                        "ownerKey TEXT NOT NULL, conversationId TEXT, text TEXT NOT NULL, " +
                        "at INTEGER NOT NULL, model TEXT NOT NULL, vector BLOB NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_embeddings_ownerKey ON embeddings (ownerKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_conversationId ON embeddings (conversationId)")
            }
        }

        fun build(context: Context): ChatDatabase =
            Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, "conversations.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
