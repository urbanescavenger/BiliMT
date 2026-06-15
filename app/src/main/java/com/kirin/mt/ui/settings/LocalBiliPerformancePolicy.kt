package com.kirin.mt.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.kirin.mt.core.settings.AppPerformancePolicy

val LocalBiliPerformancePolicy = staticCompositionLocalOf {
  AppPerformancePolicy.Standard
}
