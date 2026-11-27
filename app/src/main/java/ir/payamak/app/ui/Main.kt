package ir.payamak.app.ui

import androidx.compose.runtime.saveable.rememberSaveable
import android.Manifest
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import ir.payamak.app.App
import ir.payamak.app.app
import ir.payamak.app.data.AppSettings
import ir.payamak.app.sms.ContactNames
import ir.payamak.app.sms.Phone
import ir.payamak.app.sms.has
import ir.payamak.app.sms.vmFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

data class Target(val key: String, val address: String, val body: String)

object LaunchTarget { val flow = MutableStateFlow<Target?>(null) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { AppRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        val key = i.getStringExtra("key")
        if (key != null) { LaunchTarget.flow.value = Target(key, i.getStringExtra("address").orEmpty(), ""); return }
        when (i.action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val d = i.data ?: return
                if (d.scheme !in listOf("sms", "smsto", "mms", "mmsto")) return
                val num = Uri.decode(d.schemeSpecificPart.orEmpty()).substringBefore('?').substringBefore(',')
                val body = i.getStringExtra("sms_body") ?: i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                if (num.isNotBlank()) LaunchTarget.flow.value = Target(Phone.normalize(num), num, body)
            }
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { LaunchTarget.flow.value = Target("", "", it) }
        }
    }
}

val pagingCfg = PagingConfig(pageSize = 30, initialLoadSize = 60, enablePlaceholders = false)

class ListsVM(private val a: App) : ViewModel() {
    val conversations = Pager(pagingCfg) { a.db.smsDao().conversations(false, false) }.flow.cachedIn(viewModelScope)
    val archived = Pager(pagingCfg) { a.db.smsDao().conversations(false, true) }.flow.cachedIn(viewModelScope)
    val blocked = Pager(pagingCfg) { a.db.smsDao().conversations(true, false) }.flow.cachedIn(viewModelScope)
    val statusFilter = MutableStateFlow(-1)

    @OptIn(ExperimentalCoroutinesApi::class)
    val outgoing = statusFilter.flatMapLatest { s -> Pager(pagingCfg) { a.db.smsDao().outgoing(s) }.flow }.cachedIn(viewModelScope)
    val statusCounts = a.db.smsDao().statusCounts()
}

@Composable
fun AppRoot() {
    val app = LocalContext.current.app
    val settings by app.settings.flow.collectAsState(initial = AppSettings())
    PayamakTheme(settings) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                PermissionGate { AppNav() }
            }
        }
    }
}

private val permissions = arrayOf(
    Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
    Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS
)

@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    fun ok() = ctx.has(Manifest.permission.READ_SMS) && ctx.has(Manifest.permission.SEND_SMS)
    var granted by remember { mutableStateOf(ok()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = ok() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = ok() }

    if (granted) {
        val app = ctx.app
        LaunchedEffect(Unit) { ContactNames.load(ctx); app.sync.sync() }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { app.scope.launch { ContactNames.load(ctx); app.sync.sync() } }
        DisposableEffect(Unit) {
            val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { app.scope.launch { app.sync.sync() } }
            }
            ctx.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, obs)
            onDispose { ctx.contentResolver.unregisterContentObserver(obs) }
        }
        content()
    } else {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Sms, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("به پیامک من خوش آمدید", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "برای خواندن و ارسال پیامک، تشخیص سیم‌کارت‌ها، نمایش نام مخاطبین و اعلان پیام‌ها به دسترسی‌های زیر نیاز داریم. اطلاعات شما فقط روی همین دستگاه می‌ماند.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launcher.launch(permissions) }) { Text("اعطای دسترسی‌ها") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("باز کردن تنظیمات برنامه") }
            Spacer(Modifier.height(8.dp))
            Text(
                "اگر برنامه را مستقیم از فایل APK نصب کرده‌اید و اندروید ۱۳ یا بالاتر دارید: در تنظیمات برنامه، منوی ⋮ بالا را بزنید و «Allow restricted settings» را بزنید، سپس دسترسی‌ها را بدهید.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val app = LocalContext.current.app
    val listsVm: ListsVM = viewModel(factory = vmFactory { ListsVM(app) })
    val newRoute = "new?body={body}"

    fun openChat(key: String, address: String, body: String = "", popNew: Boolean = false) {
        nav.navigate("chat/${Uri.encode(key)}?address=${Uri.encode(address)}&body=${Uri.encode(body)}") {
            if (popNew) popUpTo(newRoute) { inclusive = true }
        }
    }

    val target by LaunchTarget.flow.collectAsState()
    LaunchedEffect(target) {
        target?.let {
            if (it.key.isEmpty()) nav.navigate("new?body=${Uri.encode(it.body)}") else openChat(it.key, it.address, it.body)
            LaunchTarget.flow.value = null
        }
    }

    NavHost(nav, startDestination = "main") {
        composable("main") {
            MainScreen(listsVm, onOpen = { k, a -> openChat(k, a) }, onSearch = { nav.navigate("search") }, onNew = { nav.navigate("new") }, onReports = { nav.navigate("reports") })
        }
        composable(
            "chat/{key}?address={address}&body={body}",
            arguments = listOf(
                navArgument("key") { type = NavType.StringType },
                navArgument("address") { type = NavType.StringType; defaultValue = "" },
                navArgument("body") { type = NavType.StringType; defaultValue = "" }
            )
        ) { e ->
            ChatScreen(
                key = e.arguments?.getString("key").orEmpty(),
                address = e.arguments?.getString("address").orEmpty(),
                initialBody = e.arguments?.getString("body").orEmpty(),
                onBack = { nav.popBackStack() },
                onForward = { text -> nav.navigate("new?body=${Uri.encode(text)}") }
            )
        }
        composable("reports") { ReportsScreen(listsVm, onBack = { nav.popBackStack() }, onOpen = { k, a -> openChat(k, a) }) }
        composable("search") { SearchScreen(onBack = { nav.popBackStack() }, onOpen = { k, a -> openChat(k, a) }) }
        composable(newRoute, arguments = listOf(navArgument("body") { type = NavType.StringType; defaultValue = "" })) { e ->
            val body = e.arguments?.getString("body").orEmpty()
            NewMessageScreen(onBack = { nav.popBackStack() }, onGo = { num -> openChat(Phone.normalize(num), num, body, popNew = true) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: ListsVM, onOpen: (String, String) -> Unit, onSearch: () -> Unit, onNew: () -> Unit, onReports: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val items = listOf(
        Triple("گفتگوها", Icons.Default.Forum, 0),
        Triple("زمان‌بندی", Icons.Default.Schedule, 1),
        Triple("مسدودی", Icons.Default.Block, 2),
        Triple("تنظیمات", Icons.Default.Settings, 3)
    )
    Scaffold(
        contentWindowInsets = NoInsets,
        bottomBar = {
            NavigationBar {
                items.forEach { (label, icon, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, label) }, label = { Text(label, maxLines = 1) })
                }
            }
        }
    ) { inner ->
        Box(Modifier.padding(inner).consumeWindowInsets(inner)) {
            when (tab) {
                0 -> ConversationsScreen(vm, onOpen, onSearch, onNew)
                1 -> ScheduledScreen()
                2 -> BlocklistScreen()
                else -> SettingsScreen(onReports)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(onBack: () -> Unit, onGo: (String) -> Unit) {
    val ctx = LocalContext.current
    var num by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
                if (it.moveToFirst()) num = it.getString(0).orEmpty()
            }
        } catch (_: Exception) {}
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("پیام جدید") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
        })
    }) { inner ->
        Column(Modifier.padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = num, onValueChange = { num = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("شماره گیرنده") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Phone, null) },
                trailingIcon = {
                    IconButton(onClick = {
                        picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    }) { Icon(Icons.Default.Contacts, "انتخاب از مخاطبین") }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone)
            )
            Button(onClick = { onGo(num.trim()) }, enabled = Phone.isReplyable(num), modifier = Modifier.fillMaxWidth()) { Text("ادامه") }
        }
    }
}
