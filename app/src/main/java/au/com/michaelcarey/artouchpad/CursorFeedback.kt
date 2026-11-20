package au.com.michaelcarey.artouchpad

import android.util.Log
import java.util.concurrent.Executors
import kotlin.random.Random

/** Native colour for the duration of drag-ready mode, with a brief size pulse.
 * Settings work never runs on the input or main threads. The shell waits on a pipe
 * owned by this service; release or service death closes it and restores the style.
 * Restore only values we still own: a user's new pointer preference always wins.
 */
internal class CursorFeedback {
    private val worker = Executors.newSingleThreadExecutor { work ->
        Thread(work, "cursor-feedback").apply { isDaemon = true }
    }
    private val lock = Any()
    private var generation = 0
    private var active: Process? = null

    fun pulse() {
        val request = synchronized(lock) {
            endActive()
            ++generation
        }
        worker.execute {
            try {
                if (synchronized(lock) { request != generation }) return@execute
                val read = ProcessBuilder("/system/bin/sh", "-c",
                    "settings get system pointer_fill_style; settings get system pointer_scale")
                    .redirectErrorStream(true).start()
                val original = read.inputStream.bufferedReader().use { it.readLines() }
                if (read.waitFor() != 0 || original.size != 2) return@execute
                val plan = CursorPulsePlan.create(original[0], original[1], Random.nextInt())
                val process = synchronized(lock) {
                    if (request != generation) return@execute
                    ProcessBuilder("/system/bin/sh", "-c", PULSE, "cursor-feedback",
                        original[0], original[1], plan.colour.toString(), plan.scale.toString())
                        .redirectErrorStream(true).start().also { active = it }
                }
                // Wait for the EXIT trap before another gesture reads its baseline.
                val errors = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
                val exit = process.waitFor()
                synchronized(lock) { if (active === process) active = null }
                if (exit != 0) Log.w("CursorFeedback", "Pulse failed: $errors")
            } catch (e: Exception) {
                Log.w("CursorFeedback", "Cursor feedback unavailable", e)
            }
        }
    }

    fun cancel() {
        synchronized(lock) { generation++; endActive() }
    }

    private fun endActive() {
        runCatching { active?.outputStream?.close() }
    }

    companion object {
        // All values are positional arguments, never interpolated into shell source.
        // Android's mksh read returns 142 on timeout, 0 on a release line and 1 on EOF.
        // A closed service pipe therefore ends even the initial size pulse immediately.
        internal val PULSE = """
            old_colour=${'$'}1
            old_scale=${'$'}2
            pulse_colour=${'$'}3
            pulse_scale=${'$'}4
            restore() {
                key=${'$'}1
                expected=${'$'}2
                original=${'$'}3
                current=${'$'}(settings get system "${'$'}key")
                if [ "${'$'}current" = "${'$'}expected" ]; then
                    if [ "${'$'}original" = null ]; then
                        settings delete system "${'$'}key" >/dev/null
                    else
                        settings put system "${'$'}key" "${'$'}original"
                    fi
                fi
            }
            cleanup() {
                restore pointer_fill_style "${'$'}pulse_colour" "${'$'}old_colour"
                restore pointer_scale "${'$'}pulse_scale" "${'$'}old_scale"
            }
            trap cleanup EXIT
            trap 'exit 0' TERM INT HUP
            settings put system pointer_fill_style "${'$'}pulse_colour" || exit 1
            settings put system pointer_scale "${'$'}pulse_scale" || exit 1
            read -r -t 0.2 release
            [ "${'$'}?" -eq 142 ] || exit 0
            restore pointer_scale "${'$'}pulse_scale" "${'$'}old_scale"
            read -r release
            exit 0
        """.trimIndent()
    }
}
