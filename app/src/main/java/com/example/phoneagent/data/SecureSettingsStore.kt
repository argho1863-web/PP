package com.example.phoneagent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProviderType { OPENAI, GEMINI, ANTHROPIC }

/** Voice personas (inspired by the male/female voice switch in GauravSingh9356/J.A.R.V.I.S). */
enum class Persona(val label: String, val callName: String, val pitch: Float, val rate: Float, val female: Boolean) {
    JARVIS("J.A.R.V.I.S", "Sir", 0.80f, 0.92f, false),
    FRIDAY("F.R.I.D.A.Y", "Boss", 1.20f, 1.05f, true);

    companion object {
        fun from(name: String): Persona = entries.firstOrNull { it.name == name } ?: JARVIS
    }
}

data class ProviderPreset(
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val models: List<String>,
)

object ProviderPresets {
    const val CUSTOM = "Custom OpenAI-Compatible"
    const val LOCAL = "Local Gateway (127.0.0.1:3001)"

    val all = listOf(
        ProviderPreset("OpenRouter", ProviderType.OPENAI, "https://openrouter.ai/api/v1",
            listOf("openai/gpt-4o", "google/gemini-2.5-flash", "deepseek/deepseek-chat")),
        ProviderPreset("OpenAI", ProviderType.OPENAI, "https://api.openai.com/v1",
            listOf("gpt-4o", "gpt-4o-mini")),
        ProviderPreset("Google Gemini", ProviderType.GEMINI, "https://generativelanguage.googleapis.com/v1beta",
            listOf("gemini-2.5-flash", "gemini-2.5-pro")),
        ProviderPreset("Anthropic Claude", ProviderType.ANTHROPIC, "https://api.anthropic.com/v1",
            listOf("claude-sonnet-4-6", "claude-haiku-5-5")),
        ProviderPreset("Groq", ProviderType.OPENAI, "https://api.groq.com/openai/v1",
            listOf("llama-3.3-70b-versatile")),
        ProviderPreset("DeepSeek", ProviderType.OPENAI, "https://api.deepseek.com/v1",
            listOf("deepseek-chat")),
        // For a pooled gateway such as FreeLLMAPI running on the phone itself (e.g. in Termux). Cleartext http is allowed.
        ProviderPreset(LOCAL, ProviderType.OPENAI, "http://127.0.0.1:3001/v1",
            listOf("auto")),
        ProviderPreset(CUSTOM, ProviderType.OPENAI, "http://10.0.2.2:11434/v1",
            listOf("llama3.1")),
    )

    val quickModels = listOf("gpt-4o", "claude-sonnet-4-6", "gemini-2.5-flash", "deepseek-chat")

    fun byName(name: String): ProviderPreset = all.firstOrNull { it.name == name } ?: all.last()
}

data class AgentSettings(
    val presetName: String = "OpenRouter",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val apiKey: String = "",
    val model: String = "openai/gpt-4o",
    val vision: Boolean = false,
    val tts: Boolean = true,
    val persona: String = Persona.JARVIS.name,
    val voicePitch: Float = Persona.JARVIS.pitch,
    val voiceRate: Float = Persona.JARVIS.rate,
) {
    val providerType: ProviderType get() = ProviderPresets.byName(presetName).type
    val personaEnum: Persona get() = Persona.from(persona)

    /** Local / custom endpoints often need no API key. */
    val keyOptional: Boolean get() = presetName == ProviderPresets.CUSTOM || presetName == ProviderPresets.LOCAL
}

/** All settings (and especially the API key) live in EncryptedSharedPreferences backed by the Android Keystore. */
class SecureSettingsStore(context: Context) {
    private companion object { const val FILE = "agent_secure_prefs" }

    private val prefs: SharedPreferences = createPrefs(context)

    private fun createPrefs(context: Context): SharedPreferences {
        fun create() = EncryptedSharedPreferences.create(
            context,
            FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return try {
            create()
        } catch (e: Exception) {
            // Keystore key lost/corrupted (restore, OS upgrade...): wipe the unreadable file and start clean.
            context.deleteSharedPreferences(FILE)
            create()
        }
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    private fun load(): AgentSettings {
        val d = AgentSettings()
        return AgentSettings(
            presetName = prefs.getString("preset", d.presetName) ?: d.presetName,
            baseUrl = prefs.getString("baseUrl", d.baseUrl) ?: d.baseUrl,
            apiKey = prefs.getString("apiKey", "") ?: "",
            model = prefs.getString("model", d.model) ?: d.model,
            vision = prefs.getBoolean("vision", d.vision),
            tts = prefs.getBoolean("tts", d.tts),
            persona = prefs.getString("persona", d.persona) ?: d.persona,
            voicePitch = prefs.getFloat("voicePitch", d.voicePitch),
            voiceRate = prefs.getFloat("voiceRate", d.voiceRate),
        )
    }

    fun save(s: AgentSettings) {
        prefs.edit()
            .putString("preset", s.presetName)
            .putString("baseUrl", s.baseUrl.trim())
            .putString("apiKey", s.apiKey.trim())
            .putString("model", s.model.trim())
            .putBoolean("vision", s.vision)
            .putBoolean("tts", s.tts)
            .putString("persona", s.persona)
            .putFloat("voicePitch", s.voicePitch)
            .putFloat("voiceRate", s.voiceRate)
            .apply()
        _settings.value = s.copy(baseUrl = s.baseUrl.trim(), apiKey = s.apiKey.trim(), model = s.model.trim())
    }

    fun update(f: (AgentSettings) -> AgentSettings) = save(f(_settings.value))
}
