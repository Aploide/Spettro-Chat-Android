package to.eyed.spettro.chat.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Reads assistant messages aloud with the platform [TextToSpeech] service -
 * on-device, multilingual, and adding nothing to the APK, in the same spirit
 * as [SpeechTranscriber] on the input side.
 *
 * One message plays at a time; [speakingKey] holds its key so the UI can
 * flip that message's button into a stop control. The spoken language is
 * detected per message with the on-device [TextLanguage] classifier, so a
 * reply in another language is read with the right voice.
 */
class TtsSpeaker(private val context: Context) {

    /** Key of the message currently being read, or null when silent. */
    private val _speakingKey = MutableStateFlow<String?>(null)
    val speakingKey: StateFlow<String?> = _speakingKey.asStateFlow()

    /** One-shot user-facing failure message. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    @Volatile private var lastUtteranceId: String? = null
    // Language detection and speak calls run off the main thread; a single
    // lane keeps them ordered.
    private val executor = Executors.newSingleThreadExecutor()

    fun toggle(key: String, text: String) {
        if (_speakingKey.value == key) {
            stop()
            return
        }
        val spoken = markdownToSpeech(text)
        if (spoken.isBlank()) return
        _speakingKey.value = key
        ensureEngine { speakNow(key, spoken) }
    }

    fun stop() {
        tts?.stop()
        _speakingKey.value = null
    }

    fun consumeError() = _error.update { null }

    /** Release the engine when the host UI leaves composition. */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
        _speakingKey.value = null
        executor.shutdown()
    }

    /** The engine binds asynchronously; the first speak waits for it. */
    private fun ensureEngine(onReady: () -> Unit) {
        if (ready) {
            onReady()
            return
        }
        pending = onReady
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setOnUtteranceProgressListener(progressListener)
                    pending?.invoke()
                } else {
                    tts = null
                    _speakingKey.value = null
                    _error.value = "Text-to-speech isn't available on this device."
                }
                pending = null
            }
        }
    }

    private fun speakNow(key: String, text: String) {
        executor.execute {
            val engine = tts ?: return@execute
            val locale = detectLocale(text) ?: Locale.getDefault()
            val supported = engine.setLanguage(locale)
            if (supported == TextToSpeech.LANG_MISSING_DATA ||
                supported == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.setLanguage(Locale.getDefault())
            }
            // The user may have hit stop while detection ran.
            if (_speakingKey.value != key) return@execute
            val parts = chunks(text)
            lastUtteranceId = "$key#${parts.size - 1}"
            parts.forEachIndexed { i, part ->
                val queue = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                engine.speak(part, queue, null, "$key#$i")
            }
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            if (utteranceId == lastUtteranceId) _speakingKey.value = null
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            _speakingKey.value = null
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            _speakingKey.value = null
        }
    }

    /** Best on-device language guess for the message, off the main thread. */
    private fun detectLocale(text: String): Locale? = try {
        val tcm = context.getSystemService(TextClassificationManager::class.java)
        val result = tcm?.textClassifier?.detectLanguage(
            TextLanguage.Request.Builder(text.take(300)).build(),
        )
        if (result != null && result.localeHypothesisCount > 0) {
            val best = result.getLocale(0)
            if (result.getConfidenceScore(best) >= 0.5f) best.toLocale() else null
        } else null
    } catch (_: Exception) {
        null
    }

    /** The engine caps a single utterance; long messages queue in pieces. */
    private fun chunks(text: String): List<String> {
        val max = TextToSpeech.getMaxSpeechInputLength() - 1
        if (text.length <= max) return listOf(text)
        val parts = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = window.lastIndexOfAny(charArrayOf('.', '!', '?', '\n', ' '))
                .takeIf { it > max / 2 } ?: max
            parts += rest.substring(0, cut + 1)
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) parts += rest
        return parts
    }
}

/**
 * Markdown read literally sounds like line noise; reduce a message to the
 * words worth hearing. Code blocks are skipped entirely, links keep only
 * their label, and list/emphasis/table markup is dropped.
 */
internal fun markdownToSpeech(md: String): String = md
    .replace(Regex("```[\\s\\S]*?(```|$)"), " ")
    .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " ")
    .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
    .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
    .replace(Regex("(?m)^\\s*([-*+]|\\d+\\.)\\s+"), "")
    .replace(Regex("(?m)^\\s*>\\s?"), "")
    .replace(Regex("(?m)^\\s*[|\\s:-]+\\s*$"), " ")
    .replace("|", ", ")
    .replace(Regex("[*_~`]+"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
