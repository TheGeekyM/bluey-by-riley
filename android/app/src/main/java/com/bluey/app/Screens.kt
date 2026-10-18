@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.bluey.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date

// Colors and type from the design.

fun hex(rgb: Int, opacity: Float = 1f) = Color(0xFF000000 or rgb.toLong()).copy(alpha = opacity)

private val inkSoft = hex(Palette.inkSoft)
private val panel = hex(Palette.panel)
private val green = hex(0x5BE49B)
private val berryBrush = Brush.linearGradient(Palette.gradient.map { hex(it) })

private val fredoka = FontFamily(Font(R.font.fredoka, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))))
private val plexSans = FontFamily(Font(R.font.plex_sans, FontWeight.Normal), Font(R.font.plex_sans, FontWeight.SemiBold,
    variationSettings = FontVariation.Settings(FontVariation.weight(600))))
private val plexMono = FontFamily(Font(R.font.plex_mono), Font(R.font.plex_mono_medium, FontWeight.Medium))

private fun fredoka(size: Int) = TextStyle(fontFamily = fredoka, fontWeight = FontWeight.Bold, fontSize = size.sp)
private fun sans(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = plexSans, fontWeight = weight, fontSize = size.sp)
private fun mono(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = plexMono, fontWeight = weight, fontSize = size.sp)

private fun formatDate(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
private fun formatTime(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
private fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun RootScreen(link: MacLink, live: LiveVoice, store: SessionStore, animator: FaceAnimator, cornerRadiusPx: Float?, onConnected: () -> Unit) {
    var showApp by remember { mutableStateOf(false) }
    var showPairing by remember { mutableStateOf(true) }
    val view = LocalView.current

    LaunchedEffect(link.connected) {
        if (link.connected) {
            showPairing = false
            onConnected()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        FaceCanvas(animator, Modifier.fillMaxSize().pointerInput(Unit) {
            // Double tap: wake up / back to follow mode. Press and hold: ask him something; let go and he answers.
            var lastTap = 0L
            awaitEachGesture {
                awaitFirstDown()
                val quick = withTimeoutOrNull(300) { waitForUpOrCancellation(); true }  // a quick tap isn't a hold
                if (quick == true) {
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 350) { lastTap = 0; live.toggle() } else lastTap = now
                } else {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    live.beginAsk()
                    waitForUpOrCancellation()
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    live.endAsk()
                }
            }
        })

        ModeIndicator(live.state, cornerRadiusPx)

        SoundButton(link, live)

        // Bluey's app: sessions, transcripts and settings.
        FaintButton(Icons.Default.Menu, "Open Bluey", Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 62.dp)) { showApp = true }

        AnimatedVisibility(showPairing && !link.connected, enter = fadeIn(), exit = fadeOut()) {
            PairingScreen { showPairing = false }
        }

        if (showApp) {
            BackHandler { showApp = false }
            BlueyAppScreen(store, live, link) { showApp = false }
        }
    }
}

/** Keeps touches on a full-screen panel from reaching his face underneath (siblings below don't get hit). */
private fun Modifier.blockTouches() = pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }

@Composable
private fun FaceCanvas(animator: FaceAnimator, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = remember { FaceRenderer(ResourcesCompat.getFont(context, R.font.fredoka)) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }
    Canvas(modifier) {
        frame  // redraw every frame
        drawIntoCanvas { renderer.draw(animator.step(System.nanoTime() / 1e9), it.nativeCanvas, size.width, size.height) }
    }
}

@Composable
private fun FaintButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, bright: Boolean = false, onClick: () -> Unit) {
    Box(modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp).background(Color.White.copy(alpha = if (bright) 0.14f else 0.06f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(17.dp), tint = Color.White.copy(alpha = if (bright) 0.9f else 0.35f))
        }
    }
}

/** Shows which mode he's in: a solid border hugging the screen's rounded corners (blue while he listens), plus a small label. */
@Composable
private fun ModeIndicator(state: LiveVoice.State, cornerRadiusPx: Float?) {
    data class Look(val color: Color, val label: String?, val border: Dp, val pulse: Float)
    val blue = hex(0x4F8BFF)
    val look = when (state) {
        LiveVoice.State.asleep -> Look(inkSoft, null, 0.dp, 0f)
        LiveVoice.State.waking -> Look(hex(0xFFD66B), "Waking up", 6.dp, 1.6f)
        LiveVoice.State.listening -> Look(blue, "Listening · hold to ask", 10.dp, 0f)
        LiveVoice.State.asking -> Look(blue, "I'm all ears", 16.dp, 1.2f)
        LiveVoice.State.thinking -> Look(hex(0xC79BFF), "Thinking", 8.dp, 1.1f)
        LiveVoice.State.speaking -> Look(hex(0xFF9AD0), "Replying", 8.dp, 0f)
    }
    val radius = cornerRadiusPx?.let { with(LocalDensity.current) { it.toDp() } } ?: 40.dp
    val wave by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(((if (look.pulse > 0) 1000 / look.pulse else 1000f) / 2).toInt(), easing = LinearEasing), RepeatMode.Reverse), label = "wave",
    )
    Box(Modifier.fillMaxSize()) {
        if (look.border > 0.dp) {
            Box(Modifier.fillMaxSize().alpha(if (look.pulse > 0) 0.65f + 0.35f * wave else 1f)
                .border(BorderStroke(look.border, look.color), RoundedCornerShape(radius)))
        }
        Row(
            Modifier.padding(top = 14.dp + look.border, start = 24.dp + look.border)
                .heightIn(min = 34.dp).widthIn(min = 34.dp)
                .background(if (look.label == null) Color.White.copy(alpha = 0.06f) else look.color, RoundedCornerShape(50))
                .padding(horizontal = if (look.label == null) 0.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            if (look.label != null) Text(look.label, style = fredoka(16), color = Color.White)
            else Text("◉", style = sans(14), color = look.color.copy(alpha = 0.7f))  // following your mouse
        }
    }
}

/** A tiny, quiet button in the top-right corner: chirp volume, a test hi, and which Mac to pair with. */
@Composable
private fun SoundButton(link: MacLink, live: LiveVoice) {
    var open by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(live.volume.toFloat()) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lastTouch, open) {
        // Tuck the panel away after a few quiet seconds so it never ends up in a shot.
        if (open) { delay(6000); open = false }
    }
    Column(Modifier.fillMaxSize().padding(top = 10.dp, end = 14.dp), horizontalAlignment = Alignment.End) {
        FaintButton(if (open) Icons.Default.Close else Icons.Default.Notifications, if (open) "Close sound settings" else "Sound settings", bright = open) {
            open = !open; lastTouch = System.currentTimeMillis()
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 10.dp).width(260.dp).background(panel.copy(alpha = 0.95f), RoundedCornerShape(18.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    Text("CHIRP VOLUME", style = mono(12), color = inkSoft)
                    Spacer(Modifier.weight(1f))
                    Text("${(volume * 100).toInt()}%", style = mono(13), color = Color.White)
                }
                Slider(volume, { volume = it; live.volume = it.toDouble(); lastTouch = System.currentTimeMillis() },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = hex(Palette.berry2)))
                BerryButton(if (live.state == LiveVoice.State.asleep) "Wake him up" else "Say hi", enabled = link.connected) {
                    live.sayHi(); lastTouch = System.currentTimeMillis()
                }
                MacPicker(link) { lastTouch = System.currentTimeMillis() }
                if (!link.connected) Text("Connect to your computer to talk to him.", style = sans(12), color = inkSoft)
            }
        }
    }
}

@Composable
private fun BerryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).alpha(if (enabled) 1f else 0.4f)
        .background(berryBrush, RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = sans(15, FontWeight.SemiBold), color = Color.White)
    }
}

/** Every Mac on this Wi-Fi, when there's more than one. */
@Composable
private fun MacPicker(link: MacLink, onPick: () -> Unit = {}) {
    if (link.macs.size <= 1) return
    Column {
        Text("COMPUTER", style = mono(12), color = inkSoft)
        for (name in link.macs) {
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { link.choose(name); onPick() }, verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = sans(14), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (name == link.currentMac) Icon(if (link.connected) Icons.Default.Check else Icons.Default.MoreVert, null, tint = hex(Palette.berry2))
            }
        }
    }
}

@Composable
private fun MiniBluey(modifier: Modifier) = Canvas(modifier) { drawIntoCanvas { Blob.drawMini(it.nativeCanvas, size.width, size.height) } }

/** Shown until the phone finds the Mac: a sleepy blob and how to wake him up. */
@Composable
private fun PairingScreen(onPlay: () -> Unit) {
    val context = LocalContext.current
    val zPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.fredoka); fontVariationSettings = "'wght' 700"
        }
    }
    val t by rememberInfiniteTransition(label = "sleepy").animateFloat(0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(5700, easing = LinearEasing)), label = "t")
    Row(Modifier.fillMaxSize().background(Color.Black).blockTouches().padding(horizontal = 72.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally)) {
        Canvas(Modifier.size(220.dp, 190.dp)) {
            drawIntoCanvas { c ->
                val n = c.nativeCanvas
                val s = 1 + 0.02f * kotlin.math.sin(t)
                n.save(); n.scale(s, s, size.width / 2, size.height)
                val r = android.graphics.RectF(0f, 0f, size.width, size.height)
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                n.drawPath(Blob.path(r), p.apply { shader = Blob.shader(r); alpha = 230 })
                p.shader = null; p.alpha = 255
                val u = size.width / 220
                p.color = Palette.argb(Palette.ink)
                for (side in listOf(-1, 1)) {
                    val cx = size.width / 2 + side * 36 * u
                    n.drawRoundRect(cx - 23 * u, size.height / 2 - 21.5f * u, cx + 23 * u, size.height / 2 - 14.5f * u, 4 * u, 4 * u, p)
                }
                p.color = Palette.argb(Palette.nose)
                n.drawOval(size.width / 2 - 5 * u, size.height / 2 + 15 * u, size.width / 2 + 13 * u, size.height / 2 + 27 * u, p)
                n.restore()
                zPaint.color = Palette.argb(Palette.berry3); zPaint.textSize = 26 * u
                n.drawText("z", size.width / 2 + 110 * u, size.height / 2 - (95 + 4 * kotlin.math.sin(t * 1.5f)) * u, zPaint)
                zPaint.color = Palette.argb(Palette.berry4); zPaint.textSize = 18 * u
                n.drawText("z", size.width / 2 + 128 * u, size.height / 2 - (122 + 4 * kotlin.math.sin(t * 1.5f + 1)) * u, zPaint)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Wake me up from your computer", style = fredoka(32), color = hex(0xF4F1FA))
            Text("Run Bluey on your Mac or Ubuntu PC. Keep both on the same Wi-Fi and I'll find it.",
                style = sans(16), color = inkSoft, modifier = Modifier.widthIn(max = 380.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = hex(Palette.berry1), strokeWidth = 2.dp)
                Text("Looking for your computer", style = mono(13), color = inkSoft)
            }
            Box(Modifier.heightIn(min = 44.dp).background(panel, RoundedCornerShape(14.dp)).clickable(onClick = onPlay).padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center) {
                Text("Play without the computer", style = sans(15, FontWeight.SemiBold), color = hex(0xF4F1FA))
            }
        }
    }
}

/** Bluey's own app screen: start or end a session, past sessions and their transcripts, and settings. */
@Composable
private fun BlueyAppScreen(store: SessionStore, live: LiveVoice, link: MacLink, onClose: () -> Unit) {
    var openID by remember { mutableStateOf<String?>(null) }
    val opened = openID?.let { id -> store.sessions.firstOrNull { it.id == id } }
    if (opened != null) {
        BackHandler { openID = null }
        SessionDetail(opened, store) { openID = null }
        return
    }
    var volume by remember { mutableFloatStateOf(live.volume.toFloat()) }
    Row(Modifier.fillMaxSize().background(Color.Black).blockTouches()) {
        // Left: who he is, start/end, settings.
        Column(Modifier.width(300.dp).fillMaxHeight().background(panel.copy(alpha = 0.6f)).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniBluey(Modifier.size(54.dp, 48.dp))
                Column {
                    Text("Bluey", style = fredoka(28), color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).background(if (link.connected) green else inkSoft.copy(alpha = 0.5f), CircleShape))
                        Text(if (link.connected) link.macName ?: link.currentMac ?: "Computer" else "Looking for your computer",
                            style = sans(12), color = inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val asleep = live.state == LiveVoice.State.asleep
            val enabled = link.connected || !asleep
            Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).alpha(if (enabled) 1f else 0.5f)
                .background(if (asleep) berryBrush else Brush.linearGradient(listOf(hex(0xE5547A), hex(0xE5547A))), RoundedCornerShape(16.dp))
                .clickable(enabled = enabled) { live.toggle(); if (asleep) onClose() },  // back to his face when starting
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (asleep) Icon(Icons.Default.PlayArrow, null, tint = Color.White) else Box(Modifier.size(14.dp).background(Color.White, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(10.dp))
                Text(if (asleep) "Start session" else "End session", style = fredoka(18), color = Color.White)
            }
            Text(if (asleep) "He listens the whole session. Hold his face to ask something, let go and he answers."
                else "Session running. Hold his face to ask; everything you say is saved here.", style = sans(12), color = inkSoft)
            Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(Color.White.copy(alpha = 0.08f)))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CHIRP VOLUME", style = mono(11), color = inkSoft)
                Slider(volume, { volume = it; live.volume = it.toDouble() },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = hex(Palette.berry1)))
            }
            MacPicker(link)
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).clickable(onClick = onClose),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Face, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Back to his face", style = sans(15, FontWeight.SemiBold), color = Color.White)
            }
        }

        // Right: sessions.
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp)) {
                Text("Sessions", style = fredoka(24), color = Color.White)
                Spacer(Modifier.weight(1f))
                Text("${store.sessions.size}", style = mono(13), color = inkSoft)
            }
            if (store.sessions.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    MiniBluey(Modifier.size(40.dp, 35.dp))
                    Text("No sessions yet", style = fredoka(18), color = Color.White)
                    Text("Start one and your transcript and his replies show up here.", style = sans(13), color = inkSoft)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(store.sessions, key = { it.id }) { session ->
                        SessionRow(session, session.id == store.currentID) { openID = session.id }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: BlueySession, live: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatDate(session.started), style = sans(15, FontWeight.SemiBold), color = Color.White)
                if (live) Text("LIVE", style = mono(10, FontWeight.Medium), color = Color.Black,
                    modifier = Modifier.background(green, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Text(session.summary, style = sans(13), color = inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(duration(session.durationMs), style = mono(12), color = inkSoft)
            Text("${session.questionCount} asked", style = mono(11), color = hex(Palette.berry1))
        }
    }
}

/** A prompt for an agent (like Claude Code on the Mac) that points it at this session's notes files. */
private fun agentPrompt(session: BlueySession): String {
    val root = "~/Documents/Bluey Notes"
    val path = session.notesPath?.let { p -> p.indexOf("/Documents/Bluey Notes").takeIf { it >= 0 }?.let { "~" + p.substring(it) } ?: p }
        ?: "$root/ (the folder starting ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date(session.started))})"
    val date = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(session.started))
    return """I recorded a meeting with my Bluey app on $date. The notes are on my Mac in $path

- notes.md: the full transcript with timestamps and speaker labels (A, B, … per five-minute chunk), plus my questions to Bluey, its replies and research reports. Start here.
- session.json: the same data, structured.
- audio/: the original recording in five-minute chunks.

All my sessions are in $root/, one folder per session (see README.md there). Read the notes, then help me with: """
}

/** One session as a chat: what you said in grey, your questions in blue, his replies in white bubbles. */
@Composable
private fun SessionDetail(session: BlueySession, store: SessionStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val entries = session.displayEntries
    val list = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) list.animateScrollToItem(entries.size - 1) }

    Column(Modifier.fillMaxSize().background(Color.Black).blockTouches()) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Column(Modifier.weight(1f)) {
                Text(formatDate(session.started), style = fredoka(20), color = Color.White)
                val t = session.transcribing
                Text("${duration(session.durationMs)} · ${session.questionCount} asked" + if (t > 0) " · transcribing $t chunk${if (t == 1) "" else "s"}…" else "",
                    style = mono(11), color = inkSoft)
            }
            Row(Modifier.heightIn(min = 40.dp).background(hex(Palette.berry3), RoundedCornerShape(50)).clickable {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Bluey prompt", agentPrompt(session)))
                copied = true
            }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (copied) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text(if (copied) "Copied" else "Copy prompt for agent", style = sans(13, FontWeight.SemiBold), color = Color.White)
            }
            RoundIcon(Icons.Default.Share, "Share") {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, session.exportText)
                context.startActivity(Intent.createChooser(send, null))
            }
            RoundIcon(Icons.Default.Delete, "Delete session") { store.delete(session.id); onBack() }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries, key = { it.id }) { EntryView(it) }
            item { Spacer(Modifier.heightIn(min = 24.dp)) }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).background(Color.White.copy(alpha = 0.08f), CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

private fun speakerColor(speaker: String?): Color {
    val colors = intArrayOf(0x8FB3FF, 0x5BE49B, 0xFFB86B, 0xFF9AD0, 0xC79BFF, 0x6BE3E0)
    val first = speaker?.firstOrNull()?.code
    return if (first == null || speaker == "?") inkSoft else hex(colors[first % colors.size])
}

@Composable
private fun EntryView(entry: TranscriptEntry) {
    when (entry.kind) {
        TranscriptEntry.Kind.transcript -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(entry.speaker?.let { "Speaker $it" } ?: "Speaker", style = mono(11, FontWeight.Medium), color = speakerColor(entry.speaker),
                modifier = Modifier.width(78.dp).padding(top = 2.dp))
            Text(entry.text, style = sans(15), color = Color.White.copy(alpha = 0.92f), modifier = Modifier.weight(1f))
            Text(formatTime(entry.time), style = mono(10), color = inkSoft.copy(alpha = 0.7f), modifier = Modifier.padding(top = 3.dp))
        }
        TranscriptEntry.Kind.heard -> Text(entry.text, style = sans(14), color = inkSoft, modifier = Modifier.padding(start = 4.dp))
        TranscriptEntry.Kind.asked -> Row {
            Spacer(Modifier.weight(1f).widthIn(min = 120.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("You asked", style = mono(10), color = hex(Palette.berry1))
                Text(entry.text, style = sans(15, FontWeight.SemiBold), color = Color.White,
                    modifier = Modifier.background(berryBrush, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        TranscriptEntry.Kind.reply -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniBluey(Modifier.size(28.dp, 25.dp))
            Text(entry.text, style = fredoka(16), color = hex(Palette.ink),
                modifier = Modifier.weight(1f, fill = false).background(Color.White, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
            Spacer(Modifier.width(120.dp))
        }
        TranscriptEntry.Kind.report -> {
            var open by remember { mutableStateOf(false) }
            val parts = entry.text.split("\n\n")
            Column(Modifier.padding(start = 36.dp, end = 80.dp).fillMaxWidth()
                .background(hex(0xEEF0FF), RoundedCornerShape(18.dp)).border(2.dp, hex(Palette.berry2), RoundedCornerShape(18.dp))
                .clickable { open = !open }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(parts.first().ifEmpty { "Research" }, style = fredoka(16), color = hex(Palette.ink), modifier = Modifier.weight(1f))
                    Box(Modifier.size(24.dp).background(hex(Palette.berry2), CircleShape), contentAlignment = Alignment.Center) {
                        Text(if (open) "−" else "+", style = sans(14, FontWeight.SemiBold), color = Color.White)
                    }
                }
                Text(parts.drop(1).joinToString("\n\n"), style = sans(14), color = hex(Palette.ink),
                    maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
