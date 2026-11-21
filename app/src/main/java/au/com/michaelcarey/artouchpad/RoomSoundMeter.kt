package au.com.michaelcarey.artouchpad

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class RoomLevel(
    val rms: Int? = null,
    val peak: Int? = null,
    val recentAverage: Int? = null,
    val status: String = "Starting mic…",
) {
    fun label(reference: Int?): String {
        if (rms == null) return status
        val delta = reference?.let { rms - it }
        val change = delta?.let { " · ${if (it > 0) "+" else ""}$it dB vs quiet" }.orEmpty()
        return "$rms dBFS · Peak $peak$change"
    }
}

internal fun dbfs(amplitude: Double): Int = (20 * log10(amplitude.coerceIn(1.0 / 32768, 1.0))).roundToInt()

/** PCM exists only in this read buffer. One collector owns one recording session. */
@SuppressLint("MissingPermission") // The hosting activity checks RECORD_AUDIO before collection.
internal fun roomLevels(context: Context) = flow {
    val audio = context.getSystemService(AudioManager::class.java)
    val mic = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    if (mic == null) {
        emit(RoomLevel(status = "Phone mic unavailable"))
        return@flow
    }
    val source = if (audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
        MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    val rate = 44100
    val chunk = ShortArray(rate / 8)
    val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val record = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBytes, chunk.size * 4))
    try {
        check(record.state == AudioRecord.STATE_INITIALIZED && record.setPreferredDevice(mic))
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        val peaks = ArrayDeque<Int>()
        val meanSquares = ArrayDeque<Double>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = record.read(chunk, 0, chunk.size)
            check(read > 0)
            if (record.routedDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_MIC || record.activeRecordingConfiguration?.isClientSilenced == true) {
                peaks.clear()
                meanSquares.clear()
                emit(RoomLevel(status = "Phone mic unavailable"))
                continue
            }
            var squares = 0.0
            var peak = 0
            for (i in 0 until read) {
                val value = chunk[i].toInt()
                squares += value.toDouble() * value
                peak = maxOf(peak, abs(value))
            }
            // Hold peaks over the last three seconds so brief spikes remain legible.
            peaks.addLast(peak)
            if (peaks.size > 24) peaks.removeFirst()
            meanSquares.addLast(squares / read)
            if (meanSquares.size > 8) meanSquares.removeFirst()
            val heldPeak = peaks.max()
            if (heldPeak == 0) emit(RoomLevel(status = "No mic signal"))
            else emit(RoomLevel(
                rms = dbfs(sqrt(squares / read) / 32768),
                peak = dbfs(heldPeak / 32768.0),
                recentAverage = meanSquares.takeIf { it.size == 8 && it.any { power -> power > 0 } }
                    ?.let { dbfs(sqrt(it.average()) / 32768) },
            ))
        }
    } finally {
        try { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
        finally { record.release() }
    }
}.flowOn(Dispatchers.IO)
