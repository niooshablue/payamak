package ir.payamak.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import ir.payamak.app.App
import ir.payamak.app.app
import ir.payamak.app.sms.Jalali
import ir.payamak.app.sms.vmFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

class SearchVM(a: App) : ViewModel() {
    val query = MutableStateFlow("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results = query.debounce(300).flatMapLatest { q ->
        val t = q.trim()
        if (t.length < 2) emptyFlow()
        else {
            val esc = t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            Pager(pagingCfg) { a.db.smsDao().search(esc) }.flow
        }
    }.cachedIn(viewModelScope)
}

fun highlight(text: String, q: String, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    val t = q.trim()
    if (t.isEmpty()) { append(text); return@buildAnnotatedString }
    var i = 0
    while (i < text.length) {
        val j = text.indexOf(t, i, ignoreCase = true)
        if (j < 0) { append(text.substring(i)); break }
        append(text.substring(i, j))
        withStyle(SpanStyle(background = color)) { append(text.substring(j, j + t.length)) }
        i = j + t.length
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (String, String) -> Unit) {
    val app = LocalContext.current.app
    val vm: SearchVM = viewModel(factory = vmFactory { SearchVM(app) })
    val q by vm.query.collectAsState()
    val items = vm.results.collectAsLazyPagingItems()
    val hl = MaterialTheme.colorScheme.tertiaryContainer

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = q, onValueChange = { vm.query.value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("جستجو در متن و شماره...") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent)
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") } },
                actions = { if (q.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, "پاک‌کردن") } }
            )
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            if (q.trim().length < 2) EmptyState(Icons.Default.Search, "حداقل دو حرف وارد کنید")
            else if (items.itemCount == 0) EmptyState(Icons.Default.SearchOff, "نتیجه‌ای پیدا نشد")
            else LazyColumn {
                items(count = items.itemCount, key = items.itemKey { it.id }) { i ->
                    val m = items[i] ?: return@items
                    val name = rememberContactName(m.address)
                    Row(Modifier.fillMaxWidth().clickable { onOpen(m.normalized, m.address) }.padding(16.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Avatar(name, 40.dp)
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                                Text(Jalali.date(m.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(highlight(m.body, q, hl), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
