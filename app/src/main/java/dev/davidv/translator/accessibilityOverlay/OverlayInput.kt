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
import dev.davidv.translator.StyledFragment
import dev.davidv.translator.areEquivalentFragmentTexts
import dev.davidv.translator.getOverlayColors
import dev.davidv.translator.Rect as TranslatorRect

data class TapTextBlock(
  val text: String,
  val bounds: Rect,
)

private data class TextFragment(
  val text: String,
  val bounds: Rect,
  val recyclerViewItemId: Int = -1,
)

class OverlayInput(
  private val service: TranslatorAccessibilityService,
  private val windowManager: WindowManager,
  private val ui: OverlayUI,
  private val settingsManager: SettingsManager,
) {
  private val isDebuggable = (service.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
  private var touchInterceptOverlay: View? = null
  private var selectionRectView: View? = null
  private var selectionRectDrawView: SelectionRectView? = null
  private var regionSelectionArmed = false

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
            regionSelectionArmed = false
            service.stopManualOcrSelection()
            if (region.width() > ui.dpToPx(20) && region.height() > ui.dpToPx(20)) {
              service.handleRegionCapture(region)
            }
          } else if (regionSelectionArmed) {
            regionSelectionArmed = false
            service.stopManualOcrSelection()
            removeSelectionRect()
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

  fun startRegionSelection() {
    regionSelectionArmed = true
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
    val containsPoint = bounds.contains(x, y)

    val shouldTryChildren = containsPoint || (bounds.height() <= 0 || bounds.bottom < bounds.top)

    if (shouldTryChildren) {
      for (i in root.childCount - 1 downTo 0) {
        val child = root.getChild(i) ?: continue
        val found = findDeepestNodeAtPoint(child, x, y)
        if (found != null) return found
      }
    }
    return if (containsPoint) root else null
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

  fun extractStyledFragmentsAtPoint(
    root: AccessibilityNodeInfo?,
    x: Int,
    y: Int,
  ): List<StyledFragment> {
    val node = findDeepestNodeAtPoint(root, x, y) ?: return emptyList()
    val screenHeight = service.resources.displayMetrics.heightPixels
    val screenWidth = service.resources.displayMetrics.widthPixels
    val screenArea = screenWidth.toLong() * screenHeight.toLong()

    val nodeBounds = Rect()
    node.getBoundsInScreen(nodeBounds)
    android.util.Log.d(
      "A11yTap",
      "Deepest node: class=${node.className} text='${node.text?.take(
        30,
      )}' bounds=${nodeBounds.toShortString()} children=${node.childCount}",
    )

    var webView: AccessibilityNodeInfo? = node
    while (webView != null && webView.className?.toString() != "android.webkit.WebView") {
      webView = webView.parent
    }
    if (webView != null) {
      dumpA11yTree(webView)
    }

    var bestFragments = emptyList<TextFragment>()
    var current: AccessibilityNodeInfo? = node
    while (current != null) {
      val fragments = collectTextFragments(current)
      val bounds = Rect()
      current.getBoundsInScreen(bounds)
      val area = bounds.width().toLong() * bounds.height().toLong()
      android.util.Log.d(
        "A11yTap",
        "Walk: class=${current.className} fragments=${fragments.size} bounds=${bounds.toShortString()} area=$area",
      )

      if (fragments.isEmpty()) {
        current = current.parent
        continue
      }

      if (area > screenArea / 2) {
        android.util.Log.d("A11yTap", "Large node with ${fragments.size} fragments, trying children")
        var childFragments = fragments
        if (childFragments.isEmpty()) {
          for (i in 0 until current.childCount) {
            val child = current.getChild(i) ?: continue
            childFragments = collectTextFragments(child)
            if (childFragments.isNotEmpty()) break
          }
        }
        if (childFragments.isNotEmpty()) {
          val tapMarginLocal = ui.dpToPx(16)
          val nearFragments =
            childFragments.filter { fragment ->
              val expanded = Rect(fragment.bounds)
              expanded.inset(-tapMarginLocal, -tapMarginLocal)
              expanded.contains(x, y)
            }
          android.util.Log.d("A11yTap", "Found ${childFragments.size} total, ${nearFragments.size} near tap")
          if (nearFragments.isNotEmpty()) {
            val anchor = nearFragments.first()
            val anchorClusterGroup = anchor.recyclerViewItemId
            bestFragments =
              if (anchorClusterGroup >= 0) {
                childFragments.filter { it.recyclerViewItemId == anchorClusterGroup }
              } else {
                childFragments.filter { fragment ->
                  val hMargin = ui.dpToPx(4)
                  fragment.bounds.right > anchor.bounds.left - hMargin &&
                    fragment.bounds.left < anchor.bounds.right + hMargin
                }
              }
            android.util.Log.d("A11yTap", "Filtered to ${bestFragments.size} aligned fragments")
          }
        }
        break
      }

      bestFragments = fragments

      if (fragments.size > 1) {
        android.util.Log.d("A11yTap", "Stopping: found ${fragments.size} fragments")
        break
      }

      current = current.parent
    }

    android.util.Log.d("A11yTap", "bestFragments=${bestFragments.size}")
    if (bestFragments.isEmpty()) return emptyList()

    val tapMargin = ui.dpToPx(8)
    val tapNearFragment =
      bestFragments.any { fragment ->
        val expanded = Rect(fragment.bounds)
        expanded.inset(-tapMargin, -tapMargin)
        expanded.contains(x, y)
      }
    android.util.Log.d("A11yTap", "tapNearFragment=$tapNearFragment tapPoint=($x,$y)")
    if (!tapNearFragment) {
      val fallbackNode = findNodeAtPoint(root, x, y)
      if (fallbackNode != null) {
        val fallbackText = fallbackNode.text?.toString()?.trim()
        if (!fallbackText.isNullOrEmpty()) {
          val fb = Rect()
          fallbackNode.getBoundsInScreen(fb)
          android.util.Log.d("A11yTap", "Fallback to single node: '${fallbackText.take(40)}' bounds=${fb.toShortString()}")
          return listOf(
            StyledFragment(
              fallbackText,
              TranslatorRect(fb.left, fb.top, fb.right, fb.bottom),
            ),
          )
        }
      }
      return emptyList()
    }

    return bestFragments.map { fragment ->
      StyledFragment(
        fragment.text,
        TranslatorRect(fragment.bounds.left, fragment.bounds.top, fragment.bounds.right, fragment.bounds.bottom),
        clusterGroup = if (fragment.recyclerViewItemId >= 0) fragment.recyclerViewItemId + 1 else 0,
      )
    }
  }

  fun collectVisibleStyledFragments(root: AccessibilityNodeInfo): List<StyledFragment> {
    val fragments = collectTextFragments(root, skipButtons = true)
    return fragments.map { fragment ->
      StyledFragment(
        fragment.text,
        TranslatorRect(fragment.bounds.left, fragment.bounds.top, fragment.bounds.right, fragment.bounds.bottom),
        clusterGroup = if (fragment.recyclerViewItemId >= 0) fragment.recyclerViewItemId + 1 else 0,
      )
    }
  }

  fun sampleColorsFromScreenshot(
    bitmap: Bitmap,
    bounds: Rect,
  ): OverlayColors {
    val bgMode = settingsManager.settings.value.backgroundMode
    val translatorBounds = TranslatorRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    return getOverlayColors(bitmap, translatorBounds, bgMode)
  }

  private var nextRecyclerViewItemId = 0

  private fun collectTextFragments(
    node: AccessibilityNodeInfo,
    skipButtons: Boolean = false,
  ): List<TextFragment> {
    nextRecyclerViewItemId = 0
    val screenWidth = service.resources.displayMetrics.widthPixels
    val screenHeight = service.resources.displayMetrics.heightPixels
    val screenArea = screenWidth.toLong() * screenHeight.toLong()
    val results = mutableListOf<TextFragment>()
    collectTextFragmentsRecursive(node, screenWidth, screenHeight, screenArea, skipButtons, results)

    val minCharWidth = ui.dpToPx(2)
    val minLineHeight = ui.dpToPx(8)
    val duplicateBounds =
      results
        .groupBy { it.bounds.toShortString() }
        .filter { it.value.size > 1 }
        .keys
    return results.filter { fragment ->
      val charsPerLine = maxOf(1, fragment.bounds.width() / maxOf(1, minCharWidth))
      val linesAvailable = maxOf(1, fragment.bounds.height() / maxOf(1, minLineHeight))
      val fits = fragment.text.length <= charsPerLine * linesAvailable
      val uniqueBounds = fragment.bounds.toShortString() !in duplicateBounds
      fits && uniqueBounds
    }
  }

  private val skipClasses =
    setOf(
      "android.widget.Image",
      "android.widget.Button",
      "android.widget.ToggleButton",
      "android.widget.ImageButton",
    )

  private fun collectTextFragmentsRecursive(
    node: AccessibilityNodeInfo,
    screenWidth: Int,
    screenHeight: Int,
    screenArea: Long,
    skipButtons: Boolean,
    results: MutableList<TextFragment>,
    recyclerViewItemId: Int = -1,
  ): Boolean {
    if (!node.isVisibleToUser) {
      val bounds = Rect()
      node.getBoundsInScreen(bounds)
      if (bounds.height() > 0) return false
    }
    val cls = node.className?.toString()
    if (cls == "android.widget.Image") return false
    if (skipButtons && cls in skipClasses) return false

    val isRecyclerView = cls?.endsWith("RecyclerView") == true
    val childStartIndex = results.size
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val childItemId = if (isRecyclerView) nextRecyclerViewItemId++ else recyclerViewItemId
      collectTextFragmentsRecursive(child, screenWidth, screenHeight, screenArea, skipButtons, results, childItemId)
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
      if (areEquivalentFragmentTexts(text, childText)) return true
      results.subList(childStartIndex, results.size).clear()
    }

//    Log.d(
//      "A11yTree",
//      "FRAGMENT: '${text.take(40)}' bounds=${bounds.toShortString()} ${bounds.width()}x${bounds.height()} clickable=${node.isClickable} focusable=${node.isFocusable} class=${node.className} id=${node.viewIdResourceName} actions=${node.actionList.map { it.id }} parent=${node.parent?.className}",
//    )
    results.add(TextFragment(text, Rect(bounds), recyclerViewItemId))
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

  private fun hasVerticalOverlap(
    first: Rect,
    second: Rect,
  ): Boolean = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top) >= -ui.dpToPx(4)

  fun dumpA11yTree(root: AccessibilityNodeInfo) {
    if (!isDebuggable) return
    val sb = StringBuilder()
    sb.appendLine("A11y tree (root=${root.className})")
    dumpA11yNode(sb, root, depth = 0)
    for (line in sb.lines()) {
      if (line.isNotEmpty()) android.util.Log.d("A11yTap", line)
    }
  }

  private fun dumpA11yNode(
    sb: StringBuilder,
    node: AccessibilityNodeInfo,
    depth: Int,
  ) {
    val indent = "  ".repeat(depth)
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    val text = node.text?.toString()?.take(40) ?: ""
    val vis = if (node.isVisibleToUser) "V" else "H"
    sb.append(indent)
    sb.append(node.className ?: "?")
    if (text.isNotEmpty()) sb.append(" text='$text'")
    sb.append(" ${bounds.toShortString()}")
    if (vis != "V") sb.append(" $vis")
    if (node.childCount > 0) sb.append(" children=${node.childCount}")
    val desc = node.contentDescription?.toString()?.take(40)
    if (desc != null) sb.append(" desc='$desc'")
    if (node.isClickable) sb.append(" clickable")
    val id = node.viewIdResourceName
    if (id != null) sb.append(" id=$id")
    sb.appendLine()
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      dumpA11yNode(sb, child, depth + 1)
    }
  }

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
