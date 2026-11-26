package ir.payamak.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import ir.payamak.app.data.*
import ir.payamak.app.sms.ConvIndex
import ir.payamak.app.sms.Notifier
import ir.payamak.app.sms.Scheduler
import ir.payamak.app.sms.SmsSync
import kotlinx.coroutines.*

class App : Application() {
    lateinit var db: AppDatabase
    lateinit var settings: SettingsStore
    lateinit var sync: SmsSync
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "payamak.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        settings = SettingsStore(this)
        sync = SmsSync(this)
        Notifier.ensureChannel(this)
        scope.launch {
            if (db.blockDao().count() == 0) db.blockDao().insertAll(DefaultRules.all)
            if (db.extraDao().templateCount() == 0) {
                listOf("باشه، ممنون", "الان نمی‌تونم صحبت کنم، بعداً تماس می‌گیرم", "در حال رانندگی هستم، بعداً پیام می‌دهم", "چند دقیقه‌ی دیگه می‌رسم")
                    .forEach { db.extraDao().insertTemplate(Template(text = it)) }
            }
            Scheduler.restore(this@App)
            if (db.smsDao().convCount() == 0 && db.smsDao().total() > 0) ConvIndex.rebuildAll(this@App)
        }
    }
}

val Context.app: App get() = applicationContext as App
