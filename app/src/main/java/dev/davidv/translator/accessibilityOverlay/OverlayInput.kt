package dev.davidv.translator.accessibilityOverlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.davidv.translator.OverlayColors
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.getOverlayColors
import dev.davidv.translator.Rect as TranslatorRect

data class TapTextBlock(
  val text: String,
  val bounds: Rect,
)

private data class TextFragment(
  val text: String,
  val bounds: Rect,
)

class OverlayInput(
  private val service: TranslatorAccessibilityService,
  private val windowManager: WindowManager,
  private val ui: OverlayUI,
  private val settingsManager: SettingsManager,
) {
  private var touchInterceptOverlay: View? = null
  private var selectionRectView: View? = null
  private var selectionRectDrawView: SelectionRectView? = null

  fun showInteractionOverlay() {
    if (touchInterceptOverlay != null) return

    val overlay = View(service)
    overlay.setBackgroundColor(Color.TRANSPARENT)

    val toolbarHeight = ui.dpToPx(48)
    val navBarHeight = ui.getNavBarHeight()
    val screenHeight = service.resources.displayMetrics.heightPixels
    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        screenHeight - toolbarHeight - navBarHeight,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.START
    params.y = toolbarHeight

    var startX = 0
    var startY = 0
    var dragging = false
    var hadOverlayOnDown = false
    val canTakeScreenshot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    // performClick() would fire a TYPE_VIEW_CLICKED event that onAccessibilityEvent
    // catches, immediately removing the translation overlay we just created
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    overlay.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          startX = event.rawX.toInt()
          startY = event.rawY.toInt()
          dragging = false
          hadOverlayOnDown = ui.hasTranslationOverlays()
          if (hadOverlayOnDown) ui.removeTranslationOverlays()
          true
        }
        MotionEvent.ACTION_MOVE -> {
          if (canTakeScreenshot) {
            val dx = Math.abs(event.rawX.toInt() - startX)
            val dy = Math.abs(event.rawY.toInt() - startY)
            if (dx > ui.dpToPx(10) || dy > ui.dpToPx(10)) {
              dragging = true
              ui.removeTranslationOverlays()
              updateSelectionRect(startX, startY, event.rawX.toInt(), event.rawY.toInt())
            }
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          if (dragging) {
            val endX = event.rawX.toInt()
            val endY = event.rawY.toInt()
            removeSelectionRect()
            val region =
              Rect(
                minOf(startX, endX),
                minOf(startY, endY),
                maxOf(startX, endX),
                maxOf(startY, endY),
              )
            if (region.width() > ui.dpToPx(20) && region.height() > ui.dpToPx(20)) {
              service.handleRegionCapture(region)
            }
          } else if (hadOverlayOnDown || ui.hasTranslationOverlays()) {
            ui.removeTranslationOverlays()
          } else {
            service.handleTouchAtPoint(startX, startY)
          }
          true
        }
        else -> false
      }
    }

    windowManager.addView(overlay, params)
    touchInterceptOverlay = overlay
  }

  fun removeTouchInterceptOverlay() {
    touchInterceptOverlay?.let {
      windowManager.removeView(it)
      touchInterceptOverlay = null
    }
  }

  private fun ensureSelectionRectOverlay() {
    if (selectionRectView != null) return
    val rectView = SelectionRectView(service)
    val dm = service.resources.displayMetrics
    val params =
      WindowManager.LayoutParams(
        dm.widthPixels,
        dm.heightPixels + ui.getStatusBarHeight() + ui.getNavBarHeight(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.START
    windowManager.addView(rectView, params)
    selectionRectView = rectView
    selectionRectDrawView = rectView
  }

  private fun updateSelectionRect(
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
  ) {
    ensureSelectionRectOverlay()
    selectionRectDrawView?.setRect(
      minOf(x1, x2).toFloat(),
      minOf(y1, y2).toFloat(),
      maxOf(x1, x2).toFloat(),
      maxOf(y1, y2).toFloat(),
    )
  }

  fun removeSelectionRect() {
    selectionRectView?.let {
      windowManager.removeView(it)
      selectionRectView = null
      selectionRectDrawView = null
    }
  }

  fun findNodeAtPoint(
    root: AccessibilityNodeInfo?,
    x: Int,
    y: Int,
  ): AccessibilityNodeInfo? {
    val node = findDeepestNodeAtPoint(root, x, y) ?: return null
    var current: AccessibilityNodeInfo? = node
    while (current != null) {
      val text = current.text
      if (text != null && text.isNotBlank()) {
        val bounds = Rect()
        current.getBoundsInScreen(bounds)
        val screenWidth = service.resources.displayMetrics.widthPixels
        val screenHeight = service.resources.displayMetrics.heightPixels
        val boundsArea = bounds.width().toLong() * bounds.height().toLong()
        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        if (boundsArea <= screenArea / 2) return current
      }
      current = current.parent
    }
    return null
  }

  private fun findDeepestNodeAtPoint(
    root: AccessibilityNodeInfo?,
    x: Int,
    y: Int,
  ): AccessibilityNodeInfo? {
    if (root == null) return null
    val bounds = Rect()
    root.getBoundsInScreen(bounds)
    if (!bounds.contains(x, y)) return null
    for (i in root.childCount - 1 downTo 0) {
      val child = root.getChild(i) ?: continue
      val found = findDeepestNodeAtPoint(child, x, y)
      if (found != null) return found
    }
    return root
  }

  fun extractTextBlockAtPoint(
    root: AccessibilityNodeInfo?,
    x: Int,
    y: Int,
  ): TapTextBlock? {
    val node = findDeepestNodeAtPoint(root, x, y) ?: return null

    var current: AccessibilityNodeInfo? = node
    var bestFragments = emptyList<TextFragment>()
    while (current != null) {
      bestFragments = collectTextFragments(current)
      if (bestFragments.isNotEmpty()) break
      current = current.parent
    }
    if (current == null || bestFragments.isEmpty()) return null

    val tapMargin = ui.dpToPx(8)
    val tapNearFragment =
      bestFragments.any { fragment ->
        val expanded = Rect(fragment.bounds)
        expanded.inset(-tapMargin, -tapMargin)
        expanded.contains(x, y)
      }
    if (!tapNearFragment) return null

    val anchor =
      bestFragments.minBy { fragment ->
        val dx = maxOf(0, maxOf(fragment.bounds.left - x, x - fragment.bounds.right))
        val dy = maxOf(0, maxOf(fragment.bounds.top - y, y - fragment.bounds.bottom))
        dx * dx + dy * dy
      }

    val deepestBounds = Rect()
    node.getBoundsInScreen(deepestBounds)
    val initialBlock = buildTextBlock(bestFragments)
    if (current !== node && initialBlock != null && isOversizedTapFallback(deepestBounds, initialBlock.bounds, bestFragments.size)) {
      return null
    }

    var ancestor = current.parent
    while (ancestor != null) {
      val ancestorFragments = collectTextFragments(ancestor)
      val consecutive = ancestorFragments.isNotEmpty() && fragmentsFormConsecutiveFlow(ancestorFragments)
      if (!consecutive) break
      bestFragments = ancestorFragments
      ancestor = ancestor.parent
    }

    val hMargin = ui.dpToPx(4)
    bestFragments =
      bestFragments.filter { fragment ->
        fragment.bounds.right > anchor.bounds.left - hMargin &&
          fragment.bounds.left < anchor.bounds.right + hMargin
      }

    return buildTextBlock(bestFragments)
  }

  fun collectVisibleTextBlocks(root: AccessibilityNodeInfo): List<TapTextBlock> {
    val fragments = collectTextFragments(root)
    if (fragments.isEmpty()) return emptyList()

    val blocks = mutableListOf<TapTextBlock>()
    var currentFragments = mutableListOf<TextFragment>()
    var currentBounds: Rect? = null

    for (fragment in fragments) {
      val groupBounds = currentBounds
      if (groupBounds == null) {
        currentFragments.add(fragment)
        currentBounds = Rect(fragment.bounds)
        continue
      }

      if (hasVerticalOverlap(groupBounds, fragment.bounds)) {
        currentFragments.add(fragment)
        groupBounds.union(fragment.bounds)
      } else {
        buildTextBlock(currentFragments)?.let { blocks.add(it) }
        currentFragments = mutableListOf(fragment)
        currentBounds = Rect(fragment.bounds)
      }
    }

    if (currentFragments.isNotEmpty()) {
      buildTextBlock(currentFragments)?.let { blocks.add(it) }
    }

    return blocks
  }

  fun sampleColorsFromScreenshot(
    bitmap: Bitmap,
    bounds: Rect,
  ): OverlayColors {
    val bgMode = settingsManager.settings.value.backgroundMode
    val translatorBounds = TranslatorRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    return getOverlayColors(bitmap, translatorBounds, bgMode)
  }

  private fun collectTextFragments(node: AccessibilityNodeInfo): List<TextFragment> {
    val screenWidth = service.resources.displayMetrics.widthPixels
    val screenHeight = service.resources.displayMetrics.heightPixels
    val screenArea = screenWidth.toLong() * screenHeight.toLong()
    val results = mutableListOf<TextFragment>()
    collectTextFragmentsRecursive(node, screenWidth, screenHeight, screenArea, results)
    return results
  }

  private fun collectTextFragmentsRecursive(
    node: AccessibilityNodeInfo,
    screenWidth: Int,
    screenHeight: Int,
    screenArea: Long,
    results: MutableList<TextFragment>,
  ): Boolean {
    if (!node.isVisibleToUser) return false

    val childStartIndex = results.size
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      collectTextFragmentsRecursive(child, screenWidth, screenHeight, screenArea, results)
    }

    val text = node.text?.toString()?.trim()
    if (text.isNullOrEmpty()) return results.size > childStartIndex

    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    if (bounds.right <= 0 || bounds.bottom <= 0 || bounds.left >= screenWidth || bounds.top >= screenHeight) return results.size > childStartIndex
    if (bounds.width() <= 0 || bounds.height() <= 0) return results.size > childStartIndex

    val boundsArea = bounds.width().toLong() * bounds.height().toLong()
    if (boundsArea > screenArea / 2) return results.size > childStartIndex

    if (results.size > childStartIndex) {
      val childText =
        results
          .subList(childStartIndex, results.size)
          .joinToString(" ") { it.text }
      if (areEquivalentTexts(text, childText)) return true
      results.subList(childStartIndex, results.size).clear()
    }

//    Log.d(
//      "A11yTree",
//      "FRAGMENT: '${text.take(40)}' bounds=${bounds.toShortString()} ${bounds.width()}x${bounds.height()} clickable=${node.isClickable} focusable=${node.isFocusable} class=${node.className} id=${node.viewIdResourceName} actions=${node.actionList.map { it.id }} parent=${node.parent?.className}",
//    )
    results.add(TextFragment(text, Rect(bounds)))
    return true
  }

  private fun fragmentsFormConsecutiveFlow(fragments: List<TextFragment>): Boolean {
    if (fragments.size <= 1) return true

    val lines = clusterFragmentsIntoLines(fragments)
    if (lines.size > 6) return false

    val gapThreshold = maxOf(ui.dpToPx(6), (medianHeight(fragments) * 0.5f).toInt())
    for (i in 1 until lines.size) {
      val gap = lines[i].first.top - lines[i - 1].second.bottom
      if (gap > gapThreshold) return false
    }

    val totalBounds = Rect(lines.first().first)
    for ((_, lineBounds) in lines.drop(1)) {
      totalBounds.union(lineBounds)
    }
    val maxHeight = minOf(service.resources.displayMetrics.heightPixels / 3, medianHeight(fragments) * 8)
    if (totalBounds.height() > maxHeight) return false

    return true
  }

  private fun buildTextBlock(fragments: List<TextFragment>): TapTextBlock? {
    if (fragments.isEmpty()) return null

    val lines = clusterFragmentsIntoLineFragments(fragments)

    val text =
      lines
        .joinToString("\n") { line ->
          line
            .fold(StringBuilder()) { sb, fragment ->
              if (sb.isNotEmpty() && shouldInsertSpace(sb.last(), fragment.text.first())) {
                sb.append(' ')
              }
              sb.append(fragment.text)
              sb
            }.toString()
        }.trim()
    if (text.isBlank()) return null

    val unionBounds = Rect(fragments.first().bounds)
    for (fragment in fragments.drop(1)) {
      unionBounds.union(fragment.bounds)
    }

    return TapTextBlock(text, unionBounds)
  }

  private fun medianHeight(fragments: List<TextFragment>): Int {
    val heights = fragments.map { it.bounds.height() }.sorted()
    return heights[heights.size / 2].coerceAtLeast(1)
  }

  private fun isOversizedTapFallback(
    deepestBounds: Rect,
    candidateBounds: Rect,
    fragmentCount: Int,
  ): Boolean {
    val screenHeight = service.resources.displayMetrics.heightPixels
    if (candidateBounds.height() > screenHeight / 3) return true
    if (fragmentCount > 8) return true
    if (deepestBounds.width() > 0 && candidateBounds.width() > deepestBounds.width() * 2) return true
    if (deepestBounds.height() > 0 && candidateBounds.height() > deepestBounds.height() * 2) return true
    return false
  }

  private fun clusterFragmentsIntoLines(fragments: List<TextFragment>): List<Pair<Rect, Rect>> =
    clusterFragmentsIntoLineFragments(fragments).map { line ->
      val lineBounds = Rect(line.first().bounds)
      for (fragment in line.drop(1)) {
        lineBounds.union(fragment.bounds)
      }
      line.first().bounds to lineBounds
    }

  private fun clusterFragmentsIntoLineFragments(fragments: List<TextFragment>): List<List<TextFragment>> {
    if (fragments.isEmpty()) return emptyList()

    val lineThreshold = maxOf(ui.dpToPx(6), (medianHeight(fragments) * 0.35f).toInt())
    val lines = mutableListOf<MutableList<TextFragment>>()
    var currentLine = mutableListOf<TextFragment>()
    var currentTop = 0
    var currentBottom = 0

    for (fragment in fragments) {
      if (currentLine.isEmpty()) {
        currentLine.add(fragment)
        currentTop = fragment.bounds.top
        currentBottom = fragment.bounds.bottom
        continue
      }

      val centerDelta = kotlin.math.abs(fragment.bounds.centerY() - (currentTop + currentBottom) / 2)
      val verticalOverlap = minOf(currentBottom, fragment.bounds.bottom) - maxOf(currentTop, fragment.bounds.top)
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
    lines.add(currentLine)

    return lines
  }

  private fun shouldInsertSpace(
    previous: Char,
    next: Char,
  ): Boolean {
    if (previous.isWhitespace() || next.isWhitespace()) return false
    if (next in charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '%')) return false
    if (previous in charArrayOf('(', '[', '{', '/', '-', '\n')) return false
    return true
  }

  private fun areEquivalentTexts(
    first: String,
    second: String,
  ): Boolean = normalizeTextForComparison(first) == normalizeTextForComparison(second)

  private fun normalizeTextForComparison(text: String): String =
    text
      .replace(Regex("\\s+"), " ")
      .replace(Regex("\\s+([.,;:!?])"), "$1")
      .trim()
      .lowercase()

  private fun hasVerticalOverlap(
    first: Rect,
    second: Rect,
  ): Boolean = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top) >= -ui.dpToPx(4)

  private class SelectionRectView(
    context: android.content.Context,
  ) : View(context) {
    private val fillPaint =
      android.graphics.Paint().apply {
        color = Color.parseColor("#220088FF")
        style = android.graphics.Paint.Style.FILL
      }
    private val strokePaint =
      android.graphics.Paint().apply {
        color = Color.parseColor("#4488FF")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
      }
    private val rect = android.graphics.RectF()
    private val cornerRadius = 8f

    fun setRect(
      left: Float,
      top: Float,
      right: Float,
      bottom: Float,
    ) {
      rect.set(left, top, right, bottom)
      invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
      if (!rect.isEmpty) {
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
      }
    }
  }
}
