package com.bluey.app

import org.json.JSONObject

/** Everything the Mac and the phone agree on (port of Shared/GooglyLink.swift). Newline JSON over TCP. */
const val SERVICE_TYPE = "_googly._tcp"

enum class Mood { listening, resting, thinking, talking, pointing, happy, sleepy }

/** What the phone should show. gaze x: -1 left … 1 right, y: -1 up … 1 down. talk: voice level 0…1. */
data class FaceState(val gazeX: Double = 0.0, val gazeY: Double = 0.0, val mood: Mood = Mood.listening, val talk: Double = 0.0)

data class Packet(
    val face: FaceState? = null,
    val hello: String? = null,
    val volume: Double? = null,
    val command: String? = null,
    val audio: String? = null,
    val speech: Int? = null,
    val callID: String? = null,
    val tool: String? = null,
    val text: String? = null,
    val image: String? = null,
) {
    /** One line, without the newline. Missing fields are left out, like Swift's Codable. */
    fun encode(): String {
        val o = JSONObject()
        face?.let {
            o.put("face", JSONObject().put("gazeX", it.gazeX).put("gazeY", it.gazeY).put("mood", it.mood.name).put("talk", it.talk))
        }
        hello?.let { o.put("hello", it) }
        volume?.let { o.put("volume", it) }
        command?.let { o.put("command", it) }
        audio?.let { o.put("audio", it) }
        speech?.let { o.put("speech", it) }
        callID?.let { o.put("callID", it) }
        tool?.let { o.put("tool", it) }
        text?.let { o.put("text", it) }
        image?.let { o.put("image", it) }
        return o.toString()
    }

    companion object {
        fun decode(line: String): Packet? = try {
            val o = JSONObject(line)
            fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
            val face = o.optJSONObject("face")?.let { f ->
                FaceState(
                    f.optDouble("gazeX", 0.0), f.optDouble("gazeY", 0.0),
                    runCatching { Mood.valueOf(f.optString("mood")) }.getOrDefault(Mood.listening),
                    f.optDouble("talk", 0.0),
                )
            }
            Packet(
                face = face, hello = str("hello"),
                volume = if (o.has("volume") && !o.isNull("volume")) o.getDouble("volume") else null,
                command = str("command"), audio = str("audio"),
                speech = if (o.has("speech") && !o.isNull("speech")) o.getInt("speech") else null,
                callID = str("callID"), tool = str("tool"), text = str("text"), image = str("image"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** Blueberry colors from the design canvas, as 0xRRGGBB. */
object Palette {
    const val berry1 = 0xA9BCFF
    const val berry2 = 0x6C86F5
    const val berry3 = 0x4254D6
    const val berry4 = 0x2B2F8F
    const val nose = 0x1C1F66
    const val ink = 0x17151F
    const val inkSoft = 0xB9B2CC
    const val panel = 0x1E1B29

    val gradient = intArrayOf(berry1, berry2, berry3, berry4)
    val gradientStops = floatArrayOf(0f, 0.34f, 0.68f, 1f)

    /** 0xRRGGBB plus an opacity, as an Android ARGB int. */
    fun argb(hex: Int, opacity: Double = 1.0): Int = ((opacity.coerceIn(0.0, 1.0) * 255).toInt() shl 24) or (hex and 0xFFFFFF)
}
