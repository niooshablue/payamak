package ir.payamak.app.sms

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.Telephony
import ir.payamak.app.App
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
 * پیامک‌های سیستم را «تکه‌تکه» (هر بار ۵۰۰ تا) به پایگاه‌داده‌ی داخلی منتقل می‌کند
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
                if (b.items.isNotEmpty()) { dao.insertAll(b.items); ConvIndex.refresh(app, b.items.map { it.normalized }) }
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

    /** پس از تغییر لیست مسدودی، برچسب مسدود بودن همه‌ی پیام‌های دریافتی دوباره محاسبه می‌شود. */
    suspend fun reapplyBlocking(): Int {
        val dao = app.db.smsDao()
        val matcher = BlockMatcher(app.db.blockDao().enabled())
        var after = 0L
        var newlyBlocked = 0
        val changedKeys = HashSet<String>()
        while (true) {
            val chunk = dao.incomingChunk(after, 500)
            if (chunk.isEmpty()) break
            val toBlock = ArrayList<Long>(); val toUnblock = ArrayList<Long>()
            for (m in chunk) {
                val hit = matcher.isBlocked(m.address, m.body)
                if (hit && !m.isBlocked) { toBlock += m.id; changedKeys += m.normalized }
                else if (!hit && m.isBlocked) { toUnblock += m.id; changedKeys += m.normalized }
            }
            if (toBlock.isNotEmpty()) dao.setBlocked(toBlock, true)
            if (toUnblock.isNotEmpty()) dao.setBlocked(toUnblock, false)
            newlyBlocked += toBlock.size
            after = chunk.last().id
            yield()
        }
        ConvIndex.refresh(app, changedKeys)
        return newlyBlocked
    }
}
