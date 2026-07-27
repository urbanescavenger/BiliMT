package com.kirin.mt.ui.shell

import androidx.annotation.DrawableRes
import com.kirin.mt.R

enum class AppDestination(
  val titleRes: Int,
  @DrawableRes val iconRes: Int,
) {
  Search(R.string.nav_search, R.drawable.ic_nav_search),
  Recommend(R.string.nav_recommend, R.drawable.ic_nav_home),
  Dynamic(R.string.nav_dynamic, R.drawable.ic_nav_dynamic),
  Pgc(R.string.nav_pgc, R.drawable.ic_nav_pgc),
  Live(R.string.nav_live, R.drawable.ic_nav_live),
  Settings(R.string.nav_settings, R.drawable.ic_nav_settings),
}
