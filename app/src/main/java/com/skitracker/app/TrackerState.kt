package com.skitracker.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Silnik rozpoznawania: zjazd / wyciąg / postój (postoje nie są zapisywane).
 * Singleton, bo współdzielony między usługą w tle a ekranami.
 */
object TrackerState {
    // Progi do strojenia na stoku
    private const val RUN_VSPEED = -0.4      // m/s w dół
    private const val LIFT_VSPEED = 0.3      // m/s w górę
    private const val MIN_RUN_SPEED = 1.5    // m/s
    private const val MIN_LIFT_SPEED = 1.0   // m/s
    private const val CONFIRM_FIXES = 5      // ile kolejnych pomiarów potwierdza zmianę

    private val _segments = MutableStateFlow<List<Segment>>(emptyList())
    val segments: StateFlow<List<Segment>> = _segments.asStateFlow()

    private val _current = MutableStateFlow<Segment?>(null)
    val current: StateFlow<Segment?> = _current.asStateFlow()

    private val _tracking = MutableStateFlow(false)
    val tracking: StateFlow<Boolean> = _tracking.asStateFlow()

    private val _speed = MutableStateFlow(0.0)
    val speed: StateFlow<Double> = _speed.asStateFlow()

    private val done = mutableListOf<Segment>()
    private var curKind: Kind? = null
    private var curId: String = UUID.randomUUID().toString()
    private val curPts = mutableListOf<Pt>()
    private val window = ArrayDeque<Pt>()
    private var candidate: Kind? = null
    private val pending = mutableListOf<Pt>()

    @Synchronized
    fun start() {
        done.clear(); curKind = null; curPts.clear(); window.clear(); candidate = null; pending.clear()
        _segments.value = emptyList()
        _current.value = null
        _speed.value = 0.0
        _tracking.value = true
    }

    @Synchronized
    fun finish(): List<Segment> {
        closeCurrent()
        _tracking.value = false
        _speed.value = 0.0
        return done.toList()
    }

    /** Zjazdy + aktualny odcinek – do zapisu roboczego. */
    @Synchronized
    fun snapshot(): List<Segment> {
        val c = _current.value
        return if (c != null && c.points.size > 1) done + c else done.toList()
    }

    @Synchronized
    fun onFix(p: Pt) {
        if (!_tracking.value) return
        _speed.value = p.speed
        process(p)
    }

    private fun classify(): Kind? {
        val f = window.firstOrNull() ?: return curKind
        val l = window.last()
        val dt = (l.t - f.t) / 1000.0
        if (dt < 6) return curKind
        val vs = (l.alt - f.alt) / dt
        val hs = window.map { it.speed }.average()
        if (vs < RUN_VSPEED && hs > MIN_RUN_SPEED) return Kind.RUN
        if (vs > LIFT_VSPEED && hs > MIN_LIFT_SPEED) return Kind.LIFT
        return null
    }

    private fun process(p: Pt) {
        window.addLast(p)
        if (window.size > 12) window.removeFirst()
        val kind = classify()

        if (kind == curKind) {
            candidate = null
            pending.clear()
            if (curKind != null) {
                curPts.add(p)
                publishCurrent()
            }
            return
        }

        if (candidate == kind) {
            pending.add(p)
        } else {
            candidate = kind
            pending.clear()
            pending.add(p)
        }

        if (pending.size >= CONFIRM_FIXES) {          // zmiana stanu potwierdzona
            closeCurrent()
            if (kind != null) {
                curKind = kind
                curId = UUID.randomUUID().toString()
                curPts.clear()
                curPts.addAll(pending)
            }
            candidate = null
            pending.clear()
            publishCurrent()
        } else if (curKind != null) {
            curPts.add(p)
            publishCurrent()
        }
    }

    private fun publishCurrent() {
        val k = curKind
        _current.value = if (k == null) null else Segment(curId, k, curPts.toList())
    }

    private fun closeCurrent() {
        val k = curKind
        if (k != null) {
            val s = Segment(curId, k, curPts.toList())
            val ok = if (k == Kind.LIFT) s.duration > 30 else (s.duration > 10 && s.distance > 50)
            if (ok) {
                done.add(s)
                _segments.value = done.toList()
            }
        }
        curKind = null
        curPts.clear()
        _current.value = null
    }
}
