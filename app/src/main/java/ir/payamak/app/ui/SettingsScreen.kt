package ir.payamak.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.payamak.app.app
import ir.payamak.app.data.AppSettings
import ir.payamak.app.data.Template
import ir.payamak.app.sms.Backup
import ir.payamak.app.sms.Fa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onReports: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val s by app.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val def = rememberDefaultState()
    val templates by app.db.extraDao().templates().collectAsState(initial = emptyList())
    var scale by remember(s.scale) { mutableFloatStateOf(s.scale) }
    var newTpl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val n = withContext(Dispatchers.IO) { try { Backup.export(app, uri) } catch (e: Exception) { -1 } }
            busy = false
            toast(if (n >= 0) "${Fa.digits(n.toString())} پیامک در فایل ذخیره شد" else "پشتیبان‌گیری ناموفق بود")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val n = withContext(Dispatchers.IO) { try { Backup.restore(app, uri) } catch (e: Exception) { -1 } }
            busy = false
            toast(if (n >= 0) "${Fa.digits(n.toString())} پیامک بازیابی شد" else "فایل نامعتبر است")
        }
    }

    Scaffold(contentWindowInsets = NoInsets, topBar = { TopAppBar(title = { Text("تنظیمات") }) }) { inner ->
        Column(Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            Card(colors = CardDefaults.cardColors(containerColor = if (def.isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (def.isDefault) Icons.Default.CheckCircle else Icons.Default.Info, null)
                    Column(Modifier.weight(1f)) {
                        Text(if (def.isDefault) "برنامه‌ی پیش‌فرض پیامک است" else "برنامه‌ی پیش‌فرض پیامک نیست", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (def.isDefault) "اعلان پیام‌ها، حذف واقعی و مسدودسازی کامل فعال است."
                            else "برای اعلان، حذف واقعی و مسدودسازی کامل، پیش‌فرض کنید.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (!def.isDefault) Button(onClick = def.request) { Text("پیش‌فرض‌کردن") }
                }
            }

            OutlinedCard(onClick = onReports, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Assessment, null)
                    Column(Modifier.weight(1f)) {
                        Text("گزارش ارسال و تحویل", style = MaterialTheme.typography.titleSmall)
                        Text("وضعیت پیامک‌های ارسالی: ارسال‌شده، تحویل‌شده، ناموفق", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronLeft, null)
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("بررسی پیام‌های قبلی", style = MaterialTheme.typography.titleSmall)
                    Text("همه‌ی پیامک‌های دریافتی قبلی با قانون‌های مسدودسازی تطبیق داده می‌شوند و پیام‌های تبلیغاتی به تب «مسدود شده» می‌روند.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = !checking, onClick = {
                        scope.launch {
                            checking = true
                            val n = withContext(Dispatchers.IO) { try { app.sync.reapplyBlocking() } catch (e: Exception) { -1 } }
                            checking = false
                            toast(if (n < 0) "بررسی ناموفق بود" else if (n == 0) "پیام تبلیغاتی جدیدی پیدا نشد" else "${Fa.digits(n.toString())} پیام تبلیغاتی مسدود شد")
                        }
                    }) { Icon(Icons.Default.Shield, null); Spacer(Modifier.width(6.dp)); Text(if (checking) "در حال بررسی..." else "بررسی و مسدودکردن تبلیغات قبلی") }
                    if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            Text("فونت", style = MaterialTheme.typography.titleMedium)
            FontOptions.forEach { f ->
                Card(
                    Modifier.fillMaxWidth().selectable(selected = s.font == f.id, onClick = { scope.launch { app.settings.setFont(f.id) } }),
                    colors = CardDefaults.cardColors(containerColor = if (s.font == f.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.font == f.id, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(f.label, fontFamily = f.family, fontWeight = FontWeight.Bold)
                            Text("سلام! این یک نمونه متن فارسی است. ۰۱۲۳۴۵۶۷۸۹", fontFamily = f.family, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Text("اندازه‌ی نوشته‌ها: ${Fa.digits((scale * 100).toInt().toString())}٪", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = scale, onValueChange = { scale = it }, valueRange = 0.8f..1.6f, steps = 7,
                onValueChangeFinished = { scope.launch { app.settings.setScale(scale) } }
            )
            Text("نمونه: پیامک شما با موفقیت ارسال شد.", style = MaterialTheme.typography.bodyLarge)

            Text("پوسته", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("خودکار", "روشن", "تاریک").forEachIndexed { i, label ->
                    SegmentedButton(selected = s.theme == i, onClick = { scope.launch { app.settings.setTheme(i) } }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }

            HorizontalDivider()
            Text("پیام‌های آماده", style = MaterialTheme.typography.titleMedium)
            Text("با دکمه‌ی ⚡ در گفتگو می‌توانید این متن‌ها را سریع وارد کنید.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            templates.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.text, Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { app.db.extraDao().deleteTemplate(t.id) } }) { Icon(Icons.Default.Delete, "حذف") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newTpl, { newTpl = it }, Modifier.weight(1f), singleLine = true, label = { Text("متن جدید") })
                FilledIconButton(onClick = {
                    val t = newTpl.trim()
                    if (t.isNotEmpty()) { scope.launch { app.db.extraDao().insertTemplate(Template(text = t)) }; newTpl = "" }
                }) { Icon(Icons.Default.Add, "افزودن") }
            }

            HorizontalDivider()
            Text("پشتیبان‌گیری", style = MaterialTheme.typography.titleMedium)
            Text("پیامک‌های متنی در یک فایل JSON ذخیره یا از آن بازیابی می‌شوند . بازیابی، پیام‌های تکراری را نادیده می‌گیرد.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { exporter.launch("payamak-backup.json") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text("ذخیره‌ی پشتیبان")
                }
                OutlinedButton(enabled = !busy, onClick = { importer.launch(arrayOf("application/json", "*/*")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("بازیابی")
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            HorizontalDivider()
            Text("درباره", style = MaterialTheme.typography.titleMedium)
            Text("پیامک من نسخه‌ی ۲٫۱٫۰", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
