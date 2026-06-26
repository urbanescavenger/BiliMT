package com.kirin.mt.ui.home

import com.kirin.mt.R
import com.kirin.mt.core.model.UgcSubPartition

/**
 * UGC 主分区 → 子分区树，硬编码常量。
 *
 * tid 体系与 B 站传统分区一致（动画=1、番剧=13…），子分区 tid 直接可作为 `rid` 传给
 * `/x/web-interface/dynamic/region`。数据移植自 BV 源码 `util/PartitionUtil.kt`，
 * 仅保留本项目 [com.kirin.mt.core.model.HomeSection] 中已启用的 10 个主分区对应的子分区。
 */
object UgcPartitionTree {
  private val byRegionTid: Map<Int, List<UgcSubPartition>> = mapOf(
    1 to listOf( // 动画
      UgcSubPartition(24, "mad", R.string.ugc_sub_douga_mad),
      UgcSubPartition(25, "mmd", R.string.ugc_sub_douga_mmd),
      UgcSubPartition(47, "voice", R.string.ugc_sub_douga_voice),
      UgcSubPartition(210, "garage_kit", R.string.ugc_sub_douga_garage_kit),
      UgcSubPartition(86, "tokusatsu", R.string.ugc_sub_douga_tokusatsu),
      UgcSubPartition(253, "acgntalks", R.string.ugc_sub_douga_acgntalks),
      UgcSubPartition(27, "other", R.string.ugc_sub_douga_other),
    ),
    13 to listOf( // 番剧
      UgcSubPartition(32, "finish", R.string.ugc_sub_anime_finish),
      UgcSubPartition(33, "serial", R.string.ugc_sub_anime_serial),
      UgcSubPartition(51, "information", R.string.ugc_sub_anime_information),
      UgcSubPartition(152, "offical", R.string.ugc_sub_anime_offical),
    ),
    181 to listOf( // 影视
      UgcSubPartition(182, "cinecism", R.string.ugc_sub_movie_cinecism),
      UgcSubPartition(183, "montage", R.string.ugc_sub_movie_montage),
      UgcSubPartition(85, "shortfilm", R.string.ugc_sub_movie_shortfilm),
      UgcSubPartition(184, "trailer_info", R.string.ugc_sub_movie_trailer_info),
    ),
    4 to listOf( // 游戏
      UgcSubPartition(17, "stand_alone", R.string.ugc_sub_game_stand_alone),
      UgcSubPartition(171, "esports", R.string.ugc_sub_game_esports),
      UgcSubPartition(172, "mobile", R.string.ugc_sub_game_mobile),
      UgcSubPartition(65, "online", R.string.ugc_sub_game_online),
      UgcSubPartition(173, "board", R.string.ugc_sub_game_board),
      UgcSubPartition(121, "gmv", R.string.ugc_sub_game_gmv),
      UgcSubPartition(136, "music", R.string.ugc_sub_game_music),
      UgcSubPartition(19, "mugen", R.string.ugc_sub_game_mugen),
    ),
    36 to listOf( // 知识
      UgcSubPartition(201, "science", R.string.ugc_sub_knowledge_science),
      UgcSubPartition(124, "social_science", R.string.ugc_sub_knowledge_social_science),
      UgcSubPartition(228, "humanity_history", R.string.ugc_sub_knowledge_humanity_history),
      UgcSubPartition(207, "business", R.string.ugc_sub_knowledge_business),
      UgcSubPartition(208, "campus", R.string.ugc_sub_knowledge_campus),
      UgcSubPartition(209, "career", R.string.ugc_sub_knowledge_career),
      UgcSubPartition(229, "design", R.string.ugc_sub_knowledge_design),
      UgcSubPartition(122, "skill", R.string.ugc_sub_knowledge_skill),
    ),
    188 to listOf( // 科技
      UgcSubPartition(95, "digital", R.string.ugc_sub_tech_digital),
      UgcSubPartition(230, "application", R.string.ugc_sub_tech_application),
      UgcSubPartition(231, "computer_tech", R.string.ugc_sub_tech_computer_tech),
      UgcSubPartition(232, "industry", R.string.ugc_sub_tech_industry),
      UgcSubPartition(233, "diy", R.string.ugc_sub_tech_diy),
    ),
    3 to listOf( // 音乐
      UgcSubPartition(28, "original", R.string.ugc_sub_music_original),
      UgcSubPartition(31, "cover", R.string.ugc_sub_music_cover),
      UgcSubPartition(59, "perform", R.string.ugc_sub_music_perform),
      UgcSubPartition(30, "vocaloid", R.string.ugc_sub_music_vocaloid),
      UgcSubPartition(29, "live", R.string.ugc_sub_music_live),
      UgcSubPartition(193, "mv", R.string.ugc_sub_music_mv),
      UgcSubPartition(243, "commentary", R.string.ugc_sub_music_commentary),
      UgcSubPartition(244, "tutorial", R.string.ugc_sub_music_tutorial),
      UgcSubPartition(130, "other", R.string.ugc_sub_music_other),
    ),
    129 to listOf( // 舞蹈
      UgcSubPartition(20, "otaku", R.string.ugc_sub_dance_otaku),
      UgcSubPartition(198, "hiphop", R.string.ugc_sub_dance_hiphop),
      UgcSubPartition(199, "star", R.string.ugc_sub_dance_star),
      UgcSubPartition(200, "china", R.string.ugc_sub_dance_china),
      UgcSubPartition(154, "three_d", R.string.ugc_sub_dance_three_d),
      UgcSubPartition(156, "demo", R.string.ugc_sub_dance_demo),
    ),
    160 to listOf( // 生活
      UgcSubPartition(138, "funny", R.string.ugc_sub_life_funny),
      UgcSubPartition(250, "travel", R.string.ugc_sub_life_travel),
      UgcSubPartition(251, "rurallife", R.string.ugc_sub_life_rurallife),
      UgcSubPartition(239, "home", R.string.ugc_sub_life_home),
      UgcSubPartition(161, "handmake", R.string.ugc_sub_life_handmake),
      UgcSubPartition(162, "painting", R.string.ugc_sub_life_painting),
      UgcSubPartition(21, "daily", R.string.ugc_sub_life_daily),
    ),
    211 to listOf( // 美食
      UgcSubPartition(76, "make", R.string.ugc_sub_food_make),
      UgcSubPartition(212, "detective", R.string.ugc_sub_food_detective),
      UgcSubPartition(213, "measurement", R.string.ugc_sub_food_measurement),
      UgcSubPartition(214, "rural", R.string.ugc_sub_food_rural),
      UgcSubPartition(215, "record", R.string.ugc_sub_food_record),
    ),
  )

  fun subPartitions(regionTid: Int): List<UgcSubPartition> = byRegionTid[regionTid] ?: emptyList()
}