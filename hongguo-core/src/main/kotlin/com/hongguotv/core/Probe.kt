package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient

fun main(args: Array<String>) {
    val repository = if (args.contains("--http1")) ContentRepository(http = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()) else ContentRepository()
    if (args.contains("--issue61")) {
        try {
            for (keyword in listOf("无限白粥", "朕可是忠臣", "无限白粥")) {
                val found = repository.searchAll(keyword)
                println("keyword=$keyword; matches=${found.items.map { it.title to it.id }}")
                for (series in found.items.take(1)) {
                    val detail = repository.detail(series.id)
                    println("series=${series.title}; episodes=${detail.episodes.size}")
                    for (id in detail.episodes.take(2)) {
                        try {
                            val ladder = repository.streamLadderWithCompatibility(id)
                            println("episode=$id; renditions=${ladder.renditions.size}")
                            val stream = ladder.renditions.first().toStreamInfo()
                            RemoteVideo(repository.http, stream).use { video ->
                                video.prepare()
                                println("episode=$id; bytes=${video.read(0, 65536).size}")
                            }
                        } catch (e: Exception) {
                            println("episode=$id; error=${e.javaClass.simpleName}; message=${e.message?.replace(Regex("https?://[^\\s]+"), "[url]")}")
                        }
                    }
                }
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--verify-tv-playback")) {
        var failures = 0
        var verified = 0
        try {
            val home = repository.home()
            for ((seriesIndex, series) in home.items.take(2).withIndex()) {
                val detail = repository.detail(series.id)
                println("tv series=${detail.series.title}; episodes=${detail.episodes.size}")
                for ((index, id) in detail.episodes.take(2).withIndex()) {
                    try {
                        val ladder = repository.streamLadderWithCompatibility(id)
                        val rendition = ladder.playbackOrder(720) { it.codec.lowercase() in listOf("h264", "h265", "avc", "hevc") }.first()
                        RemoteVideo(repository.http, rendition.toStreamInfo()).use { remote ->
                            remote.prepare()
                            val file = java.io.File("/tmp/hongguo-tv-$seriesIndex-$index.mp4")
                            file.outputStream().use { output ->
                                var offset = 0L
                                while (offset < remote.total) {
                                    val bytes = remote.read(offset, minOf(1024 * 1024L, remote.total - offset).toInt())
                                    check(bytes.isNotEmpty())
                                    output.write(bytes)
                                    offset += bytes.size
                                }
                            }
                            println("tv episode=${index + 1}; quality=${rendition.quality}; codec=${rendition.codec}; bytes=${remote.total}; file=$file")
                            verified++
                        }
                    } catch (error: Exception) {
                        failures++
                        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                        fun report(problem: Throwable) {
                            if (!seen.add(problem) || seen.size > 20) return
                            println("tv failure=${problem.javaClass.simpleName}; trace=" + when (problem) {
                                is EmptyPlaybackResponseException -> problem.detail
                                is PlaybackDiagnosticException -> problem.detail
                                else -> "unavailable"
                            })
                            problem.cause?.let(::report)
                            problem.suppressed.forEach(::report)
                        }
                        report(error)
                        if (args.contains("--fail-fast")) throw error
                    }
                }
            }
            println("tv summary verified=$verified; failed=$failures")
            check(verified > 0 && failures == 0) { "TV playback verification failed ($failures failures)" }
        } finally { repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() }
        return
    }
    if (args.contains("--verify-playback")) {
        val at = args.indexOf("--verify-playback")
        val seriesId = args.getOrNull(at + 1) ?: error("用法: --verify-playback <seriesId> [count]")
        val count = args.getOrNull(at + 2)?.toIntOrNull()?.coerceIn(1, 5) ?: 2
        try {
            val detail = repository.detail(seriesId)
            println("verify title=${detail.series.title}; episodes=${detail.episodes.size}")
            detail.episodes.take(count).forEachIndexed { index, id ->
                val stream = repository.stream(id, 720)
                RemoteVideo(repository.http, stream).use { remote ->
                    remote.prepare()
                    val first = remote.read(0, minOf(remote.total, 1024 * 1024).toInt())
                    val tail = remote.read((remote.total - 65536).coerceAtLeast(0), minOf(remote.total, 65536).toInt())
                    check(first.size > 8 && String(first, 4, 4) == "ftyp" && tail.isNotEmpty())
                    println("verify episode=${index + 1}; quality=${stream.quality}; mediaBytes=${remote.total}; head=${first.size}; tail=${tail.size}; mp4=true")
                }
            }
        } finally { repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() }
        return
    }
    if (args.contains("--category-recommendations")) {
        try {
            for (category in listOf(DramaCategory.COMIC, DramaCategory.REAL)) {
                val cursor = RecommendationCursor()
                val feed = RecommendationFeed(repository.http, cursor = cursor, category = category)
                val first = feed.page(1)
                val second = feed.page(2)
                val fresh = second.items.count { row -> first.items.none { it.id == row.id } }
                check(first.items.isNotEmpty() && fresh > 0)
                val detail = repository.detail(first.items.first().id)
                check(detail.episodes.isNotEmpty())
                first.trialHints[first.items.first().id]?.let {
                    check(it.episodeId == detail.episodes.first())
                    check(it.episodeCount == detail.episodes.size)
                }
                println("category=${category.name}; directTrialHints=${first.trialHints.size}")
                if (args.contains("--prepare-video")) {
                    val remote =
                        RemoteVideo(
                            repository.http,
                            repository.stream(detail.episodes.first(), 720),
                        )
                    try {
                        remote.prepare()
                        check(remote.total > 0)
                        println("category=${category.name}; videoPrepared=true")
                    } finally {
                        remote.close()
                    }
                }
                println(
                    "category=${category.name}; first=${first.items.size}; second=${second.items.size}; new=$fresh; detailEpisodes=${detail.episodes.size}"
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--recommendations")) {
        try {
            val cursor = RecommendationCursor()
            val feed = RecommendationFeed(repository.http, cursor = cursor)
            val first = feed.page(1)
            val second = feed.page(2)
            val new = second.items.count { row -> first.items.none { it.id == row.id } }
            check(first.items.isNotEmpty() && second.items.isNotEmpty() && new > 0)
            val refreshed = RecommendationFeed(repository.http, cursor = cursor.nextBatch()).page(1)
            val initial = first.items.first()
            val hint = first.trialHints[initial.id]
            if (hint != null) {
                val detail = repository.detail(initial.id)
                check(hint.episodeId == detail.episodes.first())
                check(hint.episodeCount == detail.episodes.size)
            }
            println("recommendationDirectTrialHints=${first.trialHints.size}")
            println(
                "officialFeed=true; first=${first.items.size}; second=${second.items.size}; new=$new; firstMore=${first.hasMore}; secondMore=${second.hasMore}; refreshed=${refreshed.items.size}; authenticated=false"
            )
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--discovery")) {
        try {
            for (category in DramaCategory.entries) {
                val first = repository.discover(category)
                val info = checkNotNull(first.discovery)
                check(first.items.isNotEmpty() && info.topics.isNotEmpty())
                val topic = info.topics.first()
                val filtered = repository.discover(category, topic.id)
                val second = repository.discover(category, topic.id, 2)
                check(filtered.items.isNotEmpty() && second.items.isNotEmpty())
                check(filtered.items.map { it.id } != second.items.map { it.id })
                println(
                    "category=${category.route}; topics=${info.topics.joinToString { it.label }}; first=${first.items.size}; filtered=${filtered.items.size}; secondPage=${second.items.size}; totalPages=${info.totalPages}"
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--tv-rankings")) {
        try {
            for (board in RankBoard.entries) {
                val snapshot = repository.rankSnapshot(board)
                check(snapshot.items.isNotEmpty()) { "Empty ${board.name} board" }
                println(
                    "board=${board.name}; items=${snapshot.items.size}; topics=${snapshot.topics.joinToString(",")}; ranks=${snapshot.ranking.positions.values.mapNotNull { it.rank }.minOrNull()}..${snapshot.ranking.positions.values.mapNotNull { it.rank }.maxOrNull()}"
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--issue38")) {
        // 复现 issue #38「播放信息接口返回空响应」：同一请求构造下对比设备身份、device_level、
        // 备选 host 与时钟偏差。输出仅含计数与档位元数据，不打印地址与凭据。
        val sessionA = PlaybackDeviceSession()
        val sessionB = PlaybackDeviceSession()
        val repo = ContentRepository(playbackSession = sessionB)
        try {
            val keyword =
                args.getOrNull(args.indexOf("--issue38") + 1)?.takeIf { !it.startsWith("--") }
                    ?: "美人替嫁祁王"
            val found =
                runCatching { repo.search(keyword) }.getOrElse { repo.searchAll(keyword) }
            val series =
                found.items.firstOrNull { it.title == "春色韫韫，美人替嫁祁王" }
                    ?: found.items.firstOrNull { "美人替嫁祁王" in it.title }
            checkNotNull(series) { "search=${found.items.size}; no match for $keyword" }
            println("issue38 series=${series.title}; id=${series.id}")
            val detail = repo.detail(series.id)
            println("issue38 episodes=${detail.episodes.size}")
            val episode8 = detail.episodes.getOrNull(7) ?: detail.episodes.first()
            val targets =
                listOfNotNull(
                    detail.episodes.getOrNull(0)?.let { "ep1" to it },
                    "ep8" to episode8,
                )
            val identityA = sessionA.profile(repo.http)
            println(
                "issue38 identity=A; storeRegion=${Regex("store-region=([^;]+)").find(identityA.cookie)?.groupValues?.get(1) ?: "none"}"
            )
            for ((label, episodeId) in targets) {
                for (level in listOf(1, 3)) {
                    println(
                        "issue38 probe=$label; level=$level; ${playerProbe(repo.http, sessionA, episodeId, level, PLAYER_HOST_PRIMARY)}"
                    )
                }
            }
            // 失效分集 id 假设：收藏/历史里可能保存着下架或重发前的旧 id。
            println(
                "issue38 probe=bogus; level=1; ${playerProbe(repo.http, sessionA, "1", 1, PLAYER_HOST_PRIMARY)}"
            )
            for (host in PLAYER_HOST_FALLBACKS) {
                println(
                    "issue38 host=$host; ${playerProbe(repo.http, sessionA, episode8, 1, host)}"
                )
            }
            // 端到端：与线上 streamLadder 完全一致（repository 自持身份 B，含三次退避重试）。
            try {
                val ladder = repo.streamLadder(episode8)
                println(
                    "issue38 e2e=ok; tiers=${ladder.renditions.size}; codecs=${ladder.renditions.joinToString { it.codec }}"
                )
            } catch (problem: Exception) {
                println(
                    "issue38 e2e=FAILED; ${problem.javaClass.simpleName}; ${problem.message?.take(120)}"
                )
            }
            // 身份标记实验：被标记身份是否持续失败、新身份是否立即恢复。
            println(
                "issue38 again=B1; ${playerProbe(repo.http, sessionB, episode8, 1, PLAYER_HOST_PRIMARY)}"
            )
            println(
                "issue38 again=B2; ${playerProbe(repo.http, sessionB, episode8, 1, PLAYER_HOST_PRIMARY)}"
            )
            val sessionC = PlaybackDeviceSession()
            println(
                "issue38 again=C1; ${playerProbe(repo.http, sessionC, episode8, 1, PLAYER_HOST_PRIMARY)}"
            )
            val identityC = sessionC.profile(repo.http)
            println(
                "issue38 identity=C; storeRegion=${Regex("store-region=([^;]+)").find(identityC.cookie)?.groupValues?.get(1) ?: "none"}"
            )
            println(
                "issue38 again=B3; ${playerProbe(repo.http, sessionB, episode8, 1, PLAYER_HOST_PRIMARY)}"
            )
            // 窗口捕捉：2 分钟内每 ~4 秒轮询三条路径（主 host 复用连接 / 主 host 新连接 /
            // 备 host 新连接）。空响应窗口出现时，当场可见哪条路径能逃出去。
            val deadline = System.currentTimeMillis() + 120_000
            var cycle = 0
            var empties = 0
            while (System.currentTimeMillis() < deadline) {
                cycle++
                val marks =
                    listOf(
                            playerProbe(repo.http, sessionA, episode8, 1, PLAYER_HOST_PRIMARY),
                            playerProbe(repo.http, sessionA, episode8, 1, PLAYER_HOST_PRIMARY, fresh = true),
                            playerProbe(repo.http, sessionA, episode8, 1, PLAYER_HOST_FALLBACKS.first(), fresh = true),
                        )
                        .map { if (it.contains("EMPTY")) "E" else "." }
                empties += marks.count { it == "E" }
                if (marks.any { it == "E" })
                    println(
                        "issue38 window; cycle=$cycle; pooled=${marks[0]}; fresh=${marks[1]}; altHost=${marks[2]}"
                    )
                Thread.sleep(3000)
            }
            println("issue38 windowSummary; cycles=$cycle; empties=$empties")
        } finally {
            repo.http.connectionPool.evictAll()
            repo.http.dispatcher.executorService.shutdown()
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--tab-matrix")) {
        // 2026-11-02 video_tab 切换后续（方案 C）：官方现行 GET /reading/bookapi/video_tab/
        // multi_video_model/v1/ 现状只给 bytevc2 低档 + bytevc1 1080P，竖屏 1080x1920 超出
        // 电视解码上限且降无可降。跑请求参数矩阵（use_os_player / need_mp4_align / source /
        // video_id_type / device_level 等），目标找回低档 bytevc1 供给。可选位置参数指定集 id；
        // 命中可解档（* = max(w,h)≤1088）的非 base 变体用其余样本复核。输出仅含档位元数据，
        // 不打印地址与凭据。
        val session = PlaybackDeviceSession()
        val repo = ContentRepository(playbackSession = session)
        try {
            val explicit = args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) }
            val samples =
                if (explicit != null) {
                    listOf("explicit" to explicit)
                } else {
                    repo.home().items.take(3).mapNotNull { item ->
                        runCatching { repo.detail(item.id).episodes.firstOrNull() }
                            .getOrNull()
                            ?.let { item.id to it }
                    }
                }
            check(samples.isNotEmpty()) { "no samples" }
            val firstEpisode = samples.first().second
            val base = tabBaseParams()
            val variants: List<Pair<String, Map<String, String>>> =
                listOf(
                    "base" to base,
                    "use_os_player=1" to base + ("use_os_player" to "1"),
                    "need_mp4_align=1" to base + ("need_mp4_align" to "1"),
                    "os1+mp4a1" to base + mapOf("use_os_player" to "1", "need_mp4_align" to "1"),
                    "use_os_player=true" to base + ("use_os_player" to "true"),
                    "need_all=0" to base + ("need_all_video_definition" to "0"),
                    "device_level=0" to base + ("device_level" to "0"),
                    "device_level=3" to base + ("device_level" to "3"),
                    "source=1" to base + ("source" to "1"),
                    "source=2" to base + ("source" to "2"),
                    "video_id_type=1" to base + ("video_id_type" to "1"),
                    "from_video_id=self" to base + ("from_video_id" to firstEpisode),
                )
            val results = mutableListOf<Pair<String, List<String>>>()
            var matrixIndex = 0
            for ((name, extra) in variants) {
                if (matrixIndex > 0) Thread.sleep(400)
                val line = tabProbe(repo.http, session, firstEpisode, extra)
                results += name to listOf(line)
                println("tab-matrix variant=$name; episode=$firstEpisode; $line")
                matrixIndex++
            }
            val winners =
                results
                    .filter { (_, lines) -> lines.any { row -> row.substringAfter("renditions=").split(",").any { it.endsWith("*") } } }
                    .map { it.first }
            for ((label, episode) in samples.drop(1)) {
                Thread.sleep(400)
                println("tab-matrix confirm=base; label=$label; episode=$episode; ${tabProbe(repo.http, session, episode, base)}")
                for (name in winners.take(3)) {
                    Thread.sleep(400)
                    val extra = variants.first { it.first == name }.second
                    println(
                        "tab-matrix confirm=$name; label=$label; episode=$episode; ${tabProbe(repo.http, session, episode, extra)}"
                    )
                }
            }
            println(
                "tab-matrix bookapiOldPath; episode=$firstEpisode; ${tabOldPathProbe(repo.http, session, firstEpisode)}"
            )
            println("tab-matrix winners=$winners")
        } finally {
            repo.http.connectionPool.evictAll()
            repo.http.dispatcher.executorService.shutdown()
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--tab-fallback")) {
        // 2026-11-02 探针后续：video_tab 的低档编码供给按内容决定（部分剧集低档只发 bytevc2，
        // 参数无法改变）。验证两条候选出路：① 全新设备身份是否改变该内容的编码供给；
        // ② 模型内 fallback_api 遗留端点（历史上供系统播放器的兼容供给）现在的档位与编码。
        // 输出仅含档位元数据与 fallback URL 的 host/path 形状，不含查询与凭据。
        val session = PlaybackDeviceSession()
        val repo = ContentRepository(playbackSession = session)
        try {
            val ids = args.filter { it.matches(Regex("[0-9]{1,30}")) }
            val targets =
                if (ids.isNotEmpty()) {
                    ids
                } else {
                    listOfNotNull(
                        repo.home().items.firstOrNull()?.id?.let { repo.detail(it).episodes.firstOrNull() },
                    )
                }
            check(targets.isNotEmpty()) { "no targets" }
            for (episode in targets) {
                println("tab-fallback episode=$episode; tab=${tabProbe(repo.http, session, episode, tabBaseParams())}")
                val model = tabModelRaw(repo.http, session, episode)
                val fallback = model?.fallbackUrl()
                println(
                    "tab-fallback fallbackApi=" +
                        (fallback?.let { Regex("https://([^/]+)(/[^?]*)").find(it)?.let { m -> "${m.groupValues[1]}${m.groupValues[2]}" } } ?: "MISSING"),
                )
                if (fallback != null && fallback.startsWith("https://"))
                    println("tab-fallback legacy=${legacyProbe(repo.http, fallback)}")
            }
            val sessionB = PlaybackDeviceSession()
            println(
                "tab-fallback freshIdentity; episode=${targets.first()}; ${tabProbe(repo.http, sessionB, targets.first(), tabBaseParams())}"
            )
        } finally {
            repo.http.connectionPool.evictAll()
            repo.http.dispatcher.executorService.shutdown()
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--tab-episodes")) {
        // 2026-11-02 探针后续：编码供给按内容锁定（bytevc1 剧集全档 bytevc1，bytevc2 剧集低档
        // bytevc2 + bytevc1 1080P）。验证锁定粒度是剧还是集：同剧前 N 集逐集探 base 参数。
        // 输出仅含档位元数据，不含地址与凭据。
        val session = PlaybackDeviceSession()
        val repo = ContentRepository(playbackSession = session)
        try {
            val seriesId =
                args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) }
                    ?: error("用法: --tab-episodes <seriesId> [count]")
            val count =
                args.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 4
            val detail = repo.detail(seriesId)
            println("tab-episodes series=$seriesId; title=${detail.series.title}; episodes=${detail.episodes.size}")
            for (episode in detail.episodes.take(count)) {
                Thread.sleep(400)
                println("tab-episodes episode=$episode; ${tabProbe(repo.http, session, episode, tabBaseParams())}")
            }
        } finally {
            repo.http.connectionPool.evictAll()
            repo.http.dispatcher.executorService.shutdown()
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--ladder")) {
        // 正式链路探针：streamLadder = GET video_tab 失效时回退签名 POST multi_video_model。
        // 输出仅含档位元数据，不含地址与凭据。
        val idx = args.indexOf("--ladder")
        val episodeId = args.getOrNull(idx + 1) ?: error("用法: --ladder <episodeId>")
        val repository = ContentRepository()
        try {
            val ladder = repository.streamLadder(episodeId)
            if (ladder.renditions.isEmpty()) {
                println("ladder episode=$episodeId; result=EMPTY")
            } else {
                for (r in ladder.renditions) {
                    println(
                        "ladder episode=$episodeId; q=${r.quality}; codec=${r.codec}; " +
                            "${r.width}x${r.height}"
                    )
                }
            }
        } catch (e: Exception) {
            println("ladder episode=$episodeId; error=${e.message?.take(150)}; trace=${(e as? EmptyPlaybackResponseException)?.detail.orEmpty()}")
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--signmatrix")) {
        // 2026-11-06 结论仲裁：main 注释称旧 POST 接口「带完整签名头也全空」，2026-11-05 实测
        // 却是「带 x-gorgon/x-helios/x-medusa 签名 POST 恢复五档」，且 0.1.22 线上仍偶发空响应。
        // 四形态同窗口交织对照（轮次按 form 展开使同一时间窗覆盖全部形态，避免时段波动误判）：
        // get_unsigned（无签名 GET，2026-11-05 起被验签短路）/ get_signed（官方 0.1.22 主路径）/
        // post_signed（官方 playbackRequest 形态，带 Cookie 与 x-ss-stub）/ post_playback
        // （fork 备用形态，无 Cookie/stub，带 store-region 头）。末轮换全新身份复测，
        // 区分「形态差异」与「身份屏蔽」。输出仅含档位元数据，不含地址与凭据。
        val session = PlaybackDeviceSession()
        val repo = ContentRepository(playbackSession = session)
        val forms =
            listOf(
                "get_unsigned", "get_signed", "post_signed", "post_playback",
                "post_nocookie", "post_nostub", "post_playback_v",
            )
        try {
            val ids = args.filter { it.matches(Regex("[0-9]{1,30}")) }
            val episodes =
                if (ids.isNotEmpty()) {
                    ids
                } else {
                    listOfNotNull(
                        repo.home().items.getOrNull(0)?.id?.let { repo.detail(it).episodes.firstOrNull() },
                        repo.home().items.getOrNull(1)?.id?.let { repo.detail(it).episodes.firstOrNull() },
                    )
                }
            check(episodes.isNotEmpty()) { "用法: --signmatrix [episodeId...]（缺省取首页前两部剧的首集）" }
            for (round in 1..3) {
                val episode = episodes[(round - 1) % episodes.size]
                for (form in forms) {
                    println(
                        "signmatrix round=$round; form=$form; episode=$episode; " +
                            signedProbe(repo.http, session, form, episode),
                    )
                    Thread.sleep(2000)
                }
            }
            val fresh = PlaybackDeviceSession()
            for (form in forms) {
                println(
                    "signmatrix fresh; form=$form; episode=${episodes.first()}; " +
                        signedProbe(repo.http, fresh, form, episodes.first()),
                )
                Thread.sleep(2000)
            }
        } finally {
            repo.http.connectionPool.evictAll()
            repo.http.dispatcher.executorService.shutdown()
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--novel-batch")) {
        // 2026-11-02 逆向发现（红果 7.3.9.32 classes8）：官方 PlayerApiService.getVideoModelRx
        // 现行使用 `POST /novel/player/video_model/v1/`（KMP 批量 body：biz_param +
        // mixed_video_id_map），与已死的 multi_video_model 同族但从未实测。逐 host 探该路径
        // 在 bytevc2 样本集上的供给，device_level 1/3 各一发。输出仅含档位元数据。
        val session = PlaybackDeviceSession()
        val repo = ContentRepository(playbackSession = session)
        try {
            val episodes = args.filter { it.matches(Regex("[0-9]{1,30}")) }
            check(episodes.isNotEmpty()) { "用法: --novel-batch <episodeId> [episodeId2...]" }
            for (level in listOf(1, 3)) {
                for (host in PlayRetryPlan.hosts) {
                    Thread.sleep(400)
                    println(
                        "novel-batch level=$level; host=${PlayRetryPlan.tag(host)}; " +
                            novelBatchProbe(repo.http, session, host, level, episodes.take(1))
                    )
                }
            }
        } finally {
            repo.http.connectionPool.evictAll()
            repo.http.dispatcher.executorService.shutdown()
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--tab-ua")) {
        // 2026-11-02 探针后续：bytevc2 供给按集锁定且参数无法改变。最后一条线索——服务端可能
        // 按 UA 版本号推断客户端 VVC 能力（71532=7.3.5.32 自带 VVC 解码器），旧版本 UA 可能
        // 回退 bytevc1 供给。逐个 UA 探同一集。输出仅含档位元数据，不含地址与凭据。
        val session = PlaybackDeviceSession()
        val repo = ContentRepository(playbackSession = session)
        try {
            val episode =
                args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) }
                    ?: error("用法: --tab-ua <episodeId>")
            val uas =
                listOf(
                    "71532" to
                        "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)",
                    "65532" to
                        "com.phoenix.read/65532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)",
                    "62117" to
                        "com.phoenix.read/62117 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)",
                    "65532-old-ok" to
                        "com.phoenix.read/65532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.12)",
                )
            for ((label, ua) in uas) {
                Thread.sleep(400)
                println("tab-ua ua=$label; episode=$episode; ${tabProbe(repo.http, session, episode, tabBaseParams(), ua)}")
            }
        } finally {
            repo.http.connectionPool.evictAll()
            repo.http.dispatcher.executorService.shutdown()
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--soak")) {
        // 长时间采样空响应窗口（issue #38/#41 后续观测）：每约 5 秒一轮 × 3 条路径（主 host
        // 池化连接 / 主 host 新连接 / 备用 host hl 新连接），与线上重试策略及 --issue38 窗口
        // 捕捉一致。统计窗口簇数、簇时长、簇内路径分歧与总空响应率，为恢复预算判据提供数据。
        // 输出仅含计数与 host 代号，不打印地址与凭据。
        val minutes =
            args.getOrNull(args.indexOf("--soak") + 1)?.takeIf { !it.startsWith("--") }?.toIntOrNull()
                ?: 45
        val sessionA = PlaybackDeviceSession()
        val repo = ContentRepository(playbackSession = sessionA)
        try {
            val keyword = "美人替嫁祁王"
            val found = runCatching { repo.search(keyword) }.getOrElse { repo.searchAll(keyword) }
            val series =
                found.items.firstOrNull { it.title == "春色韫韫，美人替嫁祁王" }
                    ?: found.items.firstOrNull { "美人替嫁祁王" in it.title }
            checkNotNull(series) { "search=${found.items.size}; no match for $keyword" }
            val detail = repo.detail(series.id)
            val episode8 = detail.episodes.getOrNull(7) ?: detail.episodes.first()
            println(
                "soak series=${series.title}; id=${series.id}; episodes=${detail.episodes.size}; minutes=$minutes"
            )
            val deadline = System.currentTimeMillis() + minutes * 60_000L
            var cycle = 0
            var probes = 0
            var empties = 0
            var clusters = 0
            var inCluster = false
            var clusterStart = 0L
            var clusterLast = 0L
            var longestSpan = 0L
            var escape = ""
            while (System.currentTimeMillis() < deadline) {
                cycle++
                val marks =
                    listOf(
                            "pooled" to
                                playerProbe(repo.http, sessionA, episode8, 1, PLAYER_HOST_PRIMARY),
                            "fresh" to
                                playerProbe(repo.http, sessionA, episode8, 1, PLAYER_HOST_PRIMARY, fresh = true),
                            "altHost" to
                                playerProbe(repo.http, sessionA, episode8, 1, PLAYER_HOST_FALLBACKS.first(), fresh = true),
                        )
                        .map { (label, result) -> label to result.contains("EMPTY") }
                probes += marks.size
                val now = System.currentTimeMillis()
                val bad = marks.filter { it.second }.map { it.first }
                val good = marks.filterNot { it.second }.map { it.first }
                empties += bad.size
                if (bad.isNotEmpty()) {
                    if (!inCluster) {
                        inCluster = true
                        clusterStart = now
                        escape = ""
                        clusters++
                    }
                    clusterLast = now
                    // 同一轮内「部分路径空、部分路径通」即路径分歧：记下第一条逃逸路径。
                    if (good.isNotEmpty() && escape.isEmpty()) escape = good.first()
                    println(
                        "soak window; cycle=$cycle; empty=${bad.joinToString(",")}; ok=${good.joinToString(",")}"
                    )
                } else if (inCluster) {
                    inCluster = false
                    val span = clusterLast - clusterStart
                    if (span > longestSpan) longestSpan = span
                    println(
                        "soak windowEnd; cluster=$clusters; spanMs=$span; escape=${escape.ifEmpty { "none-observed" }}"
                    )
                }
                if (cycle % 10 == 0)
                    println("soak progress; cycle=$cycle; clusters=$clusters; empty=$empties/$probes")
                Thread.sleep(3000)
            }
            val spanText =
                if (inCluster) "ongoingSpanMs=${System.currentTimeMillis() - clusterStart}"
                else "longestSpanMs=$longestSpan"
            val rate = if (probes > 0) "%.1f%%".format(java.util.Locale.US, empties * 100.0 / probes) else "n/a"
            println(
                "soak summary; minutes=$minutes; cycles=$cycle; probes=$probes; empty=$empties; rate=$rate; clusters=$clusters; $spanText"
            )
        } finally {
            repo.http.connectionPool.evictAll()
            repo.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--official-qr-login")) {
        try {
            val login = WebLogin(repository.http)
            val qr = login.qr()
            java.io.File("/tmp/hongguotv-official-login.png").writeBytes(qr.image)
            println(
                "officialQrReady=true; image=/tmp/hongguotv-official-login.png; expiresIn=${qr.expiresIn}"
            )
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(3000)
                if (!login.poll()) continue
                println("officialWebLogin=true")
                val web = login.credentials()
                val client = CloudAccountClient(repository.http)
                val device = checkNotNull(client.registerDevice()) { "Device registration failed" }
                val account = web.copy(deviceId = device.deviceId, installId = device.installId)
                val profile = client.profile(account)
                println("mobileAccountVerified=true; idPresent=${profile.id.isNotBlank()}")
                val history = client.history(account)
                println("cloudHistoryLoaded=true; items=${history.size}")
                return
            }
            println("qrExpired=true")
        } catch (error: Exception) {
            println("loginTestFailed=${error.message?.take(180)}")
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--account-register")) {
        try {
            val registered = CloudAccountClient(repository.http).registerDevice()
            check(registered != null) { "Account device registration rejected" }
            println("accountDeviceRegistered=true; credentialsRequested=false; smsSent=false")
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--search-all")) {
        try {
            val keyword = args.getOrNull(args.indexOf("--search-all") + 1) ?: "大佬"
            for (page in 1..2) {
                val result = repository.searchAll(keyword, page)
                println(
                    "allSearchPage=$page; items=${result.items.size}; hasMore=${result.hasMore}; titles=${result.items.take(5).joinToString { it.title }}"
                )
                if (!result.hasMore) break
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--ranking")) {
        try {
            val first = repository.comicRanking()
            val metadata = checkNotNull(first.ranking)
            check(first.items.isNotEmpty())
            val seen = mutableSetOf<String>()
            for (pageNumber in
                listOf(1, 2, metadata.totalPages).distinct().filter { it <= metadata.totalPages }) {
                val result = if (pageNumber == 1) first else repository.comicRanking(pageNumber)
                val ranking = checkNotNull(result.ranking)
                check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                seen.addAll(result.items.map { it.id })
                val ranks = result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                check(
                    ranks.all { it != null && it > 0 } &&
                        ranks.filterNotNull().zipWithNext().all { (a, b) -> a < b }
                )
                check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                check(result.hasMore == (pageNumber < ranking.totalPages))
                println(
                    "rankingPage=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}"
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type = if (args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword = if (type == ContentType.COMIC) "修仙" else "总裁"
    val search = repository.search(keyword, type = type)
    println("search=${search.items.size}; hasMore=${search.hasMore}")
    if (type == ContentType.COMIC && search.hasMore) {
        val next = repository.search(keyword, 2, type)
        println(
            "searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}"
        )
    }
    val home = repository.home(type = type)
    println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
    if (type == ContentType.COMIC) {
        val next = repository.home(2, type)
        println(
            "homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}"
        )
    }
    val detail =
        repository.detail(
            args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: home.items.first().id
        )
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    val stream = repository.stream(detail.episodes.first())
    RemoteVideo(repository.http, stream).use { remote ->
        remote.prepare()
        val first = remote.read(0, 1024 * 1024)
        val tail = remote.read((remote.total - 65536).coerceAtLeast(0), 65536)
        println(
            "quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}"
        )
        if (args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll()
    repository.http.dispatcher.executorService.shutdown()
}

private const val PLAYER_HOST_PRIMARY = "api5-normal-sinfonlineb.fqnovel.com"

private val PLAYER_HOST_FALLBACKS =
    listOf(
        "api5-normal-hl.fqnovel.com",
        "api5-normal-lf.fqnovel.com",
        "api5-normal-sinfonlinec.fqnovel.com",
    )

/**
 * 播放信息接口单发探测：请求构造与 ContentRepository.streamLadder 完全一致，但不做内部重试。
 * 输出仅含 HTTP 状态、错误码、档位元数据与本机时钟偏差，不打印签名查询、Cookie 或媒体地址。
 */
private fun playerProbe(
    http: OkHttpClient,
    session: PlaybackDeviceSession,
    episodeId: String,
    level: Int,
    host: String,
    fresh: Boolean = false,
): String {
    val identity = session.profile(http)
    val payload =
        """{"biz_param":{"detail_page_version":0,"device_level":$level,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":${org.json.JSONArray(listOf(episodeId))}}}"""
            .toByteArray()
    val query =
        Signer.encode(identity.values + ("_rticket" to System.currentTimeMillis().toString()))
    val request =
        okhttp3.Request.Builder()
            .url("https://$host/novel/player/multi_video_model/v1/?$query")
            .header(
                "User-Agent",
                "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)",
            )
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .apply {
                if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
                // 强制每发新建 TCP+TLS，避免连接池把重试粘在同一个后端节点上。
                if (fresh) header("Connection", "close")
            }
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    return try {
        RequestScope.execute(http.newCall(request)) { response ->
            val skew =
                response.header("Date")?.let {
                    val format =
                        java.text.SimpleDateFormat(
                            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                            java.util.Locale.US,
                        )
                    format.timeZone = java.util.TimeZone.getTimeZone("GMT")
                    format.parse(it)?.let { parsed -> (System.currentTimeMillis() - parsed.time) / 1000 }
                }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@execute "http=${response.code}; body=EMPTY; skewSec=$skew"
            val json = org.json.JSONObject(body)
            val code = json.optInt("Code", json.optInt("code", 0))
            val data = json.optJSONObject("data")
            val row = data?.optJSONObject(episodeId)
            val model =
                row?.opt("video_model")?.let { value ->
                    runCatching {
                        (value as? org.json.JSONObject) ?: org.json.JSONObject(value.toString())
                    }
                        .getOrNull()
                }
            val renditions =
                model?.optJSONArray("video_list")?.objects().orEmpty().mapNotNull { entry ->
                    val meta = entry.optJSONObject("video_meta") ?: return@mapNotNull null
                    "${meta.optString("codec_type")}/${meta.optString("definition")}/${meta.optInt("vwidth")}x${meta.optInt("vheight")}"
                }
            "http=${response.code}; code=$code; rows=${data?.length() ?: 0}; renditions=${renditions.joinToString(",")}; skewSec=$skew"
        }
    } catch (problem: Exception) {
        "error=${problem.javaClass.simpleName}: ${problem.message?.take(80)}"
    }
}

/**
 * video_tab 接口单发探测：请求构造与 ContentRepository.tabVideoModel 一致，仅 extra 参数可变。
 * 档位行格式 codec/definition/wxh，电视可解档（max(w,h)≤1088）追加 * 标记。输出不含地址与凭据。
 */
private fun tabProbe(
    http: OkHttpClient,
    session: PlaybackDeviceSession,
    episodeId: String,
    extra: Map<String, String>,
    ua: String = "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)",
): String {
    val identity = session.profile(http)
    val query = Signer.encode(identity.values + ("_rticket" to System.currentTimeMillis().toString()))
    val tail = extra.entries.joinToString("&") { (key, value) -> "$key=$value" }
    val request =
        okhttp3.Request.Builder()
            .url(
                "https://${PlayRetryPlan.hosts.first()}/reading/bookapi/video_tab/multi_video_model/v1/" +
                    "?$query&video_id=$episodeId&$tail",
            )
            .header("User-Agent", ua)
            .header("Accept", "application/json; charset=utf-8")
            .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
            .get()
            .build()
    return try {
        RequestScope.execute(http.newCall(request)) { response ->
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@execute "http=${response.code}; body=EMPTY"
            val json = runCatching { org.json.JSONObject(body) }.getOrNull()
                ?: return@execute "http=${response.code}; body=NOT_JSON(${body.length})"
            val code = json.optInt("code", json.optInt("Code", -1))
            if (code != 0)
                return@execute "http=${response.code}; code=$code; message=${json.optString("message").take(50)}"
            val data = json.optJSONObject("data")
            val rows = data?.optJSONObject("video_model_data") ?: data
            val row =
                rows?.optJSONObject(episodeId)
                    ?: rows?.keys()?.asSequence()?.mapNotNull { rows.optJSONObject(it) }?.firstOrNull()
                    ?: return@execute "http=${response.code}; code=$code; row=MISSING"
            val model =
                row.opt("video_model")?.let { value ->
                    runCatching {
                        (value as? org.json.JSONObject) ?: org.json.JSONObject(value.toString())
                    }
                        .getOrNull()
                }
                    ?: return@execute "http=${response.code}; code=$code; model=MISSING"
            val tiers =
                model.optJSONArray("video_list")?.objects().orEmpty().mapNotNull { entry ->
                    val meta = entry.optJSONObject("video_meta") ?: return@mapNotNull null
                    val codec = meta.optString("codec_type")
                    val w = meta.optInt("vwidth")
                    val h = meta.optInt("vheight")
                    "${codec}/${meta.optString("definition")}/${w}x${h}${if (maxOf(w, h) in 1..1088) "*" else ""}"
                }
            "http=${response.code}; code=$code; renditions=${tiers.joinToString(",")}"
        }
    } catch (problem: Exception) {
        "error=${problem.javaClass.simpleName}: ${problem.message?.take(80)}"
    }
}

/** 与 ContentRepository.tabVideoModel 相同的 base 参数；供 --tab-matrix / --tab-fallback 复用。 */
private fun tabBaseParams() =
    linkedMapOf(
        "video_id_type" to "0",
        "device_level" to "1",
        "need_all_video_definition" to "1",
        "need_mp4_align" to "0",
        "use_os_player" to "0",
        "video_platform" to "1024",
        "source" to "0",
        "from_video_id" to "",
    )

/** --signmatrix 单发请求：四种请求形态各按其线上实现复刻，返回仅含元数据的摘要行。 */
private fun signedProbe(
    http: OkHttpClient,
    session: PlaybackDeviceSession,
    form: String,
    episodeId: String,
): String {
    val identity = session.profile(http)
    val host = PlayRetryPlan.hosts.first()
    // 官方 playbackRequest 的版本字段覆盖；fork 备用形态按其原始实现不带覆盖。
    val overrides =
        mapOf(
            "version_code" to "73932", "manifest_version_code" to "73932",
            "update_version_code" to "73932", "pv_player" to "73932",
            "version_name" to "7.3.9.32",
        )
    val getExtra = tabBaseParams() + ("video_id" to episodeId)
    val payload =
        """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":${org.json.JSONArray(listOf(episodeId))}}}"""
            .toByteArray()
    fun fromSigned(signed: Signer.Request): okhttp3.Request.Builder {
        val builder = okhttp3.Request.Builder().url(signed.url)
        for ((key, value) in signed.headers) builder.header(key, value)
        return builder
    }
    val request =
        when (form) {
            "get_unsigned" ->
                okhttp3.Request.Builder()
                    .url(
                        "https://$host/reading/bookapi/video_tab/multi_video_model/v1/?" +
                            Signer.encode(identity.values + ("_rticket" to System.currentTimeMillis().toString())) +
                            "&" + getExtra.entries.joinToString("&") { (key, value) -> "${Signer.encode(key)}=${Signer.encode(value)}" },
                    )
                    .header("User-Agent", VendorConstants.PLAYER_UA)
                    .header("Accept", "application/json; charset=utf-8")
                    .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
                    .get()
                    .build()
            "get_signed" -> {
                val profile = identity.values + overrides
                val signed =
                    Signer(device = profile).signPlayback(
                        "https://$host/reading/bookapi/video_tab/multi_video_model/v1/",
                        profile + getExtra, null,
                    )
                fromSigned(signed)
                    .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
                    .get()
                    .build()
            }
            "post_signed" -> {
                val profile = identity.values + overrides
                val signed =
                    Signer(device = profile).signPlayback(
                        "https://$host/novel/player/multi_video_model/v1/", profile, payload,
                    )
                fromSigned(signed)
                    .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            "post_playback" -> {
                val signed =
                    Signer().sign(
                        "https://$host/novel/player/multi_video_model/v1/",
                        identity.values, payload, identity = identity.values, playback = true,
                    )
                fromSigned(signed)
                    .post(payload.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    .build()
            }
            "post_nocookie" -> {
                // 二分：官方形态去掉 Cookie，验证 Cookie 是否为现行必需项。
                val profile = identity.values + overrides
                val signed =
                    Signer(device = profile).signPlayback(
                        "https://$host/novel/player/multi_video_model/v1/", profile, payload,
                    )
                fromSigned(signed)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            "post_nostub" -> {
                // 二分：官方形态去掉 x-ss-stub，验证签名体摘要是否为现行必需项。
                val profile = identity.values + overrides
                val signed =
                    Signer(device = profile).signPlayback(
                        "https://$host/novel/player/multi_video_model/v1/", profile, payload,
                    )
                fromSigned(signed)
                    .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
                    .removeHeader("x-ss-stub")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            "post_playback_v" -> {
                // 二分：fork 形态补上 version_code 73932 覆盖，验证版本字段是否为被拒原因。
                val profile = identity.values + overrides
                val signed =
                    Signer().sign(
                        "https://$host/novel/player/multi_video_model/v1/",
                        profile, payload, identity = profile, playback = true,
                    )
                fromSigned(signed)
                    .post(payload.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    .build()
            }
            else -> error("unknown form $form")
        }
    return try {
        RequestScope.execute(http.newCall(request)) { response ->
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@execute "http=${response.code}; body=EMPTY"
            val json = runCatching { org.json.JSONObject(body) }.getOrNull()
                ?: return@execute "http=${response.code}; body=NOT_JSON(${body.length})"
            val code = json.optInt("code", json.optInt("Code", -1))
            if (code != 0)
                return@execute "http=${response.code}; code=$code; message=${json.optString("message").take(50)}"
            val data = json.optJSONObject("data")
            val rows = data?.optJSONObject("video_model_data") ?: data
            val row =
                rows?.optJSONObject(episodeId)
                    ?: rows?.keys()?.asSequence()?.mapNotNull { rows.optJSONObject(it) }?.firstOrNull()
                    ?: return@execute "http=${response.code}; code=$code; row=MISSING"
            val model =
                row.opt("video_model")?.let { value ->
                    runCatching {
                        (value as? org.json.JSONObject) ?: org.json.JSONObject(value.toString())
                    }.getOrNull()
                }
            val renditions =
                model?.optJSONArray("video_list")?.objects().orEmpty().mapNotNull { entry ->
                    val meta = entry.optJSONObject("video_meta") ?: return@mapNotNull null
                    "${meta.optString("codec_type")}/${meta.optString("definition")}/${meta.optInt("vwidth")}x${meta.optInt("vheight")}"
                }
            "http=${response.code}; renditions=${renditions.joinToString(",")}; bytes=${body.length}"
        }
    } catch (problem: Exception) {
        "error=${problem.javaClass.simpleName}: ${problem.message?.take(80)}"
    }
}

/** 取 video_tab 原始 video_model 对象（对象或字符串两种形态都兼容），失败返回 null。 */
private fun tabModelRaw(
    http: OkHttpClient,
    session: PlaybackDeviceSession,
    episodeId: String,
): org.json.JSONObject? {
    val identity = session.profile(http)
    val query = Signer.encode(identity.values + ("_rticket" to System.currentTimeMillis().toString()))
    val request =
        okhttp3.Request.Builder()
            .url(
                "https://${PlayRetryPlan.hosts.first()}/reading/bookapi/video_tab/multi_video_model/v1/" +
                    "?$query&video_id=$episodeId&${tabBaseParams().entries.joinToString("&") { (k, v) -> "$k=$v" }}",
            )
            .header(
                "User-Agent",
                "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)",
            )
            .header("Accept", "application/json; charset=utf-8")
            .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
            .get()
            .build()
    return runCatching {
        RequestScope.execute(http.newCall(request)) { response ->
            val root = runCatching { org.json.JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                ?: return@execute null
            if (root.optInt("code", root.optInt("Code", -1)) != 0) return@execute null
            val data = root.optJSONObject("data")
            val rows = data?.optJSONObject("video_model_data") ?: data
            val row =
                rows?.optJSONObject(episodeId)
                    ?: rows?.keys()?.asSequence()?.mapNotNull { rows.optJSONObject(it) }?.firstOrNull()
                    ?: return@execute null
            val raw = row.opt("video_model") ?: return@execute null
            (raw as? org.json.JSONObject) ?: runCatching { org.json.JSONObject(raw.toString()) }.getOrNull()
        }
    }.getOrNull()
}

/** 提取模型内 fallback_api 完整地址（对象/数组/字符串三种形态都兼容），缺失返回 null。 */
private fun org.json.JSONObject.fallbackUrl(): String? {
    val raw = opt("fallback_api") ?: return null
    val url =
        when (raw) {
            is org.json.JSONObject -> raw.optString("fallback_api")
            is org.json.JSONArray -> raw.optString(0)
            else ->
                runCatching { org.json.JSONObject(raw.toString()).optString("fallback_api") }
                    .getOrDefault(raw.toString())
        }
    return url.takeIf { it.startsWith("https://") }
}

/** 遗留 fallback 端点单发探测：解析 video_info.data.video_list 的档位元数据，电视可解档加 *。 */
private fun legacyProbe(http: OkHttpClient, url: String): String {
    val request =
        okhttp3.Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)",
            )
            .header("Accept", "application/json; charset=utf-8")
            .get()
            .build()
    return try {
        RequestScope.execute(http.newCall(request)) { response ->
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@execute "http=${response.code}; body=EMPTY"
            val json = runCatching { org.json.JSONObject(body) }.getOrNull()
                ?: return@execute "http=${response.code}; body=NOT_JSON(${body.length})"
            val code = json.optInt("code", json.optInt("Code", 0))
            val info = json.optJSONObject("video_info")?.optJSONObject("data")
            val videoList = info?.optJSONObject("video_list")
                ?: return@execute "http=${response.code}; code=$code; video_list=MISSING"
            val tiers =
                videoList.keys().asSequence().mapNotNull { key ->
                    val row = videoList.optJSONObject(key) ?: return@mapNotNull null
                    val codec = row.optString("codec_type")
                    val w = row.optInt("vwidth", row.optInt("width", 0))
                    val h = row.optInt("vheight", row.optInt("height", 0))
                    "${codec}/${row.optString("quality_desc").ifEmpty { row.optString("definition") }}/${w}x${h}${if (maxOf(w, h) in 1..1088) "*" else ""}"
                }
            "http=${response.code}; code=$code; renditions=${tiers.joinToString(",")}"
        }
    } catch (problem: Exception) {
        "error=${problem.javaClass.simpleName}: ${problem.message?.take(80)}"
    }
}

/** 旧批量家族子路径单发探测：POST /novel/player/<sub>，body 与线上批量请求一致。 */
private fun novelBatchProbe(
    http: OkHttpClient,
    session: PlaybackDeviceSession,
    host: String,
    deviceLevel: Int,
    episodes: List<String>,
): String {
    val identity = session.profile(http)
    val query = Signer.encode(identity.values + ("_rticket" to System.currentTimeMillis().toString()))
    val payload =
        """{"biz_param":{"detail_page_version":0,"device_level":$deviceLevel,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":${org.json.JSONArray(episodes)}}}"""
            .toByteArray()
    val request =
        okhttp3.Request.Builder()
            .url("https://$host/novel/player/video_model/v1/?$query")
            .header(
                "User-Agent",
                "com.phoenix.read/73932 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)",
            )
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    return try {
        RequestScope.execute(http.newCall(request)) { response ->
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@execute "http=${response.code}; body=EMPTY"
            val json = runCatching { org.json.JSONObject(body) }.getOrNull()
                ?: return@execute "http=${response.code}; body=NOT_JSON(${body.length})"
            val code = json.optInt("code", json.optInt("Code", -1))
            if (code != 0)
                return@execute "http=${response.code}; code=$code; message=${json.optString("message").take(50)}"
            val data = json.optJSONObject("data")
            val row = data?.optJSONObject(episodes.first())
            val model =
                row?.opt("video_model")?.let { value ->
                    runCatching {
                        (value as? org.json.JSONObject) ?: org.json.JSONObject(value.toString())
                    }
                        .getOrNull()
                }
                    ?: return@execute "http=${response.code}; code=$code; model=MISSING"
            val tiers =
                model.optJSONArray("video_list")?.objects().orEmpty().mapNotNull { entry ->
                    val meta = entry.optJSONObject("video_meta") ?: return@mapNotNull null
                    val codec = meta.optString("codec_type")
                    val w = meta.optInt("vwidth")
                    val h = meta.optInt("vheight")
                    "${codec}/${meta.optString("definition")}/${w}x${h}${if (maxOf(w, h) in 1..1088) "*" else ""}"
                }
            "http=${response.code}; code=$code; renditions=${tiers.joinToString(",")}"
        }
    } catch (problem: Exception) {
        "error=${problem.javaClass.simpleName}: ${problem.message?.take(80)}"
    }
}

/** 旧批量路径换 bookapi 前缀的一次性验证（原 /novel/player 前缀八个域名已实测全空）。 */
private fun tabOldPathProbe(
    http: OkHttpClient,
    session: PlaybackDeviceSession,
    episodeId: String,
): String {
    val identity = session.profile(http)
    val query = Signer.encode(identity.values + ("_rticket" to System.currentTimeMillis().toString()))
    val payload =
        """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":${org.json.JSONArray(listOf(episodeId))}}}"""
            .toByteArray()
    val request =
        okhttp3.Request.Builder()
            .url(
                "https://${PlayRetryPlan.hosts.first()}/reading/bookapi/novel/player/multi_video_model/v1/?$query",
            )
            .header(
                "User-Agent",
                "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)",
            )
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    return try {
        RequestScope.execute(http.newCall(request)) { response ->
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@execute "http=${response.code}; body=EMPTY"
            val json = runCatching { org.json.JSONObject(body) }.getOrNull()
            val code = json?.optInt("code", json.optInt("Code", -1)) ?: -1
            "http=${response.code}; body=${body.length}B; code=$code"
        }
    } catch (problem: Exception) {
        "error=${problem.javaClass.simpleName}: ${problem.message?.take(80)}"
    }
}
