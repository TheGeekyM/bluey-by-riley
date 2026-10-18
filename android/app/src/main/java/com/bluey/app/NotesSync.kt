package com.bluey.app

import org.json.JSONArray
import org.json.JSONObject

/** Keeps the computer's meeting-notes files (~/Documents/Bluey Notes) up to date with the phone's sessions. */
class NotesSync(private val store: SessionStore, private val link: MacLink) {
    fun send(sessions: List<BlueySession>) {
        if (!link.connected) return
        for (s in sessions) {
            val entries = s.displayEntries.map { e ->
                JSONObject().put("kind", e.kind.name).put("text", e.text).put("time", e.time / 1000.0).apply { e.speaker?.let { put("speaker", it) } }
            }
            val json = JSONObject().put("id", s.id).put("started", s.started / 1000.0).put("transcribing", s.transcribing)
                .put("entries", JSONArray(entries)).apply { s.ended?.let { put("ended", it / 1000.0) } }
            link.request(Packet(command = "notes", text = json.toString())) { reply ->
                reply?.text?.let { store.setNotesPath(s.id, it) }
            }
        }
    }
}
