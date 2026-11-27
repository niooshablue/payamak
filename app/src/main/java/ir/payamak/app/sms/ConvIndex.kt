package ir.payamak.app.sms

import androidx.room.withTransaction
import ir.payamak.app.App
import ir.payamak.app.data.Conv
import kotlinx.coroutines.yield

/** نگه‌داری جدول «خلاصه‌ی گفتگوها» تا لیست پیام‌دهندگان بدون کوئری سنگین و آنی بارگذاری شود. */
object ConvIndex {
    suspend fun refresh(app: App, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val dao = app.db.smsDao()
        app.db.withTransaction {
            for (k in keys.toSet()) for (b in listOf(false, true)) {
                val last = dao.lastOf(k, b)
                if (last == null) dao.deleteConv(k, b)
                else dao.upsertConv(Conv(k, b, last.address, last.body.take(160), last.date, last.type, last.status, dao.unreadCount(k, b)))
            }
        }
    }

    suspend fun rebuildAll(app: App) {
        val dao = app.db.smsDao()
        dao.allKeys().chunked(150).forEach { refresh(app, it); yield() }
        dao.deleteOrphanConv()
    }
}
