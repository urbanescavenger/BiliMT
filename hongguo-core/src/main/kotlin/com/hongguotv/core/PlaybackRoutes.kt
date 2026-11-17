// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** 每个匿名会话独立记忆路径健康度，不保存身份、请求参数或媒体地址。 */
internal class PlaybackRoutes {
    data class Route(val single: Boolean, val host: String)
    private data class Health(var success: Long = Long.MIN_VALUE, var blockedUntil: Long = 0, var empty: Boolean = false)
    private val routes = listOf(Route(true, PlayRetryPlan.host(0))) +
        PlayRetryPlan.hosts.map { Route(false, it) }
    private val health = routes.associateWith { Health() }

    @Synchronized
    fun plan(now: Long, speculative: Boolean): List<Route> {
        val available = routes.filter { health.getValue(it).blockedUntil <= now }
            .sortedByDescending { health.getValue(it).success }
        if (available.isNotEmpty()) {
            if (speculative) return available.take(1)
            // 单集接口不适用于部分内容时，仍保留一次最早冷却的批量接口探测，避免只剩单集接口可选。
            return if (available.none { !it.single }) available + listOf(routes.filter { !it.single }.minBy { health.getValue(it).blockedUntil })
                else available
        }
        // 后台不探测冷却路径；前台只探测最早可恢复的一条，不再逐个撞已知空响应。
        return if (speculative) emptyList() else listOf(routes.filter { !it.single }.minBy { health.getValue(it).blockedUntil })
    }

    @Synchronized
    fun exhausted(now: Long) = routes.all { health.getValue(it).let { h -> h.empty && h.blockedUntil > now } }

    @Synchronized
    fun success(route: Route, now: Long) {
        health.getValue(route).apply { success = now; blockedUntil = 0; empty = false }
    }

    @Synchronized
    fun failed(route: Route, now: Long, empty: Boolean) {
        health.getValue(route).apply {
            blockedUntil = now + if (empty) 30_000 else 5_000
            this.empty = empty
        }
    }
}
