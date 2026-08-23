package com.kirin.mt.ui.i18n

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * 大数紧凑格式化(播放量/弹幕数/点赞数/粉丝数等)。
 *
 * 中文变体(简/繁)沿用「万/亿」制:除数 1e4 / 1e8,后缀 万/亿。
 * 拉丁语系变体(en/es/pt)沿用国际 K/M/B 制:除数 1e3 / 1e6 / 1e9,后缀 K/M/B。
 *
 * 除数差异(1e4→1e3)是代码逻辑,无法仅靠翻译字符串表达,故在此按 locale 分支。
 * 当前 locale 由 [localizedContext] 注入的 composition 配置决定,与 stringResource 一致。
 */
fun formatCompactCount(count: Long, locale: Locale): String {
  return when (locale.language) {
    // 拉丁语系:1K / 1.2M / 120M / 1.2B
    "en", "es", "pt" -> when {
      count >= 1_000_000_000L -> "%.1fB".format(count / 1_000_000_000.0)
      count >= 1_000_000L -> "%.1fM".format(count / 1_000_000.0)
      count >= 1_000L -> "%.1fK".format(count / 1_000.0)
      else -> count.toString()
    }
    // 中文及默认:万 / 亿
    else -> when {
      count >= 100_000_000L -> "%.1f亿".format(count / 100_000_000.0)
      count >= 10_000L -> "%.1f万".format(count / 10_000.0)
      else -> count.toString()
    }
  }
}

fun formatCompactCount(count: Int, locale: Locale): String = formatCompactCount(count.toLong(), locale)

/** 当前 composition 生效的 locale(与 stringResource 一致,即 localizedContext 替换后的配置)。 */
@Composable
fun currentUiLocale(): Locale {
  val configuration: Configuration = LocalContext.current.resources.configuration
  return configuration.locales.getOrElse(0) { Locale.SIMPLIFIED_CHINESE }
}

/** 从 Resources 读当前配置 locale;供非 composable 上下文(已持有 resources)使用。 */
fun localeFromResources(resources: Resources): Locale {
  return resources.configuration.locales.getOrElse(0) { Locale.SIMPLIFIED_CHINESE }
}
