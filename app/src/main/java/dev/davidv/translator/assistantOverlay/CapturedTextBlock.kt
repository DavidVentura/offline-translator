package dev.davidv.translator.assistantOverlay

import android.graphics.Rect

data class CapturedTextStyle(
  val textSizePx: Float? = null,
  val textColor: Int? = null,
  val textBackgroundColor: Int? = null,
  val styleBits: Int = 0,
)

data class CapturedTextBlock(
  val text: String,
  val bounds: Rect,
  val style: CapturedTextStyle? = null,
  val fromWebView: Boolean = false,
)
