package com.example.phoneagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phoneagent.data.ProviderPresets
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val s by vm.draft.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val preset = ProviderPresets.byName(s.presetName)

    BackHandler { vm.save(); onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = { vm.save(); onBack() }) { Text("Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("API Setup", style = MaterialTheme.typography.titleLarge)

            Text("Provider preset", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderPresets.all.forEach { p ->
                    FilterChip(selected = s.presetName == p.name, onClick = { vm.selectPreset(p) }, label = { Text(p.name) })
                }
            }

            OutlinedTextField(
                value = s.baseUrl, onValueChange = { v -> vm.update { it.copy(baseUrl = v) } },
                label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("Auto-filled by preset; edit for custom or local endpoints (e.g. Ollama).") },
            )

            OutlinedTextField(
                value = s.apiKey, onValueChange = { v -> vm.update { it.copy(apiKey = v) } },
                label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("Stored encrypted with the Android Keystore.") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                OutlinedButton(onClick = {
                    clipboard.getText()?.text?.trim()?.let { t -> vm.update { it.copy(apiKey = t) } }
                }) { Text("Paste") }
            }

            OutlinedTextField(
                value = s.model, onValueChange = { v -> vm.update { it.copy(model = v) } },
                label = { Text("Model name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (preset.models + ProviderPresets.quickModels).distinct().forEach { m ->
                    AssistChip(onClick = { vm.update { it.copy(model = m) } }, label = { Text(m) })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.testConnection() }, enabled = test !is TestState.Running) { Text("Test Connection") }
                Spacer(Modifier.width(12.dp))
                when (val t = test) {
                    TestState.Idle -> {}
                    TestState.Running -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    is TestState.Success -> Text("OK - ${t.latencyMs} ms", color = Color(0xFF2E7D32))
                    is TestState.Failure -> {}
                }
            }
            (test as? TestState.Failure)?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider()
            Text("Agent Controls", style = MaterialTheme.typography.titleLarge)

            Text("Max steps per task: ${s.maxSteps}")
            Slider(
                value = s.maxSteps.toFloat(), onValueChange = { v -> vm.update { it.copy(maxSteps = v.roundToInt()) } },
                valueRange = 3f..50f, steps = 46,
            )
            Text("Step delay: ${"%.1f".format(s.stepDelaySec)} s")
            Slider(
                value = s.stepDelaySec,
                onValueChange = { v -> vm.update { it.copy(stepDelaySec = (v * 10).roundToInt() / 10f) } },
                valueRange = 1f..3f,
            )
            ToggleRow("Vision (send screenshots)", "Needs a vision-capable model. Screenshots are sent to your provider.", s.vision) { v -> vm.update { it.copy(vision = v) } }
            ToggleRow("Voice (TTS) feedback", "Speak confirmations and the final summary.", s.tts) { v -> vm.update { it.copy(tts = v) } }

            Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) { Text(if (saved) "Saved" else "Save") }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
