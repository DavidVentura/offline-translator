package dev.davidv.translator

data class TextStyle(
  val textColor: Int? = null,
  val bgColor: Int? = null,
  val textSize: Float? = null,
  val bold: Boolean = false,
  val italic: Boolean = false,
  val underline: Boolean = false,
  val strikethrough: Boolean = false,
) {
  fun hasRealBackground(): Boolean {
    val c = bgColor ?: return false
    if (c == 0 || c == 1 || c == -1) return false
    if (c ushr 24 == 0) return false
    return true
  }
}

data class StyledFragment(
  val text: String,
  val bounds: Rect,
  val style: TextStyle? = null,
  val layoutGroup: Int = 0,
  val translationGroup: Int = 0,
  val clusterGroup: Int = 0,
)

data class StyleSpan(
  val start: Int,
  val end: Int,
  val style: TextStyle?,
)

data class TranslatedStyledBlock(
  val text: String,
  val bounds: Rect,
  val styleSpans: List<StyleSpan>,
  val backgroundArgb: Int,
  val foregroundArgb: Int,
)
