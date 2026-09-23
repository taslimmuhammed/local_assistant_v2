package com.local.assistant.llm

/**
 * The candidate context sizes calibration walks, largest first.
 *
 * Kept as a plain object with no Android dependencies so the selection rules can be tested
 * without a device — the probing itself is slow and destructive enough that the arithmetic
 * around it should not also be a mystery.
 */
object ContextLadder {

    /** Descending. The top is the model's documented 32k ceiling. */
    val RUNGS: List<Int> = listOf(32768, 24576, 16384, 12288, 8192, 6144, 4096, 2048)

    val LARGEST: Int = RUNGS.first()
    val SMALLEST: Int = RUNGS.last()

    /**
     * Where to begin probing, from the device's *total* memory.
     *
     * Deliberately not free memory: Android reclaims cached pages on demand, so a phone with
     * 15 GB installed can report under 3 GB available purely because other apps are warm. Keying
     * off that made the starting rung a lottery decided by whatever else was open, and on a large
     * device it never even reached for the top of the ladder.
     *
     * The bands are deliberately conservative, because overshooting is not free: the process is
     * killed outright, so every rung that is too large costs the user a visible crash before the
     * guard can step down. Measured on a 15.5 GB device, where 16384 confirmed and 24576 died
     * while filling — so that device now starts exactly where it will finish.
     *
     * Probing still decides the answer; this only chooses where to begin, and the ladder can
     * only walk downwards from here. A device that could hold more than its band suggests needs
     * the manual override.
     */
    fun startingRung(totalMemoryBytes: Long): Int {
        val gigabytes = totalMemoryBytes.toDouble() / (1L shl 30)
        return when {
            gigabytes < 4 -> 4096
            gigabytes < 8 -> 8192
            gigabytes < 12 -> 12288
            gigabytes < 24 -> 16384
            else -> LARGEST
        }
    }

    /**
     * Rungs to try, largest first, starting no higher than [start] and staying strictly below
     * [knownBad] when a previous attempt at that size killed the process.
     */
    fun rungsToTry(start: Int, knownBad: Int? = null): List<Int> =
        RUNGS.filter { it <= start && (knownBad == null || it < knownBad) }

    /** The next size down from [size], or null if [size] is already the smallest. */
    fun nextBelow(size: Int): Int? = RUNGS.firstOrNull { it < size }

    /** Snaps an arbitrary size (e.g. one the runtime reported) onto the ladder, rounding down. */
    fun snapDown(size: Int): Int = RUNGS.firstOrNull { it <= size } ?: SMALLEST
}
