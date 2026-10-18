package com.bluey.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** One line in a session. Times are epoch milliseconds. */
data class TranscriptEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    val text: String,
    val time: Long,
    /** The Realtime conversation item this came from, so its words can be filled in when they arrive. */
    val itemID: String? = null,
    /** Who was talking, for proper transcript lines ("A", "B", … within each five-minute chunk). */
    val speaker: String? = null,
) {
    enum class Kind { heard, asked, reply, report, transcript }
}

/** A recorded chunk waiting to be transcribed by the Mac. */
data class PendingChunk(val index: Int, val started: Long)

/** Everything from one wake-to-sleep session. */
data class BlueySession(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val started: Long,
    val ended: Long? = null,
    val entries: List<TranscriptEntry> = emptyList(),
    val notesPath: String? = null,
    val pending: List<PendingChunk> = emptyList(),
) {
    val questionCount get() = entries.count { it.kind == TranscriptEntry.Kind.asked }

    /** The first thing you asked, or the first thing you said. */
    val summary: String
        get() = (entries.firstOrNull { it.kind == TranscriptEntry.Kind.asked && it.text.isNotEmpty() }
            ?: displayEntries.firstOrNull { it.kind == TranscriptEntry.Kind.transcript || it.kind == TranscriptEntry.Kind.heard })
            ?.text ?: "Nothing said yet"

    val durationMs get() = (ended ?: System.currentTimeMillis()) - started

    /** The proper transcript where it exists, the rough live lines after it, plus questions, replies and reports, in time order. */
    val displayEntries: List<TranscriptEntry>
        get() {
            val properUntil = entries.filter { it.kind == TranscriptEntry.Kind.transcript }.maxOfOrNull { it.time } ?: Long.MIN_VALUE
            return entries.filter { it.text.isNotEmpty() && !(it.kind == TranscriptEntry.Kind.heard && it.time <= properUntil) }
                .sortedBy { it.time }
        }

    val transcribing get() = pending.size

    /** Plain text, for sharing. */
    val exportText: String
        get() {
            val time = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
            val lines = mutableListOf("Bluey session, ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(started))}", "")
            for (e in displayEntries) {
                val who = when (e.kind) {
                    TranscriptEntry.Kind.transcript -> "Speaker ${e.speaker ?: "?"}"
                    TranscriptEntry.Kind.heard -> "You"
                    TranscriptEntry.Kind.asked -> "You asked"
                    TranscriptEntry.Kind.reply -> "Bluey"
                    TranscriptEntry.Kind.report -> "Research"
                }
                lines += "[${time.format(Date(e.time))}] $who: ${e.text}"
            }
            return lines.joinToString("\n")
        }
}

/** Keeps every session on the phone, as one JSON file in the app's files folder. Main thread only. */
class SessionStore(dir: File) {
    var sessions by mutableStateOf(listOf<BlueySession>()); private set  // newest first
    var currentID by mutableStateOf<String?>(null); private set

    private val file = File(dir, "sessions.json")
    private val main = Handler(Looper.getMainLooper())
    private val saveWork = Runnable { save() }
    private val syncWork = Runnable {
        val changed = sessions.filter { dirty.contains(it.id) }
        dirty.clear()
        if (changed.isNotEmpty()) onSync?.invoke(changed)
    }

    /** Called with sessions that changed, a few seconds after the changes settle. */
    var onSync: ((List<BlueySession>) -> Unit)? = null
    private val dirty = mutableSetOf<String>()

    init {
        runCatching {
            val array = JSONArray(file.readText())
            // A session left open by a crash or force-quit is closed at its last line.
            sessions = (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
                .map { if (it.ended == null) it.copy(ended = it.entries.lastOrNull()?.time ?: it.started) else it }
        }
    }

    val current get() = sessions.firstOrNull { it.id == currentID }

    fun start() {
        end()
        val session = BlueySession(started = System.currentTimeMillis())
        sessions = listOf(session) + sessions
        currentID = session.id
        save()
    }

    fun end() {
        val id = currentID ?: return
        val session = sessions.firstOrNull { it.id == id } ?: return
        currentID = null
        sessions = if (session.entries.all { it.text.isEmpty() } && session.transcribing == 0 &&
            System.currentTimeMillis() - session.started < 20_000) {
            sessions.filter { it.id != id }  // a few seconds with nothing said: don't keep it
        } else {
            dirty += id
            scheduleSync()
            sessions.map { if (it.id == id) it.copy(ended = System.currentTimeMillis(), entries = it.entries.filter { e -> e.text.isNotEmpty() }) else it }
        }
        save()
    }

    /** Holds a spot for something you said, in the order you said it. The words arrive a moment later. */
    fun placeholder(itemID: String, asked: Boolean) = update { s ->
        if (s.entries.any { it.itemID == itemID }) s
        else s.copy(entries = s.entries + TranscriptEntry(kind = if (asked) TranscriptEntry.Kind.asked else TranscriptEntry.Kind.heard, text = "", time = now(), itemID = itemID))
    }

    fun heard(itemID: String, text: String, asked: Boolean) {
        val text = text.trim()
        update { s ->
            if (s.entries.any { it.itemID == itemID }) {
                s.copy(entries = s.entries.map {
                    if (it.itemID == itemID) it.copy(text = text, kind = if (asked) TranscriptEntry.Kind.asked else it.kind) else it
                })
            } else if (text.isNotEmpty()) {
                s.copy(entries = s.entries + TranscriptEntry(kind = if (asked) TranscriptEntry.Kind.asked else TranscriptEntry.Kind.heard, text = text, time = now(), itemID = itemID))
            } else s
        }
    }

    fun reply(text: String) = append(TranscriptEntry.Kind.reply, text)
    fun report(text: String) = append(TranscriptEntry.Kind.report, text)

    private fun append(kind: TranscriptEntry.Kind, text: String) {
        val text = text.trim()
        if (text.isEmpty()) return
        update { it.copy(entries = it.entries + TranscriptEntry(kind = kind, text = text, time = now())) }
    }

    fun setNotesPath(session: String, path: String) {
        if (sessions.none { it.id == session && it.notesPath != path }) return
        sessions = sessions.map { if (it.id == session) it.copy(notesPath = path) else it }
        scheduleSave()
    }

    /** Re-sends recent sessions (after the phone reconnects to the Mac). */
    fun resyncRecent() {
        val cutoff = System.currentTimeMillis() - 3 * 86_400_000L
        dirty += sessions.filter { it.started > cutoff }.map { it.id }
        scheduleSync()
    }

    private fun scheduleSync() {
        main.removeCallbacks(syncWork)
        main.postDelayed(syncWork, 6000)
    }

    fun delete(id: String) {
        if (id == currentID) currentID = null
        sessions = sessions.filter { it.id != id }
        save()
    }

    private fun update(change: (BlueySession) -> BlueySession) {
        update(currentID ?: return, change)
    }

    private fun update(id: String, change: (BlueySession) -> BlueySession) {
        if (sessions.none { it.id == id }) return
        sessions = sessions.map { if (it.id == id) change(it) else it }
        dirty += id
        scheduleSync()
        scheduleSave()
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveWork)
        main.postDelayed(saveWork, 1000)
    }

    private fun save() {
        main.removeCallbacks(saveWork)
        val json = JSONArray(sessions.map(::toJson)).toString()
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun toJson(s: BlueySession) = JSONObject().apply {
        put("id", s.id); put("started", s.started); s.ended?.let { put("ended", it) }; s.notesPath?.let { put("notesPath", it) }
        put("pending", JSONArray(s.pending.map { JSONObject().put("index", it.index).put("started", it.started) }))
        put("entries", JSONArray(s.entries.map { e ->
            JSONObject().put("id", e.id).put("kind", e.kind.name).put("text", e.text).put("time", e.time).apply {
                e.itemID?.let { put("itemID", it) }; e.speaker?.let { put("speaker", it) }
            }
        }))
    }

    private fun fromJson(o: JSONObject): BlueySession {
        val entries = o.getJSONArray("entries")
        val pending = o.optJSONArray("pending") ?: JSONArray()
        return BlueySession(
            id = o.getString("id"), started = o.getLong("started"),
            ended = if (o.has("ended")) o.getLong("ended") else null,
            notesPath = o.optString("notesPath").ifEmpty { null },
            pending = (0 until pending.length()).map { pending.getJSONObject(it).let { p -> PendingChunk(p.getInt("index"), p.getLong("started")) } },
            entries = (0 until entries.length()).map { i ->
                val e = entries.getJSONObject(i)
                TranscriptEntry(
                    id = e.getString("id"), kind = TranscriptEntry.Kind.valueOf(e.getString("kind")), text = e.getString("text"),
                    time = e.getLong("time"), itemID = e.optString("itemID").ifEmpty { null }, speaker = e.optString("speaker").ifEmpty { null },
                )
            },
        )
    }
}
