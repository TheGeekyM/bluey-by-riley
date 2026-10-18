package com.bluey.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The Mac decodes with Swift's Codable and silently drops anything that doesn't match, so pin the wire format. */
class ProtocolTest {
    @Test fun encodesOnlySetFieldsWithSwiftNames() {
        val audio = java.util.Base64.getEncoder().encodeToString(ByteArray(4000) { it.toByte() })
        val o = JSONObject(Packet(command = "transcribe", audio = audio, callID = "X", text = "{}").encode())
        assertEquals(setOf("command", "audio", "callID", "text"), o.keys().asSequence().toSet())
        assertFalse("base64 must not wrap", audio.contains('\n'))
        assertFalse(Packet(hello = "Pixel").encode().contains('\n'))
    }

    @Test fun decodesMacLines() {
        val face = Packet.decode("""{"face":{"gazeX":0.5,"gazeY":-1,"mood":"pointing","talk":0.25}}""")!!.face!!
        assertEquals(FaceState(0.5, -1.0, Mood.pointing, 0.25), face)
        val reply = Packet.decode("""{"command":"toolResult","callID":"A1","text":"ok","image":"abc"}""")!!
        assertEquals("A1", reply.callID); assertEquals("abc", reply.image); assertNull(reply.face)
        assertNull(Packet.decode("not json"))
    }
}
