package ir.payamak.app.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

fun Context.has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

@Suppress("UNCHECKED_CAST")
inline fun <reified T : ViewModel> vmFactory(crossinline create: () -> T) = object : ViewModelProvider.Factory {
    override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
}

object Fa {
    private val fa = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    fun digits(s: String): String = buildString { for (c in s) append(if (c in '0'..'9') fa[c - '0'] else c) }
    fun toLatin(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                else -> c
            }
        )
    }
}

object Phone {
    fun normalize(a: String): String {
        val t = Fa.toLatin(a.trim())
        if (t.any { it.isLetter() }) return t.lowercase()
        var d = t.filter { it.isDigit() }
        if (d.startsWith("0098")) d = "0" + d.drop(4)
        else if (d.startsWith("98") && d.length == 12) d = "0" + d.drop(2)
        else if (d.startsWith("9") && d.length == 10) d = "0$d"
        return d
    }

    fun isReplyable(a: String): Boolean {
        val t = a.trim()
        return t.isNotEmpty() && t.none { it.isLetter() } && t.count { it.isDigit() } >= 3
    }
}

object Jalali {
    private val months = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")

    fun toJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gdm[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) { jm = 1 + days / 31; jd = 1 + days % 31 } else { jm = 7 + (days - 186) / 30; jd = 1 + (days - 186) % 30 }
        return Triple(jy, jm, jd)
    }

    private fun cal(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }
    private fun j(c: Calendar) = toJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    fun time(ts: Long): String { val c = cal(ts); return Fa.digits("%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))) }
    fun date(ts: Long): String { val (y, m, d) = j(cal(ts)); return Fa.digits("%04d/%02d/%02d".format(y, m, d)) }
    fun dateLong(ts: Long): String { val (y, m, d) = j(cal(ts)); return Fa.digits("$d ${months[m - 1]} $y") }
    fun dateTime(ts: Long) = "${date(ts)}  ${time(ts)}"
    fun dayKey(ts: Long): Int { val c = cal(ts); return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR) }

    /** نمایش هوشمند برای لیست گفتگوها */
    fun smart(ts: Long): String {
        val now = System.currentTimeMillis()
        val dk = dayKey(ts); val today = dayKey(now); val yest = dayKey(now - 86_400_000L)
        return when {
            dk == today -> time(ts)
            dk == yest -> "دیروز"
            now - ts < 330L * 86_400_000L -> { val (_, m, d) = j(cal(ts)); Fa.digits("$d ${months[m - 1]}") }
            else -> date(ts)
        }
    }

    fun dayHeader(ts: Long): String {
        val now = System.currentTimeMillis()
        val dk = dayKey(ts)
        return when {
            dk == dayKey(now) -> "امروز"
            dk == dayKey(now - 86_400_000L) -> "دیروز"
            else -> dateLong(ts)
        }
    }
}

/** همه‌ی مخاطبین یک‌بار در حافظه بارگذاری می‌شوند تا نمایش نام‌ها آنی باشد. */
object ContactNames {
    private var map: Map<String, String> = emptyMap()
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    private fun key(a: String) = Fa.toLatin(a).filter { it.isDigit() }.takeLast(10)

    fun cached(a: String): String? {
        version.intValue // برای به‌روزشدن خودکار رابط
        if (map.isEmpty() || !Phone.isReplyable(a)) return null
        return map[key(a)]
    }

    suspend fun load(ctx: Context) {
        if (!ctx.has(Manifest.permission.READ_CONTACTS)) return
        val m = withContext(Dispatchers.IO) {
            val r = HashMap<String, String>()
            try {
                ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val n = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        r.putIfAbsent(key(n), name)
                    }
                }
            } catch (_: Exception) {}
            r
        }
        map = m
        version.intValue++
    }

    suspend fun lookup(ctx: Context, a: String): String? {
        if (map.isEmpty()) load(ctx)
        return cached(a)
    }
}

data class SimInfo(val subId: Int, val slot: Int, val label: String)

object SimRepo {
    @SuppressLint("MissingPermission")
    fun load(ctx: Context): List<SimInfo> {
        if (!ctx.has(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        return try {
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            sm.activeSubscriptionInfoList.orEmpty()
                .map { SimInfo(it.subscriptionId, it.simSlotIndex + 1, it.displayName?.toString().orEmpty()) }
                .sortedBy { it.slot }
        } catch (e: Exception) { emptyList() }
    }

    fun defaultSubId(): Int = try { SubscriptionManager.getDefaultSmsSubscriptionId() } catch (e: Exception) { -1 }
}
