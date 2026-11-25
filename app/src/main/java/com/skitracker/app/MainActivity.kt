package com.skitracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

sealed class Screen {
    data object Home : Screen()
    data object History : Screen()
    data class Summary(val title: String, val segments: List<Segment>) : Screen()
    data class RunDetail(val run: Segment, val index: Int) : Screen()
    data class MapScr(val showTrack: Boolean) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1B5FB3))) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFFF6F9FC)) { AppRoot() }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }

    LaunchedEffect(Unit) {
        Store.recoverDraft(ctx)
        SkiMapRepo.load(ctx, false)
    }

    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val s = stack.last()) {
        Screen.Home -> HomeScreen(
            onHistory = { push(Screen.History) },
            onMap = { push(Screen.MapScr(it)) },
            onFinished = { push(Screen.Summary("Ostatni dzień", it)) }
        )
        Screen.History -> HistoryScreen(
            onBack = { pop() },
            onOpen = { title, segs -> push(Screen.Summary(title, segs)) }
        )
        is Screen.Summary -> SummaryScreen(
            title = s.title,
            segments = s.segments,
            onBack = { pop() },
            onRun = { run, i -> push(Screen.RunDetail(run, i)) }
        )
        is Screen.RunDetail -> RunDetailScreen(s.run, s.index, onBack = { pop() })
        is Screen.MapScr -> MapScreen(s.showTrack, onBack = { pop() })
    }
}
