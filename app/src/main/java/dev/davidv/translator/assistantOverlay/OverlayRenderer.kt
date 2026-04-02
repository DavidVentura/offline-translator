package dev.davidv.translator.assistantOverlay

import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.davidv.translator.OverlayColors
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.getOverlayColors
import dev.davidv.translator.Rect as TranslatorRect

class OverlayRenderer(
  private val context: Context,
  private val dpToPx: (Int) -> Int,
  private val settingsManager: SettingsManager,
) {
  private var measureTextView: TextView? = null

  fun renderTranslatedBlocks(
    container: FrameLayout,
    blocksByText: LinkedHashMap<String, MutableList<CapturedTextBlock>>,
    results: LinkedHashMap<String, String>,
    screenshot: Bitmap?,
    systemBarTop: Int,
  ) {
    container.removeAllViews()
    val screenHeight = context.resources.displayMetrics.heightPixels
    val items = mutableListOf<OverlayItem>()
    for ((originalText, groupedBlocks) in blocksByText) {
      val translatedText = results[originalText] ?: continue
      for (block in groupedBlocks) {
        val adjustedTop = block.bounds.top - systemBarTop
        val adjustedBottom = block.bounds.bottom - systemBarTop
        val visibleHeight = minOf(adjustedBottom, screenHeight) - maxOf(adjustedTop, 0)
        if (visibleHeight < block.bounds.height() / 2) continue
        items.add(OverlayItem(translatedText, block, resolveColors(block, screenshot)))
      }
    }
    val groups = groupOverlapping(items)
    for (group in groups) {
      addOverlay(container, group, systemBarTop)
    }
  }

  private fun resolveColors(
    block: CapturedTextBlock,
    screenshot: Bitmap?,
  ): OverlayColors {
    val sampledColors =
      screenshot?.let {
        val translatorRect =
          TranslatorRect(
            block.bounds.left,
            block.bounds.top,
            block.bounds.right,
            block.bounds.bottom,
          )
        getOverlayColors(it, translatorRect, settingsManager.settings.value.backgroundMode)
      }

    val style = block.style
    val styleBg = normalizeStyleColor(style?.textBackgroundColor)
    val styleFg = normalizeStyleColor(style?.textColor)
    if (styleBg == null && styleFg != null) {
      val lum = (Color.red(styleFg) * 299 + Color.green(styleFg) * 587 + Color.blue(styleFg) * 114) / 255000f
      val bg = if (lum > 0.5f) Color.parseColor("#AA000000") else Color.parseColor("#AAF0F0F0")
      return OverlayColors(bg, styleFg)
    }
    val backgroundColor = styleBg ?: sampledColors?.background ?: Color.parseColor("#F0FFFFFF")
    val foregroundColor = styleFg ?: sampledColors?.foreground ?: Color.BLACK
    return OverlayColors(backgroundColor, foregroundColor)
  }

  private fun addOverlay(
    container: FrameLayout,
    group: List<OverlayItem>,
    systemBarTop: Int,
  ) {
    val unionBounds = Rect(group.first().block.bounds)
    for (item in group.drop(1)) unionBounds.union(item.block.bounds)

    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels
    val left = unionBounds.left.coerceIn(0, screenWidth - 1)
    val top = (unionBounds.top - systemBarTop).coerceIn(0, screenHeight - 1)
    val width = maxOf(dpToPx(48), minOf(unionBounds.width(), screenWidth - left))
    val targetHeight = unionBounds.height().coerceAtLeast(1)
    if (width <= 0) return

    val ssb = SpannableStringBuilder()
    var representativeStyle: CapturedTextStyle? = null
    for (item in group) {
      if (ssb.isNotEmpty() && !ssb.endsWith(' ') && !item.translatedText.startsWith(' ')) {
        ssb.append(' ')
      }
      val start = ssb.length
      ssb.append(item.translatedText)
      val end = ssb.length
      ssb.setSpan(ForegroundColorSpan(item.colors.foreground), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      val styleBits = item.block.style?.styleBits ?: 0
      if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_BOLD != 0) {
        ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_ITALIC != 0) {
        ssb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_UNDERLINE != 0) {
        ssb.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_STRIKE_THRU != 0) {
        ssb.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (representativeStyle == null) representativeStyle = item.block.style
    }

    val style = representativeStyle
    val initialTextSizePx =
      style
        ?.textSizePx
        ?.takeIf { it > 0f }
        ?.let { normalizeReportedTextSizePx(it, group.first().block.fromWebView) }
        ?.times(settingsManager.settings.value.fontFactor)
        ?: minOf(48f * context.resources.displayMetrics.scaledDensity, targetHeight.toFloat())
          .coerceAtLeast(12f)

    val overlayFrame =
      FrameLayout(context).apply {
        background =
          GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8).toFloat()
            setColor(group.first().colors.background)
          }
      }

    val textView =
      TextView(context).apply {
        text = ssb
        setPadding(0, 0, 0, 0)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        maxLines = if (group.size == 1) 10 else 20
        setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        setTextSize(
          TypedValue.COMPLEX_UNIT_PX,
          findFittingTextSizePx(
            translatedText = ssb.toString(),
            width = width,
            targetHeight = targetHeight,
            initialTextSizePx = initialTextSizePx,
            styleBits = style?.styleBits ?: 0,
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

  private fun groupOverlapping(items: List<OverlayItem>): List<List<OverlayItem>> {
    val groups = mutableListOf<MutableList<OverlayItem>>()
    for (item in items) {
      val itemBg = item.block.style?.textBackgroundColor
      val overlapping =
        groups.filter { group ->
          group.any { existing ->
            Rect.intersects(existing.block.bounds, item.block.bounds) &&
              existing.block.style?.textBackgroundColor == itemBg
          }
        }
      if (overlapping.isEmpty()) {
        groups.add(mutableListOf(item))
      } else {
        val merged = mutableListOf<OverlayItem>()
        for (group in overlapping) {
          merged.addAll(group)
          groups.remove(group)
        }
        merged.add(item)
        groups.add(merged)
      }
    }
    return groups
  }

  private fun normalizeStyleColor(color: Int?): Int? {
    if (color == null) return null
    if (Color.alpha(color) == 0) return null
    return color
  }

  private fun normalizeReportedTextSizePx(
    reportedSize: Float,
    fromWebView: Boolean,
  ): Float {
    if (!fromWebView) return reportedSize
    return reportedSize * context.resources.displayMetrics.density
  }

  private fun findFittingTextSizePx(
    translatedText: String,
    width: Int,
    targetHeight: Int,
    initialTextSizePx: Float,
    styleBits: Int,
  ): Float {
    val minTextSizePx = 8f * context.resources.displayMetrics.scaledDensity
    var sizePx = initialTextSizePx
    while (sizePx > minTextSizePx) {
      if (measuredTextHeight(translatedText, width, sizePx, styleBits) <= targetHeight) {
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
        maxLines = 10
        setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
      }
    measureTextView = tv
    return tv
  }

  private fun measuredTextHeight(
    translatedText: String,
    width: Int,
    textSizePx: Float,
    styleBits: Int,
  ): Int {
    val tv = getOrCreateMeasureView()
    tv.text = translatedText
    tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
    applyTextStyle(tv, styleBits)
    val exactWidth = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
    val unspecifiedHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    tv.measure(exactWidth, unspecifiedHeight)
    return tv.measuredHeight
  }

  private fun applyTextStyle(
    textView: TextView,
    styleBits: Int,
  ) {
    var typefaceStyle = Typeface.NORMAL
    if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_BOLD != 0) {
      typefaceStyle = typefaceStyle or Typeface.BOLD
    }
    if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_ITALIC != 0) {
      typefaceStyle = typefaceStyle or Typeface.ITALIC
    }
    textView.typeface = Typeface.create(Typeface.DEFAULT, typefaceStyle)

    var flags = textView.paintFlags
    if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_UNDERLINE != 0) {
      flags = flags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
    }
    if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_STRIKE_THRU != 0) {
      flags = flags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
    }
    textView.paintFlags = flags
  }

  private data class OverlayItem(
    val translatedText: String,
    val block: CapturedTextBlock,
    val colors: OverlayColors,
  )
}
