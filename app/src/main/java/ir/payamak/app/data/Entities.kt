package ir.payamak.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object Status {
    const val NONE = 0
    const val PENDING = 1
    const val SENT = 2
    const val DELIVERED = 3
    const val FAILED = 4
    const val DELIVERY_FAILED = 5
}

object SmsType { const val INBOX = 1; const val SENT = 2 }
object RuleKind { const val EXACT = 0; const val PREFIX = 1; const val REGEX = 2; const val BODY = 3; const val BODY_REGEX = 4 }


@Entity(
    tableName = "sms",
    indices = [
        Index(value = ["normalized", "date"]),
        Index(value = ["providerId"], unique = true),
        Index(value = ["type", "status"]),
        Index(value = ["normalized", "isBlocked", "date"])
    ]
)
data class SmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long? = null,
    val address: String,
    val normalized: String,
    val body: String,
    val date: Long,
    val type: Int,
    val read: Boolean = true,
    val status: Int = Status.NONE,
    val subId: Int = -1,
    val isBlocked: Boolean = false,
    val sentAt: Long? = null,
    val deliveredAt: Long? = null,
    val parts: Int = 1
)

@Entity(tableName = "thread_prefs")
data class ThreadPref(@PrimaryKey val threadKey: String, val pinned: Boolean = false, val archived: Boolean = false)

/** خلاصه‌ی هر گفتگو؛ برای نمایش سریع لیست پیام‌دهندگان (یک ردیف به ازای هر شماره و وضعیت مسدودی). */
@Entity(tableName = "conv", primaryKeys = ["threadKey", "blocked"], indices = [Index(value = ["blocked", "lastDate"])])
data class Conv(
    val threadKey: String,
    val blocked: Boolean,
    val address: String,
    val lastBody: String,
    val lastDate: Long,
    val lastType: Int,
    val lastStatus: Int,
    val unread: Int
)

@Entity(tableName = "templates")
data class Template(@PrimaryKey(autoGenerate = true) val id: Long = 0, val text: String)

@Entity(tableName = "block_rules")
data class BlockRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val kind: Int,
    val note: String = "",
    val enabled: Boolean = true,
    val isDefault: Boolean = false
)

@Entity(tableName = "scheduled")
data class ScheduledSms(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val body: String,
    val subId: Int = -1,
    val triggerAt: Long,
    val status: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

data class ConversationRow(
    val threadKey: String,
    val address: String,
    val lastBody: String,
    val lastDate: Long,
    val lastType: Int,
    val lastStatus: Int,
    val unread: Int,
    val pinned: Boolean
)

data class StatusCount(val status: Int, val cnt: Int)
data class MsgLite(val id: Long, val address: String, val body: String, val normalized: String, val isBlocked: Boolean)
