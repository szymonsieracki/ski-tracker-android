package com.skitracker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Historia dni zapisywana w pliku JSON w pamięci aplikacji. */
object Store {
    private fun file(c: Context, name: String) = File(c.filesDir, name)

    private fun segToJson(s: Segment): JSONObject {
        val pts = JSONArray()
        s.points.forEach { p ->
            pts.put(JSONArray().put(p.lat).put(p.lon).put(p.alt).put(p.speed).put(p.t))
        }
        return JSONObject().put("id", s.id).put("kind", s.kind.name).put("pts", pts)
    }

    private fun segFromJson(o: JSONObject): Segment {
        val arr = o.getJSONArray("pts")
        val pts = (0 until arr.length()).map {
            val a = arr.getJSONArray(it)
            Pt(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getLong(4))
        }
        return Segment(o.getString("id"), Kind.valueOf(o.getString("kind")), pts)
    }

    private fun sessToJson(s: Session): JSONObject {
        val segs = JSONArray()
        s.segments.forEach { segs.put(segToJson(it)) }
        return JSONObject().put("id", s.id).put("date", s.date).put("segments", segs)
    }

    private fun sessFromJson(o: JSONObject): Session {
        val arr = o.getJSONArray("segments")
        return Session(o.getString("id"), o.getLong("date"), (0 until arr.length()).map { segFromJson(arr.getJSONObject(it)) })
    }

    fun sessions(c: Context): List<Session> {
        val f = file(c, "sessions.json")
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { sessFromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(c: Context, list: List<Session>) {
        val arr = JSONArray()
        list.forEach { arr.put(sessToJson(it)) }
        val tmp = file(c, "sessions.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(c, "sessions.json"))
    }

    fun add(c: Context, segments: List<Segment>) {
        if (segments.isEmpty()) return
        val date = segments.first().points.firstOrNull()?.t ?: System.currentTimeMillis()
        write(c, listOf(Session(date = date, segments = segments)) + sessions(c))
    }

    fun delete(c: Context, id: String) = write(c, sessions(c).filter { it.id != id })

    // --- kopia robocza co minutę, na wypadek zabicia aplikacji przez system ---
    fun saveDraft(c: Context, segments: List<Segment>) {
        if (segments.isEmpty()) return
        try {
            file(c, "draft.json").writeText(sessToJson(Session(date = System.currentTimeMillis(), segments = segments)).toString())
        } catch (e: Exception) { /* ignoruj */ }
    }

    fun clearDraft(c: Context) {
        file(c, "draft.json").delete()
    }

    /** Jeśli została kopia robocza po nieoczekiwanym zamknięciu – dodaj ją do historii. */
    fun recoverDraft(c: Context) {
        val f = file(c, "draft.json")
        if (!f.exists() || TrackerState.tracking.value) return
        try {
            val s = sessFromJson(JSONObject(f.readText()))
            add(c, s.segments)
        } catch (e: Exception) { /* uszkodzony plik */ }
        f.delete()
    }
}
