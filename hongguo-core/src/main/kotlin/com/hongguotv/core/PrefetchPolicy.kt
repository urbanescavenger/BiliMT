// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

object PrefetchPolicy {
    const val WARM_BYTES = 2 * 1024 * 1024

    /** Wall-clock time to the end matters: long episodes must not outlive the slot TTL. */
    fun nearEnd(position: Long, duration: Long, speed: Float): Boolean {
        if (
            duration <= 0 || position < 0 || position >= duration || !speed.isFinite() || speed <= 0
        )
            return false
        return (duration - position) / speed <= 30_000f
    }

    /** Start sooner on short dramas, but do not compete with a starving current episode. */
    fun canStart(
        position: Long,
        duration: Long,
        speed: Float,
        stableFor: Long,
        bufferedAhead: Long,
    ): Boolean =
        nearEnd(position, duration, speed) &&
            stableFor >= 1500 &&
            bufferedAhead >= minOf(5000L, duration - position)
}
