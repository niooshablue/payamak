package ir.payamak.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.payamak.app.data.Status
import ir.payamak.app.sms.ContactNames
import ir.payamak.app.sms.SimInfo

val NoInsets = WindowInsets(0, 0, 0, 0)

@Composable
fun rememberContactName(address: String): String {
    val ctx = LocalContext.current
    val name by produceState<String?>(initialValue = ContactNames.cached(address), address) {
        value = ContactNames.lookup(ctx, address)
    }
    return name ?: address
}

@Composable
fun Avatar(name: String, size: Dp = 46.dp) {
    val palette = listOf(0xFF26A69A, 0xFF5C6BC0, 0xFFEF6C00, 0xFF8E24AA, 0xFF2E7D32, 0xFFC2185B, 0xFF0277BD)
    val c = Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
    Box(Modifier.size(size).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        val ch = name.firstOrNull { it.isLetter() }
        if (ch != null) Text(ch.uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium)
        else Icon(Icons.Default.Person, null, tint = Color.White)
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(text, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
    }
}

fun statusLabel(s: Int) = when (s) {
    Status.PENDING -> "در حال ارسال"
    Status.SENT -> "ارسال شده"
    Status.DELIVERED -> "تحویل داده شد"
    Status.FAILED -> "ارسال ناموفق"
    Status.DELIVERY_FAILED -> "تحویل ناموفق"
    else -> ""
}

@Composable
fun StatusIcon(status: Int, size: Dp = 14.dp) {
    val (icon, tint) = when (status) {
        Status.PENDING -> Icons.Default.Schedule to MaterialTheme.colorScheme.outline
        Status.SENT -> Icons.Default.Done to MaterialTheme.colorScheme.outline
        Status.DELIVERED -> Icons.Default.DoneAll to MaterialTheme.colorScheme.primary
        Status.FAILED, Status.DELIVERY_FAILED -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
        else -> return
    }
    Icon(icon, statusLabel(status), Modifier.size(size), tint = tint)
}

fun simName(sims: List<SimInfo>, subId: Int): String? =
    if (sims.size > 1) sims.firstOrNull { it.subId == subId }?.let { "سیم ${ir.payamak.app.sms.Fa.digits(it.slot.toString())}" } else null

@Composable
fun SimChooser(sims: List<SimInfo>, selected: Int, onSelect: (Int) -> Unit) {
    if (sims.size < 2) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.SimCard, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
        sims.forEach { s ->
            FilterChip(
                selected = s.subId == selected, onClick = { onSelect(s.subId) },
                label = { Text("سیم ${ir.payamak.app.sms.Fa.digits(s.slot.toString())}" + if (s.label.isNotBlank()) " · ${s.label}" else "", maxLines = 1) }
            )
        }
    }
}

// ---------------------- تصویر و نقش پیش‌فرض ----------------------

private fun decodeUri(ctx: android.content.Context, uri: String, maxPx: Int): androidx.compose.ui.graphics.ImageBitmap? = try {
    val u = android.net.Uri.parse(uri)
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / s > maxPx * 2) s *= 2
    ctx.contentResolver.openInputStream(u)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
    }?.asImageBitmap()
} catch (e: Exception) { null }

@Composable
fun UriImage(
    uri: String, modifier: Modifier, maxPx: Int = 600,
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop
) {
    val ctx = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { decodeUri(ctx, uri, maxPx) }
    }
    val b = bmp
    if (b != null) androidx.compose.foundation.Image(b, null, modifier, contentScale = contentScale)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.outline)
    }
}

class DefaultState(val isDefault: Boolean, val request: () -> Unit)

@Composable
fun rememberDefaultState(): DefaultState {
    val ctx = LocalContext.current
    var d by remember { mutableStateOf(ir.payamak.app.sms.Sys.isDefault(ctx)) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        d = ir.payamak.app.sms.Sys.isDefault(ctx)
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { d = ir.payamak.app.sms.Sys.isDefault(ctx) }
    return DefaultState(d) {
        val intent = if (android.os.Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
            if (rm.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS)) rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS) else null
        } else {
            android.content.Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, ctx.packageName)
        }
        intent?.let { launcher.launch(it) }
    }
}

@Composable
fun DefaultBanner(state: DefaultState, modifier: Modifier = Modifier) {
    if (state.isDefault) return
    Card(modifier.fillMaxWidth().padding(12.dp, 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("برنامه‌ی پیش‌فرض پیامک نیستید", style = MaterialTheme.typography.titleSmall)
            Text(
                "برای دریافت MMS، ارسال تصویر، اعلان پیام‌ها، مسدودسازی واقعی تبلیغات و حذف واقعی پیامک‌ها، این برنامه را پیش‌فرض کنید.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = state.request) { Text("تنظیم به‌عنوان پیش‌فرض") }
        }
    }
}
