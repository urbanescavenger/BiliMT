package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.kirin.mt.core.image.BiliImageSizing
import com.kirin.mt.core.image.buildVideoThumbnailRequest
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy
import com.kirin.mt.ui.theme.BiliSizing

/** 表情内联占位符的 id 前缀,只为在 AnnotatedString 与 inlineContent map 之间对齐。 */
private const val EmojiInlineIdPrefix = "dyn-emoji-"

/**
 * 动态正文渲染(P11-183)。
 *
 * B站 的正文是 `rich_text_nodes[]`:话题/@/网页链接这些节点自带可读 `text`,唯独**表情**不行 ——
 * 它的 `text` 只是 `[保佑]` 这种占位文字,真图在 `emoji.icon_url`。这里把表情用 Compose 的
 * inline content 内联成小图渲染,其余节点按文本拼接;整条没有表情时退化成平文本(与改动前等价)。
 *
 * 表情尺寸取固定 token 而非接口的 `emoji.size`(实测那是**档位**,样本值 1,不是像素)。
 */
@Composable
internal fun DynamicRichText(
  video: VideoSummary,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier,
  maxLines: Int = Int.MAX_VALUE,
  onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
  val nodes = video.dynamicTextNodes
  val annotated = remember(nodes, video.dynamicText) {
    if (nodes.none { it.emojiUrl.isNotBlank() }) {
      AnnotatedString(video.dynamicText)
    } else {
      buildAnnotatedString {
        nodes.forEachIndexed { index, node ->
          if (node.emojiUrl.isNotBlank()) {
            appendInlineContent(id = EmojiInlineIdPrefix + index, alternateText = node.text)
          } else {
            append(node.text)
          }
        }
      }
    }
  }

  val context = LocalContext.current
  val policy = LocalBiliPerformancePolicy.current
  val allowRgb565 = policy.videoThumbnailRgb565Enabled
  val memoryCacheEnabled = policy.imageMemoryCacheEnabled
  val inlineContent = remember(nodes, allowRgb565, memoryCacheEnabled) {
    nodes.withIndex()
      .filter { it.value.emojiUrl.isNotBlank() }
      .associate { (index, node) ->
        (EmojiInlineIdPrefix + index) to InlineTextContent(
          placeholder = Placeholder(
            width = BiliSizing.DynamicEmojiSize,
            height = BiliSizing.DynamicEmojiSize,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
          ),
        ) {
          AsyncImage(
            model = buildVideoThumbnailRequest(
              context = context,
              url = node.emojiUrl,
              widthPx = BiliImageSizing.DynamicEmojiSizePx,
              heightPx = BiliImageSizing.DynamicEmojiSizePx,
              allowRgb565 = allowRgb565,
              memoryCacheEnabled = memoryCacheEnabled,
            ),
            // 无障碍:表情读作它的占位文案(如「保佑」)。
            contentDescription = node.text,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
  }

  Text(
    text = annotated,
    style = style,
    color = color,
    modifier = modifier,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    inlineContent = inlineContent,
    onTextLayout = { layout -> onTextLayout?.invoke(layout) },
  )
}
