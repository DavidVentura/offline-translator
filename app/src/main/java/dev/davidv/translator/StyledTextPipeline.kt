package dev.davidv.translator

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
  val group: Int = 0,
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

  val lineHeight = lowerQuartileHeight(fragments)
  val blockGapThreshold = (lineHeight * 0.5f).toInt()

  val blockGroups = mutableListOf<MutableList<StyledFragment>>()
  val blockBounds = mutableListOf<Rect>()
  val blockGroupIds = mutableListOf<Int>()

  for (fragment in fragments) {
    var merged = false
    for (i in blockGroups.indices) {
      if (blockGroupIds[i] != fragment.group) continue
      val bb = blockBounds[i]
      val vOverlap = minOf(bb.bottom, fragment.bounds.bottom) - maxOf(bb.top, fragment.bounds.top)
      val vGap = fragment.bounds.top - bb.bottom
      val hOverlap = minOf(bb.right, fragment.bounds.right) - maxOf(bb.left, fragment.bounds.left)

      if (vOverlap > 0 || (vGap in 0..blockGapThreshold && hOverlap > 0)) {
        blockGroups[i].add(fragment)
        bb.union(fragment.bounds)
        merged = true
        break
      }
    }
    if (!merged) {
      blockGroups.add(mutableListOf(fragment))
      blockBounds.add(Rect(fragment.bounds))
      blockGroupIds.add(fragment.group)
    }
  }

  return blockGroups.mapIndexed { idx, group -> buildBlock(group, blockBounds[idx]) }
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
    for ((fragIdx, fragment) in line.withIndex()) {
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

  val medHeight = medianFragmentHeight(fragments)
  val lineThreshold = (medHeight * 0.35f).toInt().coerceAtLeast(1)

  val lines = mutableListOf<MutableList<StyledFragment>>()
  val lineTops = mutableListOf<Int>()
  val lineBottoms = mutableListOf<Int>()

  for (fragment in fragments) {
    var bestLine = -1
    for (i in lines.indices) {
      val centerDelta = kotlin.math.abs(fragment.bounds.centerY() - (lineTops[i] + lineBottoms[i]) / 2)
      val verticalOverlap =
        minOf(lineBottoms[i], fragment.bounds.bottom) - maxOf(lineTops[i], fragment.bounds.top)
      if (verticalOverlap > 0 || centerDelta <= lineThreshold) {
        bestLine = i
        break
      }
    }
    if (bestLine >= 0) {
      lines[bestLine].add(fragment)
      lineTops[bestLine] = minOf(lineTops[bestLine], fragment.bounds.top)
      lineBottoms[bestLine] = maxOf(lineBottoms[bestLine], fragment.bounds.bottom)
    } else {
      lines.add(mutableListOf(fragment))
      lineTops.add(fragment.bounds.top)
      lineBottoms.add(fragment.bounds.bottom)
    }
  }

  val lineOrder = lines.indices.sortedBy { lineTops[it] }
  return lineOrder.map { lines[it] }
}

private fun medianFragmentHeight(fragments: List<StyledFragment>): Int {
  val heights = fragments.map { it.bounds.height() }.sorted()
  return heights[heights.size / 2].coerceAtLeast(1)
}

private fun lowerQuartileHeight(fragments: List<StyledFragment>): Int {
  val heights = fragments.map { it.bounds.height() }.sorted()
  return heights[heights.size / 4].coerceAtLeast(1)
}
