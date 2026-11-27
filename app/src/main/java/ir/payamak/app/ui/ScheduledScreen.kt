package ir.payamak.app.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.payamak.app.app
import ir.payamak.app.data.ScheduledSms
import ir.payamak.app.sms.*
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

fun saveSchedule(ctx: Context, s: ScheduledSms) {
    val app = ctx.app
    app.scope.launch {
        val id = app.db.scheduledDao().upsert(s)
        Scheduler.schedule(app, s.copy(id = id))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen() {
    val ctx = LocalContext.current
    val app = ctx.app
    val list by app.db.scheduledDao().all().collectAsState(initial = emptyList())
    val sims = remember { SimRepo.load(ctx) }
    var editing by remember { mutableStateOf<ScheduledSms?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(title = { Text("پیامک‌های زمان‌بندی‌شده") }, actions = {
                if (list.any { it.status != 0 }) TextButton(onClick = { scope.launch { app.db.scheduledDao().clearFinished() } }) { Text("پاک‌کردن انجام‌شده‌ها") }
            })
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Default.AlarmAdd, null) }, text = { Text("زمان‌بندی جدید") }) }
    ) { inner ->
        Column(Modifier.padding(inner)) {
            if (!Scheduler.canExact(ctx)) {
                Card(Modifier.padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("برای ارسال دقیق‌ در لحظه‌ی تعیین‌شده، اجازه‌ی «هشدارهای دقیق» لازم است. بدون آن ممکن است ارسال کمی دیرتر انجام شود.")
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                        }) { Text("اعطای اجازه") }
                    }
                }
            }
            if (list.isEmpty()) EmptyState(Icons.Default.Alarm, "پیامک زمان‌بندی‌شده‌ای وجود ندارد")
            else LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { s ->
                    ElevatedCard(onClick = { if (s.status == 0) editing = s }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(rememberContactName(s.address), style = MaterialTheme.typography.titleMedium)
                                Text(s.body, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Event, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(Jalali.dateTime(s.triggerAt), style = MaterialTheme.typography.labelMedium)
                                    simName(sims, s.subId)?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                                    AssistChip(onClick = {}, label = { Text(when (s.status) { 0 -> "در انتظار"; 1 -> "ارسال شد"; else -> "ناموفق" }) })
                                }
                            }
                            IconButton(onClick = {
                                Scheduler.cancel(ctx, s.id)
                                scope.launch { app.db.scheduledDao().delete(s.id) }
                            }) { Icon(Icons.Default.Delete, "حذف") }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        ScheduleDialog(
            initial = editing, presetAddress = "", presetBody = "", sims = sims, defaultSub = SimRepo.defaultSubId(),
            onDismiss = { creating = false; editing = null },
            onSave = { a, b, sub, t ->
                val old = editing
                creating = false; editing = null
                saveSchedule(ctx, ScheduledSms(id = old?.id ?: 0, address = a, body = b, subId = sub, triggerAt = t, createdAt = old?.createdAt ?: System.currentTimeMillis()))
            }
        )
    }
}

private fun utcMidnight(ts: Long): Long {
    val l = Calendar.getInstance().apply { timeInMillis = ts }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun combine(utcDate: Long, hour: Int, minute: Int): Long {
    val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDate }
    return Calendar.getInstance().apply {
        clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH), hour, minute, 0)
    }.timeInMillis
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDialog(
    initial: ScheduledSms?, presetAddress: String, presetBody: String, sims: List<SimInfo>, defaultSub: Int,
    onDismiss: () -> Unit, onSave: (address: String, body: String, subId: Int, time: Long) -> Unit
) {
    val startTs = initial?.triggerAt ?: (System.currentTimeMillis() + 10 * 60_000L)
    val startCal = remember { Calendar.getInstance().apply { timeInMillis = startTs } }
    var address by remember { mutableStateOf(initial?.address ?: presetAddress) }
    var body by remember { mutableStateOf(initial?.body ?: presetBody) }
    var sub by remember { mutableIntStateOf(initial?.subId ?: defaultSub) }
    var dateUtc by remember { mutableLongStateOf(utcMidnight(startTs)) }
    var hour by remember { mutableIntStateOf(startCal.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableIntStateOf(startCal.get(Calendar.MINUTE)) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    val trigger = combine(dateUtc, hour, minute)
    val future = trigger > System.currentTimeMillis()
    val valid = Phone.isReplyable(address) && body.isNotBlank() && future

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "ارسال زمان‌دار" else "ویرایش زمان‌بندی") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(address, { address = it }, label = { Text("شماره گیرنده") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    enabled = presetAddress.isBlank())
                OutlinedTextField(body, { body = it }, label = { Text("متن پیامک") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                SimChooser(sims, sub) { sub = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDate = true }, Modifier.weight(1f)) { Icon(Icons.Default.Event, null); Spacer(Modifier.width(6.dp)); Text(Jalali.date(trigger)) }
                    OutlinedButton(onClick = { showTime = true }, Modifier.weight(1f)) { Icon(Icons.Default.AccessTime, null); Spacer(Modifier.width(6.dp)); Text(Jalali.time(trigger)) }
                }
                Text(
                    if (future) "ارسال در: ${Jalali.dateLong(trigger)} ساعت ${Jalali.time(trigger)}" else "زمان انتخاب‌شده باید در آینده باشد.",
                    style = MaterialTheme.typography.labelMedium, color = if (future) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text("انتخاب‌گر تاریخ میلادی است؛ معادل شمسی بالا نمایش داده می‌شود.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(Fa.toLatin(address.trim()), body.trim(), sub, trigger) }) { Text("ذخیره") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )

    if (showDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = dateUtc)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { dateUtc = it }; showDate = false }) { Text("تأیید") } },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("انصراف") } }
        ) { DatePicker(st) }
    }
    if (showTime) {
        val st = rememberTimePickerState(hour, minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = { TextButton(onClick = { hour = st.hour; minute = st.minute; showTime = false }) { Text("تأیید") } },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("انصراف") } },
            text = { TimePicker(st) }
        )
    }
}
