// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** One-use public metadata, so an explicit refresh still goes to the server. */
internal class WarmPageCache<T>(private val limit: Int, private val ttl: Long = 300_000,
    private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = linkedMapOf<String, Pair<Long, T>>()
    @Synchronized fun put(key: String, value: T) {
        RequestScope.ensureActive()
        entries.remove(key)
        entries[key] = clock() to value
        while (entries.size > limit) entries.remove(entries.keys.first())
    }
    @Synchronized fun take(key: String): T? = entries.remove(key)?.let {
        it.second.takeIf { _ -> clock() - it.first in 0 until ttl }
    }
}
