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
        "SELECT c.threadKey AS threadKey, c.address AS address, c.lastBody AS lastBody, c.lastDate AS lastDate, " +
        "c.lastType AS lastType, c.lastStatus AS lastStatus, c.unread AS unread, COALESCE(p.pinned, 0) AS pinned " +
        "FROM conv c LEFT JOIN thread_prefs p ON p.threadKey = c.threadKey " +
        "WHERE c.blocked = :blocked AND COALESCE(p.archived, 0) = :archived ORDER BY pinned DESC, c.lastDate DESC"
    )
    fun conversations(blocked: Boolean, archived: Boolean): PagingSource<Int, ConversationRow>

    @Query("SELECT * FROM sms WHERE normalized = :key AND body LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY date DESC, id DESC")
    fun searchThread(key: String, q: String): PagingSource<Int, SmsEntity>

    // --- نگه‌داری جدول خلاصه‌ی گفتگوها ---
    @Query("SELECT * FROM sms WHERE normalized = :k AND isBlocked = :b ORDER BY date DESC, id DESC LIMIT 1")
    suspend fun lastOf(k: String, b: Boolean): SmsEntity?

    @Query("SELECT COUNT(*) FROM sms WHERE normalized = :k AND isBlocked = :b AND type = 1 AND read = 0")
    suspend fun unreadCount(k: String, b: Boolean): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConv(c: Conv)

    @Query("DELETE FROM conv WHERE threadKey = :k AND blocked = :b")
    suspend fun deleteConv(k: String, b: Boolean)

    @Query("DELETE FROM conv WHERE threadKey = :k")
    suspend fun deleteConvAll(k: String)

    @Query("SELECT COUNT(*) FROM conv")
    suspend fun convCount(): Int

    @Query("SELECT DISTINCT normalized FROM sms")
    suspend fun allKeys(): List<String>

    @Query("DELETE FROM conv WHERE threadKey NOT IN (SELECT normalized FROM sms)")
    suspend fun deleteOrphanConv()

    @Query("UPDATE conv SET unread = 0 WHERE threadKey = :k")
    suspend fun clearUnread(k: String)

    @Query("UPDATE conv SET unread = 0")
    suspend fun clearUnreadAll()

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

    @Query("DELETE FROM sms WHERE normalized = :key")
    suspend fun deleteThread(key: String)

    @Query("DELETE FROM sms WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT id, address, body, normalized, isBlocked FROM sms WHERE type = 1 AND id > :after ORDER BY id LIMIT :limit")
    suspend fun incomingChunk(after: Long, limit: Int): List<MsgLite>

    @Query("UPDATE sms SET isBlocked = :b WHERE id IN (:ids)")
    suspend fun setBlocked(ids: List<Long>, b: Boolean)

    @Query("SELECT * FROM sms WHERE type IN (1, 2) AND id > :after ORDER BY id LIMIT :limit")
    suspend fun backupChunk(after: Long, limit: Int): List<SmsEntity>

    @Query("SELECT COUNT(*) FROM sms WHERE normalized = :n AND date = :d AND type = :t AND body = :b")
    suspend fun exists(n: String, d: Long, t: Int, b: String): Int
}

@Dao
interface ExtraDao {
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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM sms WHERE isMms = 1")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sms_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `providerId` INTEGER, `address` TEXT NOT NULL, `normalized` TEXT NOT NULL, `body` TEXT NOT NULL, `date` INTEGER NOT NULL, `type` INTEGER NOT NULL, `read` INTEGER NOT NULL, `status` INTEGER NOT NULL, `subId` INTEGER NOT NULL, `isBlocked` INTEGER NOT NULL, `sentAt` INTEGER, `deliveredAt` INTEGER, `parts` INTEGER NOT NULL)")
        db.execSQL("INSERT INTO sms_new (id, providerId, address, normalized, body, date, type, read, status, subId, isBlocked, sentAt, deliveredAt, parts) SELECT id, providerId, address, normalized, body, date, type, read, status, subId, isBlocked, sentAt, deliveredAt, parts FROM sms")
        db.execSQL("DROP TABLE sms")
        db.execSQL("ALTER TABLE sms_new RENAME TO sms")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_normalized_date` ON `sms` (`normalized`, `date`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sms_providerId` ON `sms` (`providerId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_type_status` ON `sms` (`type`, `status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_normalized_isBlocked_date` ON `sms` (`normalized`, `isBlocked`, `date`)")
        db.execSQL("DROP TABLE IF EXISTS `attachments`")
        db.execSQL("CREATE TABLE IF NOT EXISTS `conv` (`threadKey` TEXT NOT NULL, `blocked` INTEGER NOT NULL, `address` TEXT NOT NULL, `lastBody` TEXT NOT NULL, `lastDate` INTEGER NOT NULL, `lastType` INTEGER NOT NULL, `lastStatus` INTEGER NOT NULL, `unread` INTEGER NOT NULL, PRIMARY KEY(`threadKey`, `blocked`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_conv_blocked_lastDate` ON `conv` (`blocked`, `lastDate`)")
    }
}

@Database(
    entities = [SmsEntity::class, Conv::class, ThreadPref::class, Template::class, BlockRule::class, ScheduledSms::class],
    version = 3, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun smsDao(): SmsDao
    abstract fun extraDao(): ExtraDao
    abstract fun blockDao(): BlockDao
    abstract fun scheduledDao(): ScheduledDao
}
