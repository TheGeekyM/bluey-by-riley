package com.bluey.app

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private fun rnd(a: Double, b: Double) = Random.nextDouble(a, b)

/** A tiny damped spring, so every part of the face moves with a little life. */
private class Spring(var value: Double, val stiffness: Double = 200.0, val damping: Double = 0.7) {
    var velocity = 0.0
    fun step(target: Double, dt: Double) {
        val c = 2 * sqrt(stiffness) * damping
        velocity += (stiffness * (target - value) - c * velocity) * dt
        value += velocity * dt
    }
}

/** Smooths what the Mac asks for into lifelike motion: darting eyes, brows, blinks, leaning, hops. Times in seconds. */
class FaceAnimator {
    class Frame(
        val gazeX: Double, val gazeY: Double, val mood: Mood, val talk: Double,
        val closed: Double, val breathe: Double, val time: Double, val pupil: Double,
        val browLift: Double, val browTilt: Double, val lean: Double, val hop: Double,
        val blush: Double, val squint: Double,
    )

    private var target = FaceState()
    private var lastPacket = -100.0
    private val gazeX = Spring(0.0, 260.0, 0.82)
    private val gazeY = Spring(0.0, 260.0, 0.82)
    private val pupil = Spring(1.0, 160.0, 0.5)
    private val browLift = Spring(0.0, 180.0, 0.55)
    private val browTilt = Spring(0.0, 150.0, 0.6)
    private val lean = Spring(0.0, 60.0, 0.8)
    private val hop = Spring(0.0, 260.0, 0.35)
    private val blush = Spring(0.25, 40.0, 1.0)
    private val squint = Spring(0.0, 150.0, 0.7)
    private var talk = 0.0
    private var mood = Mood.listening
    private var blinkStart = -1.0
    private var doubleBlink = false
    private var nextBlink = 2.0
    private var lastTime: Double? = null
    private var nextSaccade = 0.0
    private var wanderX = 0.0
    private var wanderY = -0.3
    private var jitterX = 0.0
    private var jitterY = 0.0
    private var nextJitter = 0.0
    private var nextHop = 6.0

    /** Mood set on the phone itself (waking up, talking). */
    var localMood: Mood? = null
    /** Loudness of the chirp playing on this phone, 0…1. */
    var localTalk: () -> Double = { 0.0 }
    /** True while he's awake and talking with you. In follow mode his eyes stay locked on your mouse. */
    var awake = false

    fun receive(face: FaceState, time: Double) {
        target = face
        lastPacket = time
    }

    fun step(now: Double): Frame {
        val dt = min(now - (lastTime ?: now), 1.0 / 20)
        lastTime = now

        val live = now - lastPacket < 2.5
        val wantedMood = localMood ?: if (live) target.mood else Mood.listening
        if (wantedMood != mood) {
            mood = wantedMood
            blinkStart = now  // blink through every mood change
            if (mood != Mood.sleepy && mood != Mood.resting) {
                hop.velocity += 260  // and a little bounce of surprise
                pupil.velocity += 3
            }
        }

        // Where to look, plus tiny darting movements so the eyes never sit dead still.
        var wantX: Double
        var wantY: Double
        if (live) {
            wantX = target.gazeX; wantY = target.gazeY
        } else {
            if (now > nextSaccade) {
                wanderX = rnd(-0.9, 0.9); wanderY = rnd(-0.9, 0.4)
                nextSaccade = now + rnd(0.6, 2.2)
            }
            wantX = wanderX; wantY = wanderY
        }
        if (mood == Mood.thinking) { wantX = 0.6 + 0.08 * sin(now * 1.3); wantY = -0.85 }
        if (now > nextJitter) {
            // While he's listening his eyes stay on your mouse; the livelier darting is for replying.
            val amount = if (!awake) 0.015 else if (mood == Mood.talking) 0.07 else if (mood == Mood.thinking) 0.05 else 0.02
            jitterX = rnd(-amount, amount); jitterY = rnd(-amount, amount)
            nextJitter = now + rnd(0.5, 1.4)
        }
        gazeX.step(wantX + jitterX, dt)
        gazeY.step(wantY + jitterY, dt)

        var wantTalk = if (live) target.talk else if (localMood == Mood.talking) 0.5 + 0.5 * sin(now * 19) * sin(now * 7.3) else 0.0
        wantTalk = max(wantTalk, localTalk())
        talk += (wantTalk - talk) * min(1.0, dt * 25)

        // Expression targets per mood.
        var wPupil = 1.0; var wLift = 0.0; var wTilt = 0.0; var wBlush = 0.25; var wSquint = 0.0
        when (mood) {
            Mood.listening -> { wPupil = 1.12; wLift = 10.0 }
            Mood.talking -> { wPupil = 1.05; wLift = 6 + talk * 18; wTilt = 0.05 * sin(now * 2.3) }
            Mood.pointing -> { wPupil = 0.95; wLift = -4.0; wTilt = -0.14 }
            Mood.thinking -> { wPupil = 0.9; wLift = 4.0; wTilt = 0.22 }
            Mood.happy -> { wPupil = 1.2; wLift = 16.0; wBlush = 0.85; wSquint = 1.0 }
            Mood.resting -> { wPupil = 0.9; wLift = -8.0; wBlush = 0.15 }
            Mood.sleepy -> { wPupil = 0.88; wLift = -10.0; wTilt = 0.12; wBlush = 0.15 }
        }
        pupil.step(wPupil, dt)
        browLift.step(wLift, dt)
        browTilt.step(wTilt, dt)
        blush.step(wBlush, dt)
        squint.step(wSquint, dt)
        lean.step(-gazeX.value * 0.045, dt)

        // An occasional happy little hop when nothing much is going on.
        if (now > nextHop) {
            if (talk < 0.05 && mood != Mood.sleepy && mood != Mood.resting) hop.velocity += rnd(180.0, 320.0)
            nextHop = now + rnd(7.0, 14.0)
        }
        hop.step(0.0, dt)

        // Blinks, sometimes doubled. When he's drowsy they're slow and heavy.
        val drowsy = mood == Mood.sleepy
        if (now > nextBlink) {
            blinkStart = now
            doubleBlink = !drowsy && Random.nextDouble() < 0.25
            nextBlink = now + if (drowsy) rnd(1.6, 3.0) else rnd(2.0, 5.0)
        }
        val p = (now - blinkStart) / if (drowsy) 0.7 else 0.15
        var closed = if (p in 0.0..1.0) sin(PI * p) else 0.0
        if (doubleBlink && p in 1.3..2.3) closed = sin(PI * (p - 1.3))

        val breathe = sin(now * if (mood == Mood.resting) 1.1 else 1.8)
        return Frame(gazeX.value, gazeY.value, mood, talk, closed, breathe, now, pupil.value, browLift.value,
            browTilt.value, lean.value, hop.value, blush.value, squint.value.coerceIn(0.0, 1.0))
    }
}

/** The blob outline: CSS `border-radius: 52% 48% 46% 54% / 58% 56% 44% 42%`. */
object Blob {
    fun path(r: RectF): Path {
        val w = r.width(); val h = r.height()
        val tlw = 0.52f * w; val tlh = 0.58f * h
        val trw = 0.48f * w; val trh = 0.56f * h
        val brw = 0.46f * w; val brh = 0.44f * h
        val blw = 0.54f * w; val blh = 0.42f * h
        val k = 1 - 0.5523f  // cubic approximation of a quarter ellipse
        return Path().apply {
            moveTo(r.left + tlw, r.top)
            lineTo(r.right - trw, r.top)
            cubicTo(r.right - trw * k, r.top, r.right, r.top + trh * k, r.right, r.top + trh)
            lineTo(r.right, r.bottom - brh)
            cubicTo(r.right, r.bottom - brh * k, r.right - brw * k, r.bottom, r.right - brw, r.bottom)
            lineTo(r.left + blw, r.bottom)
            cubicTo(r.left + blw * k, r.bottom, r.left, r.bottom - blh * k, r.left, r.bottom - blh)
            lineTo(r.left, r.top + tlh)
            cubicTo(r.left, r.top + tlh * k, r.left + tlw * k, r.top, r.left + tlw, r.top)
            close()
        }
    }

    /** The blueberry gradient at 150°, like the design. */
    fun shader(r: RectF) = LinearGradient(
        r.left + 0.25f * r.width(), r.top + 0.067f * r.height(), r.left + 0.75f * r.width(), r.top + 0.933f * r.height(),
        Palette.gradient.map { Palette.argb(it) }.toIntArray(), Palette.gradientStops, Shader.TileMode.CLAMP,
    )

    /** The little blueberry crown on top of his head (SVG path from the design, 60×40 box). */
    fun crown(r: RectF): Path {
        val pts = listOf(30 to 4, 36 to 16, 52 to 12, 40 to 24, 46 to 36, 30 to 28, 14 to 36, 20 to 24, 8 to 12, 24 to 16)
        return Path().apply {
            pts.forEachIndexed { i, (x, y) ->
                val px = r.left + x / 60f * r.width(); val py = r.top + y / 40f * r.height()
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
    }

    /** A tiny Bluey: the blob with two googly eyes, filling w × h. */
    fun drawMini(c: Canvas, w: Float, h: Float) {
        val r = RectF(0f, 0f, w, h)
        c.drawPath(path(r), Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = shader(r) })
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.argb(0xFFFFFF) }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.argb(Palette.ink) }
        for (side in listOf(-1, 1)) {
            val cx = w / 2 + side * w * 0.18f; val cy = h / 2 - w * 0.04f
            c.drawCircle(cx, cy, w * 0.13f, white)
            c.drawCircle(cx + w * 0.02f, cy + w * 0.03f, w * 0.06f, ink)
        }
    }
}

/** The landscape face on pure black: he fills the screen and peeks up from the bottom edge. */
class FaceRenderer(private val font: Typeface?) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font; textAlign = Paint.Align.CENTER; fontVariationSettings = "'wght' 700"
    }
    private val navy = Palette.argb(Palette.nose)
    private val ink = Palette.argb(Palette.ink)

    private fun color(c: Int) = fill.apply { shader = null; color = c }

    private fun shine(body: RectF) = RadialGradient(body.left + 246, body.top + 126, 243f,
        Palette.argb(0xFFFFFF, 0.5), Palette.argb(0xFFFFFF, 0.0), Shader.TileMode.CLAMP)

    /** Fills a shape with his skin (gradient plus the same soft shine), for eyelids. */
    private fun skin(c: Canvas, path: Path, body: RectF) {
        c.drawPath(path, fill.apply { shader = Blob.shader(body) })
        c.drawPath(path, fill.apply { shader = shine(body) })
    }

    private fun oval(c: Canvas, x: Float, y: Float, w: Float, h: Float, paint: Paint) = c.drawOval(x, y, x + w, y + h, paint)

    fun draw(f: FaceAnimator.Frame, c: Canvas, width: Float, height: Float) {
        c.drawColor(Palette.argb(0x000000))
        val scale = max(width / DESIGN_W, height / DESIGN_H)
        c.save()
        c.translate((width - DESIGN_W * scale) / 2, height - DESIGN_H * scale)
        c.scale(scale, scale)

        // Whole-body motion: breathing, talking bounce, idle hops, and leaning toward what he looks at.
        val bounce = (-f.talk * 16 + f.breathe * 3 - f.hop).toFloat()
        val body = RectF(12f, 44 + bounce, 832f, 744 + bounce)
        c.translate(body.centerX(), body.bottom)
        c.rotate(Math.toDegrees(f.lean).toFloat())
        val stretch = (1 + (f.hop / 900).coerceIn(-0.03, 0.03)).toFloat()
        c.scale(2 - stretch, stretch)
        c.translate(-body.centerX(), -body.bottom)

        val blob = Blob.path(body)
        // Soft glow behind him.
        c.drawPath(blob, color(Palette.argb(Palette.berry3)).apply { setShadowLayer(45f, 0f, -10f, Palette.argb(Palette.berry2, 0.5)) })
        fill.clearShadowLayer()
        c.drawPath(blob, fill.apply { shader = Blob.shader(body) })
        c.save()
        c.clipPath(blob)
        c.drawPath(blob, fill.apply { shader = shine(body) })
        // A little glossy sparkle on his head.
        oval(c, body.left + 190, body.top + 40, 46f, 22f, color(Palette.argb(0xFFFFFF, 0.35)))
        c.restore()

        val lift = f.browLift.toFloat()
        c.drawPath(Blob.crown(RectF(body.left + 368, body.top - 20 - lift * 0.3f, body.left + 452, body.top + 36 - lift * 0.3f)), color(navy))

        val eyeY = body.top + 157
        val eyes = listOf(body.left + 280, body.left + 540)
        val squash = 1 - f.talk * 0.12

        // Blushy cheeks, rosier when he's happy (radial fade instead of a blur).
        for ((i, x) in eyes.withIndex()) {
            val side = if (i == 0) -1 else 1
            val cx = x + side * 62; val cy = eyeY + 117
            val a = 0.25 + 0.45 * f.blush
            fill.shader = RadialGradient(cx, cy, 60f, intArrayOf(Palette.argb(0xF3A6D8, a), Palette.argb(0xF3A6D8, a * 0.6), Palette.argb(0xF3A6D8, 0.0)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.save(); c.scale(1f, 0.5f, cx, cy); c.drawCircle(cx, cy, 60f, fill); c.restore()
        }
        fill.shader = null

        for ((i, x) in eyes.withIndex()) {
            val side = if (i == 0) -1f else 1f
            val y = eyeY

            // Little arched eyebrows do a lot of the acting.
            c.save()
            c.translate(x + side * 6, y - 112 - lift.coerceIn(-10f, 18f) * 0.7f)
            c.rotate(Math.toDegrees(f.browTilt * -side + if (f.mood == Mood.thinking && i == 1) -0.25 else 0.0).toFloat())
            c.drawPath(Path().apply { moveTo(-38f, 6f); quadTo(0f, -12f, 38f, 6f) }, stroke.apply { color = navy; strokeWidth = 14f })
            c.restore()

            when {
                f.mood == Mood.resting -> {
                    // Peacefully closed: a soft downward curve.
                    c.drawPath(Path().apply { moveTo(x - 62, y + 6); quadTo(x, y + 52, x + 62, y + 6) }, stroke.apply { color = ink; strokeWidth = 18f })
                }
                f.mood == Mood.happy && f.squint > 0.6 -> {
                    c.drawPath(Path().apply { moveTo(x - 60, y + 34); quadTo(x, y - 54, x + 60, y + 34) }, stroke.apply { color = ink; strokeWidth = 24f })
                }
                else -> {
                    val open = (max(0.06, 1 - f.closed) * squash).toFloat()
                    val white = RectF(x - 95, y - 95 * open, x + 95, y + 95 * open)
                    val whitePath = Path().apply { addOval(white, Path.Direction.CW) }
                    c.drawPath(whitePath, color(Palette.argb(0xFFFFFF)))
                    if (open <= 0.2f) continue

                    var gx = f.gazeX; var gy = f.gazeY
                    val len = hypot(gx, gy)
                    if (len > 1) { gx /= len; gy /= len }
                    // Eyes converge a little when looking down close, and reach further for a livelier look.
                    val reach = 50f
                    val r = (46 * f.pupil).toFloat()
                    val px = x + gx.toFloat() * reach - side * 3
                    var py = y + gy.toFloat() * reach * open
                    if (f.mood == Mood.sleepy) py = max(py, y) + 28  // eyes sink under heavy lids
                    c.save()
                    c.clipPath(whitePath)
                    c.drawCircle(px, py, r, color(ink))
                    // Two catchlights make them shine.
                    oval(c, px - r * 0.58f, py - r * 0.72f, r * 0.56f, r * 0.56f, color(Palette.argb(0xFFFFFF)))
                    oval(c, px + r * 0.28f, py + r * 0.22f, r * 0.24f, r * 0.24f, color(Palette.argb(0xFFFFFF, 0.85)))
                    // Happy cheeks push up from below as he smiles.
                    if (f.squint > 0.02) {
                        val top = y + 95 * open - 70 * f.squint.toFloat()
                        skin(c, Path().apply { addOval(RectF(x - 110, top, x + 110, top + 160), Path.Direction.CW) }, body)
                    }
                    // Heavy, droopy lids when he's getting sleepy.
                    if (f.mood == Mood.sleepy) {
                        val droop = (0.5 + 0.06 * sin(f.time * 1.3)).toFloat()
                        skin(c, Path().apply { addRect(x - 100, white.top - 10, x + 100, white.top + white.height() * droop, Path.Direction.CW) }, body)
                        val ly = white.top + white.height() * droop
                        c.drawLine(x - 92, ly, x + 92, ly, stroke.apply { color = Palette.argb(Palette.nose, 0.7); strokeWidth = 8f })
                    }
                    // A soft upper lid when he's focused on pointing.
                    if (f.mood == Mood.pointing) {
                        skin(c, Path().apply { addRect(x - 100, white.top - 20, x + 100, white.top + 20, Path.Direction.CW) }, body)
                    }
                    c.restore()
                }
            }
        }

        if (f.mood == Mood.resting) {
            // Little z's floating up while he naps.
            for (i in 0 until 3) {
                val phase = (f.time * 0.35 + i / 3.0) % 1.0
                text.textSize = 26f + i * 8
                text.color = Palette.argb(0xFFFFFF, 0.9 * sin(PI * phase))
                c.drawText("z", body.left + 660 + phase.toFloat() * 70 + sin(phase * 6).toFloat() * 8,
                    body.top + 90 - phase.toFloat() * 110 + text.textSize * 0.35f, text)
            }
        }

        if (f.mood == Mood.thinking) {
            for (i in 0 until 3) {
                val wobble = sin(f.time * 3 + i).toFloat() * 4
                oval(c, body.left + 690 + i * 26, body.top + 40 - i * 12 + wobble, 16f, 16f, color(Palette.argb(0xFFFFFF, 1 - i * 0.3)))
            }
        }

        // His little mouth-nose: opens as he talks, curls into a smile when he's happy.
        val talk = f.talk.toFloat()
        val noseW = 60 - talk * 8
        val noseH = 38 + talk * 34
        val nose = RectF(body.left + 410 - noseW / 2, body.top + 272 - talk * 6, body.left + 410 + noseW / 2, body.top + 272 - talk * 6 + noseH)
        if (f.mood == Mood.happy && f.talk < 0.1) {
            c.drawPath(Path().apply {
                moveTo(nose.left - 8, nose.top + 8); quadTo(nose.centerX(), nose.top + 58, nose.right + 8, nose.top + 8); close()
            }, color(navy))
        } else {
            c.drawOval(nose, color(navy))
            if (f.talk > 0.25) {
                oval(c, nose.centerX() - noseW * 0.28f, nose.bottom - noseH * 0.38f, noseW * 0.56f, noseH * 0.3f, color(Palette.argb(0xE58BC4, 0.9)))
            }
        }
        c.restore()
    }

    companion object {
        // The design is drawn on an 844 × 390 landscape phone.
        const val DESIGN_W = 844f
        const val DESIGN_H = 390f
    }
}
