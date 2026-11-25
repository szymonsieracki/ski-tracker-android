package com.skitracker.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF2E9D4F)
private val Red = Color(0xFFD32F2F)
private val Muted = Color(0xFF5C6B78)

// ---------- sterowanie nagrywaniem ----------
private fun startTracking(ctx: Context) {
    TrackerState.start()
    ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
}

private fun stopTracking(ctx: Context): List<Segment> {
    val segs = TrackerState.finish()
    ctx.stopService(Intent(ctx, TrackingService::class.java))
    Store.add(ctx, segs)
    Store.clearDraft(ctx)
    return segs
}

private fun hasFine(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun neededPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

// ---------- wspólne elementy ----------
@Composable
fun TopBar(title: String, onBack: () -> Unit, action: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text("←", fontSize = 24.sp) }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
        action()
    }
}

@Composable
fun StatCell(modifier: Modifier, value: String, label: String) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 12.sp, color = Muted)
    }
}

@Composable
fun StyleSelector(style: MapStyle, onChange: (MapStyle) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MapStyle.values().forEach { st ->
            FilterChip(selected = style == st, onClick = { onChange(st) }, label = { Text(st.label) })
        }
    }
}

@Composable
fun InfoRow(a: String, b: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(a)
        Text(b, color = Muted)
    }
}

// ---------- ekran główny ----------
@Composable
fun HomeScreen(onHistory: () -> Unit, onMap: (Boolean) -> Unit, onFinished: (List<Segment>) -> Unit) {
    val ctx = LocalContext.current
    val tracking by TrackerState.tracking.collectAsState()
    val speed by TrackerState.speed.collectAsState()
    val segs by TrackerState.segments.collectAsState()
    val cur by TrackerState.current.collectAsState()

    val runs = segs.filter { it.kind == Kind.RUN } + listOfNotNull(cur?.takeIf { it.kind == Kind.RUN })
    val finishedRuns = segs.count { it.kind == Kind.RUN }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFine(ctx)) startTracking(ctx)
    }

    val status = when (cur?.kind) {
        Kind.RUN -> "⛷️ Zjazd"
        Kind.LIFT -> "🚡 Wyciąg"
        null -> if (tracking) "Postój / oczekiwanie" else "Gotowy"
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.logo_circle),
                contentDescription = "Logo",
                modifier = Modifier.size(132.dp).clip(CircleShape)
            )
            Text("Ski Tracker", fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp))
        }

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(Fmt.kmh(speed), fontSize = 60.sp, fontWeight = FontWeight.Bold)
            Text(status, color = Muted, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth().padding(top = 28.dp)) {
                StatCell(Modifier.weight(1f), "$finishedRuns", "Zjazdy")
                StatCell(Modifier.weight(1f), Fmt.km(runs.sumOf { it.distance }), "Dystans")
                StatCell(Modifier.weight(1f), Fmt.kmh(runs.maxOfOrNull { it.maxSpeed } ?: 0.0), "Vmax")
            }
        }

        Button(
            onClick = {
                if (tracking) {
                    val s = stopTracking(ctx)
                    if (s.isNotEmpty()) onFinished(s)
                } else if (hasFine(ctx)) {
                    startTracking(ctx)
                } else {
                    launcher.launch(neededPermissions())
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (tracking) Red else Green)
        ) { Text(if (tracking) "Zakończ" else "Start", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }

        if (!tracking) {
            OutlinedButton(
                onClick = onHistory,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) { Text("Historia") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { onMap(false) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) { Text("Mapa ośrodka") }
            OutlinedButton(
                onClick = { onMap(true) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) { Text("Mapa z trasą") }
        }
    }
}

// ---------- historia ----------
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (String, List<Segment>) -> Unit) {
    val ctx = LocalContext.current
    var sessions by remember { mutableStateOf(Store.sessions(ctx)) }
    val fmt = remember { SimpleDateFormat("EEEE, d MMMM yyyy", Locale("pl")) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Historia", onBack)
        if (sessions.isEmpty()) {
            Text("Brak zapisanych dni.", color = Muted, modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(sessions, key = { it.id }) { s ->
                val title = fmt.format(Date(s.date)).replaceFirstChar { it.uppercase() }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onOpen(title, s.segments) }, modifier = Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(title, fontWeight = FontWeight.Bold)
                            Text(
                                "${s.runs.size} zjazdów · ${Fmt.km(s.runs.sumOf { it.distance })} · Vmax ${Fmt.kmh(s.runs.maxOfOrNull { it.maxSpeed } ?: 0.0)}",
                                fontSize = 12.sp, color = Muted
                            )
                        }
                    }
                    TextButton(onClick = {
                        Store.delete(ctx, s.id)
                        sessions = Store.sessions(ctx)
                    }) { Text("Usuń", color = Red) }
                }
            }
        }
    }
}

// ---------- podsumowanie dnia ----------
@Composable
fun SummaryScreen(title: String, segments: List<Segment>, onBack: () -> Unit, onRun: (Segment, Int) -> Unit) {
    val ctx = LocalContext.current
    val ski by SkiMapRepo.data.collectAsState()
    var showSki by remember { mutableStateOf(true) }
    var style by remember { mutableStateOf(MapStyle.STANDARD) }
    val runs = segments.filter { it.kind == Kind.RUN }

    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack) {
            TextButton(onClick = { Gpx.share(ctx, segments, title) }) { Text("GPX") }
        }
        SkiMapView(
            Modifier.fillMaxWidth().height(300.dp),
            ski = ski, showSki = showSki, tracks = segments, style = style, fitToTracks = true
        )
        StyleSelector(style, { style = it })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pokaż trasy i wyciągi ośrodka", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = showSki, onCheckedChange = { showSki = it })
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text("Podsumowanie", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                InfoRow("Zjazdy", "${runs.size}")
                InfoRow("Łączny dystans", Fmt.km(runs.sumOf { it.distance }))
                InfoRow("Czas zjazdów", Fmt.time(runs.sumOf { it.duration }))
                InfoRow("Przewyższenie", Fmt.meters(runs.sumOf { it.drop }))
                InfoRow("Vmax", Fmt.kmh(runs.maxOfOrNull { it.maxSpeed } ?: 0.0))
                Text("Zjazdy", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            itemsIndexed(runs, key = { _, r -> r.id }) { i, r ->
                val names = remember(r.id, ski) { Matcher.names(r, ski) }
                TextButton(onClick = { onRun(r, i + 1) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("Zjazd ${i + 1}", fontWeight = FontWeight.Bold)
                        if (names != null) Text(names, fontSize = 14.sp)
                        Text(
                            "${Fmt.km(r.distance)} · ${Fmt.time(r.duration)} · śr. ${Fmt.kmh(r.avgSpeed)} · max ${Fmt.kmh(r.maxSpeed)} · ↓${r.drop.toInt()} m",
                            fontSize = 12.sp, color = Muted
                        )
                    }
                }
            }
        }
    }
}

// ---------- szczegóły zjazdu z wykresami ----------
@Composable
fun LineChart(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = if (hi - lo < 1e-6) 1.0 else hi - lo
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - (size.height * ((v - lo) / span)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(Color(0x22000000), Offset(0f, size.height), Offset(size.width, size.height), 2f)
        drawPath(path, color, style = Stroke(width = 4f))
    }
}

@Composable
fun RunDetailScreen(run: Segment, index: Int, onBack: () -> Unit) {
    val ski by SkiMapRepo.data.collectAsState()
    val names = remember(run.id, ski) { Matcher.names(run, ski) }
    val speeds = remember(run.id) { run.points.map { it.speed * 3.6 } }
    val alts = remember(run.id) { run.points.map { it.alt } }

    Column(Modifier.fillMaxSize()) {
        TopBar("Zjazd $index", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (names != null) {
                Text("Trasa", fontWeight = FontWeight.Bold)
                Text(names, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text("Statystyki", fontWeight = FontWeight.Bold)
            InfoRow("Dystans", Fmt.km(run.distance))
            InfoRow("Czas", Fmt.time(run.duration))
            InfoRow("Średnia", Fmt.kmh(run.avgSpeed))
            InfoRow("Maksymalna", Fmt.kmh(run.maxSpeed))
            InfoRow("Przewyższenie", Fmt.meters(run.drop))

            Text("Prędkość (km/h)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            if (speeds.isNotEmpty()) {
                Text("max ${"%.0f".format(speeds.max())} · min ${"%.0f".format(speeds.min())}", fontSize = 12.sp, color = Muted)
            }
            LineChart(speeds, Color(0xFF1B5FB3), Modifier.fillMaxWidth().height(150.dp).padding(vertical = 6.dp))

            Text("Wysokość (m n.p.m.)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            if (alts.isNotEmpty()) {
                Text("${"%.0f".format(alts.max())} → ${"%.0f".format(alts.min())} m", fontSize = 12.sp, color = Muted)
            }
            LineChart(alts, Color(0xFF8E24AA), Modifier.fillMaxWidth().height(150.dp).padding(vertical = 6.dp))
        }
    }
}

// ---------- mapa na żywo ----------
@Composable
fun MapScreen(showTrack: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ski by SkiMapRepo.data.collectAsState()
    val loading by SkiMapRepo.loading.collectAsState()
    val error by SkiMapRepo.error.collectAsState()
    val segs by TrackerState.segments.collectAsState()
    val cur by TrackerState.current.collectAsState()
    var style by remember { mutableStateOf(MapStyle.STANDARD) }
    var recenter by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        if (!hasFine(ctx)) launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (showTrack) "Mapa z trasą" else "Mapa ośrodka", onBack) {
            TextButton(onClick = { scope.launch { SkiMapRepo.load(ctx, true) } }) { Text("↻", fontSize = 20.sp) }
        }
        StyleSelector(style, { style = it })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SkiMapView(
                Modifier.fillMaxSize(),
                ski = ski, showSki = true,
                tracks = if (showTrack) segs + listOfNotNull(cur) else emptyList(),
                style = style, fitToTracks = false, recenterKey = recenter
            )
            Button(
                onClick = { recenter++ },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(52.dp),
                shape = CircleShape,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text("◎", fontSize = 22.sp) }
            Column(
                Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCCFFFFFF)).padding(8.dp)
            ) {
                if (loading) Text("Pobieram trasy…", fontSize = 12.sp)
                error?.let { Text(it, fontSize = 11.sp, color = Red) }
                ski?.let { Text("${it.pistes.size} tras · ${it.lifts.size} wyciągów", fontSize = 11.sp) }
                Text(
                    "zielona/niebieska/czerwona/czarna = trudność · żółte przerywane = wyciągi" +
                        if (showTrack) " · fioletowa = Twoja trasa" else "",
                    fontSize = 10.sp, textAlign = TextAlign.Start
                )
            }
        }
    }
}
