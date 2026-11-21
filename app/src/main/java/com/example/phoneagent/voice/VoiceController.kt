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
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { speaking = true }
                    override fun onDone(utteranceId: String?) { speaking = false; scheduleRestart() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { speaking = false; scheduleRestart() }
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

    fun speak(text: String) {
        if (!settingsStore.settings.value.tts || !ttsReady || text.isBlank()) return
        main.post {
            runCatching { recognizer?.stopListening() }
            listening.value = false
            tts?.speak(text.take(500), TextToSpeech.QUEUE_FLUSH, null, "agent")
        }
    }

    fun stopSpeaking() {
        main.post { tts?.stop(); speaking = false }
    }

    fun shutdown() {
        main.post {
            runCatching { recognizer?.destroy() }
            tts?.shutdown()
        }
    }
}
