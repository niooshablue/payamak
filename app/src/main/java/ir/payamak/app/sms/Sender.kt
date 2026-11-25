package ir.payamak.app.sms

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import ir.payamak.app.App
import ir.payamak.app.app
import ir.payamak.app.data.ScheduledSms
import ir.payamak.app.data.SmsEntity
import ir.payamak.app.data.SmsType
import ir.payamak.app.data.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SmsSender {
    @Suppress("DEPRECATION")
    fun manager(ctx: Context, subId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            val m = ctx.getSystemService(SmsManager::class.java)
            if (subId >= 0) m.createForSubscriptionId(subId) else m
        } else {
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }

    private fun pi(ctx: Context, id: Long, kind: String): PendingIntent {
        val intent = Intent(ctx, SmsStatusReceiver::class.java).putExtra("id", id).putExtra("kind", kind)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (kind == "delivered" && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(ctx, id.toInt() * 2 + (if (kind == "sent") 0 else 1), intent, flags)
    }

    /** پیام را ذخیره (در Provider سیستم هم، اگر پیش‌فرض باشیم) و ارسال می‌کند. */
    suspend fun send(app: App, address: String, body: String, subId: Int): Long {
        val to = Fa.toLatin(address.trim())
        val dao = app.db.smsDao()
        val mgr = manager(app, subId)
        val parts = mgr.divideMessage(body)
        val now = System.currentTimeMillis()
        val pid = if (Sys.isDefault(app)) Sys.insert(app, to, body, now, 0L, true, 4, subId) else -1L
        var id = dao.insert(
            SmsEntity(
                providerId = pid.takeIf { it > 0 }, address = to, normalized = Phone.normalize(to), body = body, date = now,
                type = SmsType.SENT, status = Status.PENDING, subId = subId, parts = parts.size
            )
        )
        if (id < 0 && pid > 0) id = dao.idByProvider(pid) ?: -1L
        if (id < 0) throw IllegalStateException("ذخیره‌ی پیام ناموفق بود")
        try {
            val sent = ArrayList<PendingIntent>(); val del = ArrayList<PendingIntent>()
            repeat(parts.size) { sent += pi(app, id, "sent"); del += pi(app, id, "delivered") }
            if (parts.size == 1) mgr.sendTextMessage(to, null, body, sent[0], del[0])
            else mgr.sendMultipartTextMessage(to, null, parts, sent, del)
        } catch (e: Exception) {
            dao.markFailed(id)
            if (pid > 0) Sys.updateSms(app, pid, 5, null)
            throw e
        }
        return id
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val kind = intent.getStringExtra("kind")
        val code = resultCode
        val pdu = intent.getByteArrayExtra("pdu")
        val fmt = intent.getStringExtra("format")
        val app = context.app
        val pending = goAsync()
        app.scope.launch {
            try {
                val dao = app.db.smsDao()
                val now = System.currentTimeMillis()
                val pid = dao.get(id)?.providerId?.takeIf { it > 0 && Sys.isDefault(app) }
                if (kind == "sent") {
                    if (code == Activity.RESULT_OK) { dao.markSent(id, now); pid?.let { Sys.updateSms(app, it, 2, null) } }
                    else { dao.markFailed(id); pid?.let { Sys.updateSms(app, it, 5, null) } }
                } else {
                    val st = try { if (pdu != null) SmsMessage.createFromPdu(pdu, fmt).status else 0 } catch (e: Exception) { 0 }
                    when {
                        st < 32 -> { dao.markDelivered(id, Status.DELIVERED, now); pid?.let { Sys.updateSms(app, it, null, 0) } }
                        st >= 64 -> { dao.markDelivered(id, Status.DELIVERY_FAILED, now); pid?.let { Sys.updateSms(app, it, null, 64) } }
                    }
                }
            } finally { pending.finish() }
        }
    }
}

/** پیامک دریافتی وقتی «پیش‌فرض» نیستیم: فقط همگام‌سازی. */
class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        if (Sys.isDefault(app)) return
        val pending = goAsync()
        app.scope.launch { try { delay(1500); app.sync.sync() } finally { pending.finish() } }
    }
}

object Incoming {
    suspend fun handle(app: App, address: String, body: String, sentAt: Long, sub: Int) {
        val now = System.currentTimeMillis()
        val pid = Sys.insert(app, address, body, now, sentAt, false, 1, sub)
        val blocked = BlockMatcher(app.db.blockDao().enabled()).isBlocked(address, body)
        val key = Phone.normalize(address)
        val id = app.db.smsDao().insert(
            SmsEntity(
                providerId = pid.takeIf { it > 0 }, address = address, normalized = key, body = body, date = now,
                type = SmsType.INBOX, read = false, status = Status.NONE, subId = sub, isBlocked = blocked
            )
        )
        if (!blocked && id > 0) Notifier.notifyIncoming(app, address, key, body)
    }
}

/** پیامک دریافتی در نقش پیش‌فرض. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (msgs.isNullOrEmpty()) return
        val address = msgs[0].displayOriginatingAddress.orEmpty()
        val body = msgs.joinToString("") { it.displayMessageBody.orEmpty() }
        val sub = intent.getIntExtra("subscription", -1)
        val sentAt = msgs[0].timestampMillis
        val app = context.app
        val pending = goAsync()
        app.scope.launch { try { Incoming.handle(app, address, body, sentAt, sub) } finally { pending.finish() } }
    }
}

object Scheduler {
    private fun pi(ctx: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.toInt(), Intent(ctx, ScheduledSmsReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(ctx: Context, s: ScheduledSms) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, s.id)
        val t = maxOf(s.triggerAt, System.currentTimeMillis() + 1000)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
        }
    }

    fun cancel(ctx: Context, id: Long) { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id)) }

    suspend fun restore(app: App) { app.db.scheduledDao().pending().forEach { schedule(app, it) } }
}

class ScheduledSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val app = context.app
        val pending = goAsync()
        app.scope.launch {
            try {
                val dao = app.db.scheduledDao()
                val s = dao.get(id) ?: return@launch
                if (s.status != 0) return@launch
                val ok = try { SmsSender.send(app, s.address, s.body, s.subId); true } catch (e: Exception) { false }
                dao.setStatus(id, if (ok) 1 else 2)
            } finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        val pending = goAsync()
        app.scope.launch { try { Scheduler.restore(app) } finally { pending.finish() } }
    }
}
