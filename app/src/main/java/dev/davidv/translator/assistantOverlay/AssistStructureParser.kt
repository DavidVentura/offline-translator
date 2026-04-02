package dev.davidv.translator.assistantOverlay

import android.app.assist.AssistStructure
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

class AssistStructureParser {
  fun parse(structure: AssistStructure): List<CapturedTextBlock> {
    val fragments = mutableListOf<TextFragment>()
    for (windowIndex in 0 until structure.windowNodeCount) {
      val window = structure.getWindowNodeAt(windowIndex)
      val viewport =
        Rect(
          window.left,
          window.top,
          window.left + window.width,
          window.top + window.height,
        )
      collectFragmentsRecursive(
        node = window.rootViewNode,
        baseLeft = window.left,
        baseTop = window.top,
        viewport = viewport,
        insideWebView = false,
        inheritedBg = null,
        results = fragments,
      )
    }

    val distinct = fragments.distinctBy { "${it.bounds.flattenToString()}|${normalizeText(it.text)}" }
    val blocks = distinct.map { CapturedTextBlock(it.text, Rect(it.bounds), it.style, it.fromWebView) }
    return mergeAdjacentSameStyle(blocks)
  }

  private fun mergeAdjacentSameStyle(blocks: List<CapturedTextBlock>): List<CapturedTextBlock> {
    if (blocks.size < 2) return blocks
    val result = mutableListOf<CapturedTextBlock>()
    var i = 0
    while (i < blocks.size) {
      val current = blocks[i]
      if (!current.fromWebView) {
        result += current
        i++
        continue
      }
      val merged = StringBuilder(current.text)
      val mergedBounds = Rect(current.bounds)
      var j = i + 1
      while (j < blocks.size) {
        val next = blocks[j]
        if (!next.fromWebView || !sameStyle(current.style, next.style)) break
        if (!adjacentBounds(mergedBounds, next.bounds)) break
        if (merged.isNotEmpty() && !merged.endsWith(' ') && !next.text.startsWith(' ')) {
          merged.append(' ')
        }
        merged.append(next.text)
        mergedBounds.union(next.bounds)
        j++
      }
      result += CapturedTextBlock(merged.toString(), mergedBounds, current.style, current.fromWebView)
      i = j
    }
    return result
  }

  private fun sameStyle(
    a: CapturedTextStyle?,
    b: CapturedTextStyle?,
  ): Boolean {
    if (a == null && b == null) return true
    if (a == null || b == null) return false
    return a.textColor == b.textColor &&
      a.textBackgroundColor == b.textBackgroundColor &&
      a.styleBits == b.styleBits &&
      a.textSizePx == b.textSizePx
  }

  private fun adjacentBounds(
    a: Rect,
    b: Rect,
  ): Boolean {
    val verticalOverlap = a.top < b.bottom && b.top < a.bottom
    val horizontalGap =
      if (b.left >= a.right) {
        b.left - a.right
      } else if (a.left >= b.right) {
        a.left - b.right
      } else {
        0
      }
    if (verticalOverlap && horizontalGap < a.height()) return true
    val directlyBelow = b.top >= a.top && b.top <= a.bottom + a.height() / 2
    val horizontalOverlap = a.left < b.right && b.left < a.right
    if (directlyBelow && horizontalOverlap) return true
    return false
  }

  private fun collectFragmentsRecursive(
    node: AssistStructure.ViewNode,
    baseLeft: Int,
    baseTop: Int,
    viewport: Rect,
    insideWebView: Boolean,
    inheritedBg: Int?,
    results: MutableList<TextFragment>,
  ): Boolean {
    if (node.visibility != View.VISIBLE) return false
    if (node.idEntry == "url_bar") return false
    if (node.className == "android.widget.Image") return false
    if (node.className == "android.widget.Button") return false
    if (node.className == "android.widget.SeekBar") return false
    val className = node.className?.toString().orEmpty()
    val nestedInWebView = insideWebView || className == "android.webkit.WebView"

    val nodeBg = node.textBackgroundColor.takeIf { it != 0 && Color.alpha(it) > 0 }
    val effectiveBg = nodeBg ?: inheritedBg

    val isWebView = className == "android.webkit.WebView"
    val nodeLeft = baseLeft + node.left - (if (isWebView) 0 else node.scrollX)
    val nodeTop = baseTop + node.top - (if (isWebView) 0 else node.scrollY)
    val bounds =
      Rect(
        nodeLeft,
        nodeTop,
        nodeLeft + node.width,
        nodeTop + node.height,
      )
    applyTransform(node.transformation, bounds)

    val childStartIndex = results.size
    for (childIndex in 0 until node.childCount) {
      val child = node.getChildAt(childIndex) ?: continue
      collectFragmentsRecursive(child, nodeLeft, nodeTop, viewport, nestedInWebView, effectiveBg, results)
    }

    val text = node.text?.toString()?.trim()
    if (text.isNullOrEmpty()) return results.size > childStartIndex
    if (node.width <= 2 || node.height <= 2) return results.size > childStartIndex
    if (bounds.width() <= 2 || bounds.height() <= 2) return results.size > childStartIndex
    if (!Rect.intersects(bounds, viewport)) return results.size > childStartIndex
    if (isTransparentText(node.textColor)) return results.size > childStartIndex
    if (nestedInWebView && className == "android.view.View" && results.size == childStartIndex) {
      return false
    }

    val childFragments =
      if (results.size > childStartIndex) {
        results.subList(childStartIndex, results.size).toList()
      } else {
        emptyList()
      }

    if (childFragments.isNotEmpty()) {
      val childText = childFragments.joinToString(" ") { it.text }
      val normalizedParent = normalizeText(text)
      val normalizedChildren = normalizeText(childText)
      if (normalizedParent == normalizedChildren) {
        return true
      }

      // WebViews and other rich containers often expose a coarse summary string on the
      // parent and the real visible content on descendants. Prefer the descendants.
      if (shouldPreferChildren(node, bounds, viewport, childFragments, normalizedParent)) {
        return true
      }
    }

    results +=
      TextFragment(
        text = text,
        bounds = bounds,
        fromWebView = nestedInWebView,
        style =
          CapturedTextStyle(
            textSizePx = node.textSize.takeIf { it > 0f },
            textColor = node.textColor,
            textBackgroundColor = effectiveBg,
            styleBits = node.textStyle,
          ),
      )
    return true
  }

  private fun applyTransform(
    matrix: Matrix?,
    bounds: Rect,
  ) {
    if (matrix == null || matrix.isIdentity) return
    val rectF = RectF(bounds)
    matrix.mapRect(rectF)
    bounds.set(
      rectF.left.toInt(),
      rectF.top.toInt(),
      rectF.right.toInt(),
      rectF.bottom.toInt(),
    )
  }

  private fun normalizeText(text: String): String =
    text
      .replace(Regex("\\s+"), " ")
      .replace(Regex("\\s+([.,;:!?])"), "$1")
      .trim()
      .lowercase()

  private fun isTransparentText(textColor: Int): Boolean = textColor == 0 || Color.alpha(textColor) == 0

  private fun shouldPreferChildren(
    node: AssistStructure.ViewNode,
    bounds: Rect,
    viewport: Rect,
    childFragments: List<TextFragment>,
    normalizedParentText: String,
  ): Boolean {
    val className = node.className?.toString().orEmpty()
    if (className == "android.webkit.WebView") return true

    val childUnion = Rect(childFragments.first().bounds)
    for (fragment in childFragments.drop(1)) {
      childUnion.union(fragment.bounds)
    }

    val parentArea = bounds.width().toLong() * bounds.height().toLong()
    val viewportArea = viewport.width().toLong() * viewport.height().toLong()
    val childArea = childUnion.width().toLong() * childUnion.height().toLong()

    val parentLooksLikeContainer =
      childFragments.size >= 2 &&
        parentArea >= viewportArea / 4 &&
        childArea >= parentArea / 4
    if (parentLooksLikeContainer) return true

    val longestChildLength = childFragments.maxOf { normalizeText(it.text).length }
    val parentLooksLikeSummary =
      normalizedParentText.length <= 80 &&
        longestChildLength > normalizedParentText.length * 2
    if (parentLooksLikeSummary) return true

    return false
  }

  private data class TextFragment(
    val text: String,
    val bounds: Rect,
    val fromWebView: Boolean,
    val style: CapturedTextStyle?,
  )
}
