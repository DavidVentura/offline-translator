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

class OverlayInput(
  private val service: TranslatorAccessibilityService,
  private val windowManager: WindowManager,
  private val ui: OverlayUI,
  private val settingsManager: SettingsManager,
) {
  private val tag = "OverlayInput"
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

    overlay.setOnTouchListener { v, event ->
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
          if (!dragging) v.performClick()
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
    if (root == null) return null
    val bounds = Rect()
    root.getBoundsInScreen(bounds)
    if (!bounds.contains(x, y)) return null
    for (i in root.childCount - 1 downTo 0) {
      val child = root.getChild(i) ?: continue
      val found = findNodeAtPoint(child, x, y)
      if (found != null) return found
    }
    val text = root.text ?: root.contentDescription
    if (text != null && text.isNotBlank()) {
      val screenWidth = service.resources.displayMetrics.widthPixels
      val screenHeight = service.resources.displayMetrics.heightPixels
      val boundsArea = bounds.width().toLong() * bounds.height().toLong()
      val screenArea = screenWidth.toLong() * screenHeight.toLong()
      if (boundsArea > screenArea / 2) return null
      return root
    }
    return null
  }

  fun extractText(node: AccessibilityNodeInfo): String? {
    node.text?.let { return it.toString().trim() }
    node.contentDescription?.let { return it.toString().trim() }
    val sb = StringBuilder()
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val childText = extractText(child)
      if (!childText.isNullOrBlank()) {
        if (sb.isNotEmpty()) sb.append(" ")
        sb.append(childText)
      }
    }
    return sb.toString().ifBlank { null }
  }

  fun collectVisibleTextNodes(root: AccessibilityNodeInfo): List<Pair<String, Rect>> {
    val screenWidth = service.resources.displayMetrics.widthPixels
    val screenHeight = service.resources.displayMetrics.heightPixels
    val screenArea = screenWidth.toLong() * screenHeight.toLong()
    val results = mutableListOf<Pair<String, Rect>>()
    collectVisibleTextNodesRecursive(root, screenWidth, screenHeight, screenArea, results)
    return results
  }

  private fun collectVisibleTextNodesRecursive(
    node: AccessibilityNodeInfo,
    screenWidth: Int,
    screenHeight: Int,
    screenArea: Long,
    results: MutableList<Pair<String, Rect>>,
  ) {
    val bounds = Rect()
    node.getBoundsInScreen(bounds)

    if (bounds.right <= 0 || bounds.bottom <= 0 || bounds.left >= screenWidth || bounds.top >= screenHeight) return
    if (bounds.width() <= 0 || bounds.height() <= 0) return

    val boundsArea = bounds.width().toLong() * bounds.height().toLong()
    if (boundsArea > screenArea / 2) {
      for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        collectVisibleTextNodesRecursive(child, screenWidth, screenHeight, screenArea, results)
      }
      return
    }

    val sizeBefore = results.size
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      collectVisibleTextNodesRecursive(child, screenWidth, screenHeight, screenArea, results)
    }

    if (results.size == sizeBefore) {
      val text = node.text
      if (text != null && text.isNotBlank()) {
        results.add(Pair(text.toString().trim(), Rect(bounds)))
      }
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

  private class SelectionRectView(context: android.content.Context) : View(context) {
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
