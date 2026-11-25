package ir.payamak.app.sms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import ir.payamak.app.App
import ir.payamak.app.data.SmsEntity
import ir.payamak.app.data.SmsType
import ir.payamak.app.data.Status
import ir.payamak.app.data.ThreadPref
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** نوشتن/خواندن در Provider سیستم؛ فقط وقتی برنامه «پیش‌فرض پیامک» باشد مجاز است. */
object Sys {
    fun isDefault(ctx: Context): Boolean =
        try { Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName } catch (e: Exception) { false }

    fun insert(ctx: Context, address: String, body: String, date: Long, dateSent: Long, read: Boolean, type: Int, sub: Int): Long =
        try {
            val cv = ContentValues().apply {
                put("address", address); put("body", body); put("date", date); put("date_sent", dateSent)
                put("read", if (read) 1 else 0); put("seen", if (read) 1 else 0); put("type", type)
                if (sub >= 0) put("sub_id", sub)
            }
            ContentUris.parseId(ctx.contentResolver.insert(Telephony.Sms.CONTENT_URI, cv)!!)
        } catch (e: Exception) { -1L }

    fun updateSms(ctx: Context, id: Long, type: Int?, status: Int?) {
        try {
            val cv = ContentValues()
            type?.let { cv.put("type", it) }
            status?.let { cv.put("status", it) }
            if (cv.size() > 0) ctx.contentResolver.update(Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()), cv, null, null)
        } catch (_: Exception) {}
    }

    fun markRead(ctx: Context, ids: List<Long>) {
        try {
            ids.chunked(500).forEach {
                val cv = ContentValues().apply { put("read", 1); put("seen", 1) }
                ctx.contentResolver.update(Telephony.Sms.CONTENT_URI, cv, "_id IN (${it.joinToString(",")})", null)
            }
        } catch (_: Exception) {}
    }

    fun deleteSms(ctx: Context, ids: List<Long>) {
        try { ids.chunked(500).forEach { ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "_id IN (${it.joinToString(",")})", null) } } catch (_: Exception) {}
    }

    fun deleteMms(ctx: Context, ids: List<Long>) {
        try { ids.chunked(500).forEach { ctx.contentResolver.delete(Telephony.Mms.CONTENT_URI, "_id IN (${it.joinToString(",")})", null) } } catch (_: Exception) {}
    }
}

object Repo {
    suspend fun markRead(app: App, key: String) {
        val dao = app.db.smsDao()
        val ids = dao.unreadProviderIds(key)
        dao.markRead(key)
        if (ids.isNotEmpty() && Sys.isDefault(app)) Sys.markRead(app, ids)
    }

    suspend fun markAllRead(app: App) {
        val dao = app.db.smsDao()
        val ids = dao.allUnreadProviderIds()
        dao.markAllRead()
        if (ids.isNotEmpty() && Sys.isDefault(app)) Sys.markRead(app, ids)
    }

    private fun removeFiles(uris: List<String>) {
        uris.forEach { try { File(Uri.parse(it).path.orEmpty()).delete() } catch (_: Exception) {} }
    }

    private fun removeFromProvider(app: App, pids: List<Long>) {
        if (pids.isEmpty() || !Sys.isDefault(app)) return
        Sys.deleteSms(app, pids.filter { it > 0 })
        Sys.deleteMms(app, pids.filter { it < 0 }.map { -it })
    }

    suspend fun deleteMessage(app: App, m: SmsEntity) {
        val files = app.db.extraDao().attachmentsFor(m.id).map { it.uri }.filter { it.startsWith("file:") }
        m.providerId?.let { removeFromProvider(app, listOf(it)) }
        app.db.extraDao().deleteAttachments(m.id)
        app.db.smsDao().deleteById(m.id)
        removeFiles(files)
    }

    suspend fun deleteThread(app: App, key: String) {
        val dao = app.db.smsDao()
        val pids = dao.providerIdsFor(key)
        val files = dao.ownFilesFor(key)
        removeFromProvider(app, pids)
        dao.deleteAttachmentsOfThread(key)
        dao.deleteThread(key)
        removeFiles(files)
    }

    suspend fun setPinned(app: App, key: String, v: Boolean) {
        val p = app.db.extraDao().pref(key) ?: ThreadPref(key)
        app.db.extraDao().setPref(p.copy(pinned = v))
    }

    suspend fun setArchived(app: App, key: String, v: Boolean) {
        val p = app.db.extraDao().pref(key) ?: ThreadPref(key)
        app.db.extraDao().setPref(p.copy(archived = v))
    }
}

/** پشتیبان‌گیری و بازیابی پیامک‌ها (JSON، بدون MMS). */
object Backup {
    suspend fun export(app: App, uri: Uri): Int {
        var count = 0
        val out = app.contentResolver.openOutputStream(uri) ?: return 0
        JsonWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
            w.setIndent(" ")
            w.beginObject(); w.name("version").value(1L); w.name("messages"); w.beginArray()
            var after = 0L
            while (true) {
                val chunk = app.db.smsDao().backupChunk(after, 500)
                if (chunk.isEmpty()) break
                chunk.forEach {
                    w.beginObject()
                    w.name("address").value(it.address); w.name("body").value(it.body); w.name("date").value(it.date)
                    w.name("type").value(it.type.toLong()); w.name("read").value(it.read); w.name("sub").value(it.subId.toLong())
                    w.endObject(); count++
                }
                after = chunk.last().id
            }
            w.endArray(); w.endObject()
        }
        return count
    }

    suspend fun restore(app: App, uri: Uri): Int {
        val dao = app.db.smsDao()
        val matcher = BlockMatcher(app.db.blockDao().enabled())
        val isDef = Sys.isDefault(app)
        var added = 0
        val input = app.contentResolver.openInputStream(uri) ?: return 0
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "messages") { r.skipValue(); continue }
                r.beginArray()
                while (r.hasNext()) {
                    var a = ""; var b = ""; var d = 0L; var t = 1; var rd = true; var sub = -1
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "address" -> a = r.nextString(); "body" -> b = r.nextString(); "date" -> d = r.nextLong()
                        "type" -> t = r.nextInt(); "read" -> rd = r.nextBoolean(); "sub" -> sub = r.nextInt()
                        else -> r.skipValue()
                    }
                    r.endObject()
                    val n = Phone.normalize(a)
                    if (dao.exists(n, d, t, b) > 0) continue
                    val pid = if (isDef) Sys.insert(app, a, b, d, d, rd, if (t == SmsType.SENT) 2 else 1, sub) else -1L
                    val id = dao.insert(
                        SmsEntity(
                            providerId = pid.takeIf { it > 0 }, address = a, normalized = n, body = b, date = d, type = t,
                            read = rd, status = if (t == SmsType.SENT) Status.SENT else Status.NONE, subId = sub,
                            isBlocked = t == SmsType.INBOX && matcher.isBlocked(a, b)
                        )
                    )
                    if (id > 0) added++
                }
                r.endArray()
            }
            r.endObject()
        }
        return added
    }
}
