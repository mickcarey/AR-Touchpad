package au.com.michaelcarey.artouchpad

/** Observed device state, never an optimistic toggle of our last button press. */
data class MediaState(
    val playing: Boolean? = null,
    val muted: Boolean? = null,
    val volume: Int = 0,
    val maxVolume: Int = 0,
    val error: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
) {
    val volumeFraction get() = if (maxVolume > 0) {
        if (muted == true) 0f else (volume.toFloat() / maxVolume).coerceIn(0f, 1f)
    } else 0f
}
