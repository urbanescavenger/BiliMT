// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Only boards verified on the public first-party website. */
enum class RankBoard(val label: String, val route: String, val key: String) {
    ALL("综合", "hot-drama", "hongguo"),
    REAL("真人剧", "hot-real-drama", "real"),
    COMIC("漫剧", "hot-comic-drama", "comic"),
    AI("AI 剧", "hot-ai-drama", "ai");

    val loader
        get() = "rank_$route/page"
}

data class RankSnapshot(val board: RankBoard, val items: List<Series>, val ranking: ComicRanking) {
    // Website tags mix genres with content types and cast/character names.
    // Only known genre tokens can become filters; never infer genres from prose.
    val topics: List<String>
        get() =
            items
                .flatMap { topics(it) }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
                )
                .map { it.key }

    fun filtered(topic: String) =
        if (topic.isBlank()) items else items.filter { topic in topics(it) }

    private fun topics(item: Series) =
        item.tags.split(" · ").map(String::trim).filter { it in genreTopics }.distinct()

    companion object {
        // Deliberately conservative: unknown website metadata stays on the card,
        // but must not create a new filter. Content types already have board tabs.
        private val genreTopics =
            setOf(
                "都市", "都市修仙", "玄幻", "仙侠", "修仙", "武侠", "奇幻", "科幻",
                "穿越", "重生", "逆袭", "系统", "脑洞", "异能", "末世", "游戏", "电竞",
                "古装", "古风", "古代", "历史", "宫廷", "宫斗", "宅斗", "权谋", "民国", "年代",
                "爱情", "言情", "现言", "古言", "甜宠", "虐恋", "暗恋", "恋爱", "婚恋",
                "闪婚", "先婚后爱", "破镜重圆", "总裁", "霸总", "豪门", "豪门恩怨",
                "家庭", "家族", "亲情", "萌宝", "女性", "女性成长", "大女主",
                "职场", "校园", "青春", "成长", "励志", "创业", "现实", "乡村", "农村",
                "剧情", "喜剧", "搞笑", "轻喜剧", "治愈", "悬疑", "推理", "探案", "犯罪",
                "复仇", "战神", "神医", "赘婿", "高手下山", "热血", "动作", "冒险", "战斗",
            )
    }
}

/** Retained while visiting details or another tab, bounded to four boards. */
/**
 * 每个榜单独立的会话缓存：TTL 内切换零请求，过期后由界面层静默续期；
 * 在途标记按榜去重，多个榜单可并行加载、互不取消。真正的重新获取只发生在
 * TTL 过期的后台续期或用户手动刷新。
 */
class RankBrowserState(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 10 * 60_000,
) {
    var board = RankBoard.ALL
    var topic = ""
    var page = 1
    var selectedId = ""
    val snapshots = mutableMapOf<RankBoard, RankSnapshot>()
    private val loadedAt = mutableMapOf<RankBoard, Long>()
    private val refreshing = mutableSetOf<RankBoard>()

    /** 界面层只读视图：该榜是否正在加载。 */
    fun isRefreshing(target: RankBoard) = target in refreshing

    /** TTL 内已有缓存则无需任何请求。 */
    fun isFresh(target: RankBoard = board): Boolean {
        val at = loadedAt[target] ?: return false
        return clock() - at < ttlMillis
    }

    /** 声明开始拉取；该榜已在途时返回 false（同榜去重，跨榜互不影响）。 */
    fun beginRefresh(target: RankBoard): Boolean {
        synchronized(refreshing) {
            if (target in refreshing) return false
            refreshing += target
            return true
        }
    }

    /** 拉取结束：成功记录时间戳并缓存快照，失败保留旧缓存只清在途标记。 */
    fun endRefresh(target: RankBoard, snapshot: RankSnapshot?) {
        synchronized(refreshing) {
            refreshing -= target
            if (snapshot != null) {
                snapshots[target] = snapshot
                loadedAt[target] = clock()
            }
        }
    }

    fun select(next: RankBoard) {
        if (board != next) {
            board = next
            topic = ""
            page = 1
            selectedId = ""
        }
    }

    fun filter(next: String) {
        topic = next
        page = 1
        selectedId = ""
    }
}
