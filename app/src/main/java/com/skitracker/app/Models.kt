package com.skitracker.app

import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class Kind { RUN, LIFT }

/** Punkt trasy. speed w m/s, t w milisekundach (epoch). */
data class Pt(val lat: Double, val lon: Double, val alt: Double, val speed: Double, val t: Long)

data class Segment(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    val points: List<Pt>
) {
    val distance: Double
        get() {
            var d = 0.0
            for (i in 1 until points.size) {
                d += haversine(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
            }
            return d
        }
    val duration: Double
        get() = if (points.size < 2) 0.0 else (points.last().t - points.first().t) / 1000.0
    val avgSpeed: Double get() = if (duration > 0) distance / duration else 0.0
    val maxSpeed: Double get() = points.maxOfOrNull { it.speed } ?: 0.0
    val drop: Double
        get() = if (points.size < 2) 0.0 else abs(points.first().alt - points.last().alt)
}

data class Session(val id: String = UUID.randomUUID().toString(), val date: Long, val segments: List<Segment>) {
    val runs: List<Segment> get() = segments.filter { it.kind == Kind.RUN }
}

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = Math.toRadians(lat2 - lat1)
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

object Fmt {
    fun kmh(ms: Double): String = String.format(Locale.getDefault(), "%.1f km/h", ms * 3.6)
    fun km(m: Double): String = String.format(Locale.getDefault(), "%.2f km", m / 1000)
    fun meters(m: Double): String = String.format(Locale.getDefault(), "%.0f m", m)
    fun time(s: Double): String {
        val i = s.toInt()
        return String.format(Locale.getDefault(), "%d:%02d:%02d", i / 3600, i % 3600 / 60, i % 60)
    }
}
