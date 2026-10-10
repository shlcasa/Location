package com.geoint.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoint.core.AgentOutcome
import com.geoint.core.ClaudeModel
import com.geoint.core.GeoResult
import com.geoint.core.Mode
import com.geoint.core.toDms
import java.util.Locale

private val Ink = Color(0xFF0B0F14)
private val Panel = Color(0xFF151B23)
private val Lime = Color(0xFFC6F432)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Lime, onPrimary = Ink, background = Ink, surface = Panel)) {
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.running) {
                    if (state.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                App(state, vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(vm::loadPhoto)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(state: UiState, vm: MainViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(state.settings.apiKey.isBlank()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::loadPhoto) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        picker.launch(arrayOf("image/*"))
    }
    val context = LocalContext.current
    val pickPhoto = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        else picker.launch(arrayOf("image/*"))
    }

    Column(Modifier.fillMaxSize().background(Ink).systemBarsPadding().imePadding()) {
        TopAppBar(
            title = { Text("GeoInt", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
            actions = {
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink),
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhotoCard(state, pickPhoto)
            if (state.photo != null && !state.running) Controls(state, vm)
            if (state.running) Progress(state, vm::cancel)
            state.error?.let { Text(it, color = Color(0xFFFF8A80)) }
            state.outcome?.let { ResultView(it, state.settings.model) }
            Spacer(Modifier.size(24.dp))
        }
    }

    if (showSettings) SettingsDialog(state.settings, onDismiss = { showSettings = false }) {
        vm.saveSettings(it)
        showSettings = false
    }
}

@Composable
private fun PhotoCard(state: UiState, pick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val photo = state.photo
            when {
                state.loadingPhoto -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), Alignment.Center) { CircularProgressIndicator() }
                photo != null -> Image(
                    photo.preview.asImageBitmap(), contentDescription = "Selected photo",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit,
                )
                else -> Text(
                    "Pick a photo or share one to GeoInt from any app.\nخْتار تصويرة ولا بارطاجيها مع GeoInt.",
                    color = Color.LightGray,
                )
            }
            photo?.let {
                it.fileName?.let { n -> Text(n, fontSize = 12.sp, color = Color.Gray) }
                it.gps?.let { (lat, lon) ->
                    Text(
                        "📍 GPS in the file: %.5f, %.5f".format(Locale.US, lat, lon),
                        color = Lime, fontWeight = FontWeight.Bold,
                    )
                }
                Text("${it.images.size} image(s) will be sent", fontSize = 12.sp, color = Color.Gray)
            }
            OutlinedButton(onClick = pick, enabled = !state.running) { Text(if (photo == null) "Pick photo" else "Change photo") }
        }
    }
}

@Composable
private fun Controls(state: UiState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.mode == Mode.QUICK, onClick = { vm.setMode(Mode.QUICK) }, label = { Text("Quick") })
            FilterChip(selected = state.mode == Mode.DEEP, onClick = { vm.setMode(Mode.DEEP) }, label = { Text("Deep (slower, costs more)") })
        }
        OutlinedTextField(
            value = state.note, onValueChange = vm::setNote, modifier = Modifier.fillMaxWidth(),
            label = { Text("Optional context (e.g. \"GeoGuessr round\", \"from a trip in 2019\")") },
        )
        Button(
            onClick = vm::analyze, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
        ) { Text("Find location", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Progress(state: UiState, cancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            state.log.takeLast(14).forEach { Text(it, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
            if (state.thinking.isNotBlank()) {
                Text("Reasoning: …" + state.thinking.takeLast(280), fontSize = 12.sp, color = Color.Gray)
            }
            if (state.liveText.isNotBlank()) Text(state.liveText.takeLast(1200), fontSize = 13.sp)
            TextButton(onClick = cancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ResultView(outcome: AgentOutcome, model: ClaudeModel) {
    val context = LocalContext.current
    val r = outcome.result
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r == null) {
                Text("No structured answer was returned; read the report below.", color = Color(0xFFFFD180))
            } else {
                Text(r.headline, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Lime)
                if (r.hasPin) {
                    val lat = r.latitude!!
                    val lon = r.longitude!!
                    SelectionContainer {
                        Column {
                            Text(toDms(lat, lon), fontFamily = FontFamily.Monospace)
                            Text("%.6f, %.6f".format(Locale.US, lat, lon), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Text(
                    listOfNotNull(
                        r.confidence?.let { "Confidence: $it" },
                        r.level?.let { "level: $it" },
                        r.radiusKm?.let { "± " + formatRadius(it) },
                    ).joinToString(" · "),
                    color = Color.LightGray,
                )
                r.summary?.let { Text(it) }
                if (r.hasPin) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { openMap(context, r) },
                            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
                        ) { Text("Open in Maps") }
                        OutlinedButton(onClick = { copy(context, "%.6f, %.6f".format(Locale.US, r.latitude, r.longitude)) }) { Text("Copy") }
                    }
                }
            }
            val u = outcome.usage
            Text(
                "≈ $%.3f · %,d in / %,d out tokens · %d searches · %s".format(
                    Locale.US, u.estimatedCostUsd(model), u.inputTokens + u.cacheReadTokens + u.cacheWriteTokens,
                    u.outputTokens, u.webSearches, outcome.modelUsed,
                ),
                fontSize = 11.sp, color = Color.Gray,
            )
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            SelectionContainer { Text(Markdown.render(GeoResult.stripJson(outcome.report)), fontSize = 14.sp, lineHeight = 20.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { copy(context, outcome.report) }) { Text("Copy report") }
                OutlinedButton(onClick = { share(context, outcome.report) }) { Text("Share") }
            }
        }
    }
}

@Composable
private fun SettingsDialog(current: AppSettings, onDismiss: () -> Unit, onSave: (AppSettings) -> Unit) {
    var s by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(s) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = s.apiKey, onValueChange = { s = s.copy(apiKey = it) }, singleLine = true,
                    label = { Text("Anthropic API key (sk-ant-…)") }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Get one at console.anthropic.com. Each photo is billed to your account.", fontSize = 12.sp, color = Color.Gray)
                Text("Model", fontWeight = FontWeight.Bold)
                ClaudeModel.entries.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.model == m, onClick = { s = s.copy(model = m) })
                        Text(m.label, fontSize = 14.sp)
                    }
                }
                Text("Effort (higher = smarter, slower, pricier)", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppSettings.EFFORTS.forEach { e ->
                        FilterChip(selected = s.effort == e, onClick = { s = s.copy(effort = e) }, label = { Text(e, fontSize = 11.sp) })
                    }
                }
                Text("Report language", fontWeight = FontWeight.Bold)
                AppSettings.LANGUAGES.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.language == l, onClick = { s = s.copy(language = l) })
                        Text(l, fontSize = 14.sp)
                    }
                }
                SwitchRow("Zoomed tiles (reads small text, ~4x image cost)", s.tiles) { s = s.copy(tiles = it) }
                SwitchRow("Let Claude open web pages (web fetch)", s.webFetch) { s = s.copy(webFetch = it) }
            }
        },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun formatRadius(km: Double) = if (km < 1) "%.0f m".format(Locale.US, km * 1000) else "%.1f km".format(Locale.US, km)

private fun openMap(context: Context, r: GeoResult) {
    val label = Uri.encode(r.headline)
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${r.latitude},${r.longitude}?q=${r.latitude},${r.longitude}($label)"))
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${r.latitude},${r.longitude}"))
    runCatching { context.startActivity(geo) }.onFailure { runCatching { context.startActivity(web) } }
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("GeoInt", text))
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share report"))
}
