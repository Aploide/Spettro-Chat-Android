package to.eyed.spettro.chat.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Dictation for the composer, on top of the platform [SpeechRecognizer]
 * (Google's on-device/hybrid ASR - fast, streaming, and recognizing the
 * device languages; 14+ adds automatic language detection and switching).
 *
 * The recognizer ends a session at every pause, so this keeps a loop going:
 * each final segment is accumulated and listening restarts until the user
 * confirms or cancels. Confirming waits briefly for the in-flight segment,
 * then emits the whole transcript as a one-shot [State.result].
 *
 * Main-thread only, as SpeechRecognizer itself requires.
 */
class SpeechTranscriber(private val context: Context) {

    data class State(
        /** True while the composer should show the recording pill. */
        val active: Boolean = false,
        /** True between confirm and the final segment landing. */
        val finishing: Boolean = false,
        /** Rolling mic levels in 0..1, newest last; feeds the waveform. */
        val levels: List<Float> = emptyList(),
        /** One-shot: the finished transcript to insert into the input. */
        val result: String? = null,
        /** One-shot: a user-facing failure message. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private val segments = StringBuilder()
    private var partial = ""
    private val main = Handler(Looper.getMainLooper())
    private var finishTimeout: Runnable? = null

    fun start() {
        if (_state.value.active) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = State(error = "Speech recognition isn't available on this device.")
            return
        }
        segments.setLength(0)
        partial = ""
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
        }
        _state.value = State(active = true)
        listen()
    }

    /** Stop and deliver the transcript (after the in-flight segment, if any). */
    fun finish() {
        val s = _state.value
        if (!s.active || s.finishing) return
        _state.update { it.copy(finishing = true) }
        recognizer?.stopListening()
        // The recognizer normally answers stopListening() with onResults or
        // onError; if neither lands, fall back to what's already accumulated.
        finishTimeout = Runnable { deliver() }.also { main.postDelayed(it, 1500L) }
    }

    /** Discard the session and everything heard so far. */
    fun cancel() {
        clearTimeout()
        releaseRecognizer()
        _state.value = State()
    }

    fun consumeResult() = _state.update { it.copy(result = null) }

    fun consumeError() = _state.update { it.copy(error = null) }

    /** Release the recognizer when the host UI leaves composition. */
    fun destroy() {
        clearTimeout()
        releaseRecognizer()
        _state.value = State()
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Pauses shouldn't end the dictation; the restart loop covers
            // engines that stop anyway.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                    RecognizerIntent.LANGUAGE_SWITCH_BALANCED,
                )
            }
        }
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onRmsChanged(rmsdB: Float) {
            // The recognizer reports roughly -2..10 dB.
            val level = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
            _state.update { s ->
                if (!s.active) s else s.copy(levels = (s.levels + level).takeLast(96))
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) partial = text
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                if (segments.isNotEmpty()) segments.append(' ')
                segments.append(text.trim())
            }
            partial = ""
            val s = _state.value
            if (s.finishing) deliver() else if (s.active) listen()
        }

        override fun onError(error: Int) {
            val s = _state.value
            if (!s.active) return
            if (s.finishing) {
                deliver()
                return
            }
            when (error) {
                // A quiet stretch, not a failure: keep the session going.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> listen()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.cancel()
                    listen()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    fail("Microphone permission is needed to dictate.")
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> fail("Network error during transcription. Try again.")
                else -> fail("Couldn't transcribe. Try again.")
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun deliver() {
        clearTimeout()
        val text = buildString {
            append(segments)
            if (partial.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append(partial.trim())
            }
        }.trim()
        releaseRecognizer()
        _state.value = State(result = text.takeIf { it.isNotEmpty() })
    }

    private fun fail(message: String) {
        clearTimeout()
        releaseRecognizer()
        _state.value = State(error = message)
    }

    private fun clearTimeout() {
        finishTimeout?.let(main::removeCallbacks)
        finishTimeout = null
    }

    private fun releaseRecognizer() {
        recognizer?.destroy()
        recognizer = null
        segments.setLength(0)
        partial = ""
    }
}
