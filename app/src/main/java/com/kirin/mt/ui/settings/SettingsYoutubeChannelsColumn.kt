package com.kirin.mt.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kirin.mt.R
import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import kotlinx.coroutines.launch

/**
 * 设置右侧「YouTube 频道管理」面板。
 *
 * 内嵌轻量 D-pad 键盘(字母+数字+@-_.)输入 @handle / UC... 频道 ID,
 * 解析成功加入 [YoutubeChannelStore],频道行确认键删除。左键统一回设置列。
 */
@Composable
internal fun SettingsYoutubeChannelsColumn(
  channels: List<YoutubeChannel>,
  onAdd: suspend (String) -> Boolean,
  onRemove: suspend (String) -> Boolean,
  onMoveLeftToSettings: () -> Boolean,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val coroutineScope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  val firstKeyFocusRequester = remember { FocusRequester() }
  val expandFocusRequester = remember { FocusRequester() }
  // 组合期预取提示文案(stringResource 只能在此调用,协程里直接用值)。
  val addedMsg = stringResource(R.string.youtube_channel_added)
  val parseFailedMsg = stringResource(R.string.youtube_channel_parse_failed)

  var inputText by remember { mutableStateOf("") }
  var message by remember { mutableStateOf<String?>(null) }
  var adding by remember { mutableStateOf(false) }
  // 面板默认折叠,点击展开;展开后焦点落到「添加」按钮而非键盘。
  var expanded by remember { mutableStateOf(false) }

  // 面板打开时(默认折叠)把焦点落到展开区,提供 D-pad 入口。
  LaunchedEffect(Unit) {
    runCatching { expandFocusRequester.requestFocus() }
  }

  // 展开后把焦点落到「添加」按钮,不再自动切到字母键盘。
  LaunchedEffect(expanded) {
    if (expanded) {
      runCatching { firstKeyFocusRequester.requestFocus() }
    }
  }

  fun appendKey(key: String) {
    if (adding) return
    inputText += key
    message = null
  }

  fun backspace() {
    if (adding) return
    if (inputText.isNotEmpty()) {
      inputText = inputText.dropLast(1)
    }
  }

  fun clearInput() {
    if (adding) return
    inputText = ""
    message = null
  }

  fun submit() {
    val query = inputText.trim()
    if (query.isBlank() || adding) return
    adding = true
    message = null
    coroutineScope.launch {
      val ok = runCatching { onAdd(query) }.getOrDefault(false)
      adding = false
      if (ok) {
        inputText = ""
        message = addedMsg
      } else {
        message = parseFailedMsg
      }
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .onPreviewKeyEvent { event ->
        // 面板内任意位置按左键回设置列(覆盖键盘、按钮、频道行)。
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
          onMoveLeftToSettings()
        } else {
          false
        }
      },
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
  ) {
    Text(
      text = stringResource(R.string.settings_youtube_channels),
      color = homeColors.textSecondary,
      fontSize = BiliTypography.SectionTitle,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = stringResource(R.string.settings_youtube_channels_desc),
      color = homeColors.textSecondary,
      fontSize = BiliTypography.BodySmall,
    )

    // 展开/收起 区头(默认折叠,点击展开;展开后焦点落「添加」按钮而非键盘)。
    BiliFocusableSurface(
      scaleOnFocus = false,
      shadowOnFocus = false,
      shape = RoundedCornerShape(BiliRadius.Card),
      onClick = { expanded = !expanded },
      modifier = Modifier
        .fillMaxWidth()
        .focusRequester(expandFocusRequester),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = BiliSpacing.Lg, vertical = BiliSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(
            if (expanded) R.string.youtube_channel_collapse else R.string.youtube_channel_expand,
          ),
          color = homeColors.accent,
          fontSize = BiliTypography.Body,
          fontWeight = FontWeight.Bold,
        )
      }
    }

    if (expanded) {
      Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md)) {
        // 输入框(只读显示,焦点落在键盘/按钮)。
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(BiliSizing.SearchInputHeight)
            .clip(RoundedCornerShape(BiliRadius.Card))
            .background(homeColors.glassSurfaceStrong)
            .padding(horizontal = BiliSpacing.Lg),
          contentAlignment = Alignment.CenterStart,
        ) {
          Text(
            text = if (inputText.isBlank()) stringResource(R.string.youtube_channel_hint) else inputText,
            color = if (inputText.isBlank()) homeColors.textTertiary else homeColors.textPrimary,
            fontSize = BiliTypography.SearchInput,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        // 添加 / 清空 行。
        Row(
          horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
          modifier = Modifier.fillMaxWidth(),
        ) {
          YoutubeChannelActionButton(
            label = stringResource(R.string.youtube_add_channel),
            enabled = !adding,
            modifier = Modifier
              .weight(1f)
              .focusRequester(firstKeyFocusRequester),
            onClick = ::submit,
          )
          YoutubeChannelActionButton(
            label = stringResource(R.string.youtube_clear_input),
            enabled = !adding,
            modifier = Modifier.weight(1f),
            onClick = ::clearInput,
          )
        }

        // 键盘。
        YoutubeChannelKeyboard(
          onKey = ::appendKey,
          onBackspace = ::backspace,
        )

        // 状态提示(添加成功/失败)。
        message?.let { msg ->
          Text(
            text = msg,
            color = homeColors.accent,
            fontSize = BiliTypography.BodySmall,
            fontWeight = FontWeight.Bold,
          )
        }

        Text(
          text = stringResource(R.string.youtube_channel_count, channels.size),
          color = homeColors.textSecondary,
          fontSize = BiliTypography.BodySmall,
        )
        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
        ) {
          if (channels.isEmpty()) {
            item(key = "youtube-empty") {
              Text(
                text = stringResource(R.string.youtube_channel_empty),
                color = homeColors.textTertiary,
                fontSize = BiliTypography.BodySmall,
              )
            }
          } else {
            itemsIndexed(channels, key = { _, c -> c.channelId }) { _, channel ->
              YoutubeChannelRow(
                channel = channel,
                onRemove = {
                  coroutineScope.launch { onRemove(channel.channelId) }
                },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun YoutubeChannelRow(
  channel: YoutubeChannel,
  onRemove: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  BiliFocusableSurface(
    scaleOnFocus = false,
    shadowOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Panel),
    onClick = onRemove,
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.SettingsRowHeight),
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(start = BiliSpacing.Lg, end = BiliSpacing.Xl),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = channel.name,
          color = homeColors.textPrimary,
          fontSize = BiliTypography.Body,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = channel.channelId,
          color = homeColors.textSecondary,
          fontSize = BiliTypography.BodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = stringResource(R.string.youtube_channel_remove),
        color = homeColors.accent,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
private fun YoutubeChannelActionButton(
  label: String,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  BiliFocusableSurface(
    scaleOnFocus = false,
    shadowOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Card),
    onClick = onClick,
    modifier = modifier.height(BiliSizing.SearchKeyboardButtonHeight),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .clip(RoundedCornerShape(BiliRadius.Card))
        .background(
          if (enabled) homeColors.accent.copy(alpha = BiliFocus.SettingsChipSelectedBackgroundAlpha)
          else homeColors.glassSurfaceStrong,
        ),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = label,
        color = if (enabled) homeColors.textPrimary else homeColors.textTertiary,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

/** 轻量 D-pad 键盘:字母 + 数字 + @-_.,末行退格。 */
private val YoutubeKeyboardRows = listOf(
  listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"),
  listOf("m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x"),
  listOf("y", "z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"),
  listOf("@", "-", "_", "."),
)

@Composable
private fun YoutubeChannelKeyboard(
  onKey: (String) -> Unit,
  onBackspace: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm)) {
    YoutubeKeyboardRows.forEach { row ->
      Row(
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
        modifier = Modifier.fillMaxWidth(),
      ) {
        row.forEach { key ->
          YoutubeChannelKeyButton(
            label = key,
            modifier = Modifier
              .weight(1f)
              .height(BiliSizing.SearchKeyboardButtonHeight),
            onClick = { onKey(key) },
          )
        }
        // 末行(符号行)补一个等宽退格,其余行用透明占位保持行高一致。
        if (row.size == 4) {
          YoutubeChannelKeyButton(
            label = "⌫",
            modifier = Modifier
              .weight(1f)
              .height(BiliSizing.SearchKeyboardButtonHeight),
            onClick = onBackspace,
          )
        } else {
          Spacer(
            modifier = Modifier
              .weight(1f)
              .height(BiliSizing.SearchKeyboardButtonHeight),
          )
        }
      }
    }
  }
}

@Composable
private fun YoutubeChannelKeyButton(
  label: String,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  BiliFocusableSurface(
    scaleOnFocus = false,
    shadowOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Card),
    onClick = onClick,
    modifier = modifier,
  ) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = label,
        color = homeColors.textSecondary,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}
