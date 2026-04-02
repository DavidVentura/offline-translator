package dev.davidv.translator.assistantOverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.davidv.translator.OverlayColors
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.TextStyle
import dev.davidv.translator.TranslatedStyledBlock
import dev.davidv.translator.getOverlayColors
import dev.davidv.translator.Rect as TranslatorRect

class OverlayRenderer(
  private val context: Context,
  private val dpToPx: (Int) -> Int,
  private val settingsManager: SettingsManager,
) {
  private val tag = "TranslatorAssistant"
  private val debugLoggingEnabled = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
  private var measureTextView: TextView? = null

  fun renderStyledBlocks(
    container: FrameLayout,
    blocks: List<TranslatedStyledBlock>,
    screenshot: Bitmap?,
    systemBarTop: Int,
  ) {
    if (debugLoggingEnabled) {
      Log.d(
        tag,
        "Rendering ${blocks.size} styled blocks with screenshot=${screenshot != null} backgroundMode=${settingsManager.settings.value.backgroundMode}",
      )
    }
    container.removeAllViews()
    val screenHeight = context.resources.displayMetrics.heightPixels
    for (block in blocks) {
      if (block.text.isBlank()) continue
      val adjustedTop = block.bounds.top - systemBarTop
      val adjustedBottom = block.bounds.bottom - systemBarTop
      val visibleHeight = minOf(adjustedBottom, screenHeight) - maxOf(adjustedTop, 0)
      if (visibleHeight < block.bounds.height() / 2) continue
      addStyledOverlay(container, block, screenshot, systemBarTop)
    }
  }

  private fun addStyledOverlay(
    container: FrameLayout,
    block: TranslatedStyledBlock,
    screenshot: Bitmap?,
    systemBarTop: Int,
  ) {
    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels
    val left = block.bounds.left.coerceIn(0, screenWidth - 1)
    val top = (block.bounds.top - systemBarTop).coerceIn(0, screenHeight - 1)
    val width = maxOf(dpToPx(48), minOf(block.bounds.width(), screenWidth - left))
    val targetHeight = block.bounds.height().coerceAtLeast(1)
    if (width <= 0) return

    val colors = resolveBlockColors(block.bounds, block.styleSpans.firstOrNull()?.style, screenshot)
    val ssb = SpannableStringBuilder(block.text)

    ssb.setSpan(ForegroundColorSpan(colors.foreground), 0, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

    for (span in block.styleSpans) {
      val start = span.start.coerceIn(0, ssb.length)
      val end = span.end.coerceIn(start, ssb.length)
      if (start == end) continue
      val style = span.style ?: continue

      val fg = normalizeStyleColor(style.textColor)
      if (fg != null) {
        ssb.setSpan(ForegroundColorSpan(fg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (style.hasRealBackground()) {
        ssb.setSpan(BackgroundColorSpan(style.bgColor!!), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (style.bold) {
        ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (style.italic) {
        ssb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (style.underline) {
        ssb.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (style.strikethrough) {
        ssb.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
    }

    val representativeTextSize =
      block.styleSpans.firstNotNullOfOrNull { it.style?.textSize?.takeIf { s -> s > 0f } }
    val initialTextSizePx =
      representativeTextSize
        ?.times(settingsManager.settings.value.fontFactor)
        ?: minOf(48f * context.resources.displayMetrics.scaledDensity, targetHeight.toFloat())
          .coerceAtLeast(12f)

    val hasBold = block.styleSpans.any { it.style?.bold == true }

    val overlayFrame =
      FrameLayout(context).apply {
        background =
          GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8).toFloat()
            setColor(colors.background)
          }
      }

    val textView =
      TextView(context).apply {
        text = ssb
        setPadding(0, 0, 0, 0)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        maxLines = Int.MAX_VALUE
        setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        setTextSize(
          TypedValue.COMPLEX_UNIT_PX,
          findFittingTextSizePx(
            translatedText = ssb.toString(),
            width = width,
            targetHeight = targetHeight,
            initialTextSizePx = initialTextSizePx,
            bold = hasBold,
          ),
        )
      }

    overlayFrame.addView(
      textView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )

    val params =
      FrameLayout
        .LayoutParams(
          width,
          targetHeight,
        ).apply {
          leftMargin = left
          topMargin = top
        }
    container.addView(overlayFrame, params)
  }

  private fun resolveBlockColors(
    bounds: TranslatorRect,
    firstStyle: TextStyle?,
    screenshot: Bitmap?,
  ): OverlayColors {
    val sampledColors =
      screenshot?.let {
        getOverlayColors(it, bounds, settingsManager.settings.value.backgroundMode)
      }

    val styleFg = normalizeStyleColor(firstStyle?.textColor)
    val styleBg = if (firstStyle?.hasRealBackground() == true) firstStyle.bgColor else null
    if (styleBg != null) {
      return OverlayColors(styleBg, styleFg ?: sampledColors?.foreground ?: Color.BLACK)
    }
    if (sampledColors != null) {
      return OverlayColors(sampledColors.background, styleFg ?: sampledColors.foreground)
    }
    if (styleFg != null) {
      val lum = (Color.red(styleFg) * 299 + Color.green(styleFg) * 587 + Color.blue(styleFg) * 114) / 255000f
      val bg = if (lum > 0.5f) Color.BLACK else Color.WHITE
      return OverlayColors(bg, styleFg)
    }
    return OverlayColors(Color.WHITE, Color.BLACK)
  }

  private fun normalizeStyleColor(color: Int?): Int? {
    if (color == null) return null
    if (Color.alpha(color) == 0) return null
    return color
  }

  private fun findFittingTextSizePx(
    translatedText: String,
    width: Int,
    targetHeight: Int,
    initialTextSizePx: Float,
    bold: Boolean,
  ): Float {
    val minTextSizePx = 8f * context.resources.displayMetrics.scaledDensity
    var sizePx = initialTextSizePx
    while (sizePx > minTextSizePx) {
      if (measuredTextHeight(translatedText, width, sizePx, bold) <= targetHeight) {
        return sizePx
      }
      sizePx -= 1f
    }
    return minTextSizePx
  }

  private fun getOrCreateMeasureView(): TextView {
    measureTextView?.let { return it }
    val tv =
      TextView(context).apply {
        layoutParams =
          FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
          )
        setPadding(0, 0, 0, 0)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        maxLines = Int.MAX_VALUE
        setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
      }
    measureTextView = tv
    return tv
  }

  private fun measuredTextHeight(
    translatedText: String,
    width: Int,
    textSizePx: Float,
    bold: Boolean,
  ): Int {
    val tv = getOrCreateMeasureView()
    tv.text = translatedText
    tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
    val typefaceStyle = if (bold) Typeface.BOLD else Typeface.NORMAL
    tv.typeface = Typeface.create(Typeface.DEFAULT, typefaceStyle)
    val exactWidth = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
    val unspecifiedHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    tv.measure(exactWidth, unspecifiedHeight)
    return tv.measuredHeight
  }
}
