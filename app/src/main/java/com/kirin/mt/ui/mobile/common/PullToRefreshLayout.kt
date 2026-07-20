package com.kirin.mt.ui.mobile.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 轻量自定义下拉刷新容器(版本无关,仅用 foundation nestedScroll + material3 CircularProgressIndicator)。
 * 列表到顶后继续下拉,越过阈值松手触发 onRefresh;下拉手势进行中顶部显示进度弧(0→1),
 * 松手触发刷新后进度弧收起,加载态由内容侧(各 screen 内联转圈)承担。
 * isRefreshing 仅用于下拉期间禁用重复拉动与兜底重置 pullPx,不再画顶部圈。
 */
@Composable
fun PullToRefreshLayout(
  isRefreshing: Boolean,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val density = LocalDensity.current
  val thresholdPx = with(density) { 160.dp.toPx() }
  val maxPullPx = with(density) { 240.dp.toPx() }
  var pullPx by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(isRefreshing) {
    if (!isRefreshing) pullPx = 0f
  }

  // rememberUpdatedState 让单例 connection 读到最新 isRefreshing/onRefresh,避免 remember{object} 陈旧捕获
  val refreshState = rememberUpdatedState(isRefreshing)
  val refreshAction = rememberUpdatedState(onRefresh)

  val connection = remember {
    object : NestedScrollConnection {
      override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // available.y > 0 => 列表到顶后继续下拉(overscroll),累加拉动距离并消费,避免子控件回弹。
        // (Compose 约定:y>0=手指下拖/内容下移=下拉,见官方 PullToRefresh 样板 onPostScroll)
        if (available.y > 0f && !refreshState.value) {
          pullPx = (pullPx + available.y).coerceAtMost(maxPullPx)
          return Offset(0f, available.y)
        }
        return Offset.Zero
      }

      override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (pullPx >= thresholdPx && !refreshState.value) {
          // 收起顶部进度弧,让内容侧内联转圈接管加载指示(避免顶部静止圈 + 居中转圈同屏)。
          pullPx = 0f
          refreshAction.value()
        } else if (!refreshState.value) {
          pullPx = 0f
        }
        return Velocity.Zero
      }
    }
  }

  Box(modifier.nestedScroll(connection)) {
    content()
    // 仅在下拉手势进行中显示进度弧(0→1);触发刷新后由内容侧内联转圈承担加载指示,
    // 不在 isRefreshing 时画顶部圈(否则 determinate 满圈静止 + 居中转圈同屏成两个圈)。
    if (pullPx > 0f && !isRefreshing) {
      CircularProgressIndicator(
        progress = (pullPx / thresholdPx).coerceIn(0f, 1f),
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(top = 8.dp)
          .offset { IntOffset(0, pullPx.roundToInt()) },
      )
    }
  }
}