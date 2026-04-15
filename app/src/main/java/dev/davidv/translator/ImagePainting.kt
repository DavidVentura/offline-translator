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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.Log
import uniffi.translator.PreparedTextBlock
import uniffi.translator.PreparedTextLine
import kotlin.math.floor

data class OverlayColors(val background: Int, val foreground: Int)

private const val REPAINT_DEBUG_TAG = "RepaintBlocks"

fun getOverlayColors(
  bitmap: Bitmap,
  bounds: Rect,
  backgroundMode: BackgroundMode,
  wordRects: Array<Rect>? = null,
): OverlayColors = sampleOverlayColors(bitmap, bounds, backgroundMode, wordRects)

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

fun doesTextFitInLines(
  text: String,
  lines: List<PreparedTextLine>,
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
  bounds: uniffi.translator.Rect,
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
  blocks: List<PreparedTextBlock>,
): Pair<Bitmap, String> {
  val mutableBitmap = originalBitmap.copy(originalBitmap.config, true)
  val canvas = Canvas(mutableBitmap)

  val textPaint =
    TextPaint().apply {
      isAntiAlias = true
    }

  val testingBoxes = false
  var allTranslatedText = ""

  blocks.forEachIndexed { i, block ->
    debugRepaintBlock(
      blockIndex = i,
      block = block,
    )

    val blockAvgPixelHeight =
      block.lines
        .map { line -> line.boundingBox.height() }
        .average()
        .toFloat()

    val translated = block.translatedText

    allTranslatedText = "${allTranslatedText}\n$translated"

    val minTextSize = 8f

    textPaint.textSize = floor(blockAvgPixelHeight)
    var fitResult = doesTextFitInLines(translated, block.lines, textPaint)
    while (fitResult is TextFitResult.DoesNotFit && textPaint.textSize > minTextSize) {
      textPaint.textSize -= 1f
      fitResult = doesTextFitInLines(translated, block.lines, textPaint)
    }

    if (testingBoxes) {
      block.lines.forEach {}
    }
    // only false if we would need to have text size < 8f
    if (fitResult is TextFitResult.Fits) {
      block.lines.forEachIndexed { lineIndex, line ->
        textPaint.color = line.foregroundArgb.toInt()

        if (testingBoxes) {
          val p =
            TextPaint().apply {
              color = Color.RED
              style = Paint.Style.STROKE
            }
          line.wordRects.forEach { w ->
            canvas.drawRect(w.toAndroidRect(), p)
          }
          val l = line.boundingBox.toAndroidRect()
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
  blocks: List<PreparedTextBlock>,
): Pair<Bitmap, String> {
  val mutableBitmap = originalBitmap.copy(originalBitmap.config, true)
  val canvas = Canvas(mutableBitmap)
  val textPaint =
    TextPaint().apply {
      isAntiAlias = true
    }

  var allTranslatedText = ""

  blocks.forEachIndexed { i, block ->
    val translated = block.translatedText
    debugRepaintBlock(
      blockIndex = i,
      block = block,
    )

    allTranslatedText = "${allTranslatedText}\n$translated"

    val blockBounds = block.boundingBox
    val minTextSize = 8f
    val initialTextSize =
      floor(
        block.lines
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

    textPaint.color = block.foregroundArgb.toInt()

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

private fun Rect.compactString(): String = "[$left,$top,$right,$bottom]"

private fun Rect.toAndroidRect(): android.graphics.Rect = android.graphics.Rect(left, top, right, bottom)

private fun uniffi.translator.Rect.compactString(): String = "[$left,$top,$right,$bottom]"

private fun uniffi.translator.Rect.toAndroidRect(): android.graphics.Rect =
  android.graphics.Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

private fun uniffi.translator.Rect.width(): Float = (right - left).toFloat()

private fun uniffi.translator.Rect.height(): Float = (bottom - top).toFloat()

private fun debugRepaintBlock(
  blockIndex: Int,
  block: PreparedTextBlock,
) {
  val blockBounds = block.boundingBox
  val sourceText = block.lines.joinToString(separator = " | ") { it.text }
  Log.d(
    REPAINT_DEBUG_TAG,
    "block[$blockIndex] bounds=${blockBounds.compactString()} lines=${block.lines.size} src=\"$sourceText\" translated=\"${block.translatedText}\"",
  )
  block.lines.forEachIndexed { lineIndex, line ->
    val wordRects = line.wordRects.joinToString(separator = ",") { it.compactString() }
    Log.d(
      REPAINT_DEBUG_TAG,
      "block[$blockIndex] line[$lineIndex] bounds=${line.boundingBox.compactString()} words=${line.wordRects.size} text=\"${line.text}\" wordRects=[$wordRects]",
    )
  }
}
