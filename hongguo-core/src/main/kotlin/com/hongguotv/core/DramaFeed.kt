// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

// 首页连续播放的数据状态与选择规则，供界面层驱动作品切换。

/** Homepage feed navigation and in-session episode position. Deliberately has no library, account or progress-upload dependency. */
class DramaFeedState {
    var recommendationCursor = RecommendationCursor()
        private set

    private val sourceCursors = mutableMapOf("recommend" to recommendationCursor)

    private data class SourcePage(
        val items: List<Series>,
        val index: Int,
        val page: Int,
        val hasMore: Boolean,
        val offset: Int,
    )

    private val sourcePages = mutableMapOf<String, SourcePage>()

    private fun sourceKey() = if (recommendation) "recommend" else category.route

    var recommendation = true
        private set

    var category = DramaCategory.COMIC
        private set

    var items: List<Series> = emptyList()
        private set

    var index = 0
        private set

    var page = 0
        private set

    var hasMore = true
        private set

    var episodeIndex = 0
    var position = 0L
    var userPaused = false
    var ended = false
    var offset = 0
        private set

    val current
        get() = items.getOrNull(index)

    val next
        get() = items.getOrNull(index + 1)

    private fun rememberSource() {
        sourceCursors[sourceKey()] = recommendationCursor
        if (items.isNotEmpty())
            sourcePages[sourceKey()] = SourcePage(items, index, page, hasMore, offset)
    }

    private fun restoreSource() {
        val saved = sourcePages[sourceKey()]
        if (saved == null) {
            refresh()
            return
        }
        recommendationCursor = checkNotNull(sourceCursors[sourceKey()])
        items = saved.items
        index = saved.index
        page = saved.page
        hasMore = saved.hasMore
        offset = saved.offset
        resetPlayback()
    }

    fun select(category: DramaCategory) {
        require(category in listOf(DramaCategory.COMIC, DramaCategory.REAL))
        rememberSource()
        this.category = category
        recommendation = false
        restoreSource()
    }

    fun refresh(resetRecommendations: Boolean = false, restartCurrentSource: Boolean = false) {
        if (resetRecommendations) {
            sourceCursors.clear()
            sourcePages.clear()
        } else {
            sourcePages.remove(sourceKey())
        }
        recommendationCursor = sourceCursors[sourceKey()]?.nextBatch(restartSession = restartCurrentSource) ?: RecommendationCursor()
        sourceCursors[sourceKey()] = recommendationCursor
        items = emptyList()
        index = 0
        page = 0
        hasMore = true
        offset = 0
        resetPlayback()
    }

    fun recommend() {
        rememberSource()
        recommendation = true
        restoreSource()
    }

    fun append(result: CatalogPage, requestedPage: Int) {
        require(requestedPage == page + 1)
        val before = items.size
        items = (items + result.items).distinctBy { it.id }
        hasMore = result.hasMore && items.size > before
        page = requestedPage
        if (items.size > 72) {
            val drop = minOf(items.size - 72, index)
            items = items.drop(drop)
            index -= drop
            offset += drop
        }
    }

    /** Start another recommendation batch without clearing the visible carousel or playback. */
    fun continueBatch() {
        recommendationCursor = recommendationCursor.nextBatch(restartSession = true)
        sourceCursors[sourceKey()] = recommendationCursor
        page = 0
        hasMore = true
    }

    fun move(delta: Int): Boolean {
        val next = index + delta
        if (next !in items.indices) return false
        index = next
        resetPlayback()
        return true
    }

    private fun resetPlayback() {
        episodeIndex = 0
        position = 0
        userPaused = false
        ended = false
    }
}
