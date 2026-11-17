// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

object PlaybackQuality {
    val options = listOf(480, 720, 1080)

    fun normalize(value: Int) = value.takeIf { it in options } ?: 1080
}

/**
 * One automatic recovery budget per episode; only an explicit user retry resets it.
 *
 * 两段式（issue #38）：快段三次紧跟失败现场（约 8.5 秒），慢段退避拉长到累计五分钟，覆盖
 * 服务端秒级到分钟级的空响应窗口；快段救「一瞬间坏了」，慢段救「这一阵子坏了」。
 */
class RecoveryBudget {
    var attempts = 0
        private set

    val remaining
        get() = attempts < DELAYS_MILLIS.size

    val delayMillis
        get() = DELAYS_MILLIS.getOrNull(attempts)

    val total
        get() = DELAYS_MILLIS.size

    /** 快段（前三）之后进入慢段：文案从倒计时切换为「正在自动恢复」。 */
    val slow
        get() = attempts >= FAST_ATTEMPTS

    fun consume(): Boolean {
        if (!remaining) return false
        attempts++
        return true
    }

    fun reset() {
        attempts = 0
    }

    companion object {
        const val FAST_ATTEMPTS = 3
        private val DELAYS_MILLIS =
            listOf(1000L, 2500L, 5000L, 15000L, 30000L, 60000L, 90000L, 105000L)
    }
}

data class ResumeTarget(val index: Int, val position: Long)

object ResumePlayback {
    // 生成本次播放的临时进度，不写入本机历史。
    // 云端与官网的分集 ID 可能不同：先匹配 ID，无法匹配时才使用有效云端集号。
    fun progress(
        detail: Detail,
        local: BackupProgress?,
        cloud: CloudProgress?,
        preferCloud: Boolean = false,
    ): BackupProgress? {
        val device = local?.takeIf { it.series.id == detail.series.id }
        val remote =
            cloud
                ?.takeIf { it.series.id == detail.series.id }
                ?.let {
                    val index =
                        detail.episodes.indexOf(it.episodeId).takeIf { n -> n >= 0 }
                            ?: it.episodeIndex.takeIf { n -> n in detail.episodes.indices }
                            ?: return@let null
                    BackupProgress(
                        detail.series,
                        detail.episodes[index],
                        index,
                        it.position.coerceAtLeast(0),
                        it.duration.coerceAtLeast(0),
                        false,
                        it.updatedAt,
                    )
                }
        // 明确点击账户历史时采用该条云端记录；普通详情入口选较新的记录，同时间优先本机。
        return if (preferCloud && remote != null) remote
        else if (remote != null && (device == null || remote.updatedAt > device.updatedAt)) remote
        else device
    }

    fun target(
        episodes: List<String>,
        episodeId: String?,
        position: Long,
        completed: Boolean,
    ): ResumeTarget {
        require(episodes.isNotEmpty())
        val index = episodes.indexOf(episodeId)
        if (index < 0) return ResumeTarget(0, 0)
        return if (completed) ResumeTarget((index + 1).coerceAtMost(episodes.lastIndex), 0)
        else ResumeTarget(index, position.coerceAtLeast(0))
    }
}

/** Seen count is a high-water mark, so temporary catalog shrinkage is not a new update. */
data class FavoriteUpdate(val total: Int, val seen: Int, val checkedAt: Long) {
    val added
        get() = (total - seen).coerceAtLeast(0)

    fun observe(count: Int, now: Long, acknowledge: Boolean = false): FavoriteUpdate {
        require(count > 0)
        if (now < checkedAt) return this
        return FavoriteUpdate(count, if (acknowledge) maxOf(seen, count) else seen, now)
    }

    fun json() = JSONObject().put("total", total).put("seen", seen).put("checkedAt", checkedAt)

    companion object {
        fun first(count: Int, now: Long): FavoriteUpdate {
            require(count > 0)
            return FavoriteUpdate(count, count, now)
        }

        fun decode(value: JSONObject?) =
            runCatching {
                    require(value != null)
                    val total = value.getInt("total")
                    val seen = value.getInt("seen")
                    val time = value.getLong("checkedAt")
                    require(total > 0 && seen > 0 && time >= 0)
                    FavoriteUpdate(total, seen, time)
                }
                .getOrNull()
    }
}
