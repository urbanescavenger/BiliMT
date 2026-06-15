package com.kirin.mt.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.kirin.mt.ui.theme.BiliFocus
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

internal val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
internal fun Modifier.biliLiquidGlassSurface(
  enabled: Boolean,
  shape: Shape,
  surfaceColor: Color,
  borderColor: Color,
  borderWidth: Dp,
): Modifier {
  val backdrop = LocalLiquidGlassBackdrop.current
  val surfaceModifier = if (enabled && backdrop != null) {
    drawBackdrop(
      backdrop = backdrop,
      shape = { shape },
      effects = {
        vibrancy()
        backdropBlur(BiliFocus.LiquidGlassBlurRadius.toPx())
        lens(
          refractionHeight = BiliFocus.LiquidGlassRefractionHeight.toPx(),
          refractionAmount = BiliFocus.LiquidGlassRefractionAmount.toPx(),
          depthEffect = true,
        )
      },
      highlight = { Highlight.Ambient },
      shadow = { Shadow.Default },
      innerShadow = null,
      onDrawSurface = {
        drawShape(shape = shape, color = surfaceColor)
      },
      onDrawFront = {
        val strokeWidth = borderWidth.toPx()
        if (strokeWidth > 0f) {
          drawShape(
            shape = shape,
            color = borderColor,
            style = Stroke(width = strokeWidth),
          )
        }
      },
    )
  } else {
    background(surfaceColor, shape)
      .border(BorderStroke(borderWidth, borderColor), shape)
  }

  return then(surfaceModifier)
}

private fun DrawScope.drawShape(
  shape: Shape,
  color: Color,
  style: DrawStyle = Fill,
) {
  when (val outline = shape.createOutline(size, layoutDirection, this)) {
    is Outline.Rectangle -> drawRect(color = color, style = style)
    is Outline.Rounded -> drawPath(
      path = Path().apply { addRoundRect(outline.roundRect) },
      color = color,
      style = style,
    )
    is Outline.Generic -> drawPath(
      path = outline.path,
      color = color,
      style = style,
    )
  }
}
