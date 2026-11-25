package ir.payamak.app.data

import androidx.paging.PagingSource
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<SmsEntity>)

    /** در صورت تکراری بودن providerId مقدار ‎-1 برمی‌گرداند. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(s: SmsEntity): Long

    @Query("SELECT COALESCE(MAX(providerId), 0) FROM sms WHERE providerId > 0")
    suspend fun maxProviderId(): Long

    @Query("SELECT COUNT(*) FROM sms")
    suspend fun total(): Int

    @Query("SELECT id FROM sms WHERE providerId = :pid")
    suspend fun idByProvider(pid: Long): Long?

    @Query("SELECT * FROM sms WHERE id = :id")
    suspend fun get(id: Long): SmsEntity?

    @Query(
        "SELECT m.normalized AS threadKey, m.address AS address, m.body AS lastBody, m.date AS lastDate, " +
        "m.type AS lastType, m.status AS lastStatus, " +
        "(SELECT COUNT(*) FROM sms u WHERE u.normalized = m.normalized AND u.type = 1 AND u.read = 0 AND u.isBlocked = :blocked) AS unread, " +
        "COALESCE(p.pinned, 0) AS pinned " +
        "FROM sms m LEFT JOIN thread_prefs p ON p.threadKey = m.normalized " +
        "WHERE m.id = (SELECT s.id FROM sms s WHERE s.normalized = m.normalized AND s.isBlocked = :blocked " +
        "ORDER BY s.date DESC, s.id DESC LIMIT 1) AND COALESCE(p.archived, 0) = :archived " +
        "ORDER BY pinned DESC, m.date DESC, m.id DESC"
    )
    fun conversations(blocked: Boolean, archived: Boolean): PagingSource<Int, ConversationRow>

    @Query("SELECT * FROM sms WHERE normalized = :key ORDER BY date DESC, id DESC")
    fun thread(key: String): PagingSource<Int, SmsEntity>

    @Query("SELECT address FROM sms WHERE normalized = :key ORDER BY date DESC LIMIT 1")
    suspend fun addressFor(key: String): String?

    @Query(
        "SELECT * FROM sms WHERE isBlocked = 0 AND (body LIKE '%' || :q || '%' ESCAPE '\\' " +
        "OR address LIKE '%' || :q || '%' ESCAPE '\\') ORDER BY date DESC, id DESC"
    )
    fun search(q: String): PagingSource<Int, SmsEntity>

    @Query("SELECT * FROM sms WHERE type = 2 AND (:status = -1 OR status = :status) ORDER BY date DESC, id DESC")
    fun outgoing(status: Int): PagingSource<Int, SmsEntity>

    @Query("SELECT status AS status, COUNT(*) AS cnt FROM sms WHERE type = 2 GROUP BY status")
    fun statusCounts(): Flow<List<StatusCount>>

    @Query("SELECT providerId FROM sms WHERE normalized = :key AND type = 1 AND read = 0 AND providerId > 0")
    suspend fun unreadProviderIds(key: String): List<Long>

    @Query("SELECT providerId FROM sms WHERE type = 1 AND read = 0 AND providerId > 0")
    suspend fun allUnreadProviderIds(): List<Long>

    @Query("UPDATE sms SET read = 1 WHERE normalized = :key AND read = 0")
    suspend fun markRead(key: String)

    @Query("UPDATE sms SET read = 1 WHERE read = 0")
    suspend fun markAllRead()

    @Query("UPDATE sms SET status = 2, sentAt = :t WHERE id = :id AND status = 1")
    suspend fun markSent(id: Long, t: Long)

    @Query("UPDATE sms SET status = 4 WHERE id = :id AND status IN (1, 2)")
    suspend fun markFailed(id: Long)

    @Query("UPDATE sms SET status = :status, deliveredAt = :t WHERE id = :id")
    suspend fun markDelivered(id: Long, status: Int, t: Long)

    @Query("SELECT providerId FROM sms WHERE normalized = :key AND providerId IS NOT NULL")
    suspend fun providerIdsFor(key: String): List<Long>

    @Query("SELECT uri FROM attachments WHERE smsId IN (SELECT id FROM sms WHERE normalized = :key) AND uri LIKE 'file:%'")
    suspend fun ownFilesFor(key: String): List<String>

    @Query("DELETE FROM attachments WHERE smsId IN (SELECT id FROM sms WHERE normalized = :key)")
    suspend fun deleteAttachmentsOfThread(key: String)

    @Query("DELETE FROM sms WHERE normalized = :key")
    suspend fun deleteThread(key: String)

    @Query("DELETE FROM sms WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT id, address, body FROM sms WHERE type = 1 AND id > :after ORDER BY id LIMIT :limit")
    suspend fun incomingChunk(after: Long, limit: Int): List<MsgLite>

    @Query("UPDATE sms SET isBlocked = :b WHERE id IN (:ids)")
    suspend fun setBlocked(ids: List<Long>, b: Boolean)

    @Query("SELECT * FROM sms WHERE isMms = 0 AND type IN (1, 2) AND id > :after ORDER BY id LIMIT :limit")
    suspend fun backupChunk(after: Long, limit: Int): List<SmsEntity>

    @Query("SELECT COUNT(*) FROM sms WHERE normalized = :n AND date = :d AND type = :t AND body = :b")
    suspend fun exists(n: String, d: Long, t: Int, b: String): Int
}

@Dao
interface ExtraDao {
    @Query("SELECT * FROM attachments WHERE smsId = :id")
    suspend fun attachmentsFor(id: Long): List<Attachment>

    @Insert suspend fun insertAttachments(list: List<Attachment>)

    @Query("DELETE FROM attachments WHERE smsId = :id")
    suspend fun deleteAttachments(id: Long)

    @Query("SELECT * FROM thread_prefs WHERE threadKey = :key")
    fun prefFlow(key: String): Flow<ThreadPref?>

    @Query("SELECT * FROM thread_prefs WHERE threadKey = :key")
    suspend fun pref(key: String): ThreadPref?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPref(p: ThreadPref)

    @Query("SELECT * FROM templates ORDER BY id")
    fun templates(): Flow<List<Template>>

    @Insert suspend fun insertTemplate(t: Template)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun deleteTemplate(id: Long)

    @Query("SELECT COUNT(*) FROM templates")
    suspend fun templateCount(): Int
}

@Dao
interface BlockDao {
    @Query("SELECT * FROM block_rules ORDER BY kind, pattern")
    fun all(): Flow<List<BlockRule>>

    @Query("SELECT * FROM block_rules WHERE enabled = 1")
    suspend fun enabled(): List<BlockRule>

    @Query("SELECT COUNT(*) FROM block_rules")
    suspend fun count(): Int

    @Insert suspend fun insert(r: BlockRule): Long
    @Insert suspend fun insertAll(r: List<BlockRule>)
    @Update suspend fun update(r: BlockRule)
    @Delete suspend fun delete(r: BlockRule)

    @Query("DELETE FROM block_rules WHERE isDefault = 1")
    suspend fun deleteDefaults()
}

@Dao
interface ScheduledDao {
    @Query("SELECT * FROM scheduled ORDER BY status, triggerAt")
    fun all(): Flow<List<ScheduledSms>>

    @Query("SELECT * FROM scheduled WHERE status = 0")
    suspend fun pending(): List<ScheduledSms>

    @Query("SELECT * FROM scheduled WHERE id = :id")
    suspend fun get(id: Long): ScheduledSms?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: ScheduledSms): Long

    @Query("UPDATE scheduled SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: Int)

    @Query("DELETE FROM scheduled WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM scheduled WHERE status != 0")
    suspend fun clearFinished()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sms ADD COLUMN isMms INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sms ADD COLUMN subject TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS `attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `smsId` INTEGER NOT NULL, `contentType` TEXT NOT NULL, `uri` TEXT NOT NULL, `name` TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_smsId` ON `attachments` (`smsId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `thread_prefs` (`threadKey` TEXT NOT NULL, `pinned` INTEGER NOT NULL, `archived` INTEGER NOT NULL, PRIMARY KEY(`threadKey`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `templates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL)")
    }
}

@Database(
    entities = [SmsEntity::class, Attachment::class, ThreadPref::class, Template::class, BlockRule::class, ScheduledSms::class],
    version = 2, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun smsDao(): SmsDao
    abstract fun extraDao(): ExtraDao
    abstract fun blockDao(): BlockDao
    abstract fun scheduledDao(): ScheduledDao
}
