package ir.payamak.app.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import ir.payamak.app.App
import ir.payamak.app.R
import ir.payamak.app.app
import ir.payamak.app.ui.MainActivity
import kotlinx.coroutines.launch

object Otp {
    private val words = listOf("کد", "رمز", "تایید", "تأیید", "code", "otp", "password", "pin", "verification", "ورود")
    private val re = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")

    /** کد یکبارمصرف داخل متن پیامک (در صورت وجود). */
    fun find(body: String): String? {
        val t = Fa.toLatin(body)
        if (words.none { t.contains(it, ignoreCase = true) }) return null
        return re.find(t)?.value
    }
}

object Notifier {
    const val CHANNEL = "messages"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "پیام‌های دریافتی", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun mutableFlag() = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    suspend fun notifyIncoming(app: App, address: String, key: String, body: String) {
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return
        val name = ContactNames.lookup(app, address) ?: address
        val id = key.hashCode()

        val open = PendingIntent.getActivity(
            app, id, Intent(app, MainActivity::class.java).putExtra("key", key).putExtra("address", address)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(a: String, req: Int, flags: Int) = PendingIntent.getBroadcast(
            app, id * 4 + req,
            Intent(app, NotificationActionReceiver::class.java).setAction(a).putExtra("key", key).putExtra("address", address).putExtra("body", body),
            PendingIntent.FLAG_UPDATE_CURRENT or flags
        )

        val b = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(open)

        if (Phone.isReplyable(address)) {
            val ri = RemoteInput.Builder("reply").setLabel("پاسخ").build()
            b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_notification, "پاسخ", action("reply", 0, mutableFlag())).addRemoteInput(ri).build())
        }
        b.addAction(R.drawable.ic_notification, "خوانده شد", action("read", 1, PendingIntent.FLAG_IMMUTABLE))
        Otp.find(body)?.let { code -> b.addAction(R.drawable.ic_notification, "کپی کد $code", action("copy", 2, PendingIntent.FLAG_IMMUTABLE)) }

        try { nm.notify(id, b.build()) } catch (_: SecurityException) {}
    }

    fun cancel(ctx: Context, key: String) { NotificationManagerCompat.from(ctx).cancel(key.hashCode()) }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        val key = intent.getStringExtra("key").orEmpty()
        val address = intent.getStringExtra("address").orEmpty()
        when (intent.action) {
            "copy" -> {
                val code = Otp.find(intent.getStringExtra("body").orEmpty()) ?: return
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("code", code))
                Toast.makeText(context, "کد کپی شد", Toast.LENGTH_SHORT).show()
            }
            "read" -> {
                Notifier.cancel(context, key)
                val p = goAsync(); app.scope.launch { try { Repo.markRead(app, key) } finally { p.finish() } }
            }
            "reply" -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")?.toString()?.trim().orEmpty()
                Notifier.cancel(context, key)
                if (text.isEmpty()) return
                val p = goAsync()
                app.scope.launch {
                    try {
                        SmsSender.send(app, address, text, SimRepo.defaultSubId())
                        Repo.markRead(app, key)
                    } catch (_: Exception) {} finally { p.finish() }
                }
            }
        }
    }
}

/** «پاسخ با پیام» پس از رد تماس. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent
        val num = i?.data?.schemeSpecificPart?.substringBefore('?')?.substringBefore(',')
        val text = i?.getStringExtra(Intent.EXTRA_TEXT)
        if (!num.isNullOrBlank() && !text.isNullOrBlank()) {
            val app = application as App
            app.scope.launch { try { SmsSender.send(app, num, text, SimRepo.defaultSubId()) } catch (_: Exception) {} }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
