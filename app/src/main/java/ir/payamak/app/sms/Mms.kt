package ir.payamak.app.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.room.withTransaction
import ir.payamak.app.App
import ir.payamak.app.app
import ir.payamak.app.data.Attachment
import ir.payamak.app.data.SmsEntity
import ir.payamak.app.data.SmsType
import ir.payamak.app.data.Status
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** رمزگذاری/رمزگشایی ساده‌ی PDU استاندارد MMS (OMA MMS 1.x). */
object MmsPdu {
    class Part(val contentType: String, val name: String, val data: ByteArray)
    class Parsed(val subject: String?, val from: String?, val parts: List<Part>)
    class Notif(val location: String?, val transactionId: String?)
    class OutPart(val contentType: String, val name: String, val data: ByteArray)

    private val wellKnown = mapOf(
        0x02 to "text/html", 0x03 to "text/plain", 0x06 to "text/x-vcalendar", 0x07 to "text/x-vcard",
        0x1D to "image/gif", 0x1E to "image/jpeg", 0x1F to "image/tiff", 0x20 to "image/png", 0x21 to "image/vnd.wap.wbmp"
    )

    private class V(val kind: Int, val text: String = "", val num: Int = 0, val bytes: ByteArray = ByteArray(0))

    private class R(val b: ByteArray, var p: Int = 0) {
        fun more() = p < b.size
        fun u8(): Int = b[p++].toInt() and 0xFF
        fun peek(): Int = b[p].toInt() and 0xFF
        fun uintvar(): Int {
            var v = 0
            while (true) { val x = u8(); v = (v shl 7) or (x and 0x7F); if ((x and 0x80) == 0) break }
            return v
        }
        fun bytes(n: Int): ByteArray { val r = b.copyOfRange(p, p + n); p += n; return r }
        fun text(): String {
            if (peek() == 0x7F) p++
            val s = p
            while (p < b.size && b[p].toInt() != 0) p++
            val r = String(b, s, p - s, Charsets.UTF_8)
            if (p < b.size) p++
            return r
        }
        fun value(): V {
            val f = peek()
            return when {
                f <= 30 -> { p++; V(2, bytes = bytes(f)) }
                f == 31 -> { p++; val n = uintvar(); V(2, bytes = bytes(n)) }
                f >= 128 -> { p++; V(1, num = f and 0x7F) }
                else -> V(0, text = text())
            }
        }
    }

    private fun mediaType(v: V): String = when (v.kind) {
        0 -> v.text.lowercase()
        1 -> wellKnown[v.num] ?: "application/octet-stream"
        else -> if (v.bytes.isEmpty()) "application/octet-stream" else {
            val f = v.bytes[0].toInt() and 0xFF
            if (f >= 128) wellKnown[f and 0x7F] ?: "application/octet-stream" else R(v.bytes).text().lowercase()
        }
    }

    private fun decodeEncoded(v: V): String? = when (v.kind) {
        0 -> v.text
        2 -> if (v.bytes.size > 1) String(v.bytes, 1, v.bytes.size - 1, Charsets.UTF_8).trimEnd('\u0000') else null
        else -> null
    }

    private fun decodeFrom(v: V): String? =
        if (v.kind == 2 && v.bytes.size > 1 && (v.bytes[0].toInt() and 0xFF) == 0x80)
            String(v.bytes, 1, v.bytes.size - 1, Charsets.UTF_8).trimEnd('\u0000').substringBefore("/")
        else null

    fun parseNotification(b: ByteArray): Notif? = try {
        val r = R(b); var loc: String? = null; var tx: String? = null
        while (r.more()) {
            val h = r.u8()
            if (h == 0x84) break
            val v = r.value()
            if (h == 0x83 && v.kind == 0) loc = v.text
            if (h == 0x98 && v.kind == 0) tx = v.text
        }
        Notif(loc, tx)
    } catch (e: Exception) { null }

    fun parseRetrieve(b: ByteArray): Parsed? = try {
        val r = R(b); var subject: String? = null; var from: String? = null
        while (r.more()) {
            val h = r.u8()
            if (h == 0x84) { r.value(); break }
            val v = r.value()
            if (h == 0x96) subject = decodeEncoded(v)
            if (h == 0x89) from = decodeFrom(v)
        }
        val parts = ArrayList<Part>()
        if (r.more()) {
            val n = r.uintvar()
            repeat(n) {
                val hl = r.uintvar(); val dl = r.uintvar()
                val hr = R(r.bytes(hl)); val data = r.bytes(dl)
                val ct = mediaType(hr.value())
                var name = ""
                while (hr.more()) {
                    val h = hr.u8()
                    if (h == 0x8E) name = hr.text() else if (h == 0xC0) hr.text() else hr.value()
                }
                parts += Part(ct, name, data)
            }
        }
        Parsed(subject, from, parts)
    } catch (e: Exception) { null }

    private fun ByteArrayOutputStream.txt(s: String) { write(s.toByteArray(Charsets.UTF_8)); write(0) }
    private fun ByteArrayOutputStream.uvar(v: Int) {
        var n = v; val t = ArrayList<Int>()
        t += n and 0x7F; n = n shr 7
        while (n > 0) { t += (n and 0x7F) or 0x80; n = n shr 7 }
        for (i in t.indices.reversed()) write(t[i])
    }
    private fun ByteArrayOutputStream.valueLength(n: Int) { if (n <= 30) write(n) else { write(31); uvar(n) } }

    fun compose(to: List<String>, subject: String?, parts: List<OutPart>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x8C); o.write(0x80)               // m-send-req
        o.write(0x98); o.txt("T" + System.currentTimeMillis())
        o.write(0x8D); o.write(0x90)               // MMS 1.0
        o.write(0x89); o.write(0x01); o.write(0x81) // From: insert-address-token
        to.forEach { o.write(0x97); o.txt("$it/TYPE=PLMN") }
        if (!subject.isNullOrBlank()) {
            val sb = subject.toByteArray(Charsets.UTF_8)
            o.write(0x96); o.valueLength(sb.size + 2); o.write(0xEA); o.write(sb); o.write(0)
        }
        o.write(0x84); o.write(0xA3)               // multipart/mixed
        o.uvar(parts.size)
        for (p in parts) {
            val h = ByteArrayOutputStream()
            if (p.contentType.startsWith("text/")) {
                val ct = ByteArrayOutputStream(); ct.txt(p.contentType); ct.write(0x81); ct.write(0xEA)
                h.valueLength(ct.size()); h.write(ct.toByteArray())
            } else h.txt(p.contentType)
            h.write(0x8E); h.txt(p.name)
            h.write(0xC0); h.txt("<${p.name}>")
            o.uvar(h.size()); o.uvar(p.data.size); o.write(h.toByteArray()); o.write(p.data)
        }
        return o.toByteArray()
    }
}

class MmsFileProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val dir = File(context!!.cacheDir, "mms").apply { mkdirs() }
        val name = uri.lastPathSegment ?: return null
        return ParcelFileDescriptor.open(File(dir, name), ParcelFileDescriptor.parseMode(mode))
    }
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        fun uri(ctx: Context, name: String): Uri = Uri.parse("content://${ctx.packageName}.mms/$name")
    }
}

object Mms {
    private val counter = AtomicInteger(1000)
    private const val PHONE = "com.android.phone"

    private fun dir(ctx: Context) = File(ctx.cacheDir, "mms").apply { mkdirs() }
    private fun attDir(ctx: Context) = File(ctx.filesDir, "mms_att").apply { mkdirs() }
    private fun mutable() = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    private fun ext(ct: String) = when {
        ct.contains("jpeg") -> "jpg"; ct.contains("png") -> "png"; ct.contains("gif") -> "gif"; else -> "bin"
    }

    private fun saveAttachment(ctx: Context, ct: String, bytes: ByteArray, name: String): Attachment {
        val f = File(attDir(ctx), UUID.randomUUID().toString() + "." + ext(ct))
        f.writeBytes(bytes)
        return Attachment(smsId = 0, contentType = ct, uri = Uri.fromFile(f).toString(), name = name)
    }

    private fun compress(ctx: Context, uri: Uri): ByteArray? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return null
        var s = 1
        while (maxOf(opts.outWidth, opts.outHeight) / s > 2048) s *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        } ?: return null
        val m = maxOf(bmp.width, bmp.height)
        val scaled = if (m > 1280) {
            val k = 1280f / m
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        } else bmp
        var q = 85
        while (true) {
            val o = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, q, o)
            if (o.size() <= 300 * 1024 || q <= 40) return o.toByteArray()
            q -= 15
        }
    }

    /** ارسال MMS (فقط در نقش «برنامه‌ی پیش‌فرض پیامک» مجاز است). */
    suspend fun send(app: App, address: String, text: String, uris: List<Uri>, subId: Int): Long {
        val to = Fa.toLatin(address.trim())
        val parts = ArrayList<MmsPdu.OutPart>()
        if (text.isNotBlank()) parts += MmsPdu.OutPart("text/plain", "text.txt", text.toByteArray(Charsets.UTF_8))
        uris.forEachIndexed { i, u -> compress(app, u)?.let { parts += MmsPdu.OutPart("image/jpeg", "image$i.jpg", it) } }
        if (parts.isEmpty()) throw IllegalArgumentException("پیام خالی است")

        val f = File(dir(app), "snd_${System.nanoTime()}")
        f.writeBytes(MmsPdu.compose(listOf(to), null, parts))
        val uri = MmsFileProvider.uri(app, f.name)
        app.grantUriPermission(PHONE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val dao = app.db.smsDao()
        var id = 0L
        app.db.withTransaction {
            id = dao.insert(
                SmsEntity(
                    address = to, normalized = Phone.normalize(to), body = text, date = System.currentTimeMillis(),
                    type = SmsType.SENT, status = Status.PENDING, subId = subId, isMms = true
                )
            )
            val atts = parts.filter { it.contentType.startsWith("image/") }.map { saveAttachment(app, it.contentType, it.data, it.name).copy(smsId = id) }
            if (atts.isNotEmpty()) app.db.extraDao().insertAttachments(atts)
        }
        try {
            val pi = PendingIntent.getBroadcast(
                app, counter.incrementAndGet(),
                Intent(app, MmsSentReceiver::class.java).putExtra("id", id).putExtra("file", f.name),
                PendingIntent.FLAG_UPDATE_CURRENT or mutable()
            )
            SmsSender.manager(app, subId).sendMultimediaMessage(app, uri, null, null, pi)
        } catch (e: Exception) {
            dao.markFailed(id); f.delete(); throw e
        }
        return id
    }

    /** اعلان رسیدن MMS: پیام را از اپراتور دانلود می‌کند. */
    fun handleNotification(app: App, data: ByteArray, sub: Int) {
        val n = MmsPdu.parseNotification(data) ?: return
        val loc = n.location ?: return
        val f = File(dir(app), "dl_${System.nanoTime()}").also { it.createNewFile() }
        val uri = MmsFileProvider.uri(app, f.name)
        app.grantUriPermission(PHONE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val pi = PendingIntent.getBroadcast(
            app, counter.incrementAndGet(),
            Intent(app, MmsDownloadedReceiver::class.java).putExtra("file", f.name).putExtra("sub", sub),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable()
        )
        try { SmsSender.manager(app, sub).downloadMultimediaMessage(app, loc, uri, null, pi) } catch (e: Exception) { f.delete() }
    }

    suspend fun onDownloaded(app: App, fileName: String, sub: Int, ok: Boolean) {
        val f = File(dir(app), fileName)
        try {
            if (!ok || f.length() == 0L) return
            val parsed = MmsPdu.parseRetrieve(f.readBytes()) ?: return
            val from = parsed.from ?: "نامشخص"
            val text = parsed.parts.filter { it.contentType.startsWith("text/plain") }.joinToString("\n") { String(it.data, Charsets.UTF_8) }
            val blocked = BlockMatcher(app.db.blockDao().enabled()).isBlocked(from, text)
            val key = Phone.normalize(from)
            var id = 0L
            app.db.withTransaction {
                id = app.db.smsDao().insert(
                    SmsEntity(
                        address = from, normalized = key, body = text, date = System.currentTimeMillis(), type = SmsType.INBOX,
                        read = false, subId = sub, isBlocked = blocked, isMms = true, subject = parsed.subject
                    )
                )
                val atts = parsed.parts.filter { !it.contentType.startsWith("text/") && it.contentType != "application/smil" }
                    .map { saveAttachment(app, it.contentType, it.data, it.name).copy(smsId = id) }
                if (id > 0 && atts.isNotEmpty()) app.db.extraDao().insertAttachments(atts)
            }
            if (!blocked && id > 0) Notifier.notifyIncoming(app, from, key, text.ifBlank { "📷 پیام چندرسانه‌ای" })
        } finally { f.delete() }
    }
}

class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        val file = intent.getStringExtra("file")
        val code = resultCode
        val app = context.app
        val pending = goAsync()
        app.scope.launch {
            try {
                if (id >= 0) {
                    if (code == Activity.RESULT_OK) app.db.smsDao().markSent(id, System.currentTimeMillis()) else app.db.smsDao().markFailed(id)
                }
            } finally {
                file?.let { File(File(context.cacheDir, "mms"), it).delete() }
                pending.finish()
            }
        }
    }
}

class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val file = intent.getStringExtra("file") ?: return
        val sub = intent.getIntExtra("sub", -1)
        val ok = resultCode == Activity.RESULT_OK
        val app = context.app
        val pending = goAsync()
        app.scope.launch { try { Mms.onDownloaded(app, file, sub, ok) } finally { pending.finish() } }
    }
}

class WapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.type != "application/vnd.wap.mms-message") return
        val data = intent.getByteArrayExtra("data") ?: return
        val sub = intent.getIntExtra("subscription", -1)
        Mms.handleNotification(context.app, data, sub)
    }
}
