package com.example.phoneagent

import android.app.Application
import android.content.Context
import com.example.phoneagent.agent.AgentOrchestrator
import com.example.phoneagent.data.SecureSettingsStore
import com.example.phoneagent.llm.UniversalLlmClient
import com.example.phoneagent.voice.VoiceController

/** Simple manual DI container shared by the Activity, ViewModels and the overlay service. */
class PhoneAgentApp : Application() {
    lateinit var settingsStore: SecureSettingsStore
    lateinit var llm: UniversalLlmClient
    lateinit var voice: VoiceController
    lateinit var orchestrator: AgentOrchestrator

    override fun onCreate() {
        super.onCreate()
        settingsStore = SecureSettingsStore(this)
        llm = UniversalLlmClient()
        voice = VoiceController(this, settingsStore)
        orchestrator = AgentOrchestrator(this, settingsStore, llm, voice)
    }
}

val Context.phoneApp: PhoneAgentApp get() = applicationContext as PhoneAgentApp
