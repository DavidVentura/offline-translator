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

package dev.davidv.translator.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.yalantis.ucrop.R
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import com.yalantis.ucrop.callback.OverlayViewChangeListener
import com.yalantis.ucrop.view.OverlayView
import com.yalantis.ucrop.view.UCropView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

class AppCropActivity : UCropActivity() {
  private var inAutoZoom = false
  private var ucropView: UCropView? = null
  private var lastCropRect: RectF? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

    findViewById<View>(R.id.toolbar)?.visibility = View.GONE
    findViewById<View>(R.id.state_rotate)?.performClick()
    findViewById<View>(R.id.wrapper_states)?.visibility = View.GONE

    findViewById<View>(R.id.ucrop_photobox)?.let { root ->
      ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
        insets
      }
    }

    val toolbarColor =
      intent.getIntExtra(
        UCrop.Options.EXTRA_TOOL_BAR_COLOR,
        getColor(R.color.ucrop_color_toolbar),
      )
    val toolbarWidgetColor =
      intent.getIntExtra(
        UCrop.Options.EXTRA_UCROP_WIDGET_COLOR_TOOLBAR,
        getColor(R.color.ucrop_color_toolbar_widget),
      )

    findViewById<ViewGroup>(R.id.wrapper_controls)?.let { wrapper ->
      wrapper.setBackgroundColor(toolbarColor)
      for (i in 0 until wrapper.childCount) {
        val child = wrapper.getChildAt(i)
        if (child is ImageView && child.id == View.NO_ID) {
          child.background = null
        }
      }
    }

    installBottomActionBar(toolbarColor, toolbarWidgetColor)

    ucropView = findViewById(R.id.ucrop)
    ucropView?.let { ucrop ->
      val cropImageView = ucrop.cropImageView
      cropImageView.setScaleEnabled(true)
      cropImageView.setRotateEnabled(false)
      val overlay = ucrop.overlayView
      overlay.setFreestyleCropMode(OverlayView.FREESTYLE_CROP_MODE_ENABLE_WITH_PASS_THROUGH)
      overlay.setOverlayViewChangeListener(
        object : OverlayViewChangeListener {
          override fun onCropRectUpdated(cropRect: RectF) {
            if (inAutoZoom) {
              cropImageView.setCropRect(cropRect)
              return
            }
            val prev = lastCropRect
            if (prev == null) {
              lastCropRect = RectF(cropRect)
              cropImageView.setCropRect(cropRect)
              return
            }
            zoomCropToViewport(overlay, cropRect, prev)
          }
        },
      )
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      findViewById<View>(R.id.ucrop)?.addOnLayoutChangeListener(
        View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
          v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
        },
      )
    }

    installModeSwitchButton()
  }

  private fun installModeSwitchButton() {
    val photobox = findViewById<ViewGroup>(R.id.ucrop_photobox) as? RelativeLayout ?: return
    val catalog = (application as dev.davidv.translator.TranslatorApplication).catalogRepository.catalog.value
    if (!catalog.supportInstalledByKind("doc_detect")) return
    val sizePx =
      TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        40f,
        resources.displayMetrics,
      ).toInt()
    val marginPx =
      TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        8f,
        resources.displayMetrics,
      ).toInt()

    val button =
      ImageButton(this).apply {
        setImageResource(dev.davidv.translator.R.drawable.auto_awesome)
        setColorFilter(android.graphics.Color.WHITE)
        val outValue = TypedValue()
        context.theme.resolveAttribute(
          android.R.attr.selectableItemBackgroundBorderless,
          outValue,
          true,
        )
        setBackgroundResource(outValue.resourceId)
        contentDescription = getString(dev.davidv.translator.R.string.a11y_detect_document_corners)
        setOnClickListener {
          setResult(RESULT_SWITCH_TO_DOC_ALIGN, Intent())
          finish()
        }
        layoutParams =
          RelativeLayout.LayoutParams(sizePx, sizePx).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.ALIGN_PARENT_END)
            setMargins(marginPx, marginPx, marginPx, marginPx)
          }
      }
    photobox.addView(button)
  }

  private fun zoomCropToViewport(
    overlay: OverlayView,
    newRect: RectF,
    prevRect: RectF,
  ) {
    val ucrop = ucropView ?: return
    val cropImageView = ucrop.cropImageView

    val vL = overlay.paddingLeft.toFloat()
    val vT = overlay.paddingTop.toFloat()
    val vR = (overlay.width - overlay.paddingRight).toFloat()
    val vB = (overlay.height - overlay.paddingBottom).toFloat()

    if (newRect.width() <= 0f || newRect.height() <= 0f) return

    val eps = MOVE_EPS_PX
    val leftMoved = abs(newRect.left - prevRect.left) > eps
    val rightMoved = abs(newRect.right - prevRect.right) > eps
    val topMoved = abs(newRect.top - prevRect.top) > eps
    val bottomMoved = abs(newRect.bottom - prevRect.bottom) > eps

    val xMoves = (if (leftMoved) 1 else 0) + (if (rightMoved) 1 else 0)
    val yMoves = (if (topMoved) 1 else 0) + (if (bottomMoved) 1 else 0)

    if (xMoves > 1 || yMoves > 1 || (xMoves == 0 && yMoves == 0)) {
      finishWithoutZoom(overlay, cropImageView, newRect)
      return
    }

    val anchorX: Float
    val maxScaleX: Float
    when {
      xMoves == 0 -> {
        anchorX = newRect.centerX()
        maxScaleX = 1f
      }
      leftMoved -> {
        anchorX = prevRect.right
        maxScaleX = (anchorX - vL) / (anchorX - newRect.left)
      }
      else -> {
        anchorX = prevRect.left
        maxScaleX = (vR - anchorX) / (newRect.right - anchorX)
      }
    }

    val anchorY: Float
    val maxScaleY: Float
    when {
      yMoves == 0 -> {
        anchorY = newRect.centerY()
        maxScaleY = 1f
      }
      topMoved -> {
        anchorY = prevRect.bottom
        maxScaleY = (anchorY - vT) / (anchorY - newRect.top)
      }
      else -> {
        anchorY = prevRect.top
        maxScaleY = (vB - anchorY) / (newRect.bottom - anchorY)
      }
    }

    var scale = min(maxScaleX, maxScaleY)
    val maxImage = cropImageView.maxScale / cropImageView.currentScale
    if (scale > maxImage) scale = maxImage
    if (scale <= 1.001f) {
      finishWithoutZoom(overlay, cropImageView, newRect)
      return
    }

    val totalScale = scale
    val initialRect = RectF(newRect)

    inAutoZoom = true
    overlay.setShowCropGrid(false)
    var lastF = 0f
    ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 180L
      interpolator = DecelerateInterpolator()
      addUpdateListener {
        val f = it.animatedFraction
        val df = f - lastF
        if (df > 0f) {
          val r = totalScale.toDouble().pow(df.toDouble()).toFloat()
          cropImageView.postScale(r, anchorX, anchorY)

          val sf = totalScale.toDouble().pow(f.toDouble()).toFloat()
          overlay.cropViewRect.set(
            anchorX + (initialRect.left - anchorX) * sf,
            anchorY + (initialRect.top - anchorY) * sf,
            anchorX + (initialRect.right - anchorX) * sf,
            anchorY + (initialRect.bottom - anchorY) * sf,
          )
          overlay.invalidate()
        }
        lastF = f
      }
      addListener(
        object : AnimatorListenerAdapter() {
          override fun onAnimationEnd(animation: Animator) {
            cropImageView.setCropRect(overlay.cropViewRect)
            overlay.setShowCropGrid(true)
            overlay.setCropGridRowCount(GRID_COUNT)
            overlay.setCropGridColumnCount(GRID_COUNT)
            refreshOverlayGridCorners(overlay)
            overlay.invalidate()
            lastCropRect = RectF(overlay.cropViewRect)
            inAutoZoom = false
          }
        },
      )
      start()
    }
  }

  private fun finishWithoutZoom(
    overlay: OverlayView,
    cropImageView: com.yalantis.ucrop.view.GestureCropImageView,
    newRect: RectF,
  ) {
    cropImageView.setCropRect(newRect)
    lastCropRect = RectF(newRect)
  }

  private fun refreshOverlayGridCorners(overlay: OverlayView) {
    runCatching { updateGridPointsMethod.invoke(overlay) }
  }

  companion object {
    private const val GRID_COUNT = 2
    private const val MOVE_EPS_PX = 12f

    const val RESULT_SWITCH_TO_DOC_ALIGN = Activity.RESULT_FIRST_USER + 2

    private val updateGridPointsMethod by lazy {
      OverlayView::class.java.getDeclaredMethod("updateGridPoints").apply { isAccessible = true }
    }
  }

  private fun installBottomActionBar(
    barColor: Int,
    textColor: Int,
  ) {
    val photobox = findViewById<ViewGroup>(R.id.ucrop_photobox) as? RelativeLayout ?: return
    val controlsWrapper = findViewById<View>(R.id.controls_wrapper) ?: return

    val barHeightPx =
      TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        56f,
        resources.displayMetrics,
      ).toInt()

    val bottomBarId = View.generateViewId()
    val bottomBar =
      LinearLayout(this).apply {
        id = bottomBarId
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(barColor)
        gravity = Gravity.CENTER_VERTICAL
        layoutParams =
          RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            barHeightPx,
          ).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) }
      }

    fun makeButton(
      label: String,
      onClick: () -> Unit,
    ) = TextView(this).apply {
      text = label
      setTextColor(textColor)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      gravity = Gravity.CENTER
      isAllCaps = true
      val outValue = TypedValue()
      context.theme.resolveAttribute(
        android.R.attr.selectableItemBackgroundBorderless,
        outValue,
        true,
      )
      setBackgroundResource(outValue.resourceId)
      setOnClickListener { onClick() }
      layoutParams =
        LinearLayout.LayoutParams(
          0,
          LinearLayout.LayoutParams.MATCH_PARENT,
          1f,
        )
    }

    bottomBar.addView(makeButton(getString(dev.davidv.translator.R.string.common_cancel)) { finish() })
    bottomBar.addView(makeButton(getString(dev.davidv.translator.R.string.image_crop_action)) { cropAndSaveImage() })

    photobox.addView(bottomBar)

    val wrapperParams = controlsWrapper.layoutParams as RelativeLayout.LayoutParams
    wrapperParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
    wrapperParams.addRule(RelativeLayout.ABOVE, bottomBarId)
    controlsWrapper.layoutParams = wrapperParams
  }
}
