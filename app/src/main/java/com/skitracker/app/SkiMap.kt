package com.skitracker.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class LL(val lat: Double, val lon: Double)
data class Piste(val id: Long, val name: String, val difficulty: String, val coords: List<LL>)
data class Lift(val id: Long, val name: String, val kind: String, val coords: List<LL>)
data class SkiMapData(val pistes: List<Piste>, val lifts: List<Lift>, val fetched: Long)

/** Trasy i wyciągi z OpenStreetMap (Overpass) dla Madonna di Campiglio + Folgarida-Marilleva. */
object SkiMapRepo {
    private const val BBOX = "46.15,10.70,46.40,10.95"   // south,west,north,east
    private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000

    private val _data = MutableStateFlow<SkiMapData?>(null)
    val data: StateFlow<SkiMapData?> = _data.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun load(ctx: Context, force: Boolean) {
        val cache = File(ctx.filesDir, "skimap.json")
        if (_data.value == null && cache.exists()) {
            try { _data.value = parseCache(JSONObject(cache.readText())) } catch (e: Exception) { /* ignoruj */ }
        }
        val stale = _data.value?.let { System.currentTimeMillis() - it.fetched > MAX_AGE_MS } ?: true
        if (!force && !stale) return
        _loading.value = true
        _error.value = null
        try {
            val fresh = fetch()
            _data.value = fresh
            withContext(Dispatchers.IO) { cache.writeText(toCache(fresh).toString()) }
        } catch (e: Exception) {
            _error.value = "Nie udało się pobrać mapy tras: ${e.message}"
        } finally {
            _loading.value = false
        }
    }

    private suspend fun fetch(): SkiMapData = withContext(Dispatchers.IO) {
        val lifts = "chair_lift|gondola|cable_car|mixed_lift|drag_lift|t-bar|j-bar|platter|magic_carpet|rope_tow"
        val q = "[out:json][timeout:90];(" +
            "way[\"piste:type\"=\"downhill\"]($BBOX);" +
            "way[\"aerialway\"~\"^($lifts)\$\"]($BBOX);" +
            ");out tags geom;"
        val conn = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 100000
            conn.setRequestProperty("User-Agent", "SkiTracker/1.0")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            parseOverpass(JSONObject(text))
        } finally {
            conn.disconnect()
        }
    }

    private fun parseOverpass(root: JSONObject): SkiMapData {
        val els = root.getJSONArray("elements")
        val pistes = mutableListOf<Piste>()
        val lifts = mutableListOf<Lift>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val g = e.optJSONArray("geometry") ?: continue
            val t = e.optJSONObject("tags") ?: continue
            if (g.length() < 2) continue
            val coords = (0 until g.length()).map { LL(g.getJSONObject(it).getDouble("lat"), g.getJSONObject(it).getDouble("lon")) }
            val name = t.optString("name").ifEmpty { t.optString("piste:name").ifEmpty { t.optString("ref").ifEmpty { "Bez nazwy" } } }
            val id = e.getLong("id")
            if (t.optString("piste:type") == "downhill") {
                pistes.add(Piste(id, name, t.optString("piste:difficulty", "unknown"), coords))
            } else if (t.has("aerialway")) {
                lifts.add(Lift(id, name, t.getString("aerialway"), coords))
            }
        }
        return SkiMapData(pistes, lifts, System.currentTimeMillis())
    }

    private fun coordsToJson(c: List<LL>): JSONArray {
        val a = JSONArray()
        c.forEach { a.put(JSONArray().put(it.lat).put(it.lon)) }
        return a
    }

    private fun coordsFromJson(a: JSONArray): List<LL> =
        (0 until a.length()).map { LL(a.getJSONArray(it).getDouble(0), a.getJSONArray(it).getDouble(1)) }

    private fun toCache(d: SkiMapData): JSONObject {
        val p = JSONArray()
        d.pistes.forEach { p.put(JSONObject().put("id", it.id).put("n", it.name).put("d", it.difficulty).put("c", coordsToJson(it.coords))) }
        val l = JSONArray()
        d.lifts.forEach { l.put(JSONObject().put("id", it.id).put("n", it.name).put("k", it.kind).put("c", coordsToJson(it.coords))) }
        return JSONObject().put("fetched", d.fetched).put("pistes", p).put("lifts", l)
    }

    private fun parseCache(o: JSONObject): SkiMapData {
        val p = o.getJSONArray("pistes")
        val l = o.getJSONArray("lifts")
        return SkiMapData(
            (0 until p.length()).map { val x = p.getJSONObject(it); Piste(x.getLong("id"), x.getString("n"), x.getString("d"), coordsFromJson(x.getJSONArray("c"))) },
            (0 until l.length()).map { val x = l.getJSONObject(it); Lift(x.getLong("id"), x.getString("n"), x.getString("k"), coordsFromJson(x.getJSONArray("c"))) },
            o.getLong("fetched")
        )
    }
}

/** Dopasowanie przejazdów do nazw tras i wyciągów. */
object Matcher {
    private fun dist(p: LL, a: LL, b: LL): Double {
        val k = cos(Math.toRadians(p.lat)) * 111320.0
        val m = 110540.0
        val px = p.lon * k; val py = p.lat * m
        val ax = a.lon * k; val ay = a.lat * m
        val bx = b.lon * k; val by = b.lat * m
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / len2))
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun nearest(p: LL, lines: List<Pair<String, List<LL>>>, limit: Double): String? {
        var best: String? = null
        var bestD = Double.MAX_VALUE
        for ((name, c) in lines) {
            for (i in 0 until c.size - 1) {
                val d = dist(p, c[i], c[i + 1])
                if (d < limit && d < bestD) { bestD = d; best = name }
            }
        }
        return best
    }

    /** Nazwy tras/wyciągów w kolejności przejazdu, np. "Genziana → Boch". */
    fun names(seg: Segment, map: SkiMapData?): String? {
        if (map == null || seg.points.size < 3) return null
        val lines: List<Pair<String, List<LL>>> =
            if (seg.kind == Kind.RUN) map.pistes.map { it.name to it.coords } else map.lifts.map { it.name to it.coords }
        val limit = if (seg.kind == Kind.RUN) 35.0 else 50.0
        val step = max(1, seg.points.size / 15)
        val samples = seg.points.filterIndexed { i, _ -> i % step == 0 }

        val order = mutableListOf<String>()
        val count = mutableMapOf<String, Int>()
        for (p in samples) {
            val name = nearest(LL(p.lat, p.lon), lines, limit) ?: continue
            if (name !in count) order.add(name)
            count[name] = (count[name] ?: 0) + 1
        }
        val found = order.filter { (count[it] ?: 0).toDouble() / samples.size >= 0.25 }
        return if (found.isEmpty()) null else found.joinToString(" → ")
    }
}
