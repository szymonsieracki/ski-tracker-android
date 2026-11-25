package com.skitracker.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Gpx {
    fun share(ctx: Context, segments: List<Segment>, title: String) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"SkiTracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("<metadata><name>").append(title.replace("&", "&amp;").replace("<", "&lt;")).append("</name></metadata>\n")
        segments.forEachIndexed { i, s ->
            val label = if (s.kind == Kind.RUN) "Zjazd" else "Wyciag"
            sb.append("<trk><name>$label ${i + 1}</name><trkseg>\n")
            s.points.forEach { p ->
                sb.append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\"><ele>${String.format(Locale.US, "%.1f", p.alt)}</ele><time>${iso.format(Date(p.t))}</time></trkpt>\n")
            }
            sb.append("</trkseg></trk>\n")
        }
        sb.append("</gpx>\n")

        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(segments.firstOrNull()?.points?.firstOrNull()?.t ?: System.currentTimeMillis()))
        val dir = File(ctx.cacheDir, "gpx").apply { mkdirs() }
        val file = File(dir, "narty-$day.gpx")
        file.writeText(sb.toString())

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Eksport GPX").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
