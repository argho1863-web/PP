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
import com.example.phoneagent.data.Persona
import com.example.phoneagent.data.ProviderPresets

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

            Text(
                "Steps are unlimited and each step starts as soon as the screen settles. " +
                    "Press STOP (here or on the floating bubble) to end a task.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow("Vision (send screenshots)", "Needs a vision-capable model. Screenshots are sent to your provider.", s.vision) { v -> vm.update { it.copy(vision = v) } }
            ToggleRow("Voice (TTS) feedback", "Speak confirmations and the final summary.", s.tts) { v -> vm.update { it.copy(tts = v) } }

            HorizontalDivider()
            Text("Voice & Persona", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Persona.entries.forEach { p ->
                    FilterChip(
                        selected = s.persona == p.name,
                        onClick = { vm.selectPersona(p) },
                        label = { Text(p.label) },
                    )
                }
            }
            Text(
                "Picking a persona loads its voice preset (J.A.R.V.I.S: deep and calm, F.R.I.D.A.Y: brighter and quicker). Fine-tune below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Voice pitch: ${"%.2f".format(s.voicePitch)}")
            Slider(
                value = s.voicePitch,
                onValueChange = { v -> vm.update { it.copy(voicePitch = v) } },
                valueRange = 0.5f..2.0f,
            )
            Text("Speech rate: ${"%.2f".format(s.voiceRate)}")
            Slider(
                value = s.voiceRate,
                onValueChange = { v -> vm.update { it.copy(voiceRate = v) } },
                valueRange = 0.5f..2.0f,
            )
            OutlinedButton(onClick = { vm.previewVoice() }) { Text("Preview voice") }

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
