package com.kirin.mt.ui.home

import androidx.annotation.StringRes
import com.kirin.mt.R
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.model.UgcSubPartition

@StringRes
fun HomeSection.titleRes(): Int {
  return when (this) {
    HomeSection.Recommend -> R.string.home_section_recommend
    HomeSection.Popular -> R.string.home_section_popular
    HomeSection.Anime -> R.string.home_section_anime
    HomeSection.Movie -> R.string.home_section_movie
    HomeSection.Game -> R.string.home_section_game
    HomeSection.Knowledge -> R.string.home_section_knowledge
    HomeSection.Tech -> R.string.home_section_tech
    HomeSection.Music -> R.string.home_section_music
    HomeSection.Dance -> R.string.home_section_dance
    HomeSection.Life -> R.string.home_section_life
    HomeSection.Food -> R.string.home_section_food
    HomeSection.Douga -> R.string.home_section_douga
  }
}

/**
 * 该主分区下的子分区列表。Recommend/Popular 等无 [HomeSection.regionTid] 的入口返回空，
 * 调用方据此决定是否渲染子分区胶囊行。
 */
fun HomeSection.subPartitions(): List<UgcSubPartition> {
  return regionTid?.let { UgcPartitionTree.subPartitions(it) } ?: emptyList()
}
