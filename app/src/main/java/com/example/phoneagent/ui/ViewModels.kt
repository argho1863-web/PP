package com.example.phoneagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.phoneagent.data.AgentSettings
import com.example.phoneagent.data.ProviderPreset
import com.example.phoneagent.data.ProviderPresets
import com.example.phoneagent.phoneApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.phoneApp
    val log = app.orchestrator.log
    val running = app.orchestrator.running
    val status = app.orchestrator.status
    val approval = app.orchestrator.pendingApproval
    val question = app.orchestrator.pendingQuestion
    val listening = app.voice.listening
    val partial = app.voice.partial
    val handsFree = app.voice.handsFree
    val voiceError = app.voice.error
    val settings = app.settingsStore.settings

    fun send(text: String) = app.orchestrator.submitUserInput(text)
    fun stop() = app.orchestrator.stop()
    fun clearLog() = app.orchestrator.clearLog()
    fun resolveApproval(ok: Boolean) = app.orchestrator.resolveApproval(ok)

    fun toggleMic() {
        if (app.voice.listening.value) app.voice.stopListening()
        else app.voice.startListening { app.orchestrator.submitUserInput(it) }
    }

    fun setHandsFree(on: Boolean) = app.voice.setHandsFree(on) { app.orchestrator.submitUserInput(it) }
    fun toggleMute() = app.settingsStore.update { it.copy(tts = !it.tts) }
}

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val latencyMs: Long, val reply: String) : TestState
    data class Failure(val message: String) : TestState
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.phoneApp
    private val _draft = MutableStateFlow(app.settingsStore.settings.value)
    val draft: StateFlow<AgentSettings> = _draft
    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    fun update(f: (AgentSettings) -> AgentSettings) { _draft.value = f(_draft.value); _saved.value = false }

    fun selectPreset(p: ProviderPreset) = update {
        it.copy(
            presetName = p.name,
            baseUrl = p.baseUrl,
            model = p.models.first(),
        )
    }

    fun save() { app.settingsStore.save(_draft.value); _saved.value = true }

    fun testConnection() {
        val s = _draft.value
        if (s.apiKey.isBlank() && s.presetName != ProviderPresets.CUSTOM) {
            _test.value = TestState.Failure("Enter an API key first."); return
        }
        _test.value = TestState.Running
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            _test.value = try {
                val reply = app.llm.complete(s, "You are a connectivity test.", "Reply with the single word: ok")
                TestState.Success(System.currentTimeMillis() - t0, reply.trim().take(60))
            } catch (e: Exception) {
                TestState.Failure(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
