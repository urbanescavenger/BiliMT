package com.kirin.mt.core.i18n

import com.github.houbb.opencc4j.util.ZhHkConverterUtil
import com.github.houbb.opencc4j.util.ZhTwConverterUtil

fun interface ChineseTextConverter {
  fun convert(text: String): String
}

object ChineseTextConverters {
  val Simplified = ChineseTextConverter { text -> text }

  fun forVariant(variant: ChineseTextVariant): ChineseTextConverter {
    return when (variant) {
      ChineseTextVariant.Simplified -> Simplified
      ChineseTextVariant.HongKong -> ChineseTextConverter { text ->
        if (text.isBlank()) text else ZhHkConverterUtil.toTraditional(text)
      }
      ChineseTextVariant.Taiwan -> ChineseTextConverter { text ->
        if (text.isBlank()) text else ZhTwConverterUtil.toTraditional(text)
      }
      // 非中文界面语言:动态中文内容(标题/UP 名等)保持简体原样,不做转换
      ChineseTextVariant.English,
      ChineseTextVariant.Spanish,
      ChineseTextVariant.Portuguese,
      -> Simplified
    }
  }
}
