package com.kirin.mt.ui.common

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ClockOverlay(
  clockText: String,
  modifier: Modifier = Modifier,
) {
  Text(
    text = clockText,
    color = BiliColors.TextPrimary,
    fontSize = BiliTypography.PlayerMeta,
    fontWeight = FontWeight.Bold,
    modifier = modifier,
  )
}

fun currentClockText(): String {
  return SimpleDateFormat("HH:mm", Locale.US).format(Date())
}

fun currentClockMinuteKey(): Long {
  return System.currentTimeMillis() / MillisPerMinute
}

private const val MillisPerMinute = 60_000L
