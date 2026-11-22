package com.example.phoneagent.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.example.phoneagent.data.AgentSettings
import com.example.phoneagent.data.SecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** STT (SpeechRecognizer) + TTS (TextToSpeech). All platform calls are marshalled onto the main thread. */
class VoiceController(
    private val ctx: Context,
    private val settingsStore: SecureSettingsStore,
) {
    private val main = Handler(Looper.getMainLooper())

    val listening = MutableStateFlow(false)
    val partial = MutableStateFlow("")
    val handsFree = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private var recognizer: SpeechRecognizer? = null
    private var onResult: ((String) -> Unit)? = null
    @Volatile private var speaking = false
    @Volatile private var ttsReady = false
    @Volatile private var listenAfterSpeech: ((String) -> Unit)? = null
    private var tts: TextToSpeech? = null
    private var appliedPersona = ""

    init {
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { speaking = true }
                    override fun onDone(utteranceId: String?) { speaking = false; onSpeechFinished() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { speaking = false; onSpeechFinished() }
                })
            }
        }
    }

    // ---------------- STT ----------------

    fun startListening(callback: (String) -> Unit) {
        onResult = callback
        main.post { begin() }
    }

    fun stopListening() {
        main.post {
            runCatching { recognizer?.stopListening() }
            listening.value = false
        }
    }

    fun setHandsFree(enabled: Boolean, callback: (String) -> Unit) {
        handsFree.value = enabled
        if (enabled) startListening(callback) else stopListening()
    }

    private fun begin() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error.value = "Microphone permission not granted"
            handsFree.value = false
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            error.value = "Speech recognition is not available on this device"
            handsFree.value = false
            return
        }
        error.value = null
        runCatching { recognizer?.destroy() }
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(listener) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        partial.value = ""
        listening.value = true
        recognizer?.startListening(intent)
    }

    private fun scheduleRestart() {
        if (!handsFree.value) return
        main.postDelayed({
            if (handsFree.value && !listening.value && !speaking) begin()
        }, 700)
    }

    /** After a spoken question, open the mic so the user can answer by voice (two-way conversation). */
    private fun onSpeechFinished() {
        val cb = listenAfterSpeech
        if (cb != null) {
            listenAfterSpeech = null
            startListening(cb)
        } else {
            scheduleRestart()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            listening.value = false
            partial.value = ""
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) onResult?.invoke(text)
            scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partial.value = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
        }

        override fun onError(code: Int) {
            listening.value = false
            partial.value = ""
            if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                error.value = "Microphone permission not granted"
                handsFree.value = false
            } else scheduleRestart()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------------- TTS ----------------

    /** Speaks [text] if voice is on. [flush] = true interrupts current speech; false queues behind it. */
    fun speak(text: String, flush: Boolean = true) {
        if (!settingsStore.settings.value.tts || !ttsReady || text.isBlank()) return
        main.post {
            runCatching { recognizer?.stopListening() }
            listening.value = false
            applyVoice(settingsStore.settings.value)
            tts?.speak(
                text.take(500),
                if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "agent",
            )
        }
    }

    /** Applies the persona's pitch, speech rate and (best effort) male / female voice. Main thread only. */
    private fun applyVoice(s: AgentSettings) {
        val engine = tts ?: return
        val persona = s.personaEnum
        engine.setPitch(s.voicePitch.coerceIn(0.5f, 2.0f))
        engine.setSpeechRate(s.voiceRate.coerceIn(0.5f, 2.0f))
        if (appliedPersona != persona.name) {
            appliedPersona = persona.name
            runCatching {
                val lang = Locale.getDefault().language
                val candidates = engine.voices.orEmpty().filter { it.locale.language == lang && !it.isNetworkConnectionRequired }
                val match = candidates.firstOrNull { v ->
                    val n = v.name.lowercase()
                    if (persona.female) n.contains("female") else (n.contains("male") && !n.contains("female"))
                }
                if (match != null) engine.setVoice(match)
            }
        }
    }

    /** Plays a sample line with the given (possibly unsaved) settings so the persona can be auditioned. */
    fun speakPreview(s: AgentSettings) {
        if (!ttsReady) return
        main.post {
            applyVoice(s)
            val p = s.personaEnum
            tts?.speak("Hello ${p.callName}. I am ${p.label}. All systems are online.", TextToSpeech.QUEUE_FLUSH, null, "preview")
        }
    }

    /**
     * Speaks a question and then listens for the spoken answer. With voice muted (or TTS unavailable)
     * the mic opens immediately instead.
     */
    fun askAndListen(text: String, callback: (String) -> Unit) {
        if (!settingsStore.settings.value.tts || !ttsReady) {
            startListening(callback)
            return
        }
        listenAfterSpeech = callback
        speak(text)
    }

    fun stopSpeaking() {
        listenAfterSpeech = null
        main.post { tts?.stop(); speaking = false }
    }

    fun shutdown() {
        main.post {
            runCatching { recognizer?.destroy() }
            tts?.shutdown()
        }
    }
}
