package ir.payamak.app.sms

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import androidx.room.withTransaction
import ir.payamak.app.App
import ir.payamak.app.data.Attachment
import ir.payamak.app.data.SmsEntity
import ir.payamak.app.data.SmsType
import ir.payamak.app.data.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

data class SyncState(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

/**
 * پیامک‌ها و MMSهای سیستم را «تکه‌تکه» (هر بار ۵۰۰ تا) به پایگاه‌داده‌ی داخلی منتقل می‌کند
 * تا لیست‌ها با Paging 3 بارگذاری شوند و برنامه هنگ نکند.
 */
class SmsSync(private val app: App) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()
    private val sp = app.getSharedPreferences("sync", Context.MODE_PRIVATE)

    private class Batch(val items: List<SmsEntity>, val lastId: Long, val raw: Int)

    suspend fun sync() = mutex.withLock {
        if (!app.has(Manifest.permission.READ_SMS)) return@withLock
        syncSms()
        try { syncMms() } catch (_: Exception) {}
    }

    private suspend fun syncSms() {
        val dao = app.db.smsDao()
        val matcher = BlockMatcher(app.db.blockDao().enabled())
        var last = sp.getLong("sms_cursor", -1L)
        if (last < 0) last = dao.maxProviderId()
        val total = try {
            app.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), "_id > ?", arrayOf(last.toString()), null)?.use { it.count } ?: 0
        } catch (e: Exception) { 0 }
        if (total == 0) { sp.edit().putLong("sms_cursor", last).apply(); return }
        _state.value = SyncState(true, 0, total)
        var done = 0
        try {
            while (true) {
                val b = readBatch(last, 500, matcher)
                if (b.raw == 0) break
                if (b.items.isNotEmpty()) dao.insertAll(b.items)
                last = b.lastId
                sp.edit().putLong("sms_cursor", last).apply()
                done += b.raw
                _state.value = SyncState(true, done.coerceAtMost(total), total)
                yield()
            }
        } finally { _state.value = SyncState() }
    }

    private fun readBatch(after: Long, limit: Int, matcher: BlockMatcher): Batch {
        val full = arrayOf("_id", "address", "body", "date", "type", "read", "status", "sub_id")
        val basic = arrayOf("_id", "address", "body", "date", "type", "read", "status")
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "_id > ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(after.toString()))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "_id ASC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val cursor = try {
            app.contentResolver.query(Telephony.Sms.CONTENT_URI, full, args, null)
        } catch (e: Exception) {
            app.contentResolver.query(Telephony.Sms.CONTENT_URI, basic, args, null)
        } ?: return Batch(emptyList(), after, 0)

        val out = ArrayList<SmsEntity>(limit)
        var lastId = after
        var raw = 0
        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow("_id"); val iAddr = c.getColumnIndexOrThrow("address")
            val iBody = c.getColumnIndexOrThrow("body"); val iDate = c.getColumnIndexOrThrow("date")
            val iType = c.getColumnIndexOrThrow("type"); val iRead = c.getColumnIndexOrThrow("read")
            val iSt = c.getColumnIndexOrThrow("status"); val iSub = c.getColumnIndex("sub_id")
            while (c.moveToNext()) {
                raw++
                val id = c.getLong(iId); lastId = id
                val t = c.getInt(iType)
                val addr = c.getString(iAddr).orEmpty()
                val body = c.getString(iBody).orEmpty()
                val sub = if (iSub >= 0) c.getInt(iSub) else -1
                when (t) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> out += SmsEntity(
                        providerId = id, address = addr, normalized = Phone.normalize(addr), body = body,
                        date = c.getLong(iDate), type = SmsType.INBOX, read = c.getInt(iRead) == 1,
                        status = Status.NONE, subId = sub, isBlocked = matcher.isBlocked(addr, body)
                    )
                    Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.MESSAGE_TYPE_OUTBOX,
                    Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.MESSAGE_TYPE_QUEUED -> {
                        val st = when {
                            t == Telephony.Sms.MESSAGE_TYPE_FAILED -> Status.FAILED
                            t == Telephony.Sms.MESSAGE_TYPE_SENT && c.getInt(iSt) == 0 -> Status.DELIVERED
                            t == Telephony.Sms.MESSAGE_TYPE_SENT -> Status.SENT
                            else -> Status.PENDING
                        }
                        out += SmsEntity(
                            providerId = id, address = addr, normalized = Phone.normalize(addr), body = body,
                            date = c.getLong(iDate), type = SmsType.SENT, read = true, status = st, subId = sub
                        )
                    }
                }
            }
        }
        return Batch(out, lastId, raw)
    }

    // ---------- MMS های موجود در سیستم (فقط خواندن) ----------
    private suspend fun syncMms() {
        val dao = app.db.smsDao()
        val extra = app.db.extraDao()
        val matcher = BlockMatcher(app.db.blockDao().enabled())
        var last = sp.getLong("mms_cursor", 0L)
        while (true) {
            val rows = ArrayList<LongArray>() // id, date, box, read, sub
            val subjects = HashMap<Long, String?>()
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "_id > ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(last.toString()))
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "_id ASC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, 100)
            }
            app.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id", "date", "msg_box", "read", "sub"), args, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    rows += longArrayOf(id, c.getLong(1), c.getLong(2), c.getLong(3))
                    subjects[id] = c.getString(4)
                }
            }
            if (rows.isEmpty()) break
            for (r in rows) {
                val id = r[0]; last = id
                val box = r[2].toInt()
                if (box != 1 && box != 2 && box != 4) continue
                val addr = mmsAddress(id, if (box == 1) 137 else 151) ?: continue
                val (text, atts) = mmsParts(id)
                val inbox = box == 1
                val e = SmsEntity(
                    providerId = -id, address = addr, normalized = Phone.normalize(addr), body = text, date = r[1] * 1000,
                    type = if (inbox) SmsType.INBOX else SmsType.SENT, read = inbox.not() || r[3] == 1L,
                    status = if (inbox) Status.NONE else Status.SENT, isBlocked = inbox && matcher.isBlocked(addr, text),
                    isMms = true, subject = subjects[id]?.takeIf { it.isNotBlank() }
                )
                app.db.withTransaction {
                    val rid = dao.insert(e)
                    if (rid > 0 && atts.isNotEmpty()) extra.insertAttachments(atts.map { it.copy(smsId = rid) })
                }
            }
            sp.edit().putLong("mms_cursor", last).apply()
            yield()
        }
    }

    private fun mmsAddress(id: Long, type: Int): String? =
        app.contentResolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), "type=?", arrayOf(type.toString()), null)?.use { c ->
            var res: String? = null
            while (c.moveToNext()) {
                val a = c.getString(0)
                if (!a.isNullOrBlank() && a != "insert-address-token") { res = a; break }
            }
            res
        }

    private fun mmsParts(id: Long): Pair<String, List<Attachment>> {
        val texts = ArrayList<String>(); val atts = ArrayList<Attachment>()
        app.contentResolver.query(Uri.parse("content://mms/part"), arrayOf("_id", "ct", "name", "text", "_data"), "mid=?", arrayOf(id.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val pid = c.getLong(0); val ct = c.getString(1).orEmpty()
                when {
                    ct == "application/smil" -> {}
                    ct.startsWith("text/plain") -> {
                        var t = c.getString(3)
                        if (t == null && !c.getString(4).isNullOrEmpty()) {
                            t = try { app.contentResolver.openInputStream(Uri.parse("content://mms/part/$pid"))?.use { String(it.readBytes()) } } catch (e: Exception) { null }
                        }
                        if (!t.isNullOrBlank()) texts += t
                    }
                    else -> atts += Attachment(smsId = 0, contentType = ct, uri = "content://mms/part/$pid", name = c.getString(2).orEmpty())
                }
            }
        }
        return texts.joinToString("\n") to atts
    }

    /** پس از تغییر لیست مسدودی، برچسب مسدود بودن همه‌ی پیام‌های دریافتی دوباره محاسبه می‌شود. */
    suspend fun reapplyBlocking() {
        val dao = app.db.smsDao()
        val matcher = BlockMatcher(app.db.blockDao().enabled())
        var after = 0L
        while (true) {
            val chunk = dao.incomingChunk(after, 500)
            if (chunk.isEmpty()) break
            val (blk, ok) = chunk.partition { matcher.isBlocked(it.address, it.body) }
            if (blk.isNotEmpty()) dao.setBlocked(blk.map { it.id }, true)
            if (ok.isNotEmpty()) dao.setBlocked(ok.map { it.id }, false)
            after = chunk.last().id
            yield()
        }
    }
}
