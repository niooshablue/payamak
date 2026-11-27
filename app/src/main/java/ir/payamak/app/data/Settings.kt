package ir.payamak.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val font: String = "vazirmatn", val scale: Float = 1f, val theme: Int = 0,
    val design: Int = 0, val showArchiveTab: Boolean = true, val showBlockedTab: Boolean = true,
    val showScheduleNav: Boolean = true, val showReportsNav: Boolean = true,
    val delayedSend: Boolean = false, val primaryColor: Long = 0L, val backgroundColor: Long = 0L,
    val cardColor: Long = 0L, val textColor: Long = 0L
)

class SettingsStore(private val ctx: Context) {
    private val FONT = stringPreferencesKey("font")
    private val SCALE = floatPreferencesKey("scale")
    private val THEME = intPreferencesKey("theme")
    private val DESIGN = intPreferencesKey("design")
    private val ARCHIVE = booleanPreferencesKey("show_archive")
    private val BLOCKED = booleanPreferencesKey("show_blocked")
    private val SCHEDULE = booleanPreferencesKey("show_schedule")
    private val REPORTS = booleanPreferencesKey("show_reports")
    private val DELAYED = booleanPreferencesKey("delayed_send")
    private val PRIMARY = longPreferencesKey("custom_primary")
    private val BACKGROUND = longPreferencesKey("custom_background")
    private val CARD = longPreferencesKey("custom_card")
    private val TEXT = longPreferencesKey("custom_text")

    val flow: Flow<AppSettings> = ctx.dataStore.data.map {
        AppSettings(it[FONT] ?: "vazirmatn", it[SCALE] ?: 1f, it[THEME] ?: 0,
            it[DESIGN] ?: 0, it[ARCHIVE] ?: true, it[BLOCKED] ?: true,
            it[SCHEDULE] ?: true, it[REPORTS] ?: true, it[DELAYED] ?: false,
            it[PRIMARY] ?: 0L, it[BACKGROUND] ?: 0L, it[CARD] ?: 0L, it[TEXT] ?: 0L)
    }

    suspend fun setFont(v: String) { ctx.dataStore.edit { it[FONT] = v } }
    suspend fun setScale(v: Float) { ctx.dataStore.edit { it[SCALE] = v } }
    suspend fun setTheme(v: Int) { ctx.dataStore.edit { it[THEME] = v } }
    suspend fun setDesign(v: Int) { ctx.dataStore.edit { it[DESIGN] = v } }
    suspend fun setArchiveTab(v: Boolean) { ctx.dataStore.edit { it[ARCHIVE] = v } }
    suspend fun setBlockedTab(v: Boolean) { ctx.dataStore.edit { it[BLOCKED] = v } }
    suspend fun setScheduleNav(v: Boolean) { ctx.dataStore.edit { it[SCHEDULE] = v } }
    suspend fun setReportsNav(v: Boolean) { ctx.dataStore.edit { it[REPORTS] = v } }
    suspend fun setDelayedSend(v: Boolean) { ctx.dataStore.edit { it[DELAYED] = v } }
    suspend fun setColors(primary: Long, background: Long, card: Long, text: Long) { ctx.dataStore.edit { it[PRIMARY] = primary; it[BACKGROUND] = background; it[CARD] = card; it[TEXT] = text } }
}

/**
 * فهرست نمونه‌ی خطوط تبلیغاتی رایج ایران. این فهرست فقط نقطه‌ی شروع است؛
 * از داخل برنامه (تب «مسدودی») می‌توانید هر مورد را ویرایش، غیرفعال یا حذف کنید و موارد جدید اضافه کنید.
 */
object DefaultRules {
    val all = listOf(
        BlockRule(pattern = "3000", kind = RuleKind.PREFIX, note = "خطوط تبلیغاتی ۳۰۰۰xxxx (ممکن است شامل برخی پیامک‌های خدماتی هم باشد)", isDefault = true),
        BlockRule(pattern = "2000", kind = RuleKind.PREFIX, note = "خطوط تبلیغاتی ۲۰۰۰xxxx", isDefault = true),
        BlockRule(pattern = "5000", kind = RuleKind.PREFIX, note = "خطوط تبلیغاتی ۵۰۰۰xxxx", isDefault = true),
        BlockRule(pattern = "9000", kind = RuleKind.PREFIX, note = "خطوط تبلیغاتی ۹۰۰۰xxxx", isDefault = true),
        BlockRule(pattern = "^(\\+?98|0098)?(10|20|30|50|90)\\d{2}\\d{4,}$", kind = RuleKind.REGEX, enabled = false,
            note = "الگوی گسترده‌تر سرشماره‌های خدماتی/تبلیغاتی (به‌صورت پیش‌فرض غیرفعال)", isDefault = true),
        BlockRule(pattern = "لغو11", kind = RuleKind.BODY, note = "متن لغو تبلیغات (لغو ۱۱)", isDefault = true),
        BlockRule(pattern = "لغو تبلیغات", kind = RuleKind.BODY, note = "عبارت رایج پایان پیامک‌های تبلیغاتی", isDefault = true),
        BlockRule(pattern = "برای لغو", kind = RuleKind.BODY, enabled = false, note = "عبارت «برای لغو …» (پیش‌فرض غیرفعال)", isDefault = true)
    )
}


/** نمونه الگوهای آماده برای مسدودسازی تبلیغات (با یک ضربه به فهرست اضافه می‌شوند). */
object RuleSamples {
    val all = listOf(
        BlockRule(pattern = "^(\\+?98|0098|0)?(2000|3000|5000|9000)\\d{3,}$", kind = RuleKind.REGEX, note = "همه‌ی خطوط ۲۰۰۰/۳۰۰۰/۵۰۰۰/۹۰۰۰ در هر قالب نوشتن (۰۹۸، +۹۸، ۹۸)"),
        BlockRule(pattern = "3000", kind = RuleKind.PREFIX, note = "شماره‌های شروع‌شونده با ۳۰۰۰ (تبلیغاتی)"),
        BlockRule(pattern = "2000", kind = RuleKind.PREFIX, note = "شماره‌های شروع‌شونده با ۲۰۰۰"),
        BlockRule(pattern = "5000", kind = RuleKind.PREFIX, note = "شماره‌های شروع‌شونده با ۵۰۰۰"),
        BlockRule(pattern = "9000", kind = RuleKind.PREFIX, note = "شماره‌های شروع‌شونده با ۹۰۰۰"),
        BlockRule(pattern = "^[A-Za-z][A-Za-z0-9 ._-]{2,}$", kind = RuleKind.REGEX, note = "فرستنده‌های متنی (نام برند). احتیاط: خدمات لاتین‌نام را هم می‌گیرد"),
        BlockRule(pattern = "^\\d{5,8}$", kind = RuleKind.REGEX, note = "هر سرشماره‌ی کوتاه ۵ تا ۸ رقمی. احتیاط: پیامک بانک‌ها را هم می‌گیرد"),
        BlockRule(pattern = "لغو\\s*(11|10)", kind = RuleKind.BODY_REGEX, note = "عبارت «لغو ۱۱» یا «لغو۱۱» (ارقام فارسی هم پوشش داده می‌شود)"),
        BlockRule(pattern = "لغو تبلیغات", kind = RuleKind.BODY, note = "عبارت پایان پیامک‌های تبلیغاتی"),
        BlockRule(pattern = "(تخفیف|حراج|فروش\\s*ویژه|جشنواره|قرعه\\s*کشی|برنده\\s*شدید)", kind = RuleKind.BODY_REGEX, note = "کلمات تبلیغاتی رایج در متن"),
        BlockRule(pattern = "(bit\\.ly|tinyurl|cutt\\.ly|t\\.me/|ble\\.ir/join|eitaa\\.com/join)", kind = RuleKind.BODY_REGEX, note = "لینک کوتاه و دعوت به کانال/گروه"),
        BlockRule(pattern = "https?://\\S+", kind = RuleKind.BODY_REGEX, note = "هر پیام دارای لینک (شدید؛ ممکن است پیام‌های مهم را هم بگیرد)")
    )
}
