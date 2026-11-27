package ir.payamak.app.ui

import androidx.compose.runtime.saveable.rememberSaveable
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.SmsMessage
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import ir.payamak.app.App
import ir.payamak.app.app
import ir.payamak.app.data.*
import ir.payamak.app.sms.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatVM(private val a: App, val key: String) : ViewModel() {
    val query = MutableStateFlow("")
    private val cfg = PagingConfig(pageSize = 30, initialLoadSize = 40, prefetchDistance = 20, enablePlaceholders = false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = query.flatMapLatest { q ->
        val t = q.trim()
        if (t.isEmpty()) Pager(cfg) { a.db.smsDao().thread(key) }.flow
        else flow {
            delay(250)
            val esc = t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            emitAll(Pager(cfg) { a.db.smsDao().searchThread(key, esc) }.flow)
        }
    }.cachedIn(viewModelScope)

    init { viewModelScope.launch(Dispatchers.IO) { Repo.markRead(a, key); Notifier.cancel(a, key) } }
}

private fun toast(ctx: android.content.Context, msg: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}

private fun copy(ctx: android.content.Context, text: String, msg: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text))
    toast(ctx, msg)
}

private fun addToContacts(ctx: android.content.Context, number: String) {
    try {
        ctx.startActivity(Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_ITEM_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
        })
    } catch (e: Exception) { toast(ctx, "برنامه‌ی مخاطبین پیدا نشد") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(key: String, address: String, initialBody: String, onBack: () -> Unit, onForward: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val vm: ChatVM = viewModel(key = "chat$key", factory = vmFactory { ChatVM(app, key) })
    val items = vm.messages.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val def = rememberDefaultState()
    val settings by app.settings.flow.collectAsState(initial = AppSettings())
    val blockRules by app.db.blockDao().all().collectAsState(initial = emptyList())
    var addr by remember { mutableStateOf(address) }
    val exactBlock = blockRules.firstOrNull { it.kind == RuleKind.EXACT && Phone.normalize(it.pattern) == Phone.normalize(addr) }
    val q by vm.query.collectAsState()

    LaunchedEffect(key) { if (addr.isBlank()) addr = app.db.smsDao().addressFor(key) ?: key }
    val name = rememberContactName(addr)
    val canReply = Phone.isReplyable(addr)
    val unknownNumber = canReply && name == addr
    val pref by app.db.extraDao().prefFlow(key).collectAsState(initial = null)
    val templates by app.db.extraDao().templates().collectAsState(initial = emptyList())

    val sims by produceState(emptyList<SimInfo>()) { value = withContext(Dispatchers.IO) { SimRepo.load(ctx) } }
    var subOverride by remember { mutableStateOf<Int?>(null) }
    val sub = subOverride ?: SimRepo.defaultSubId().takeIf { d -> sims.any { it.subId == d } } ?: sims.firstOrNull()?.subId ?: -1
    var text by rememberSaveable { mutableStateOf(initialBody) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var tplMenu by remember { mutableStateOf(false) }
    var actionMsg by remember { mutableStateOf<SmsEntity?>(null) }
    var detailMsg by remember { mutableStateOf<SmsEntity?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingSend by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableIntStateOf(5) }

    val newestId = if (items.itemCount > 0) items.peek(0)?.id else null
    LaunchedEffect(newestId) {
        if (newestId != null) {
            listState.scrollToItem(0)
            withContext(Dispatchers.IO) { Repo.markRead(app, key) }
        }
    }

    fun sendNow(body: String) {
        if (!ctx.has(Manifest.permission.SEND_SMS)) { toast(ctx, "دسترسی ارسال پیامک داده نشده است"); return }
        app.scope.launch { try { SmsSender.send(app, addr, body, sub) } catch (e: Exception) { toast(ctx, "ارسال پیامک ناموفق بود") } }
    }
    fun doSend() {
        val body = text.trim()
        if (body.isEmpty() || !canReply) return
        text = ""
        if (settings.delayedSend) { pendingSend = body; countdown = 5 }
        else sendNow(body)
    }
    LaunchedEffect(pendingSend) {
        if (pendingSend != null) {
            for (n in 5 downTo 1) { countdown = n; delay(1000) }
            val ready = pendingSend
            pendingSend = null
            if (ready != null) sendNow(ready)
        }
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = q, onValueChange = { vm.query.value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("جستجو در پیام‌های ${name}") },
                            colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent)
                        )
                    } else Column {
                        Text(name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        if (name != addr) Text(addr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (searching) { searching = false; vm.query.value = "" } else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
                },
                actions = {
                    if (searching) {
                        if (q.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, "پاک‌کردن") }
                    } else {
                        if (canReply && !addr.matches(Regex("^[A-Za-z].*"))) IconButton(onClick = {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$addr"))) }
                        }) { Icon(Icons.Default.Call, "تماس با مخاطب") }
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "جستجو در این گفتگو") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "بیشتر") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val pinned = pref?.pinned == true; val arch = pref?.archived == true
                            if (unknownNumber) DropdownMenuItem(text = { Text("افزودن به مخاطبین") }, onClick = { menu = false; addToContacts(ctx, addr) })
                            DropdownMenuItem(text = { Text(if (pinned) "برداشتن سنجاق" else "سنجاق‌کردن") }, onClick = { menu = false; scope.launch { Repo.setPinned(app, key, !pinned) } })
                            DropdownMenuItem(text = { Text(if (arch) "خروج از بایگانی" else "بایگانی") }, onClick = { menu = false; scope.launch { Repo.setArchived(app, key, !arch) } })
                            DropdownMenuItem(text = { Text(if (exactBlock != null) "خارج‌کردن از فهرست مسدودها" else "مسدودکردن این شماره") }, onClick = {
                                menu = false
                                app.scope.launch {
                                    if (exactBlock != null) app.db.blockDao().delete(exactBlock)
                                    else app.db.blockDao().insert(BlockRule(pattern = addr, kind = RuleKind.EXACT, note = "مسدود‌شده از داخل گفتگو"))
                                    app.sync.reapplyBlocking()
                                }
                                toast(ctx, if (exactBlock != null) "شماره از مسدودی خارج شد" else "شماره مسدود شد")
                            })
                            DropdownMenuItem(text = { Text("حذف گفتگو") }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                }
            )
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().imePadding()) {
            if (pendingSend != null) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("ارسال تا ${Fa.digits(countdown.toString())} ثانیه دیگر", Modifier.weight(1f))
                        TextButton(onClick = { text = pendingSend.orEmpty(); pendingSend = null }) { Text("ویرایش") }
                        TextButton(onClick = { pendingSend = null }) { Text("لغو") }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                if (searching && q.isNotBlank() && items.itemCount == 0) EmptyState(Icons.Default.SearchOff, "نتیجه‌ای پیدا نشد")
                LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(count = items.itemCount, key = items.itemKey { it.id }) { i ->
                        val m = items[i] ?: return@items
                        val older = if (!searching && i + 1 < items.itemCount) items.peek(i + 1) else null
                        Column {
                            Bubble(
                                m, sims, hl = if (searching) q else "",
                                onLong = { actionMsg = m },
                                onRetry = {
                                    app.scope.launch {
                                        Repo.deleteMessage(app, m)
                                        try { SmsSender.send(app, m.address, m.body, m.subId) } catch (_: Exception) {}
                                    }
                                }
                            )
                            if (!searching && (older == null || Jalali.dayKey(older.date) != Jalali.dayKey(m.date))) DayHeader(m.date)
                        }
                    }
                }
            }
            if (searching) {
                // در حالت جستجو نوار ارسال نمایش داده نمی‌شود
            } else if (canReply) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.navigationBarsPadding().padding(vertical = 6.dp)) {
                        SimChooser(sims, sub) { subOverride = it }
                        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
                            Box {
                                IconButton(onClick = { tplMenu = true }) { Icon(Icons.Default.Bolt, "پیام‌های آماده") }
                                DropdownMenu(expanded = tplMenu, onDismissRequest = { tplMenu = false }) {
                                    if (templates.isEmpty()) DropdownMenuItem(text = { Text("قالبی ثبت نشده (از تنظیمات اضافه کنید)") }, onClick = { tplMenu = false })
                                    templates.forEach { t ->
                                        DropdownMenuItem(text = { Text(t.text, maxLines = 1) }, onClick = { text = if (text.isBlank()) t.text else text + " " + t.text; tplMenu = false })
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                                placeholder = { Text("پیامک...") }, minLines = 2, maxLines = 6, shape = RoundedCornerShape(24.dp)
                            )
                            IconButton(onClick = { showSchedule = true }) { Icon(Icons.Default.AlarmAdd, "ارسال زمان‌دار") }
                            FilledIconButton(onClick = ::doSend, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "ارسال") }
                        }
                        if (text.isNotEmpty()) {
                            val calc = remember(text) { try { SmsMessage.calculateLength(text, false) } catch (e: Exception) { intArrayOf(1, text.length, 0, 0) } }
                            Text(
                                "${Fa.digits(calc[2].toString())} نویسه‌ی باقی‌مانده · ${Fa.digits(calc[0].toString())} پیامک",
                                Modifier.padding(horizontal = 56.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            } else {
                Surface(tonalElevation = 3.dp) {
                    Text("به این فرستنده نمی‌توان پاسخ داد.", Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    if (showSchedule) {
        ScheduleDialog(
            initial = null, presetAddress = addr, presetBody = text, sims = sims, defaultSub = sub,
            onDismiss = { showSchedule = false },
            onSave = { a, b, s, t ->
                showSchedule = false; text = ""
                saveSchedule(ctx, ScheduledSms(address = a, body = b, subId = s, triggerAt = t))
                toast(ctx, "پیام برای ${Jalali.dateTime(t)} زمان‌بندی شد")
            }
        )
    }

    actionMsg?.let { m ->
        AlertDialog(
            onDismissRequest = { actionMsg = null },
            text = {
                Column {
                    TextButton(onClick = { copy(ctx, m.body, "متن کپی شد"); actionMsg = null }) { Text("کپی متن") }
                    TextButton(onClick = { detailMsg = m; actionMsg = null }) { Text("جزئیات پیام") }
                    TextButton(onClick = { onForward(m.body); actionMsg = null }) { Text("ارسال به شخص دیگر") }
                    if (unknownNumber) TextButton(onClick = { addToContacts(ctx, addr); actionMsg = null }) { Text("افزودن شماره به مخاطبین") }
                    TextButton(onClick = { scope.launch { Repo.deleteMessage(app, m) }; actionMsg = null }) { Text("حذف پیام", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { actionMsg = null }) { Text("بستن") } }
        )
    }

    detailMsg?.let { m ->
        MessageDetails(m, name, sims, unknownNumber, onAddContact = { addToContacts(ctx, m.address) }, onClose = { detailMsg = null })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف گفتگو") },
            text = { Text(if (def.isDefault) "همه‌ی پیام‌های این گفتگو برای همیشه از گوشی حذف می‌شود." else "این گفتگو فقط از داخل این برنامه حذف می‌شود.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { Repo.deleteThread(app, key) }; onBack() }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("انصراف") } }
        )
    }
}

@Composable
private fun MessageDetails(m: SmsEntity, name: String, sims: List<SimInfo>, unknown: Boolean, onAddContact: () -> Unit, onClose: () -> Unit) {
    val out = m.type == SmsType.SENT
    val greg = remember(m.date) { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(m.date)) }
    val calc = remember(m.body) { try { SmsMessage.calculateLength(m.body, false) } catch (e: Exception) { intArrayOf(1, m.body.length, 0, 0) } }
    @Composable fun row(k: String, v: String) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            Text(v, style = MaterialTheme.typography.bodyMedium)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("جزئیات پیام") },
        text = {
            Column {
                row(if (out) "گیرنده" else "فرستنده", if (name != m.address) "$name (${m.address})" else m.address)
                row("نوع", if (out) "ارسالی" else "دریافتی")
                row("زمان", "${Jalali.dateLong(m.date)} ${Jalali.time(m.date)}  ·  $greg")
                simName(sims, m.subId)?.let { row("سیم‌کارت", it) }
                row("طول پیام", "${Fa.digits(m.body.length.toString())} نویسه · ${Fa.digits(calc[0].toString())} پیامک")
                if (out) {
                    row("وضعیت", statusLabel(m.status))
                    m.sentAt?.takeIf { it > 0 }?.let { row("زمان ارسال به شبکه", Jalali.dateTime(it)) }
                    m.deliveredAt?.takeIf { it > 0 }?.let { row("زمان گزارش تحویل", Jalali.dateTime(it)) }
                }
                if (m.isBlocked) row("وضعیت مسدودی", "به‌عنوان تبلیغاتی مسدود شده است")
                if (unknown) TextButton(onClick = onAddContact, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("افزودن شماره به مخاطبین")
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("بستن") } }
    )
}

@Composable
private fun DayHeader(ts: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(Jalali.dayHeader(ts), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Bubble(m: SmsEntity, sims: List<SimInfo>, hl: String, onLong: () -> Unit, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    val out = m.type == SmsType.SENT
    val bg = if (out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (out) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (out) 4.dp else 18.dp, bottomEnd = if (out) 18.dp else 4.dp
    )
    val code = remember(m.id, m.body) { if (!out) Otp.find(m.body) else null }
    val hlColor = MaterialTheme.colorScheme.tertiaryContainer

    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = if (out) Arrangement.Start else Arrangement.End) {
        Surface(color = bg, contentColor = fg, shape = shape, modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = onLong)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(if (hl.isBlank()) AnnotatedString(m.body) else highlight(m.body, hl, hlColor), style = MaterialTheme.typography.bodyLarge)
                code?.let {
                    TextButton(onClick = { copy(ctx, it, "کد کپی شد") }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("کپی کد ${Fa.digits(it)}")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (hl.isNotBlank()) Jalali.dateTime(m.date) else Jalali.time(m.date), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                    simName(sims, m.subId)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f)) }
                    if (out) StatusIcon(m.status)
                    if (out && m.status != Status.PENDING) Text(statusLabel(m.status), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                }
                if (out && m.status == Status.FAILED) TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("تلاش مجدد") }
            }
        }
    }
}
