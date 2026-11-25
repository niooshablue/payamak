package ir.payamak.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(val font: String = "vazirmatn", val scale: Float = 1f, val theme: Int = 0)

class SettingsStore(private val ctx: Context) {
    private val FONT = stringPreferencesKey("font")
    private val SCALE = floatPreferencesKey("scale")
    private val THEME = intPreferencesKey("theme")

    val flow: Flow<AppSettings> = ctx.dataStore.data.map {
        AppSettings(it[FONT] ?: "vazirmatn", it[SCALE] ?: 1f, it[THEME] ?: 0)
    }

    suspend fun setFont(v: String) { ctx.dataStore.edit { it[FONT] = v } }
    suspend fun setScale(v: Float) { ctx.dataStore.edit { it[SCALE] = v } }
    suspend fun setTheme(v: Int) { ctx.dataStore.edit { it[THEME] = v } }
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
