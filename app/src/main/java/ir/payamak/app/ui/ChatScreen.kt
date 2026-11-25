package ir.payamak.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telephony.SmsMessage
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import ir.payamak.app.App
import ir.payamak.app.app
import ir.payamak.app.data.*
import ir.payamak.app.sms.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChatVM(private val a: App, val key: String) : ViewModel() {
    val messages = Pager(pagingCfg) { a.db.smsDao().thread(key) }.flow.cachedIn(viewModelScope)
    init {
        viewModelScope.launch(Dispatchers.IO) { Repo.markRead(a, key); Notifier.cancel(a, key) }
    }
}

private fun toast(ctx: android.content.Context, msg: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}

private fun copy(ctx: android.content.Context, text: String, msg: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text))
    toast(ctx, msg)
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

    var addr by remember { mutableStateOf(address) }
    LaunchedEffect(key) { if (addr.isBlank()) addr = app.db.smsDao().addressFor(key) ?: key }
    val name = rememberContactName(addr)
    val canReply = Phone.isReplyable(addr)
    val pref by app.db.extraDao().prefFlow(key).collectAsState(initial = null)
    val templates by app.db.extraDao().templates().collectAsState(initial = emptyList())

    val sims = remember { SimRepo.load(ctx) }
    var sub by remember { mutableIntStateOf(SimRepo.defaultSubId().takeIf { d -> sims.any { it.subId == d } } ?: sims.firstOrNull()?.subId ?: -1) }
    var text by rememberSaveable { mutableStateOf(initialBody) }
    var attachments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showSchedule by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var tplMenu by remember { mutableStateOf(false) }
    var actionMsg by remember { mutableStateOf<SmsEntity?>(null) }
    var viewer by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var needDefault by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        attachments = (attachments + uris).distinct().take(6)
    }

    val newestId = if (items.itemCount > 0) items.peek(0)?.id else null
    LaunchedEffect(newestId) {
        if (newestId != null) { listState.animateScrollToItem(0); Repo.markRead(app, key) }
    }

    fun doSend() {
        val body = text.trim()
        if ((body.isEmpty() && attachments.isEmpty()) || !canReply) return
        if (!ctx.has(Manifest.permission.SEND_SMS)) { toast(ctx, "دسترسی ارسال پیامک داده نشده است"); return }
        if (attachments.isNotEmpty()) {
            if (!def.isDefault) { needDefault = true; return }
            val uris = attachments; text = ""; attachments = emptyList()
            app.scope.launch {
                try { Mms.send(app, addr, body, uris, sub) } catch (e: Exception) { toast(ctx, "ارسال MMS ناموفق بود") }
            }
            return
        }
        text = ""
        app.scope.launch { try { SmsSender.send(app, addr, body, sub) } catch (e: Exception) { /* وضعیت «ناموفق» ثبت شده */ } }
    }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        if (name != addr) Text(addr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "بیشتر") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val pinned = pref?.pinned == true; val arch = pref?.archived == true
                        DropdownMenuItem(text = { Text(if (pinned) "برداشتن سنجاق" else "سنجاق‌کردن") }, onClick = { menu = false; scope.launch { Repo.setPinned(app, key, !pinned) } })
                        DropdownMenuItem(text = { Text(if (arch) "خروج از بایگانی" else "بایگانی") }, onClick = { menu = false; scope.launch { Repo.setArchived(app, key, !arch) } })
                        DropdownMenuItem(text = { Text("مسدودکردن این شماره") }, onClick = {
                            menu = false
                            app.scope.launch {
                                app.db.blockDao().insert(BlockRule(pattern = addr, kind = RuleKind.EXACT, note = "مسدود‌شده از داخل گفتگو"))
                                app.sync.reapplyBlocking()
                            }
                            toast(ctx, "شماره مسدود شد")
                        })
                        DropdownMenuItem(text = { Text("حذف گفتگو") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            )
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f), state = listState, reverseLayout = true, contentPadding = PaddingValues(vertical = 8.dp)) {
                items(count = items.itemCount, key = items.itemKey { it.id }) { i ->
                    val m = items[i] ?: return@items
                    val older = if (i + 1 < items.itemCount) items.peek(i + 1) else null
                    Column {
                        Bubble(
                            m, sims,
                            onLong = { actionMsg = m },
                            onImage = { viewer = it },
                            onRetry = {
                                app.scope.launch {
                                    Repo.deleteMessage(app, m)
                                    try { SmsSender.send(app, m.address, m.body, m.subId) } catch (_: Exception) {}
                                }
                            }
                        )
                        if (older == null || Jalali.dayKey(older.date) != Jalali.dayKey(m.date)) DayHeader(m.date)
                    }
                }
            }
            if (canReply) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.navigationBarsPadding().padding(vertical = 6.dp)) {
                        SimChooser(sims, sub) { sub = it }
                        if (attachments.isNotEmpty()) {
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(attachments) { u ->
                                    Box {
                                        UriImage(u.toString(), Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)), 200)
                                        IconButton(onClick = { attachments = attachments - u }, modifier = Modifier.size(22.dp).align(Alignment.TopEnd)) {
                                            Icon(Icons.Default.Cancel, "حذف", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
                            Column {
                                IconButton(onClick = { picker.launch("image/*") }) { Icon(Icons.Default.AttachFile, "پیوست تصویر (MMS)") }
                                Box {
                                    IconButton(onClick = { tplMenu = true }) { Icon(Icons.Default.Bolt, "پیام‌های آماده") }
                                    DropdownMenu(expanded = tplMenu, onDismissRequest = { tplMenu = false }) {
                                        if (templates.isEmpty()) DropdownMenuItem(text = { Text("قالبی ثبت نشده (از تنظیمات اضافه کنید)") }, onClick = { tplMenu = false })
                                        templates.forEach { t ->
                                            DropdownMenuItem(text = { Text(t.text, maxLines = 1) }, onClick = { text = if (text.isBlank()) t.text else text + " " + t.text; tplMenu = false })
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                                placeholder = { Text("پیامک...") }, maxLines = 5, shape = RoundedCornerShape(24.dp)
                            )
                            Column {
                                IconButton(onClick = { showSchedule = true }) { Icon(Icons.Default.AlarmAdd, "ارسال زمان‌دار") }
                                FilledIconButton(onClick = ::doSend, enabled = text.isNotBlank() || attachments.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Send, "ارسال") }
                            }
                        }
                        if (text.isNotEmpty() && attachments.isEmpty()) {
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
                    if (m.body.isNotBlank()) {
                        TextButton(onClick = { copy(ctx, m.body, "متن کپی شد"); actionMsg = null }) { Text("کپی متن") }
                        TextButton(onClick = { onForward(m.body); actionMsg = null }) { Text("ارسال به شخص دیگر") }
                    }
                    TextButton(onClick = { scope.launch { Repo.deleteMessage(app, m) }; actionMsg = null }) { Text("حذف پیام", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { actionMsg = null }) { Text("بستن") } }
        )
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

    if (needDefault) {
        AlertDialog(
            onDismissRequest = { needDefault = false },
            title = { Text("ارسال MMS") },
            text = { Text("ارسال تصویر (MMS) فقط وقتی ممکن است که این برنامه، برنامه‌ی پیش‌فرض پیامک باشد.") },
            confirmButton = { TextButton(onClick = { needDefault = false; def.request() }) { Text("پیش‌فرض‌کردن") } },
            dismissButton = { TextButton(onClick = { needDefault = false }) { Text("انصراف") } }
        )
    }

    viewer?.let { u ->
        Dialog(onDismissRequest = { viewer = null }) {
            Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.clickable { viewer = null }) {
                UriImage(u, Modifier.fillMaxWidth().heightIn(max = 520.dp), 1600, ContentScale.Fit)
            }
        }
    }
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
fun Bubble(m: SmsEntity, sims: List<SimInfo>, onLong: () -> Unit, onImage: (String) -> Unit, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    val out = m.type == SmsType.SENT
    val bg = if (out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (out) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (out) 4.dp else 18.dp, bottomEnd = if (out) 18.dp else 4.dp
    )
    val atts by produceState(emptyList<Attachment>(), m.id) {
        if (m.isMms) value = ctx.app.db.extraDao().attachmentsFor(m.id)
    }
    val code = if (!out) Otp.find(m.body) else null

    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = if (out) Arrangement.Start else Arrangement.End) {
        Surface(color = bg, contentColor = fg, shape = shape, modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = onLong)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                m.subject?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                atts.forEach { a ->
                    if (a.contentType.startsWith("image/")) {
                        UriImage(a.uri, Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)).clickable { onImage(a.uri) }, 500)
                        Spacer(Modifier.height(6.dp))
                    } else {
                        AssistChip(onClick = {}, label = { Text(a.name.ifBlank { a.contentType }, maxLines = 1) }, leadingIcon = { Icon(Icons.Default.AttachFile, null) })
                    }
                }
                if (m.body.isNotBlank()) Text(m.body, style = MaterialTheme.typography.bodyLarge)
                code?.let {
                    TextButton(onClick = { copy(ctx, it, "کد کپی شد") }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("کپی کد ${Fa.digits(it)}")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Jalali.time(m.date), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                    if (m.isMms) Text("MMS", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                    simName(sims, m.subId)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f)) }
                    if (out) StatusIcon(m.status)
                    if (out && m.status != Status.PENDING) Text(statusLabel(m.status), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                }
                if (out && m.status == Status.FAILED && !m.isMms) TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("تلاش مجدد") }
            }
        }
    }
}
