package dev.davidv.translator.assistantOverlay

import android.app.assist.AssistStructure
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import dev.davidv.translator.StyledFragment
import dev.davidv.translator.TextStyle
import dev.davidv.translator.Rect as TranslatorRect

class AssistStructureParser(
  private val displayDensity: Float = 1f,
) {
  fun parse(structure: AssistStructure): List<StyledFragment> {
    val fragments = mutableListOf<InternalFragment>()
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

    val deduped = fragments.distinctBy { "${it.bounds.flattenToString()}|${normalizeText(it.text)}" }
    val distinct = deduped.groupBy { it.bounds.flattenToString() }.values.map { it.first() }
    var currentTransGroup = 0
    var lastBgColor: Int? = null
    return distinct.map {
      val bgColor = it.style?.bgColor
      if (bgColor != lastBgColor) {
        currentTransGroup++
        lastBgColor = bgColor
      }
      StyledFragment(
        it.text,
        TranslatorRect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom),
        it.style,
        layoutGroup = if (!it.fromWebView) 1 else 0,
        translationGroup = currentTransGroup,
      )
    }
  }

  private fun collectFragmentsRecursive(
    node: AssistStructure.ViewNode,
    baseLeft: Int,
    baseTop: Int,
    viewport: Rect,
    insideWebView: Boolean,
    inheritedBg: Int?,
    results: MutableList<InternalFragment>,
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

      if (shouldPreferChildren(node, bounds, viewport, childFragments, normalizedParent)) {
        return true
      }
    }

    val rawSize = node.textSize.takeIf { it > 0f }
    val textSize = if (nestedInWebView && rawSize != null) rawSize * displayDensity else rawSize
    val styleBits = node.textStyle

    results +=
      InternalFragment(
        text = text,
        bounds = bounds,
        style =
          TextStyle(
            textColor = node.textColor,
            bgColor = effectiveBg,
            textSize = textSize,
            bold = styleBits and AssistStructure.ViewNode.TEXT_STYLE_BOLD != 0,
            italic = styleBits and AssistStructure.ViewNode.TEXT_STYLE_ITALIC != 0,
            underline = styleBits and AssistStructure.ViewNode.TEXT_STYLE_UNDERLINE != 0,
            strikethrough = styleBits and AssistStructure.ViewNode.TEXT_STYLE_STRIKE_THRU != 0,
          ),
        fromWebView = nestedInWebView,
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

  fun normalizeText(text: String): String =
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
    childFragments: List<InternalFragment>,
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

  private data class InternalFragment(
    val text: String,
    val bounds: Rect,
    val style: TextStyle?,
    val fromWebView: Boolean,
  )
}
