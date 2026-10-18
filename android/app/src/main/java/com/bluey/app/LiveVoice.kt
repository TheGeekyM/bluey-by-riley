package com.bluey.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A listening session. Once he's awake the phone's speech recognition runs the whole time: everything you say goes
 *  to the computer as background context, and what you say while holding the screen is the question. Claude runs on
 *  the computer and answers in a speech bubble there, with a little cartoon chirp here. Main thread only. */
class LiveVoice(private val context: Context) {
    enum class State { asleep, waking, listening, asking, thinking, speaking }

    var state by mutableStateOf(State.asleep); private set

    /** Asks the user for the mic. Calls back with whether it was granted. */
    var requestMic: ((((Boolean) -> Unit)) -> Unit)? = null
    var isConnected: () -> Boolean = { false }
    /** Something said in the background, for context. */
    var heard: ((String) -> Unit)? = null
    /** Sends a question to the computer; calls back with his answer (null if the computer went away). */
    var ask: ((String, (String?) -> Unit) -> Unit)? = null
    var onNotice: ((String) -> Unit)? = null
    var onStateChange: ((State) -> Unit)? = null
    var onSessionStart: (() -> Unit)? = null
    var onSessionEnd: (() -> Unit)? = null
    var onUserTurn: ((String, Boolean) -> Unit)? = null
    var onUserWords: ((String, String, Boolean) -> Unit)? = null
    var onReply: ((String) -> Unit)? = null

    /** Loudness of his chirp right now, 0…1. */
    @Volatile var level = 0.0; private set

    private val prefs = context.getSharedPreferences("bluey", Context.MODE_PRIVATE)
    var volume: Double
        get() = prefs.getFloat("volume", 1f).toDouble()
        set(value) {
            prefs.edit().putFloat("volume", value.toFloat()).apply()
            track?.setVolume(value.toFloat())
        }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var track: AudioTrack? = null
    private val chirper = Executors.newSingleThreadExecutor()
    private var pendingBuffers = 0

    private var holding = false
    /** Let go of the screen: waiting for the recognizer's last words before asking. */
    private var askPending = false
    private var question = ""
    private var partial = ""
    private var waitingForAnswer = false
    private var sleepAfterReply = false
    private val finishAskLater = Runnable { finishAsk() }

    private fun moveTo(new: State) {
        if (new == state) return
        state = new
        onStateChange?.invoke(new)
    }

    // Wake and sleep

    fun toggle() = if (state == State.asleep) wake() else sleep()

    fun wake() {
        if (state != State.asleep) return
        if (!isConnected()) {
            onNotice?.invoke("Connect to your computer to talk to him.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onNotice?.invoke("This phone has no speech recognition. Install the Google app.")
            return
        }
        moveTo(State.waking)
        val askMic = requestMic ?: { done -> done(true) }
        askMic { granted ->
            if (!granted) {
                onNotice?.invoke("I need the microphone. Turn it on for Bluey in Android's Settings.")
                moveTo(State.asleep)
                return@askMic
            }
            if (state != State.waking) return@askMic
            startChirps()
            onSessionStart?.invoke()
            chirp(2)
            startRecognizer()
            moveTo(if (holding) State.asking else State.listening)
        }
    }

    fun sleep() {
        Log.i(TAG, "sleep (was $state)")
        val wasRunning = state != State.asleep
        main.removeCallbacks(finishAskLater)
        recognizer?.destroy()
        recognizer = null
        val t = track
        track = null
        chirper.execute { t?.runCatching { stop(); release() } }
        if (wasRunning) onSessionEnd?.invoke()
        holding = false; askPending = false; waitingForAnswer = false; sleepAfterReply = false
        question = ""; partial = ""; pendingBuffers = 0; level = 0.0
        moveTo(State.asleep)
    }

    /** Says a quick hi (for checking the chirp volume). */
    fun sayHi() {
        if (state == State.asleep) { wake(); return }
        send("Say a quick, cheerful hi in under eight words.")
    }

    // Hold to ask

    /** You pressed and held the screen: what you say now is the question. */
    fun beginAsk() {
        holding = true
        question = ""
        if (state == State.asleep) { wake(); return }
        if (state == State.listening || state == State.speaking) moveTo(State.asking)
    }

    /** You let go: once the recognizer hands over your last words, he answers. */
    fun endAsk() {
        if (!holding) return
        holding = false
        if (state == State.asleep || state == State.waking) return
        askPending = true
        recognizer?.stopListening()  // → onResults / onError → finishAsk
        main.postDelayed(finishAskLater, 3000)  // some recognizers never call back
    }

    private fun finishAsk() {
        if (!askPending) return
        if (partial.isNotBlank()) deliver(partial)  // words it heard but never finalized
        askPending = false
        main.removeCallbacks(finishAskLater)
        val q = question.trim()
        question = ""
        listen()
        if (q.isEmpty()) {
            onNotice?.invoke("I didn't catch that.")
            moveTo(State.listening)
            return
        }
        send(q)
    }

    private fun send(q: String) {
        val ask = ask ?: return
        moveTo(State.thinking)
        waitingForAnswer = true
        ask(q) { answer ->
            if (state == State.asleep) return@ask
            waitingForAnswer = false
            if (answer == null) onNotice?.invoke("The computer didn't answer.")
            else onReply?.invoke(answer)
            finishIfQuiet()
        }
    }

    /** The computer started writing his reply. */
    fun replying() {
        if (state == State.asleep) return
        moveTo(State.speaking)
        chirp(Random.nextInt(3, 6))
    }

    /** He said goodbye: sleep once the reply is in. */
    fun sleepAfterReply() {
        sleepAfterReply = true
        finishIfQuiet()
    }

    /** Back to listening once he's replied and chirped (or asleep, if he said goodbye). */
    private fun finishIfQuiet() {
        if (pendingBuffers != 0 || waitingForAnswer) return
        if (sleepAfterReply) { sleep(); return }
        if (state == State.speaking || state == State.thinking) moveTo(if (holding) State.asking else State.listening)
    }

    // Speech recognition: one utterance at a time, restarted after each, so it's always listening.

    private fun startRecognizer() {
        // Prefer Google's recognizer: some phones default to one that only does Chinese well.
        val google = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }?.serviceInfo
        val r = if (google != null) SpeechRecognizer.createSpeechRecognizer(context, ComponentName(google.packageName, google.name))
        else SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(results: Bundle?) {
                best(results)?.let { partial = it }
            }
            override fun onResults(results: Bundle?) {
                Log.i(TAG, "heard: ${best(results)}")
                if (recognizer !== r) return
                partial = ""
                best(results)?.let(::deliver)
                if (askPending) finishAsk() else listen()
            }
            override fun onError(error: Int) {
                Log.i(TAG, "recognizer error $error")
                if (recognizer !== r) return
                if (askPending) { finishAsk(); return }
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    onNotice?.invoke("I need the microphone. Turn it on for Bluey in Android's Settings.")
                    sleep()
                    return
                }
                // Silence, a timeout or a busy recognizer: just start again in a moment.
                main.postDelayed({ if (recognizer === r) listen() }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800 else 250)
            }
        })
        Log.i(TAG, "recognizer: ${google?.packageName ?: "system default"}")
        recognizer = r
        listen()
    }

    private fun best(results: Bundle?) =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun listen() {
        val r = recognizer ?: return
        partial = ""
        r.cancel()
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // The phone's language; some recognizers (Xiaomi's) otherwise assume Chinese.
            val language = java.util.Locale.getDefault().toLanguageTag()
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        })
    }

    /** Words the recognizer finished: part of the question while you hold, background context otherwise. */
    private fun deliver(text: String) {
        val asked = holding || askPending
        val id = UUID.randomUUID().toString()
        onUserTurn?.invoke(id, asked)
        onUserWords?.invoke(id, text, asked)
        if (asked) question += " $text" else heard?.invoke(text)
    }

    // Chirps

    private fun startChirps() {
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().apply { setVolume(volume.toFloat()); play() }
    }

    /** A tiny cartoon chirp: a few soft, round, bell-like notes from a happy pentatonic scale,
     *  each with a little upward "boop" at the start, like a small creature humming. */
    private fun chirp(syllables: Int) {
        val track = track ?: return
        val rate = RATE.toDouble()
        // C major pentatonic, two octaves up high (C6…E7), so it always sounds sweet together.
        val scale = doubleArrayOf(1046.5, 1174.7, 1318.5, 1568.0, 1760.0, 2093.0, 2349.3, 2637.0)
        var index = Random.nextInt(1, 4)
        val samples = ArrayList<Float>()
        for (i in 0 until syllables) {
            if (i > 0) index = (index + listOf(-1, 1, 1, 2).random()).coerceIn(0, scale.size - 1)
            val note = scale[index] * 0.5  // drop an octave: rounder, less piercing
            val duration = if (i == syllables - 1) 0.13 else Random.nextDouble(0.07, 0.09)
            val count = (duration * rate).toInt()
            var phase = 0.0
            for (n in 0 until count) {
                val time = n / rate
                val t = n.toDouble() / count
                // Scoops up into the note over the first 25 ms, with a gentle wobble on the last one.
                val scoop = 1 - 0.18 * exp(-time / 0.012)
                val wobble = if (i == syllables - 1) 1 + 0.012 * sin(time * 2 * PI * 18) else 1.0
                phase += 2 * PI * note * scoop * wobble / rate
                val attack = minOf(1.0, time / 0.006)
                val envelope = attack * exp(-t * 3.2) * (1 - t.pow(6))
                val wave = sin(phase) + 0.12 * sin(2 * phase)
                samples += (wave * envelope * 0.26).toFloat()
            }
            repeat((rate * 0.028).toInt()) { samples += 0f }
        }
        val buffer = samples.toFloatArray()
        pendingBuffers += 1
        chirper.execute {
            // Blocking writes pace themselves to playback, so the level tracks what you hear.
            var offset = 0
            while (offset < buffer.size) {
                val n = minOf(1024, buffer.size - offset)
                var sum = 0f
                for (k in offset until offset + n) sum += buffer[k] * buffer[k]
                level = minOf(1.0, sqrt(sum / n) * 5.0)
                if (track.write(buffer, offset, n, AudioTrack.WRITE_BLOCKING) < 0) break
                offset += n
            }
            main.post {
                pendingBuffers = maxOf(0, pendingBuffers - 1)
                if (pendingBuffers == 0) level = 0.0
                finishIfQuiet()
            }
        }
    }

    companion object {
        const val RATE = 24_000
        private const val TAG = "Bluey"
    }
}
