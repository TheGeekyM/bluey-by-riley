package com.bluey.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.RoundedCorner
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private lateinit var link: MacLink
    private lateinit var live: LiveVoice
    private lateinit var store: SessionStore
    private lateinit var notes: NotesSync
    private val animator = FaceAnimator()
    private var micDone: ((Boolean) -> Unit)? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDone?.invoke(granted)
        micDone = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full screen, no system bars, screen stays awake.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        link = MacLink(this)
        store = SessionStore(filesDir)
        live = LiveVoice(this)
        notes = NotesSync(store, link)
        wire()

        setContent {
            RootScreen(link, live, store, animator, screenCornerRadius(), onConnected = { store.resyncRecent() })
        }
    }

    override fun onStart() {
        super.onStart()
        link.start()
    }

    override fun onStop() {
        super.onStop()
        link.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) live.sleep()
    }

    /** The screen's own corner radius in px, so the mode border lines up with the glass. */
    private fun screenCornerRadius(): Float? =
        if (Build.VERSION.SDK_INT >= 31) window.decorView.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
        else null

    /** Connects the live voice to the Mac: keys, tools, captions and wake/sleep. */
    private fun wire() {
        link.onFace = { animator.receive(it, System.nanoTime() / 1e9) }
        animator.localTalk = { live.level }
        live.requestMic = { done ->
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) done(true)
            else { micDone = done; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        }
        live.isConnected = { link.connected }
        live.ask = { question, audio, done -> link.request(Packet(command = "ask", text = question, audio = audio)) { done(it?.text) } }
        live.onNotice = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        live.onSessionStart = { store.start() }
        store.onSync = { notes.send(it) }
        live.onSessionEnd = { store.end() }
        live.onReply = { store.reply(it) }
        live.onStateChange = { state ->
            when (state) {
                LiveVoice.State.asleep -> { animator.awake = false; animator.localMood = null; link.send(Packet(command = "asleep")) }
                LiveVoice.State.waking -> { animator.awake = true; animator.localMood = Mood.happy }
                LiveVoice.State.listening -> { animator.awake = true; animator.localMood = null; link.send(Packet(command = "awake")) }
                LiveVoice.State.asking -> { animator.awake = true; animator.localMood = Mood.listening; link.send(Packet(command = "awake")) }  // all ears while you hold
                LiveVoice.State.thinking -> animator.localMood = Mood.thinking
                LiveVoice.State.speaking -> animator.localMood = Mood.talking
            }
        }
        link.onCommand = { command, packet ->
            when (command) {
                "wake" -> live.wake()
                "sleep" -> live.sleepAfterReply()
                "replying" -> live.replying()
                "asked" -> packet.text?.let { store.heard(java.util.UUID.randomUUID().toString(), it, asked = true) }
                "report" -> packet.text?.let { store.report(it) }
            }
        }
    }
}
