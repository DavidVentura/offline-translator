package dev.davidv.translator.assistantOverlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator

class BorderWaveView private constructor(
  context: Context,
  private val borderColor: Int,
  private val strokeWidthPx: Float,
) : View(context) {
  companion object {
    private const val COLOR = "#E08050"
    private const val STROKE_DP = 3
    private const val DURATION_MS = 3000L

    fun create(context: Context): BorderWaveView {
      val density = context.resources.displayMetrics.density
      return BorderWaveView(context, Color.parseColor(COLOR), (STROKE_DP * density))
    }
  }

  private var animator: ValueAnimator? = null

  fun startAnimation() {
    animator?.cancel()
    visibility = VISIBLE
    animator =
      ValueAnimator.ofFloat(0f, 1f).apply {
        duration = DURATION_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float }
        start()
      }
  }

  fun stopAnimation() {
    animator?.cancel()
    animator = null
    visibility = GONE
  }

  private val brightAlpha = 255
  private val dimAlpha = 20

  private val paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = strokeWidthPx
    }
  private val shaderMatrix = Matrix()
  private var sweepGradient: SweepGradient? = null
  private var lastWidth = 0
  private var lastHeight = 0
  private var leftInsetPx = 0f
  private var topInsetPx = 0f
  private var rightInsetPx = 0f
  private var bottomInsetPx = 0f

  var phase = 0f
    set(value) {
      field = value
      invalidate()
    }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    requestApplyInsets()
  }

  override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
    updateSafeInsets(insets)
    return super.onApplyWindowInsets(insets)
  }

  override fun onDraw(canvas: Canvas) {
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return

    val cx = w / 2f
    val cy = h / 2f

    if (sweepGradient == null || w != lastWidth || h != lastHeight) {
      sweepGradient =
        SweepGradient(
          cx,
          cy,
          intArrayOf(
            withAlpha(brightAlpha),
            withAlpha(dimAlpha),
            withAlpha(dimAlpha),
            withAlpha(brightAlpha),
          ),
          floatArrayOf(0f, 0.25f, 0.75f, 1f),
        )
      lastWidth = w
      lastHeight = h
    }

    shaderMatrix.setRotate(phase * 360f, cx, cy)
    sweepGradient!!.setLocalMatrix(shaderMatrix)
    paint.shader = sweepGradient

    val half = strokeWidthPx / 2f
    canvas.drawRoundRect(
      leftInsetPx + half,
      topInsetPx + half,
      w - rightInsetPx - half,
      h - bottomInsetPx - half,
      strokeWidthPx * 2,
      strokeWidthPx * 2,
      paint,
    )
  }

  private fun updateSafeInsets(insets: WindowInsets) {
    var left = 0f
    var right = 0f
    var bottom = 0f

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val cutout = insets.displayCutout
      if (cutout != null) {
        left = maxOf(left, cutout.safeInsetLeft.toFloat())
        right = maxOf(right, cutout.safeInsetRight.toFloat())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          val waterfallInsets = cutout.waterfallInsets
          left = maxOf(left, waterfallInsets.left.toFloat())
          right = maxOf(right, waterfallInsets.right.toFloat())
        }
      }
    }

    if (shouldRespectBottomNavigationBar()) {
      bottom = navigationBarInsetBottom(insets).toFloat()
    }

    if (
      left != leftInsetPx ||
        right != rightInsetPx ||
        topInsetPx != 0f ||
        bottom != bottomInsetPx
    ) {
      leftInsetPx = left
      topInsetPx = 0f
      rightInsetPx = right
      bottomInsetPx = bottom
      invalidate()
    }
  }

  private fun shouldRespectBottomNavigationBar(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    val resourceId = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
    if (resourceId == 0) return true
    return resources.getInteger(resourceId) != 2
  }

  @Suppress("DEPRECATION")
  private fun navigationBarInsetBottom(insets: WindowInsets): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      insets.getInsets(WindowInsets.Type.navigationBars()).bottom
    } else {
      insets.systemWindowInsetBottom
    }

  private fun withAlpha(alpha: Int): Int = (borderColor and 0x00FFFFFF) or (alpha shl 24)
}
