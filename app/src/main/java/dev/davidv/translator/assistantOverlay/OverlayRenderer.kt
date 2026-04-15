package dev.davidv.translator.assistantOverlay

import android.content.Context
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
import dev.davidv.translator.SettingsManager

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
    blocks: List<uniffi.translator.TranslatedStyledBlock>,
    systemBarTop: Int,
  ) {
    if (debugLoggingEnabled) {
      Log.d(
        tag,
        "Rendering ${blocks.size} styled blocks with native colors",
      )
    }
    container.removeAllViews()
    val screenHeight = context.resources.displayMetrics.heightPixels
    for (block in blocks) {
      if (block.text.isBlank()) continue
      val bounds = block.boundingBox
      val adjustedTop = bounds.top.toInt() - systemBarTop
      val adjustedBottom = bounds.bottom.toInt() - systemBarTop
      val visibleHeight = minOf(adjustedBottom, screenHeight) - maxOf(adjustedTop, 0)
      val blockHeight = (bounds.bottom - bounds.top).toInt()
      if (visibleHeight < blockHeight / 2) continue
      addStyledOverlay(container, block, systemBarTop)
    }
  }

  private fun addStyledOverlay(
    container: FrameLayout,
    block: uniffi.translator.TranslatedStyledBlock,
    systemBarTop: Int,
  ) {
    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels
    val bounds = block.boundingBox
    val left = bounds.left.toInt().coerceIn(0, screenWidth - 1)
    val top = (bounds.top.toInt() - systemBarTop).coerceIn(0, screenHeight - 1)
    val blockWidth = (bounds.right - bounds.left).toInt()
    val blockHeight = (bounds.bottom - bounds.top).toInt()
    val width = maxOf(dpToPx(48), minOf(blockWidth, screenWidth - left))
    val targetHeight = blockHeight.coerceAtLeast(1)
    if (width <= 0) return

    val colors =
      dev.davidv.translator.OverlayColors(
        background = block.backgroundArgb.toInt(),
        foreground = block.foregroundArgb.toInt(),
      )
    val ssb = SpannableStringBuilder(block.text)

    ssb.setSpan(ForegroundColorSpan(colors.foreground), 0, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

    for (span in block.styleSpans) {
      val start = span.start.toInt().coerceIn(0, ssb.length)
      val end = span.end.toInt().coerceIn(start, ssb.length)
      if (start == end) continue
      val style = span.style ?: continue

      val fg = normalizeStyleColor(style.textColor?.toInt())
      if (fg != null) {
        ssb.setSpan(ForegroundColorSpan(fg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (hasRealBackground(style)) {
        ssb.setSpan(BackgroundColorSpan(style.bgColor!!.toInt()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    val fittedTextSizePx =
      findFittingTextSizePx(
        translatedText = ssb.toString(),
        width = width,
        targetHeight = targetHeight,
        initialTextSizePx = initialTextSizePx,
        bold = hasBold,
      )
    val measuredHeight = measuredTextHeight(ssb.toString(), width, fittedTextSizePx, hasBold)
    val useWrapHeight = measuredHeight < targetHeight / 2
    val actualHeight = if (useWrapHeight) measuredHeight else targetHeight
    val actualTop = if (useWrapHeight) top + targetHeight - measuredHeight else top

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
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fittedTextSizePx)
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
          actualHeight,
        ).apply {
          leftMargin = left
          topMargin = actualTop
        }
    container.addView(overlayFrame, params)
  }

  private fun normalizeStyleColor(color: Int?): Int? {
    if (color == null) return null
    if (Color.alpha(color) == 0) return null
    return color
  }

  private fun hasRealBackground(style: uniffi.translator.TextStyle): Boolean {
    val color = style.bgColor?.toInt() ?: return false
    if (color == 0 || color == 1 || color == -1) return false
    if (Color.alpha(color) == 0) return false
    return true
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
