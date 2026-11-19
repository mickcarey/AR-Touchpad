package com.pgratz.artouchpad

/** Release every attempted key, including one whose down may have reached Android before throwing. */
internal fun sendKeyChord(keys: List<Pair<Int, Int>>, send: (key: Int, down: Boolean, meta: Int) -> Unit) {
    val pressed = ArrayList<Pair<Int, Int>>(keys.size)
    var meta = 0
    try {
        for ((key, flags) in keys) {
            meta = meta or flags
            pressed.add(key to flags)
            send(key, true, meta)
        }
    } finally {
        for ((key, flags) in pressed.asReversed()) {
            meta = meta and flags.inv()
            runCatching { send(key, false, meta) }
        }
    }
}
