package com.skitracker.app

import android.graphics.Color
import android.graphics.DashPathEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

fun difficultyColor(d: String): Int = when (d) {
    "novice" -> Color.rgb(46, 157, 79)
    "easy" -> Color.rgb(27, 95, 179)
    "intermediate" -> Color.rgb(214, 38, 44)
    "advanced", "expert" -> Color.BLACK
    else -> Color.GRAY
}

val RUN_COLOR: Int = Color.rgb(142, 36, 170)
val LIFT_TRACK_COLOR: Int = Color.rgb(0, 172, 193)

enum class MapStyle(val label: String) {
    STANDARD("Mapa"), TOPO("Topo"), SATELLITE("Satelita")
}

/** Zdjęcia satelitarne Esri World Imagery (kolejność w adresie: z/y/x). */
private val EsriSatellite = object : OnlineTileSourceBase(
    "EsriWorldImagery", 0, 19, 256, ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Tiles © Esri"
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

private fun MapStyle.source() = when (this) {
    MapStyle.STANDARD -> TileSourceFactory.MAPNIK
    MapStyle.TOPO -> TileSourceFactory.OpenTopo
    MapStyle.SATELLITE -> EsriSatellite
}

private class MapHolder(val loc: MyLocationNewOverlay) {
    var skiData: SkiMapData? = null
    var showSki = false
    var style: MapStyle? = null
    var recenter = 0
    var fitted = false
    val skiOverlays = mutableListOf<Overlay>()
    val trackOverlays = mutableListOf<Overlay>()
}

private fun line(points: List<GeoPoint>, color: Int, width: Float, dashed: Boolean = false): Polyline =
    Polyline().apply {
        setPoints(points)
        outlinePaint.color = color
        outlinePaint.strokeWidth = width
        if (dashed) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(22f, 14f), 0f)
    }

/**
 * Mapa OpenStreetMap z trasami i wyciągami ośrodka, Twoją pozycją i (opcjonalnie) nagraną trasą.
 * tracks – odcinki do narysowania (pusta lista = bez trasy).
 */
@Composable
fun SkiMapView(
    modifier: Modifier,
    ski: SkiMapData?,
    showSki: Boolean,
    tracks: List<Segment>,
    style: MapStyle,
    fitToTracks: Boolean,
    recenterKey: Int = 0
) {
    val ctx = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.cacheDir, "osm")
            osmdroidTileCache = File(ctx.cacheDir, "osm/tiles")
        }
        MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(46.27, 10.84))
            overlays.add(CopyrightOverlay(ctx))
        }
    }
    val holder = remember {
        val loc = MyLocationNewOverlay(GpsMyLocationProvider(ctx), mapView).apply {
            enableMyLocation()
            enableFollowLocation()
        }
        MapHolder(loc)
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            holder.loc.disableMyLocation()
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { m ->
            if (holder.style != style) {
                m.setTileSource(style.source())
                holder.style = style
            }

            // trasy i wyciągi ośrodka – przebudowa tylko po zmianie danych
            if (holder.skiData !== ski || holder.showSki != showSki) {
                m.overlays.removeAll(holder.skiOverlays)
                holder.skiOverlays.clear()
                if (showSki && ski != null) {
                    ski.pistes.forEach {
                        holder.skiOverlays.add(line(it.coords.map { c -> GeoPoint(c.lat, c.lon) }, difficultyColor(it.difficulty), 7f))
                    }
                    ski.lifts.forEach {
                        holder.skiOverlays.add(line(it.coords.map { c -> GeoPoint(c.lat, c.lon) }, Color.rgb(255, 193, 7), 6f, dashed = true))
                    }
                    m.overlays.addAll(1, holder.skiOverlays)   // pod pozycją, nad podkładem
                }
                holder.skiData = ski
                holder.showSki = showSki
            }

            // Twoja trasa – przebudowa przy każdej zmianie
            m.overlays.removeAll(holder.trackOverlays)
            holder.trackOverlays.clear()
            tracks.forEach { s ->
                if (s.points.size > 1) {
                    holder.trackOverlays.add(
                        line(
                            s.points.map { GeoPoint(it.lat, it.lon) },
                            if (s.kind == Kind.RUN) RUN_COLOR else LIFT_TRACK_COLOR,
                            if (s.kind == Kind.RUN) 10f else 7f
                        )
                    )
                }
            }
            m.overlays.addAll(holder.trackOverlays)
            m.overlays.remove(holder.loc)      // kropka pozycji zawsze na wierzchu
            m.overlays.add(holder.loc)

            if (holder.recenter != recenterKey) {
                holder.recenter = recenterKey
                holder.loc.enableFollowLocation()
                holder.loc.myLocation?.let { m.controller.animateTo(it) }
            }

            if (fitToTracks && !holder.fitted) {
                val pts = tracks.flatMap { it.points }.map { GeoPoint(it.lat, it.lon) }
                if (pts.size > 1) {
                    holder.fitted = true
                    holder.loc.disableFollowLocation()
                    m.post { m.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), false, 80) }
                }
            }
            m.invalidate()
        }
    )
}
