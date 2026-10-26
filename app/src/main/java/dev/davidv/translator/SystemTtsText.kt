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

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import kotlin.math.roundToInt

sealed interface SystemTtsSegment {
  data class Text(
    val text: String,
  ) : SystemTtsSegment

  data class Pause(
    val durationMs: Int,
  ) : SystemTtsSegment
}

sealed interface SystemTtsText {
  data class Segments(
    val segments: List<SystemTtsSegment>,
  ) : SystemTtsText

  data class MalformedSsml(
    val reason: String,
  ) : SystemTtsText
}

fun parseSystemTtsText(text: String): SystemTtsText {
  val verbatim = SystemTtsText.Segments(listOf(SystemTtsSegment.Text(text)))
  if (!text.trimStart().startsWith("<")) {
    return verbatim
  }

  val handler = SsmlHandler()
  try {
    val reader =
      SAXParserFactory
        .newInstance()
        .apply { isNamespaceAware = true }
        .newSAXParser()
        .xmlReader
    reader.contentHandler = handler
    // The synthesized audio goes back to the requesting app, so resolving an external entity would let it read our files.
    reader.entityResolver = EntityResolver { _, _ -> throw SAXException("external entities are not allowed in SSML") }
    reader.parse(InputSource(StringReader(text)))
  } catch (e: SAXException) {
    // Only a <speak> root commits the text to being SSML; before that it is plain text that happens to start with '<'.
    if (!handler.rootIsSpeak) {
      return verbatim
    }
    return SystemTtsText.MalformedSsml(e.message ?: e.toString())
  }
  return SystemTtsText.Segments(handler.segments())
}

private class NotSsmlException : SAXException("root element is not <speak>")

private class SsmlHandler : DefaultHandler() {
  var rootIsSpeak = false
    private set
  private val segments = mutableListOf<SystemTtsSegment>()
  private val text = StringBuilder()
  private var depth = 0

  // Set while inside an element whose children must not be spoken: <sub> speaks its alias, <desc> describes audio.
  private var silencedAtDepth: Int? = null

  override fun startElement(
    uri: String,
    localName: String,
    qName: String,
    attributes: Attributes,
  ) {
    depth++
    if (silencedAtDepth != null) return
    if (depth == 1) {
      if (localName != "speak") throw NotSsmlException()
      rootIsSpeak = true
    }
    when (localName) {
      "sub" -> {
        text.append(attributes.getValue("alias") ?: throw SAXException("<sub> without alias"))
        silencedAtDepth = depth
      }
      "desc" -> silencedAtDepth = depth
      "break" -> {
        flushText()
        segments.add(SystemTtsSegment.Pause(breakDurationMs(attributes)))
      }
    }
  }

  override fun endElement(
    uri: String,
    localName: String,
    qName: String,
  ) {
    if (silencedAtDepth == depth) {
      silencedAtDepth = null
    } else if (silencedAtDepth == null && (localName == "p" || localName == "s") && !text.endsWith("\n\n")) {
      // A blank line is a paragraph boundary to the speech chunk planner.
      text.append("\n\n")
    }
    depth--
  }

  override fun characters(
    ch: CharArray,
    start: Int,
    length: Int,
  ) {
    if (silencedAtDepth != null) return
    text.appendRange(ch, start, start + length)
  }

  fun segments(): List<SystemTtsSegment> {
    flushText()
    return segments.toList()
  }

  private fun flushText() {
    if (text.isNotBlank()) {
      segments.add(SystemTtsSegment.Text(text.toString()))
    }
    text.clear()
  }
}

private fun breakDurationMs(attributes: Attributes): Int {
  val time = attributes.getValue("time")
  if (time != null) {
    return parseSsmlTimeMs(time)
  }
  return when (val strength = attributes.getValue("strength") ?: "medium") {
    "none" -> 0
    "x-weak" -> 100
    "weak" -> 250
    "medium" -> 400
    "strong" -> 700
    "x-strong" -> 1000
    else -> throw SAXException("unknown <break> strength \"$strength\"")
  }
}

private fun parseSsmlTimeMs(value: String): Int {
  val trimmed = value.trim()
  val ms =
    when {
      trimmed.endsWith("ms") -> trimmed.removeSuffix("ms").toDoubleOrNull()
      trimmed.endsWith("s") -> trimmed.removeSuffix("s").toDoubleOrNull()?.times(1000)
      else -> null
    }
  if (ms == null || !ms.isFinite() || ms < 0) {
    throw SAXException("invalid <break> time \"$value\"")
  }
  return ms.roundToInt()
}
