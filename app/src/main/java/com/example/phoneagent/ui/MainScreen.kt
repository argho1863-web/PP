package com.example.phoneagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.phoneagent.agent.LogEntry
import com.example.phoneagent.overlay.FloatingOverlayService
import com.example.phoneagent.service.PhoneAgentAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenSettings: () -> Unit, vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val log by vm.log.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val approval by vm.approval.collectAsStateWithLifecycle()
    val question by vm.question.collectAsStateWithLifecycle()
    val listening by vm.listening.collectAsStateWithLifecycle()
    val partial by vm.partial.collectAsStateWithLifecycle()
    val handsFree by vm.handsFree.collectAsStateWithLifecycle()
    val voiceError by vm.voiceError.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val a11yOn by PhoneAgentAccessibilityService.connected.collectAsStateWithLifecycle()
    val overlayOn by FloatingOverlayService.running.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val canOverlay = remember(tick) { Settings.canDrawOverlays(ctx) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permLauncher.launch(wanted.toTypedArray())
    }

    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }

    approval?.let { req ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Approval required") },
            text = { Text(req.description) },
            confirmButton = { Button(onClick = { vm.resolveApproval(true) }) { Text("Approve") } },
            dismissButton = { OutlinedButton(onClick = { vm.resolveApproval(false) }) { Text("Deny") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone Agent") },
                actions = {
                    TextButton(onClick = { vm.toggleMute() }) { Text(if (settings.tts) "Voice: on" else "Voice: muted") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).imePadding().fillMaxSize().padding(horizontal = 12.dp)) {

            if (!a11yOn) {
                SetupCard("Accessibility service is off. The agent cannot see or control the screen until you enable it.", "Open Accessibility settings") {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            if (!canOverlay) {
                SetupCard("Overlay permission is needed for the floating bubble.", "Grant overlay permission") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = overlayOn, enabled = canOverlay, onCheckedChange = { on ->
                    if (on) FloatingOverlayService.start(ctx) else FloatingOverlayService.stop(ctx)
                })
                Spacer(Modifier.width(8.dp)); Text("Floating bubble")
                Spacer(Modifier.weight(1f))
                Switch(checked = handsFree, onCheckedChange = { vm.setHandsFree(it) })
                Spacer(Modifier.width(8.dp)); Text("Hands-free")
            }

            Card(Modifier.weight(1f).fillMaxWidth()) {
                if (log.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Tell me what to do, e.g. \"Open Settings and turn on Wi-Fi\".")
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(log) { LogRow(it) }
                    }
                }
            }

            if (running) {
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Button(
                    onClick = { vm.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                ) { Text("EMERGENCY STOP") }
            }
            question?.let { Text("Agent asks: ${it.question}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
            voiceError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = if (listening && partial.isNotBlank()) partial else input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (question != null) "Answer the agent..." else "Type a command...") },
                    maxLines = 3,
                )
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(onClick = { vm.toggleMic() }) { Text(if (listening) "Stop" else "Mic") }
                Spacer(Modifier.width(6.dp))
                Button(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank()) { Text("Send") }
            }
        }
    }
}

@Composable
private fun SetupCard(message: String, button: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun LogRow(e: LogEntry) {
    val (label, color) = when (e.kind) {
        LogEntry.Kind.USER -> "You" to MaterialTheme.colorScheme.primary
        LogEntry.Kind.THOUGHT -> "Thought" to MaterialTheme.colorScheme.onSurfaceVariant
        LogEntry.Kind.ACTION -> "Action" to MaterialTheme.colorScheme.tertiary
        LogEntry.Kind.RESULT -> "Result" to MaterialTheme.colorScheme.onSurfaceVariant
        LogEntry.Kind.ERROR -> "Error" to MaterialTheme.colorScheme.error
        LogEntry.Kind.SYSTEM -> "System" to MaterialTheme.colorScheme.secondary
        LogEntry.Kind.DONE -> "Done" to Color(0xFF2E7D32)
    }
    Column {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
        Text(e.text, style = MaterialTheme.typography.bodyMedium)
    }
}
