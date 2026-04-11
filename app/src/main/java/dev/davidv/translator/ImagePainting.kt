/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.davidv.translator

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.Log
import kotlin.math.floor

data class OverlayColors(val background: Int, val foreground: Int)

private const val REPAINT_DEBUG_TAG = "RepaintBlocks"

fun getOverlayColors(
  bitmap: Bitmap,
  bounds: Rect,
  backgroundMode: BackgroundMode,
  wordRects: Array<Rect>? = null,
): OverlayColors {
  return when (backgroundMode) {
    BackgroundMode.WHITE_ON_BLACK -> OverlayColors(Color.BLACK, Color.WHITE)
    BackgroundMode.BLACK_ON_WHITE -> OverlayColors(Color.WHITE, Color.BLACK)
    BackgroundMode.AUTO_DETECT -> {
      val bgColor =
        if (wordRects != null && wordRects.count() > 1) {
          getBackgroundColorExcludingWords(bitmap, bounds, wordRects)
        } else if (wordRects != null) {
          getSurroundingAverageColor(bitmap, bounds)
        } else {
          sampleDominantColor(bitmap, bounds)
        }
      val fgColor = getForegroundColorByContrast(bitmap, bounds, bgColor)
      OverlayColors(bgColor, fgColor)
    }
  }
}

fun sampleDominantColor(
  bitmap: Bitmap,
  bounds: Rect,
): Int {
  val left = bounds.left.coerceIn(0, bitmap.width - 1)
  val top = bounds.top.coerceIn(0, bitmap.height - 1)
  val right = bounds.right.coerceIn(left + 1, bitmap.width)
  val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)
  val w = right - left
  val h = bottom - top
  if (w <= 0 || h <= 0) return Color.WHITE

  val pixels = IntArray(w * h)
  bitmap.getPixels(pixels, 0, w, left, top, w, h)

  val step = maxOf(1, pixels.size / 500)

  data class Bucket(var count: Int, var rSum: Long, var gSum: Long, var bSum: Long)
  val buckets = mutableMapOf<Int, Bucket>()

  var i = 0
  while (i < pixels.size) {
    val pixel = pixels[i]
    val key = Color.rgb(Color.red(pixel) and 0xF0, Color.green(pixel) and 0xF0, Color.blue(pixel) and 0xF0)
    val existing = buckets[key]
    if (existing != null) {
      existing.count++
      existing.rSum += Color.red(pixel)
      existing.gSum += Color.green(pixel)
      existing.bSum += Color.blue(pixel)
    } else {
      buckets[key] = Bucket(1, Color.red(pixel).toLong(), Color.green(pixel).toLong(), Color.blue(pixel).toLong())
    }
    i += step
  }

  val best =
    buckets.values
      .maxByOrNull { it.count }
      ?: return Color.WHITE

  return Color.rgb(
    (best.rSum / best.count).toInt(),
    (best.gSum / best.count).toInt(),
    (best.bSum / best.count).toInt(),
  )
}

sealed class TextFitResult {
  object DoesNotFit : TextFitResult()

  data class Fits(
    val lineBreaks: List<TextLineBreak>,
  ) : TextFitResult()
}

data class TextLineBreak(
  val startIndex: Int,
  val endIndex: Int,
)

fun getForegroundColorByContrast(
  bitmap: Bitmap,
  textBounds: Rect,
  backgroundColor: Int,
): Int {
  val bgLuminance = getLuminance(backgroundColor)
  val bestNaiveColor = if (bgLuminance > 0.5) Color.BLACK else Color.WHITE

  val left = textBounds.left.coerceIn(0, bitmap.width - 1)
  val top = textBounds.top.coerceIn(0, bitmap.height - 1)
  val right = textBounds.right.coerceIn(left + 1, bitmap.width)
  val bottom = textBounds.bottom.coerceIn(top + 1, bitmap.height)
  val width = right - left
  val height = bottom - top
  if (width <= 0 || height <= 0) {
    return bestNaiveColor
  }

  val pixels = IntArray(width * height)
  bitmap.getPixels(
    pixels,
    0,
    width,
    left,
    top,
    width,
    height,
  )
  // Sample 1 out of every 5 pixels
  val step = maxOf(1, minOf(width, height) / 5)

  // quantized color -> (count, sum of contrasts, first original color)
  val colorData = mutableMapOf<Int, Triple<Int, Float, Int>>()

  var i = 0
  while (i < pixels.size) {
    val pixel = pixels[i]
    val contrast = getColorContrast(pixel, bgLuminance)

    if (contrast <= 1.5f) {
      i += step
      continue
    }

    // quantize the colors, we don't care that much for the least-significant nibble
    // and this dramatically reduces the amount of work we need to do
    val r = Color.red(pixel) and 0xF0
    val g = Color.green(pixel) and 0xF0
    val b = Color.blue(pixel) and 0xF0
    val quantizedColor = Color.rgb(r, g, b)

    val existing = colorData[quantizedColor]
    if (existing != null) {
      colorData[quantizedColor] =
        Triple(existing.first + 1, existing.second + contrast, existing.third)
    } else {
      colorData[quantizedColor] = Triple(1, contrast, pixel)
    }
    i += step
  }

  if (colorData.isEmpty()) {
    return bestNaiveColor
  }

  var bestColor = bestNaiveColor
  var bestScore = 0f

  for ((_, data) in colorData) {
    val count = data.first
    if (count > 3) {
      val avgContrast = data.second / count
      val score = count * avgContrast
      if (score > bestScore) {
        bestScore = score
        bestColor = data.third
      }
    }
  }

  return bestColor
}

fun getColorContrast(
  color1: Int,
  bgLuminance: Float,
): Float {
  val lum = getLuminance(color1)

  val brighter = maxOf(lum, bgLuminance)
  val darker = minOf(lum, bgLuminance)

  return (brighter + 0.05f) / (darker + 0.05f)
}

fun getLuminance(color: Int): Float {
  val r = Color.red(color) / 255f
  val g = Color.green(color) / 255f
  val b = Color.blue(color) / 255f

  return 0.299f * r + 0.587f * g + 0.114f * b
}

// TODO: this paints over each word with the average of the entire block
// maybe we should get the background color for each word?
// in pictures the color shifts quite a bit within the same block
fun removeTextWithSmartBlur(
  canvas: Canvas,
  bitmap: Bitmap,
  textBounds: Rect,
  words: Array<Rect>,
  backgroundMode: BackgroundMode = BackgroundMode.AUTO_DETECT,
): Int {
  val colors = getOverlayColors(bitmap, textBounds, backgroundMode, words)
  val surroundingColor = colors.background
  val fgColor = colors.foreground
  var paint =
    Paint().apply {
      color = surroundingColor
    }

  if (backgroundMode == BackgroundMode.AUTO_DETECT) {
    words.forEach { word ->
      val w = toAndroidRect(word)
      w.inset(-2, -2)
      canvas.drawRect(w, paint)
    }

    paint =
      Paint().apply {
        color = surroundingColor
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.INNER)
      }
  }

  canvas.drawRect(toAndroidRect(textBounds), paint)

  return fgColor
}

fun getSurroundingAverageColor(
  bitmap: Bitmap,
  textBounds: Rect,
): Int {
  val margin = 4
  val sampleRegions =
    listOf(
      // Left side
      Rect(
        maxOf(0, textBounds.left - margin),
        textBounds.top,
        textBounds.left,
        textBounds.bottom,
      ),
      // Right side
      Rect(
        textBounds.right,
        textBounds.top,
        minOf(bitmap.width, textBounds.right + margin),
        textBounds.bottom,
      ),
      // Top
      Rect(
        textBounds.left,
        maxOf(0, textBounds.top - margin),
        textBounds.right,
        textBounds.top,
      ),
      // Bottom
      Rect(
        textBounds.left,
        textBounds.bottom,
        textBounds.right,
        minOf(bitmap.height, textBounds.bottom + margin),
      ),
    )

  var totalR = 0L
  var totalG = 0L
  var totalB = 0L
  var totalCount = 0

  for (region in sampleRegions) {
    if (region.width() == 0 || region.height() == 0) {
      continue
    }

    val pixels = IntArray(region.width() * region.height())
    bitmap.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
    for (pixel in pixels) {
      totalR += Color.red(pixel)
      totalG += Color.green(pixel)
      totalB += Color.blue(pixel)
      totalCount++
    }
  }

  return if (totalCount > 0) {
    Color.rgb(
      (totalR / totalCount).toInt(),
      (totalG / totalCount).toInt(),
      (totalB / totalCount).toInt(),
    )
  } else {
    Color.WHITE
  }
}

fun doesTextFitInLines(
  text: String,
  lines: Array<TextLine>,
  textPaint: TextPaint,
): TextFitResult {
  val translatedSpaceIndices =
    text.mapIndexedNotNull { index, char ->
      if (char == ' ') index else null
    }

  val lineBreaks = mutableListOf<TextLineBreak>()
  var start = 0

  for (line in lines) {
    if (start >= text.length) break

    val measuredWidth = FloatArray(1)
    val countedChars =
      textPaint.breakText(
        text,
        start,
        text.length,
        true,
        line.boundingBox.width().toFloat(),
        measuredWidth,
      )

    val endIndex: Int =
      if (start + countedChars == text.length) {
        text.length
      } else {
        val previousSpaceIndex = translatedSpaceIndices.findLast { it < start + countedChars }
        previousSpaceIndex?.let { it + 1 } ?: (start + countedChars)
      }

    lineBreaks.add(TextLineBreak(start, endIndex))
    start = endIndex
  }

  return if (start >= text.length) {
    TextFitResult.Fits(lineBreaks)
  } else {
    TextFitResult.DoesNotFit
  }
}

fun doesTextFitInRect(
  text: String,
  bounds: Rect,
  textPaint: TextPaint,
): TextFitResult {
  if (text.isEmpty()) return TextFitResult.Fits(emptyList())
  if (bounds.width() <= 0 || bounds.height() <= 0) return TextFitResult.DoesNotFit

  val lineHeight = textPaint.descent() - textPaint.ascent()
  val maxLines = floor(bounds.height() / lineHeight).toInt().coerceAtLeast(1)
  val lineBreaks = mutableListOf<TextLineBreak>()
  var start = 0

  while (start < text.length && lineBreaks.size < maxLines) {
    while (start < text.length && text[start] == ' ') {
      start++
    }
    if (start >= text.length) break

    val newlineIndex = text.indexOf('\n', startIndex = start).let { if (it == -1) text.length else it }
    val measuredWidth = FloatArray(1)
    val countedChars =
      textPaint.breakText(
        text,
        start,
        newlineIndex,
        true,
        bounds.width().toFloat(),
        measuredWidth,
      )
    if (countedChars <= 0) {
      return TextFitResult.DoesNotFit
    }

    val rawEnd = start + countedChars
    val endIndex =
      when {
        rawEnd >= newlineIndex -> newlineIndex
        else -> {
          val previousSpaceIndex = text.lastIndexOf(' ', startIndex = rawEnd - 1)
          if (previousSpaceIndex >= start) previousSpaceIndex else rawEnd
        }
      }

    val safeEnd = if (endIndex <= start) rawEnd else endIndex
    lineBreaks.add(TextLineBreak(start, safeEnd))
    start = safeEnd

    while (start < text.length && text[start] == ' ') {
      start++
    }
    if (start < text.length && text[start] == '\n') {
      start++
    }
  }

  return if (start >= text.length) {
    TextFitResult.Fits(lineBreaks)
  } else {
    TextFitResult.DoesNotFit
  }
}

fun paintTranslatedTextOver(
  originalBitmap: Bitmap,
  textBlocks: Array<TextBlock>,
  translatedBlocks: List<String>,
  backgroundMode: BackgroundMode = BackgroundMode.AUTO_DETECT,
): Pair<Bitmap, String> {
  val mutableBitmap = originalBitmap.copy(originalBitmap.config, true)
  val canvas = Canvas(mutableBitmap)

  val textPaint =
    TextPaint().apply {
      isAntiAlias = true
    }

  val testingBoxes = false
  var allTranslatedText = ""

  textBlocks.forEachIndexed { i, textBlock ->
    debugRepaintBlock(
      blockIndex = i,
      textBlock = textBlock,
      translated = translatedBlocks.getOrNull(i),
    )

    val blockAvgPixelHeight =
      textBlock.lines
        .map { textLine -> textLine.boundingBox.height() }
        .average()
        .toFloat()

    val translated = translatedBlocks.getOrNull(i) ?: return@forEachIndexed

    allTranslatedText = "${allTranslatedText}\n$translated"

    val minTextSize = 8f

    textPaint.textSize = floor(blockAvgPixelHeight)
    var fitResult = doesTextFitInLines(translated, textBlock.lines, textPaint)
    while (fitResult is TextFitResult.DoesNotFit && textPaint.textSize > minTextSize) {
      textPaint.textSize -= 1f
      fitResult = doesTextFitInLines(translated, textBlock.lines, textPaint)
    }

    if (testingBoxes) {
      textBlock.lines.forEach {}
    }
    // Store colors for each line to avoid redundant calculations
    val lineColors = mutableMapOf<Int, Int>()
    if (!testingBoxes) {
      textBlock.lines.forEachIndexed { index, line ->
        val fg =
          removeTextWithSmartBlur(
            canvas,
            mutableBitmap,
            line.boundingBox,
            line.wordRects,
            backgroundMode,
          )
        lineColors[index] = fg
      }
    }

    // only false if we would need to have text size < 8f
    if (fitResult is TextFitResult.Fits) {
      textBlock.lines.forEachIndexed { lineIndex, line ->
        // Set color for this specific line
        lineColors[lineIndex]?.let { color ->
          textPaint.color = color
        }

        if (testingBoxes) {
          val p =
            TextPaint().apply {
              color = Color.RED
              style = Paint.Style.STROKE
            }
          line.wordRects.forEach { w ->
            canvas.drawRect(toAndroidRect(w), p)
          }
          val l = toAndroidRect(line.boundingBox)
//          l.inset(-2, -2)
          canvas.drawRect(l, p.apply { color = Color.BLUE })
        }

        val lineBreak = fitResult.lineBreaks.getOrNull(lineIndex)
        if (lineBreak != null && lineBreak.startIndex < translated.length) {
          if (!testingBoxes) {
            canvas.drawText(
              translated,
              lineBreak.startIndex,
              lineBreak.endIndex,
              line.boundingBox.left.toFloat(),
              line.boundingBox.top.toFloat() - textPaint.ascent(),
              textPaint,
            )
          }
        }
      }
    }
  }

  return Pair(mutableBitmap, allTranslatedText.trim())
}

fun paintTranslatedTextOverVerticalBlocks(
  originalBitmap: Bitmap,
  textBlocks: Array<TextBlock>,
  translatedBlocks: List<String>,
  backgroundMode: BackgroundMode = BackgroundMode.AUTO_DETECT,
): Pair<Bitmap, String> {
  val mutableBitmap = originalBitmap.copy(originalBitmap.config, true)
  val canvas = Canvas(mutableBitmap)
  val textPaint =
    TextPaint().apply {
      isAntiAlias = true
    }

  var allTranslatedText = ""

  textBlocks.forEachIndexed { i, textBlock ->
    val translated = translatedBlocks.getOrNull(i) ?: return@forEachIndexed
    debugRepaintBlock(
      blockIndex = i,
      textBlock = textBlock,
      translated = translated,
    )

    allTranslatedText = "${allTranslatedText}\n$translated"

    val blockBounds = textBlock.blockBounds()
    val allWordRects = textBlock.lines.flatMap { it.wordRects.asList() }.toTypedArray()
    val minTextSize = 8f
    val initialTextSize =
      floor(
        textBlock.lines
          .map { line -> line.boundingBox.width() }
          .average()
          .toFloat(),
      ).coerceAtLeast(minTextSize)

    textPaint.textSize = initialTextSize
    var fitResult = doesTextFitInRect(translated, blockBounds, textPaint)
    while (fitResult is TextFitResult.DoesNotFit && textPaint.textSize > minTextSize) {
      textPaint.textSize -= 1f
      fitResult = doesTextFitInRect(translated, blockBounds, textPaint)
    }

    val foregroundColor =
      removeTextWithSmartBlur(
        canvas,
        mutableBitmap,
        blockBounds,
        allWordRects,
        backgroundMode,
      )
    textPaint.color = foregroundColor

    if (fitResult is TextFitResult.Fits) {
      val lineHeight = textPaint.descent() - textPaint.ascent()
      val firstBaseline = blockBounds.top.toFloat() - textPaint.ascent()
      fitResult.lineBreaks.forEachIndexed { lineIndex, lineBreak ->
        if (lineBreak.startIndex >= translated.length) return@forEachIndexed
        canvas.drawText(
          translated,
          lineBreak.startIndex,
          lineBreak.endIndex,
          blockBounds.left.toFloat(),
          firstBaseline + (lineIndex * lineHeight),
          textPaint,
        )
      }
    }
  }

  return Pair(mutableBitmap, allTranslatedText.trim())
}

private fun debugRepaintBlock(
  blockIndex: Int,
  textBlock: TextBlock,
  translated: String?,
) {
  val blockBounds = textBlock.blockBounds()
  val sourceText = textBlock.lines.joinToString(separator = " | ") { it.text }
  Log.d(
    REPAINT_DEBUG_TAG,
    "block[$blockIndex] bounds=${blockBounds.compactString()} lines=${textBlock.lines.size} src=\"$sourceText\" translated=\"${translated ?: ""}\"",
  )
  textBlock.lines.forEachIndexed { lineIndex, line ->
    val wordRects = line.wordRects.joinToString(separator = ",") { it.compactString() }
    Log.d(
      REPAINT_DEBUG_TAG,
      "block[$blockIndex] line[$lineIndex] bounds=${line.boundingBox.compactString()} words=${line.wordRects.size} text=\"${line.text}\" wordRects=[$wordRects]",
    )
  }
}

private fun TextBlock.blockBounds(): Rect {
  val first = lines.firstOrNull()?.boundingBox ?: return Rect(0, 0, 0, 0)
  val combined = Rect(first)
  lines.drop(1).forEach { combined.union(it.boundingBox) }
  return combined
}

private fun Rect.compactString(): String = "[$left,$top,$right,$bottom]"

private fun toAndroidRect(r: Rect): android.graphics.Rect = android.graphics.Rect(r.left, r.top, r.right, r.bottom)

fun getBackgroundColorExcludingWords(
  bitmap: Bitmap,
  textBounds: Rect,
  wordRects: Array<Rect>,
): Int {
  val width = textBounds.width()
  val height = textBounds.height()
  if (width <= 0 || height <= 0) {
    return getSurroundingAverageColor(bitmap, textBounds)
  }

  val pixels = IntArray(width * height)
  bitmap.getPixels(pixels, 0, width, textBounds.left, textBounds.top, width, height)

  val mask = BooleanArray(width * height) { true }

  for (excludeRect in wordRects) {
    val intersect = android.graphics.Rect()
    if (intersect.setIntersect(toAndroidRect(textBounds), toAndroidRect(excludeRect))) {
      val offsetLeft = intersect.left - textBounds.left
      val offsetTop = intersect.top - textBounds.top
      val intersectWidth = intersect.width()
      val intersectHeight = intersect.height()

      for (y in 0 until intersectHeight) {
        val rowStart = (offsetTop + y) * width + offsetLeft
        mask.fill(false, rowStart, rowStart + intersectWidth)
      }
    }
  }

  var totalR = 0L
  var totalG = 0L
  var totalB = 0L
  var count = 0

  for (i in pixels.indices) {
    if (mask[i]) {
      val pixel = pixels[i]
      totalR += Color.red(pixel)
      totalG += Color.green(pixel)
      totalB += Color.blue(pixel)
      count++
    }
  }

  if (count == 0) {
    return getSurroundingAverageColor(bitmap, textBounds)
  }

  return Color.rgb(
    (totalR / count).toInt(),
    (totalG / count).toInt(),
    (totalB / count).toInt(),
  )
}
