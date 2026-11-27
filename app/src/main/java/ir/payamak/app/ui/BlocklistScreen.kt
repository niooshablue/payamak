package ir.payamak.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ir.payamak.app.app
import ir.payamak.app.data.BlockRule
import ir.payamak.app.data.DefaultRules
import ir.payamak.app.data.RuleKind
import ir.payamak.app.data.RuleSamples
import ir.payamak.app.sms.BlockMatcher
import kotlinx.coroutines.launch

private fun kindLabel(k: Int) = when (k) {
    RuleKind.EXACT -> "شماره‌ی دقیق"
    RuleKind.PREFIX -> "پیش‌شماره / سرشماره"
    RuleKind.REGEX -> "الگوی Regex روی شماره"
    RuleKind.BODY -> "عبارت در متن پیامک"
    else -> "الگوی Regex روی متن پیامک"
}

private fun kindHint(k: Int) = when (k) {
    RuleKind.EXACT -> "مثال: 09121234567"
    RuleKind.PREFIX -> "مثال: 3000 (همه‌ی شماره‌های شروع‌شونده با ۳۰۰۰)"
    RuleKind.REGEX -> "مثال: ^(\\+98|0)?3000\\d+$"
    RuleKind.BODY -> "مثال: لغو11"
    else -> "مثال: (تخفیف|حراج|جشنواره)"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlocklistScreen(onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val app = ctx.app
    val rules by app.db.blockDao().all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<BlockRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf("") }

    fun changed() { app.scope.launch { app.sync.reapplyBlocking() } }

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            TopAppBar(title = { Text("مسدودسازی تبلیغات") }, navigationIcon = {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "بازگشت") }
            }, actions = {
                IconButton(onClick = { samples = true }) { Icon(Icons.Default.Lightbulb, "نمونه الگوها") }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "بیشتر") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("بازگرداندن فهرست پیش‌فرض") }, onClick = {
                        menu = false
                        scope.launch { app.db.blockDao().deleteDefaults(); app.db.blockDao().insertAll(DefaultRules.all); changed() }
                    })
                }
            })
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("افزودن قانون") }) }
    ) { inner ->
        LazyColumn(Modifier.padding(inner), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("پیامک‌هایی که با این قانون‌ها بخوانند به تب «مسدود شده» می‌روند. با دکمه‌ی 💡 نمونه الگوهای آماده را اضافه کنید. برای اعمال روی پیام‌های قبلی، از تنظیمات «بررسی و مسدودکردن تبلیغات قبلی» را بزنید.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            test, { test = it }, label = { Text("آزمایش شماره/متن") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                if (test.isNotBlank()) {
                                    val hit = BlockMatcher(rules).isBlocked(test, test)
                                    Text(if (hit) "این مورد مسدود می‌شود" else "این مورد مسدود نمی‌شود", color = if (hit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                }
            }
            items(rules, key = { it.id }) { r ->
                ElevatedCard {
                    Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.pattern, style = MaterialTheme.typography.titleMedium)
                            Text(kindLabel(r.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(r.enabled, { scope.launch { app.db.blockDao().update(r.copy(enabled = it)); changed() } })
                        IconButton(onClick = { editing = r }) { Icon(Icons.Default.Edit, "ویرایش") }
                        IconButton(onClick = { scope.launch { app.db.blockDao().delete(r); changed() } }) { Icon(Icons.Default.Delete, "حذف") }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        RuleDialog(
            initial = editing, onDismiss = { creating = false; editing = null },
            onSave = { r ->
                val old = editing
                creating = false; editing = null
                scope.launch {
                    if (old == null) app.db.blockDao().insert(r) else app.db.blockDao().update(r.copy(id = old.id, isDefault = old.isDefault))
                    changed()
                }
            }
        )
    }

    if (samples) {
        AlertDialog(
            onDismissRequest = { samples = false },
            title = { Text("نمونه الگوهای پیشنهادی") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleSamples.all.forEach { s ->
                        val exists = rules.any { it.pattern == s.pattern && it.kind == s.kind }
                        OutlinedCard {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(s.pattern, style = MaterialTheme.typography.titleSmall)
                                Text(kindLabel(s.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(s.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(enabled = !exists, onClick = { scope.launch { app.db.blockDao().insert(s); changed() } }, contentPadding = PaddingValues(0.dp)) {
                                    Text(if (exists) "افزوده شده" else "افزودن به فهرست")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { samples = false }) { Text("بستن") } }
        )
    }
}

@Composable
private fun RuleDialog(initial: BlockRule?, onDismiss: () -> Unit, onSave: (BlockRule) -> Unit) {
    var kind by remember { mutableIntStateOf(initial?.kind ?: RuleKind.PREFIX) }
    var pattern by remember { mutableStateOf(initial?.pattern ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    val isRegex = kind == RuleKind.REGEX || kind == RuleKind.BODY_REGEX
    val regexOk = !isRegex || runCatching { Regex(pattern) }.isSuccess
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "قانون جدید" else "ویرایش قانون") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(RuleKind.PREFIX, RuleKind.EXACT, RuleKind.REGEX, RuleKind.BODY, RuleKind.BODY_REGEX).forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = kind == k, onClick = { kind = k }); Text(kindLabel(k)) }
                }
                OutlinedTextField(pattern, { pattern = it }, label = { Text("الگو") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(if (regexOk) kindHint(kind) else "الگوی Regex نامعتبر است", color = if (regexOk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error) }, isError = !regexOk)
                OutlinedTextField(note, { note = it }, label = { Text("یادداشت (اختیاری)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = pattern.isNotBlank() && regexOk, onClick = {
                onSave(BlockRule(pattern = pattern.trim(), kind = kind, note = note.trim(), enabled = initial?.enabled ?: true))
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}
