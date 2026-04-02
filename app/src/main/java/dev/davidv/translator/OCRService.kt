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
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.pathString
import kotlin.math.min
import kotlin.system.measureTimeMillis

data class Rect(
  var left: Int,
  var top: Int,
  var right: Int,
  var bottom: Int,
) {
  constructor(other: Rect) : this(other.left, other.top, other.right, other.bottom)

  fun width(): Int = right - left

  fun height(): Int = bottom - top

  fun centerY(): Int = (top + bottom) / 2

  fun isEmpty(): Boolean = left >= right || top >= bottom

  fun union(other: Rect) {
    if (isEmpty()) {
      left = other.left
      top = other.top
      right = other.right
      bottom = other.bottom
    } else {
      if (other.left < left) left = other.left
      if (other.top < top) top = other.top
      if (other.right > right) right = other.right
      if (other.bottom > bottom) bottom = other.bottom
    }
  }
}

data class TextLine(
  var text: String,
  var boundingBox: Rect,
  var wordRects: Array<Rect>,
)

data class TextBlock(
  val lines: Array<TextLine>,
)

data class WordInfo(
  val text: String,
  val confidence: Float,
  val boundingBox: Rect,
  // when concatenating words (undoing line-breaks)
  // the boundingBox must remain within the original boundary
  // however, the actual size of the word will be bigger
  val ghostBBox: Rect? = null,
  val isFirstInLine: Boolean,
  var isLastInLine: Boolean,
  var isLastInPara: Boolean,
)

fun mergeHyphenatedWords(words: List<WordInfo>): List<WordInfo> {
  if (words.isEmpty()) return words

  val result = mutableListOf<WordInfo>()
  var i = 0

  while (i < words.size) {
    val currentWord = words[i]

    // can't merge with next if we are at the last word
    if (i == words.size - 1) {
      result.add(currentWord)
      break
    }

    if (!currentWord.isLastInLine || !currentWord.text.endsWith("-")) {
      result.add(currentWord)
      i++
      continue
    }

    val nextWord = words[i + 1]
    // sometimes tesseract does not detect firstInLine properly
    // we only hack around it if the previous word as hyphenated, as it's unlikely
    // to cause much damage in normal usage.
    val poorMansFirstInLine =
      nextWord.boundingBox.left < currentWord.boundingBox.left && nextWord.boundingBox.top > currentWord.boundingBox.top
    if (!nextWord.isFirstInLine && !poorMansFirstInLine) {
      result.add(currentWord)
      i++
      continue
    }

    val mergedText = currentWord.text.dropLast(1) + nextWord.text
    val ghostBBox = Rect(currentWord.boundingBox)
    ghostBBox.right += nextWord.boundingBox.width()
    val mergedWord =
      WordInfo(
        text = mergedText,
        confidence = minOf(currentWord.confidence, nextWord.confidence),
        // we only take as much space
        // as available until the end of the next line.
        // the NEXT word, should start where the second half of the word
        // was. Text will be reflown in the block, but lines must
        // continue to span their entire width
        boundingBox = currentWord.boundingBox,
        isFirstInLine = currentWord.isFirstInLine,
        isLastInLine = true,
        isLastInPara = nextWord.isLastInPara,
        ghostBBox = ghostBBox,
      )
    result.add(mergedWord)

    if (i + 2 >= words.size) {
      i += 2
      continue
    }

    // Expand the next word to take over the space from the second part of the hyphenated word
    val nextWordAfterMerged = words[i + 2]
    val newRect = Rect(nextWord.boundingBox)
    newRect.union(nextWordAfterMerged.boundingBox)
    val expandedWord =
      nextWordAfterMerged.copy(
        boundingBox = newRect,
        isFirstInLine = true,
      )
    result.add(expandedWord)
    i += 3
  }

  return result
}

fun getSentences(
  bitmap: Bitmap,
  tessInstance: TesseractOCR,
  minConfidence: Int = 75,
): Array<TextBlock> {
  tessInstance.setPageSegmentationMode(PageSegMode.PSM_AUTO_OSD)

  val detectedWords = tessInstance.processImage(bitmap)
  if (detectedWords == null) {
    Log.e("OCRService", "Failed to process image with Tesseract")
    return emptyArray()
  }

  val allWords =
    detectedWords
      .map { detectedWord ->
        WordInfo(
          text = detectedWord.text,
          confidence = detectedWord.confidence,
          boundingBox = Rect(detectedWord.left, detectedWord.top, detectedWord.right, detectedWord.bottom),
          isFirstInLine = detectedWord.isAtBeginningOfPara,
          isLastInLine = detectedWord.endLine,
          isLastInPara = detectedWord.endPara,
        )
      }.toMutableList()

  var filteredWords = mutableListOf<WordInfo>()
  var pendingFirstInLine = false

  for (i in allWords.indices) {
    val wordInfo = allWords[i]

    val shouldInclude =
      wordInfo.confidence >= minConfidence &&
        !(wordInfo.text.length == 1 && wordInfo.confidence < min(100, minConfidence + 5))

    if (shouldInclude) {
      val adjustedWordInfo =
        wordInfo.copy(
          // If we skipped over the previous word (due to low confidence)
          // then we drag over the 'first' marker
          isFirstInLine = wordInfo.isFirstInLine || pendingFirstInLine,
          isLastInLine = wordInfo.isLastInLine,
          isLastInPara = wordInfo.isLastInPara,
        )
      filteredWords.add(adjustedWordInfo)
      pendingFirstInLine = false
    } else {
      if (wordInfo.isFirstInLine) {
        pendingFirstInLine = true
      }
      // if we are skipping the last word; add the 'last' marker to the previous (kept) word
      if (wordInfo.isLastInLine && i > 0) {
        allWords[i - 1].isLastInLine = true
      }
      if (wordInfo.isLastInPara && i > 0) {
        allWords[i - 1].isLastInPara = true
      }
    }
  }

  // FIXME: this is technically wrong -- if we skip a word (pendingFirstInLine)
  // we shouldn't merge hyphenations
  filteredWords = mergeHyphenatedWords(filteredWords).toMutableList()

  val blocks = mutableListOf<TextBlock>()
  val lines = mutableListOf<TextLine>()
  var line = TextLine("", Rect(0, 0, 0, 0), emptyArray())
  var lastRight = 0

  for (wordInfo in filteredWords) {
    val word = wordInfo.text
    if (word.trim() == "") {
      continue
    }
    val boundingBox = wordInfo.boundingBox
    val realBBox = wordInfo.ghostBBox ?: wordInfo.boundingBox
    val skippedFirstWord = boundingBox.right < line.boundingBox.left
    val firstWordInLine = wordInfo.isFirstInLine || skippedFirstWord
    val lastWordInLine = wordInfo.isLastInLine
    val lastWordInPara = wordInfo.isLastInPara

    if (firstWordInLine) {
      line = TextLine(word, Rect(boundingBox), arrayOf(boundingBox))
    } else {
      val delta = boundingBox.left - lastRight
      val charWidth = realBBox.width().toFloat() / word.length
      val deltaInChars: Float = if (charWidth > 0) delta.toFloat() / charWidth else 0f

      // In the same line but too far apart, make a new block
      if (deltaInChars >= 3f) { // TODO: how to figure out the delta better
        Log.d("OCRService", "Forcing new block with word $word")
        if (line.text.trim() != "") {
          lines.add(line)
        }
        line = TextLine(word, Rect(boundingBox), arrayOf(boundingBox))
        if (lines.isNotEmpty()) {
          blocks.add(TextBlock(lines.toTypedArray()))
          lines.clear()
        }
      } else {
        line.text = "${line.text} $word".trim()
        line.wordRects += boundingBox
        if (boundingBox.right < line.boundingBox.left) {
          Log.e("OCRService", "going to break $boundingBox ${line.boundingBox}")
        }
        line.boundingBox.union(boundingBox)
        if (line.boundingBox.width() < 0) {
          Log.e("OCRService", "Found a line with bbw < 0: ${line.boundingBox}")
        }
      }
    }

    if (lastWordInLine && line.text.trim() != "") {
      lines.add(line)
      line = TextLine("", Rect(0, 0, 0, 0), emptyArray())
    }

    if (lastWordInPara && lines.isNotEmpty()) {
      val block = TextBlock(lines.toTypedArray())
      blocks.add(block)
      lines.clear()
    }
    lastRight = boundingBox.right
  }

  return blocks.toTypedArray()
}

class OCRService(
  private val filePathManager: FilePathManager,
) {
  private var tess: TesseractOCR? = null
  private var initializedToLang: Language? = null

  private suspend fun initialize(lang: Language): Boolean =
    withContext(Dispatchers.IO) {
      if (initializedToLang == lang) return@withContext true

      try {
        tess?.close()
        tess = null
        initializedToLang = null

        val p = filePathManager.getTesseractDir().toPath()
        val tessdata = Path(p.pathString, "tessdata")
        val dataPath: String = p.absolutePathString()
        tessdata.createDirectories()

        val langs = "${lang.tessName}+eng"
        Log.i("OCRService", "Initializing tesseract to path $dataPath, languages $langs")

        tess = TesseractOCR(tessdata.absolutePathString(), langs)
        val initialized = tess?.initialize() ?: false
        if (!initialized) {
          tess?.close()
          tess = null
          Log.e(
            "OCRService",
            "Failed to initialize Tesseract with languages: $langs",
          )
          return@withContext false
        }

        initializedToLang = lang
        Log.i(
          "OCRService",
          "Tesseract initialized successfully with languages: $langs",
        )
        true
      } catch (e: Exception) {
        Log.e("OCRService", "Error initializing Tesseract", e)
        false
      }
    }

  suspend fun extractText(
    bitmap: Bitmap,
    fromLang: Language,
    minConfidence: Int = 75,
  ): Array<TextBlock> =
    withContext(Dispatchers.IO) {
      if (initializedToLang != fromLang) {
        val initSuccess = initialize(fromLang)
        if (!initSuccess) return@withContext emptyArray()
      }

      val tessInstance = tess ?: return@withContext emptyArray()

      val blocks: Array<TextBlock>
      val elapsed =
        measureTimeMillis {
          blocks = getSentences(bitmap, tessInstance, minConfidence)
        }
      Log.i("OCRService", "OCR took ${elapsed}ms")
      blocks
    }

  fun cleanup() {
    tess?.close()
    tess = null
    initializedToLang = null
    Log.i("OCRService", "OCR service cleaned up")
  }
}
