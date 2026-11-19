package com.pgratz.artouchpad

import kotlin.math.abs
import kotlin.math.hypot

/** Single-threaded recognizer shared by the touch UI and sequence regression tests. */
internal class TouchGestureEngine(
    private val output: Output,
    private val holdMs: Long = 600,
    private val dragLock: Boolean = false,
    private val density: Float = 1f,
) {
    interface Output {
        fun move(dx: Float, dy: Float, time: Long)
        fun click()
        fun rightClick()
        fun threeFingerTap()
        fun scroll(dx: Float, dy: Float)
        fun pinch(delta: Float)
        fun mode(mode: TouchMode)
        fun startDrag()
        fun endDrag()
        fun cancelDrag()
        fun dragHeld(held: Boolean)
        fun dragReady(ready: Boolean)
        fun settings()
        fun pocket()
    }

    private var taps = MultiFingerTapTracker(12f * density, 220)
    private var four = FourFingerGesture(16f * density)
    private var previous = emptyList<MultiFingerTapTracker.Contact>()
    private var startX = 0f
    private var startY = 0f
    private var started = 0L
    private var active = false
    private var moved = false
    private var ready = false
    private var selecting = false
    private var held = false
    private var holdDeadline: Long? = null
    private var twoMode = 0 // 0 undecided, 1 scroll, 2 pinch
    private var pendingX = 0f
    private var pendingY = 0f
    private var pendingSpan = 0f
    private var mode = TouchMode.IDLE
    private var inputAllowed = true
    private var blockedUntilLift = false

    val nextDeadline: Long? get() = listOfNotNull(four.holdDeadline, holdDeadline).minOrNull()

    private fun setMode(value: TouchMode) { if (mode != value) { mode = value; output.mode(value) } }
    private fun clearHold() {
        holdDeadline = null
        if (ready) { ready = false; output.dragReady(false) }
    }
    private fun cancelDrag() {
        if (selecting || held) output.cancelDrag()
        selecting = false
        if (held) { held = false; output.dragHeld(false) }
    }

    fun setInputAllowed(allowed: Boolean) {
        if (inputAllowed == allowed) return
        inputAllowed = allowed
        clearHold(); cancelDrag(); previous = emptyList(); setMode(TouchMode.IDLE)
    }

    fun onTime(time: Long) {
        if (four.onTime(time) == FourFingerGesture.Decision.TOGGLE_POCKET) {
            clearHold(); cancelDrag(); setMode(TouchMode.IDLE); output.pocket()
        }
        if (inputAllowed && holdDeadline?.let { time >= it } == true) {
            holdDeadline = null; ready = true; output.dragReady(true)
        }
    }

    fun event(time: Long, contacts: List<MultiFingerTapTracker.Contact>, width: Float, height: Float) {
        if (blockedUntilLift) {
            if (contacts.none { it.pressed }) blockedUntilLift = false
            return
        }
        val pressed = contacts.filter { it.pressed }
        val first = !active && pressed.isNotEmpty()
        val tap = taps.update(time, contacts)
        val fourDecision = four.update(time, contacts)
        if (first) {
            active = true; started = time; moved = false
            startX = pressed[0].x; startY = pressed[0].y
            twoMode = 0; pendingX = 0f; pendingY = 0f; pendingSpan = 0f
            clearHold()
            if (inputAllowed && pressed.size == 1 && !held) holdDeadline = time + holdMs
        }
        if (pressed.isEmpty()) active = false
        if (fourDecision != FourFingerGesture.Decision.PASS) {
            clearHold(); cancelDrag(); taps.cancelTap(); moved = true
            previous = emptyList(); setMode(TouchMode.IDLE)
            when (fourDecision) {
                FourFingerGesture.Decision.TOGGLE_SETTINGS -> output.settings()
                FourFingerGesture.Decision.TOGGLE_POCKET -> output.pocket()
                else -> Unit
            }
            return
        }
        val point = contacts.firstOrNull() ?: return
        if (!inputAllowed) { clearHold(); cancelDrag(); previous = emptyList(); return }
        if (taps.maxFingers >= 2) { clearHold(); cancelDrag() }

        val displacement = abs(point.x - startX) > 5 * density || abs(point.y - startY) > 5 * density
        val suppress = taps.maxFingers >= 3 || (taps.maxFingers >= 2 && taps.releasing)
        if (!suppress && pressed.size == 1 && taps.maxFingers == 1) {
            val p = pressed[0]
            val last = previous.firstOrNull { it.id == p.id }
            if (last != null) {
                val crossing = !moved && displacement
                if (ready && !selecting && displacement) {
                    selecting = true; moved = true; four.cancel(); output.startDrag(); setMode(TouchMode.SELECT)
                } else if (crossing && !ready) {
                    moved = true; taps.cancelTap(); clearHold()
                }
                if (selecting || held || moved) {
                    // Return the dead-zone travel once.
                    val buffered = crossing
                    output.move(p.x - if (buffered) startX else last.x,
                        p.y - if (buffered) startY else last.y, time)
                    setMode(if (selecting || held) TouchMode.SELECT else TouchMode.CURSOR)
                }
            }
        } else if (!suppress && pressed.size == 2) {
            if (previous.size == 2 && previous[0].id == pressed[0].id && previous[1].id == pressed[1].id) {
                val a = pressed[0]; val b = pressed[1]; val pa = previous[0]; val pb = previous[1]
                val dx = (a.x - pa.x + b.x - pb.x) / 2
                val dy = (a.y - pa.y + b.y - pb.y) / 2
                val span = hypot(b.x - a.x, b.y - a.y) - hypot(pb.x - pa.x, pb.y - pa.y)
                if (twoMode == 0) {
                    pendingX += dx; pendingY += dy; pendingSpan += span
                    val travel = hypot(pendingX, pendingY)
                    if (maxOf(travel, abs(pendingSpan)) >= 12 * density) {
                        four.cancel(); taps.cancelTap()
                        twoMode = if (abs(pendingSpan) > travel) 2 else 1
                        if (twoMode == 2) output.pinch(pendingSpan)
                        else { output.scroll(pendingX, pendingY); setMode(TouchMode.SCROLL) }
                    }
                } else if (twoMode == 1) { output.scroll(dx, dy); setMode(TouchMode.SCROLL) }
                else output.pinch(span)
            }
        }
        previous = pressed
        if (pressed.isEmpty()) {
            val duration = time - started
            clearHold()
            // Flush movement before releasing/copying, but leave an intentional drag lock held.
            setMode(TouchMode.IDLE)
            when {
                tap == 2 -> output.rightClick()
                tap == 3 -> output.threeFingerTap()
                taps.maxFingers >= 2 -> Unit
                selecting -> {
                    selecting = false
                    if (dragLock) { held = true; output.dragHeld(true) }
                    else output.endDrag()
                }
                held && !moved && duration < 220 -> { held = false; output.dragHeld(false); output.endDrag() }
                held -> Unit
                !moved && duration >= holdMs -> output.rightClick()
                !moved && duration < 220 -> output.click()
            }
        }
    }

    fun cancel() {
        clearHold(); cancelDrag(); four.cancel(); taps.cancelTap()
        previous = emptyList(); active = false; setMode(TouchMode.IDLE)
        four = FourFingerGesture(16f * density)
        taps = MultiFingerTapTracker(12f * density, 220)
    }

    /** External lifecycle/settings cancellation cannot turn trailing contacts into a new gesture. */
    fun invalidate() {
        blockedUntilLift = blockedUntilLift || active
        cancel()
    }
}
