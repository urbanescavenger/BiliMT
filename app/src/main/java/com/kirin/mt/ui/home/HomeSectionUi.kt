package com.kirin.mt.ui.home

import androidx.annotation.StringRes
import com.kirin.mt.R
import com.kirin.mt.core.model.HomeSection

@StringRes
fun HomeSection.titleRes(): Int {
  return when (this) {
    HomeSection.Recommend -> R.string.home_section_recommend
    HomeSection.Popular -> R.string.home_section_popular
    HomeSection.Douga -> R.string.home_section_douga
    HomeSection.Game -> R.string.home_section_game
    HomeSection.Kichiku -> R.string.home_section_kichiku
    HomeSection.Music -> R.string.home_section_music
    HomeSection.Dance -> R.string.home_section_dance
    HomeSection.Cinephile -> R.string.home_section_cinephile
    HomeSection.Ent -> R.string.home_section_ent
    HomeSection.Knowledge -> R.string.home_section_knowledge
    HomeSection.Tech -> R.string.home_section_tech
    HomeSection.Information -> R.string.home_section_information
    HomeSection.Food -> R.string.home_section_food
    HomeSection.Shortplay -> R.string.home_section_shortplay
    HomeSection.Car -> R.string.home_section_car
    HomeSection.Fashion -> R.string.home_section_fashion
    HomeSection.Sports -> R.string.home_section_sports
    HomeSection.Animal -> R.string.home_section_animal
    HomeSection.Vlog -> R.string.home_section_vlog
    HomeSection.Painting -> R.string.home_section_painting
    HomeSection.Ai -> R.string.home_section_ai
    HomeSection.HomeDecor -> R.string.home_section_home
    HomeSection.Outdoors -> R.string.home_section_outdoors
    HomeSection.Gym -> R.string.home_section_gym
    HomeSection.Handmake -> R.string.home_section_handmake
    HomeSection.Travel -> R.string.home_section_travel
    HomeSection.Rural -> R.string.home_section_rural
    HomeSection.Parenting -> R.string.home_section_parenting
    HomeSection.Health -> R.string.home_section_health
    HomeSection.Emotion -> R.string.home_section_emotion
    HomeSection.LifeJoy -> R.string.home_section_life_joy
    HomeSection.LifeExperience -> R.string.home_section_life_experience
    HomeSection.Mysticism -> R.string.home_section_mysticism
  }
}