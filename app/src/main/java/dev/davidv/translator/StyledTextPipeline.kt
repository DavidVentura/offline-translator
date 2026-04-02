package dev.davidv.translator

import android.graphics.Rect
import dev.davidv.bergamot.TokenAlignment

data class TextStyle(
  val textColor: Int? = null,
  val bgColor: Int? = null,
  val textSize: Float? = null,
  val bold: Boolean = false,
  val italic: Boolean = false,
  val underline: Boolean = false,
  val strikethrough: Boolean = false,
)

data class StyledFragment(
  val text: String,
  val bounds: Rect,
  val style: TextStyle? = null,
)

data class StyleSpan(
  val start: Int,
  val end: Int,
  val style: TextStyle?,
)

data class TranslatableBlock(
  val text: String,
  val bounds: Rect,
  val styleSpans: List<StyleSpan>,
)

data class TranslatedStyledBlock(
  val text: String,
  val bounds: Rect,
  val styleSpans: List<StyleSpan>,
)

fun clusterFragmentsIntoBlocks(fragments: List<StyledFragment>): List<TranslatableBlock> {
  if (fragments.isEmpty()) return emptyList()

  val sorted = fragments.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
  val medHeight = medianFragmentHeight(sorted)
  val blockGapThreshold = (medHeight * 0.5f).toInt()

  val groups = mutableListOf<MutableList<StyledFragment>>()
  val groupBounds = mutableListOf<Rect>()

  for (fragment in sorted) {
    var merged = false
    for (i in groups.indices.reversed()) {
      val bb = groupBounds[i]
      val vOverlap = minOf(bb.bottom, fragment.bounds.bottom) - maxOf(bb.top, fragment.bounds.top)
      val vGap = fragment.bounds.top - bb.bottom
      val hOverlap = minOf(bb.right, fragment.bounds.right) - maxOf(bb.left, fragment.bounds.left)

      if (vOverlap > 0 || (vGap in 0..blockGapThreshold && hOverlap > 0)) {
        groups[i].add(fragment)
        bb.union(fragment.bounds)
        merged = true
        break
      }
    }
    if (!merged) {
      groups.add(mutableListOf(fragment))
      groupBounds.add(Rect(fragment.bounds))
    }
  }

  return groups.mapIndexed { idx, group -> buildBlock(group, groupBounds[idx]) }
}

fun mapStylesToTranslation(
  sourceBlock: TranslatableBlock,
  alignments: Array<TokenAlignment>,
  targetText: String,
): List<StyleSpan> {
  if (sourceBlock.styleSpans.isEmpty() || alignments.isEmpty()) return emptyList()

  val targetSpans =
    alignments.mapNotNull { alignment ->
      val srcMid = (alignment.srcBegin + alignment.srcEnd) / 2
      val matchingSpan =
        sourceBlock.styleSpans.firstOrNull { srcMid in it.start until it.end }
          ?: return@mapNotNull null
      StyleSpan(alignment.tgtBegin, alignment.tgtEnd, matchingSpan.style)
    }

  if (targetSpans.isEmpty()) return emptyList()
  val sorted = targetSpans.sortedBy { it.start }
  val merged = mutableListOf(sorted.first())
  for (span in sorted.drop(1)) {
    val last = merged.last()
    if (span.style == last.style && span.start <= last.end) {
      merged[merged.lastIndex] = StyleSpan(last.start, maxOf(last.end, span.end), last.style)
    } else {
      merged.add(span)
    }
  }

  return merged
}

private fun buildBlock(
  fragments: List<StyledFragment>,
  bounds: Rect,
): TranslatableBlock {
  val lines = clusterIntoLines(fragments)
  val sb = StringBuilder()
  val spans = mutableListOf<StyleSpan>()

  for ((lineIdx, line) in lines.withIndex()) {
    if (lineIdx > 0) sb.append('\n')
    val sortedLine = line.sortedBy { it.bounds.left }
    for ((fragIdx, fragment) in sortedLine.withIndex()) {
      if (fragIdx > 0 && sb.isNotEmpty() && !sb.last().isWhitespace()) {
        sb.append(' ')
      }
      val start = sb.length
      sb.append(fragment.text)
      if (fragment.style != null) {
        spans.add(StyleSpan(start, sb.length, fragment.style))
      }
    }
  }

  return TranslatableBlock(sb.toString(), bounds, spans)
}

private fun clusterIntoLines(fragments: List<StyledFragment>): List<List<StyledFragment>> {
  if (fragments.isEmpty()) return emptyList()

  val sorted = fragments.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
  val medHeight = medianFragmentHeight(sorted)
  val lineThreshold = (medHeight * 0.35f).toInt().coerceAtLeast(1)

  val lines = mutableListOf<MutableList<StyledFragment>>()
  var currentTop = 0
  var currentBottom = 0
  var currentLine = mutableListOf<StyledFragment>()

  for (fragment in sorted) {
    if (currentLine.isEmpty()) {
      currentLine.add(fragment)
      currentTop = fragment.bounds.top
      currentBottom = fragment.bounds.bottom
      continue
    }

    val centerDelta = kotlin.math.abs(fragment.bounds.centerY() - (currentTop + currentBottom) / 2)
    val verticalOverlap =
      minOf(currentBottom, fragment.bounds.bottom) - maxOf(currentTop, fragment.bounds.top)
    if (verticalOverlap > 0 || centerDelta <= lineThreshold) {
      currentLine.add(fragment)
      currentTop = minOf(currentTop, fragment.bounds.top)
      currentBottom = maxOf(currentBottom, fragment.bounds.bottom)
    } else {
      lines.add(currentLine)
      currentLine = mutableListOf(fragment)
      currentTop = fragment.bounds.top
      currentBottom = fragment.bounds.bottom
    }
  }
  if (currentLine.isNotEmpty()) {
    lines.add(currentLine)
  }

  return lines
}

private fun medianFragmentHeight(fragments: List<StyledFragment>): Int {
  val heights = fragments.map { it.bounds.height() }.sorted()
  return heights[heights.size / 2].coerceAtLeast(1)
}
