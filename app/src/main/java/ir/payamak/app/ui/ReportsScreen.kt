package ir.payamak.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import ir.payamak.app.data.Status
import ir.payamak.app.sms.Fa
import ir.payamak.app.sms.Jalali

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(vm: ListsVM, onBack: () -> Unit, onOpen: (String, String) -> Unit) {
    val items = vm.outgoing.collectAsLazyPagingItems()
    val counts by vm.statusCounts.collectAsState(initial = emptyList())
    val filter by vm.statusFilter.collectAsState()
    fun c(vararg s: Int) = counts.filter { it.status in s }.sumOf { it.cnt }
    val total = counts.sumOf { it.cnt }

    Scaffold(topBar = {
        TopAppBar(title = { Text("گزارش ارسال و تحویل") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
        })
    }) { inner ->
        Column(Modifier.padding(inner)) {
            LazyRow(contentPadding = PaddingValues(12.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Stat("کل", total, MaterialTheme.colorScheme.secondaryContainer) }
                item { Stat("تحویل‌شده", c(Status.DELIVERED), MaterialTheme.colorScheme.primaryContainer) }
                item { Stat("ارسال‌شده", c(Status.SENT), MaterialTheme.colorScheme.surfaceVariant) }
                item { Stat("در انتظار", c(Status.PENDING), MaterialTheme.colorScheme.tertiaryContainer) }
                item { Stat("ناموفق", c(Status.FAILED, Status.DELIVERY_FAILED), MaterialTheme.colorScheme.errorContainer) }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val opts = listOf(-1 to "همه", Status.DELIVERED to "تحویل‌شده", Status.SENT to "ارسال‌شده", Status.PENDING to "در انتظار", Status.FAILED to "ناموفق", Status.DELIVERY_FAILED to "تحویل ناموفق")
                items(opts) { (v, label) -> FilterChip(selected = filter == v, onClick = { vm.statusFilter.value = v }, label = { Text(label) }) }
            }
            if (items.itemCount == 0) EmptyState(Icons.Default.Assessment, "پیامک ارسالی‌ای برای نمایش نیست")
            else LazyColumn {
                items(count = items.itemCount, key = items.itemKey { it.id }) { i ->
                    val m = items[i] ?: return@items
                    val name = rememberContactName(m.address)
                    Row(Modifier.fillMaxWidth().clickable { onOpen(m.normalized, m.address) }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusIcon(m.status, 22.dp)
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(m.body, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                buildString {
                                    append("ارسال: ${Jalali.dateTime(m.date)}")
                                    if (m.deliveredAt != null && m.deliveredAt > 0) append("  ·  تحویل: ${Jalali.time(m.deliveredAt)}")
                                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text(statusLabel(m.status), style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, n: Int, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Fa.digits(n.toString()), style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
