package com.bluey.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A listening session. What you say while holding the screen is recorded and sent to the computer, which
 *  transcribes it (Whisper) and has Claude answer in a speech bubble there, with a little cartoon chirp here.
 *  ponytail: no background listening; phone recognizers (Xiaomi's, Google's on MIUI) proved unreliable. Stream
 *  audio to the computer continuously if background context is wanted back. Main thread only. */
class LiveVoice(private val context: Context) {
    enum class State { asleep, waking, listening, asking, thinking, speaking }

    var state by mutableStateOf(State.asleep); private set

    /** Asks the user for the mic. Calls back with whether it was granted. */
    var requestMic: ((((Boolean) -> Unit)) -> Unit)? = null
    var isConnected: () -> Boolean = { false }
    /** Sends a question (text, or base64 16 kHz mono 16-bit PCM) to the computer; calls back with his answer
     *  (null if the computer went away). */
    var ask: ((String?, String?, (String?) -> Unit) -> Unit)? = null
    var onNotice: ((String) -> Unit)? = null
    var onStateChange: ((State) -> Unit)? = null
    var onSessionStart: (() -> Unit)? = null
    var onSessionEnd: (() -> Unit)? = null
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
    @Volatile private var recording: ByteArrayOutputStream? = null
    private var track: AudioTrack? = null
    private val chirper = Executors.newSingleThreadExecutor()
    private var pendingBuffers = 0

    private var holding = false
    private var waitingForAnswer = false
    private var sleepAfterReply = false

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
            if (holding) startRecording()
            moveTo(if (holding) State.asking else State.listening)
        }
    }

    fun sleep() {
        Log.i(TAG, "sleep (was $state)")
        val wasRunning = state != State.asleep
        recording = null
        val t = track
        track = null
        chirper.execute { t?.runCatching { stop(); release() } }
        if (wasRunning) onSessionEnd?.invoke()
        holding = false; waitingForAnswer = false; sleepAfterReply = false
        pendingBuffers = 0; level = 0.0
        moveTo(State.asleep)
    }

    /** Says a quick hi (for checking the chirp volume). */
    fun sayHi() {
        if (state == State.asleep) { wake(); return }
        send("Say a quick, cheerful hi in under eight words.", null)
    }

    // Hold to ask

    /** You pressed and held the screen: what you say now is recorded as the question. */
    fun beginAsk() {
        holding = true
        if (state == State.asleep) { wake(); return }
        if (state == State.waking) return
        startRecording()
        if (state == State.listening || state == State.speaking) moveTo(State.asking)
    }

    /** You let go: the recording goes to the computer, and he answers. */
    fun endAsk() {
        if (!holding) return
        holding = false
        val audio = recording?.toByteArray()
        recording = null
        if (state == State.asleep || state == State.waking || audio == null) return
        if (audio.size < REC_RATE * 2 / 3) {  // under a third of a second: a tap, not a question
            onNotice?.invoke("Hold the screen while you talk.")
            moveTo(State.listening)
            return
        }
        send(null, Base64.encodeToString(audio, Base64.NO_WRAP))
    }

    @SuppressLint("MissingPermission")  // only called once the mic is granted
    private fun startRecording() {
        if (recording != null) return
        val out = ByteArrayOutputStream()
        recording = out
        Thread {
            val size = maxOf(AudioRecord.getMinBufferSize(REC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, REC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                main.post { onNotice?.invoke("I can't use the microphone right now.") }
                return@Thread
            }
            val buf = ByteArray(size)
            rec.startRecording()
            while (recording === out && out.size() < REC_RATE * 2 * 60) {  // a minute at most
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) out.write(buf, 0, n)  // ByteArrayOutputStream is synchronized
            }
            rec.stop(); rec.release()
        }.start()
    }

    private fun send(text: String?, audio: String?) {
        val ask = ask ?: return
        moveTo(State.thinking)
        waitingForAnswer = true
        ask(text, audio) { answer ->
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
        private const val REC_RATE = 16_000
    }
}
