package ir.payamak.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import ir.payamak.app.app
import ir.payamak.app.data.ConversationRow
import ir.payamak.app.data.SmsType
import ir.payamak.app.sms.Fa
import ir.payamak.app.sms.Jalali
import ir.payamak.app.sms.Repo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(vm: ListsVM, onOpen: (String, String) -> Unit, onSearch: () -> Unit, onNew: () -> Unit) {
    val app = LocalContext.current.app
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val normal = vm.conversations.collectAsLazyPagingItems()
    val archived = vm.archived.collectAsLazyPagingItems()
    val blocked = vm.blocked.collectAsLazyPagingItems()
    val items = when (tab) { 0 -> normal; 1 -> archived; else -> blocked }
    val sync by app.sync.state.collectAsState()
    var selected by remember { mutableStateOf<ConversationRow?>(null) }
    var confirmDelete by remember { mutableStateOf<ConversationRow?>(null) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val def = rememberDefaultState()

    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("پیامک‌ها") },
                    actions = {
                        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "جستجو") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "بیشتر") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("علامت‌گذاری همه به‌عنوان خوانده") }, onClick = {
                                menu = false; scope.launch { Repo.markAllRead(app) }
                            })
                        }
                    }
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("گفتگوها") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("بایگانی") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("مسدود شده") })
                }
                if (sync.running) {
                    val f = if (sync.total > 0) sync.done / sync.total.toFloat() else 0f
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "در حال بارگذاری پیام‌ها: ${Fa.digits(sync.done.toString())} از ${Fa.digits(sync.total.toString())}",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Default.Edit, null) }, text = { Text("پیام جدید") })
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (tab == 0) DefaultBanner(def)
            Box(Modifier.weight(1f)) {
                if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading && !sync.running) {
                    EmptyState(
                        when (tab) { 0 -> Icons.Default.Forum; 1 -> Icons.Default.Archive; else -> Icons.Default.Shield },
                        when (tab) { 0 -> "هنوز پیامکی وجود ندارد"; 1 -> "گفتگوی بایگانی‌شده‌ای نیست"; else -> "پیامک مسدودشده‌ای نیست" }
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                        items(count = items.itemCount, key = items.itemKey { it.threadKey }) { i ->
                            items[i]?.let { row -> ConversationItem(row, onClick = { onOpen(row.threadKey, row.address) }, onLong = { selected = row }) }
                        }
                    }
                }
            }
        }
    }

    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(rememberContactName(row.address)) },
            text = {
                Column {
                    TextButton(onClick = { scope.launch { Repo.setPinned(app, row.threadKey, !row.pinned) }; selected = null }) {
                        Text(if (row.pinned) "برداشتن سنجاق" else "سنجاق‌کردن")
                    }
                    TextButton(onClick = { scope.launch { Repo.setArchived(app, row.threadKey, tab != 1) }; selected = null }) {
                        Text(if (tab == 1) "خروج از بایگانی" else "بایگانی")
                    }
                    TextButton(onClick = { confirmDelete = row; selected = null }) { Text("حذف گفتگو", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { selected = null }) { Text("بستن") } }
        )
    }

    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("حذف گفتگو") },
            text = { Text(if (def.isDefault) "همه‌ی پیام‌های این گفتگو برای همیشه از گوشی حذف می‌شود." else "این گفتگو فقط از داخل این برنامه حذف می‌شود؛ برای حذف واقعی، برنامه را پیش‌فرض کنید.") },
            confirmButton = { TextButton(onClick = { scope.launch { Repo.deleteThread(app, row.threadKey) }; confirmDelete = null }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("انصراف") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationItem(row: ConversationRow, onClick: () -> Unit, onLong: () -> Unit) {
    val name = rememberContactName(row.address)
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Avatar(name)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.pinned) { Icon(Icons.Default.PushPin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.lastType == SmsType.SENT) {
                    StatusIcon(row.lastStatus, 14.dp); Spacer(Modifier.width(4.dp))
                    Text("شما: ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    row.lastBody.replace('\n', ' ').ifBlank { "📷 پیام چندرسانه‌ای" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Jalali.smart(row.lastDate), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            if (row.unread > 0) Badge { Text(Fa.digits(row.unread.toString())) }
        }
    }
}
