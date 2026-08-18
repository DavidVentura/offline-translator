package dev.davidv.translator.accessibilityOverlay

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.davidv.translator.Language
import dev.davidv.translator.MainActivity
import dev.davidv.translator.ReadingOrder
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.assistantOverlay.BorderWaveView
import dev.davidv.translator.overlayChrome.FloatingBubble
import dev.davidv.translator.overlayChrome.OverlayChromeFactory
import dev.davidv.translator.overlayChrome.OverlayInsets
import dev.davidv.translator.overlayChrome.OverlayMenuHost
import dev.davidv.translator.overlayChrome.OverlayMenuManager
import dev.davidv.translator.ui.components.ScanAnimationOverlay
import dev.davidv.translator.ui.components.SelectionSurface
import dev.davidv.translator.ui.components.SelectionSurfaceState
import dev.davidv.translator.ui.components.WindowComposeHost

class OverlayUI(
  private val service: TranslatorAccessibilityService,
  private val windowManager: WindowManager,
  private val settingsManager: SettingsManager,
) {
  private val handler = Handler(Looper.getMainLooper())
  private var launcherBubble: FloatingBubble? = null
  private var toolbarView: View? = null
  private var sourceLabelView: TextView? = null
  private var targetLabelView: TextView? = null
  private var readingOrderButtonView: View? = null
  private var readingOrderIconView: ImageView? = null
  private var selectIconView: ImageView? = null
  private var refreshButtonView: View? = null
  private var flipButtonView: View? = null
  private var flipIconView: ImageView? = null
  private val translationOverlays = mutableListOf<View>()
  private var touchWatcher: View? = null
  private var borderView: BorderWaveView? = null
  private var selectDialog: Dialog? = null
  private var selectHost: WindowComposeHost? = null
  private var scanHost: WindowComposeHost? = null

  private val menuManager =
    OverlayMenuManager(
      service,
      ::dpToPx,
      object : OverlayMenuHost {
        override fun addDismissLayer(view: View) {
          val params =
            WindowManager.LayoutParams(
              WindowManager.LayoutParams.MATCH_PARENT,
              WindowManager.LayoutParams.MATCH_PARENT,
              WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
              PixelFormat.TRANSLUCENT,
            )
          params.windowAnimations = 0
          windowManager.addView(view, params)
        }

        override fun addMenuView(view: View) {
          val params =
            WindowManager.LayoutParams(
              dpToPx(180),
              WindowManager.LayoutParams.WRAP_CONTENT,
              WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
              PixelFormat.TRANSLUCENT,
            )
          params.gravity = Gravity.TOP or Gravity.END
          params.x = dpToPx(8)
          params.y = dpToPx(48)
          params.windowAnimations = 0
          windowManager.addView(view, params)
        }

        override fun addPickerView(view: View) {
          val params =
            WindowManager.LayoutParams(
              dpToPx(250),
              dpToPx(400),
              WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
              PixelFormat.TRANSLUCENT,
            )
          params.gravity = Gravity.CENTER
          params.windowAnimations = 0
          windowManager.addView(view, params)
        }

        override fun removeMenuChild(view: View) {
          windowManager.removeView(view)
        }
      },
    )

  fun showFloatingButton() {
    val bubble = launcherBubble
    if (bubble == null) {
      launcherBubble =
        FloatingBubble(service, windowManager, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, ::dpToPx) {
          service.activate()
        }.also { it.show() }
    } else {
      bubble.setShown(true)
    }
  }

  fun removeFloatingButton() {
    launcherBubble?.setShown(false)
  }

  fun restoreFloatingButton() {
    showFloatingButton()
  }

  fun showToolbar(
    forcedSourceLanguage: Language?,
    forcedTargetLanguage: Language?,
    readingOrder: ReadingOrder?,
    isAutoSource: Boolean,
  ) {
    if (toolbarView != null) return

    val toolbarViews =
      OverlayChromeFactory.createLanguageToolbar(
        context = service,
        dpToPx = ::dpToPx,
        forcedSourceLanguage = forcedSourceLanguage,
        forcedTargetLanguage = forcedTargetLanguage,
        defaultTargetLanguage = service.langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: return,
        onClose = { service.deactivate() },
        onSelectModeClick = { service.toggleSelectMode() },
        onSourceClick = { service.showLanguagePicker(true) },
        onSwap = { service.swapLanguages() },
        onTargetClick = { service.showLanguagePicker(false) },
        showReadingOrderButton = forcedSourceLanguage?.code == "ja",
        readingOrder = readingOrder,
        onReadingOrderClick = { service.toggleJapaneseOcrMode() },
        onRefreshClick = { service.handleFullScreenOcr() },
        onFlipOriginal = { service.toggleFlipOriginal() },
        onMenuClick = { service.showDotsMenu() },
        isAutoSource = isAutoSource,
      )
    val toolbar = toolbarViews.root
    sourceLabelView = toolbarViews.sourceLabel
    targetLabelView = toolbarViews.targetLabel
    readingOrderButtonView = toolbarViews.readingOrderButton
    readingOrderIconView = toolbarViews.readingOrderIcon
    selectIconView = toolbarViews.selectIcon
    refreshButtonView = toolbarViews.refreshButton
    flipButtonView = toolbarViews.flipButton
    flipIconView = toolbarViews.flipIcon
    flipButtonView?.visibility = View.GONE

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.START
    params.x = 0
    params.y = getStatusBarHeight()
    params.windowAnimations = 0

    windowManager.addView(toolbar, params)
    toolbarView = toolbar
  }

  fun removeToolbar() {
    toolbarView?.let {
      windowManager.removeView(it)
      toolbarView = null
      sourceLabelView = null
      targetLabelView = null
      readingOrderButtonView = null
      readingOrderIconView = null
      selectIconView = null
      refreshButtonView = null
      flipButtonView = null
      flipIconView = null
    }
  }

  /** Swap the mode-dependent toolbar slot: refresh drives the live screen, flip the frozen one. */
  fun setSelectModeUi(active: Boolean) {
    selectIconView?.setColorFilter(if (active) OverlayChromeFactory.ACTIVE_ICON_TINT else Color.WHITE)
    refreshButtonView?.visibility = if (active) View.GONE else View.VISIBLE
    flipButtonView?.visibility = if (active) View.VISIBLE else View.GONE
    if (!active) flipIconView?.setColorFilter(Color.WHITE)
  }

  fun setFlipActive(active: Boolean) {
    flipIconView?.setColorFilter(if (active) OverlayChromeFactory.ACTIVE_ICON_TINT else Color.WHITE)
  }

  fun updateToolbarState(
    forcedSourceLanguage: Language?,
    forcedTargetLanguage: Language?,
    readingOrder: ReadingOrder?,
    isAutoSource: Boolean,
  ) {
    sourceLabelView?.text = OverlayChromeFactory.formatSourceLabel(forcedSourceLanguage, isAutoSource)
    val currentTarget = forcedTargetLanguage ?: service.langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode)
    targetLabelView?.text = currentTarget?.shortDisplayName ?: "?"
    OverlayChromeFactory.updateReadingOrderButtonState(
      readingButton = readingOrderButtonView,
      readingIcon = readingOrderIconView,
      visible = forcedSourceLanguage?.code == "ja",
      readingOrder = readingOrder,
    )
  }

  fun showDotsMenu() {
    menuManager.showDotsMenu(
      listOf(
        "Open App" to {
          service.deactivate()
          val intent = Intent(service, MainActivity::class.java)
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          service.startActivity(intent)
        },
        "Disable Service" to {
          service.deactivate()
          service.disableSelf()
        },
      ),
    )
  }

  fun showLanguagePicker(
    isSource: Boolean,
    availableLangs: List<Language>,
    onPick: (Language?) -> Unit,
  ) {
    menuManager.showLanguagePicker(isSource, availableLangs) { lang ->
      onPick(lang)
    }
  }

  fun dismissMenu() {
    menuManager.dismiss()
  }

  fun showBitmapOverlay(
    bitmap: Bitmap,
    bounds: Rect,
  ) {
    val imageView = ImageView(service)
    imageView.setImageBitmap(bitmap)
    imageView.scaleType = ImageView.ScaleType.FIT_XY

    // Non-touchable so taps and swipes pass straight through to the app underneath;
    // the overlay clears itself off the next scroll/click via onAccessibilityEvent.
    val params =
      WindowManager.LayoutParams(
        bounds.width(),
        bounds.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.START
    params.x = bounds.left
    params.y = bounds.top
    params.usePhysicalDisplayCoordinates()

    windowManager.addView(imageView, params)
    translationOverlays.add(imageView)
    ensureTouchWatcher()
  }

  /**
   * The frozen select-text surface: one touchable window over the translated region hosting the
   * same Compose selection stack as the assistant. A Dialog rather than a raw WindowManager view
   * because the word-selection action bar needs `startActionMode(TYPE_FLOATING)`, which only a
   * DecorView provides; NOT_TOUCH_MODAL keeps the toolbar's own window clickable, and the back key
   * cancels the dialog (the service then exits select mode via `onDismiss`).
   */
  fun showSelectSurface(
    state: SelectionSurfaceState,
    region: Rect,
    onDismiss: () -> Unit,
  ) {
    if (selectDialog != null) return
    val dialog = Dialog(service, android.R.style.Theme_Material_NoActionBar)
    val host = WindowComposeHost(service)
    host.setContent { SelectionSurface(state) }
    dialog.setContentView(host.view)
    dialog.setCanceledOnTouchOutside(false)
    dialog.window?.apply {
      setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
      addFlags(
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      )
      clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
      setWindowAnimations(0)
      setGravity(Gravity.TOP or Gravity.START)
      attributes =
        attributes.apply {
          x = region.left
          y = region.top
          width = region.width()
          height = region.height()
          usePhysicalDisplayCoordinates()
        }
      decorView.setPadding(0, 0, 0, 0)
      host.installOn(decorView)
    }
    dialog.setOnCancelListener { onDismiss() }
    dialog.show()
    selectDialog = dialog
    selectHost = host
  }

  fun removeSelectSurface() {
    val dialog = selectDialog ?: return
    selectDialog = null
    dialog.setOnCancelListener(null)
    dialog.dismiss()
    selectHost?.dispose()
    selectHost = null
  }

  fun hasSelectSurface(): Boolean = selectDialog != null

  /** A 1×1 watcher window: WATCH_OUTSIDE_TOUCH fires ACTION_OUTSIDE on the *down* of
   *  any touch anywhere on screen, and NOT_TOUCH_MODAL lets that same touch reach the
   *  app — so a tap or swipe both clear the result instantly and pass straight through. */
  @android.annotation.SuppressLint("ClickableViewAccessibility")
  private fun ensureTouchWatcher() {
    if (touchWatcher != null) return
    val watcher = View(service)
    watcher.setOnTouchListener { _, event ->
      // The system zeroes ACTION_OUTSIDE coordinates unless the touched window belongs to our
      // own UID: real coordinates mean the touch landed on our chrome (toolbar, menus, their
      // dismiss layer), which can't change the screen underneath — keep the overlays up.
      if (event.action == MotionEvent.ACTION_OUTSIDE && event.rawX == 0f && event.rawY == 0f) {
        removeTranslationOverlays()
      }
      false
    }
    val params =
      WindowManager.LayoutParams(
        1,
        1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
          WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.START
    params.windowAnimations = 0
    windowManager.addView(watcher, params)
    touchWatcher = watcher
  }

  private fun removeTouchWatcher() {
    touchWatcher?.let {
      try {
        windowManager.removeView(it)
      } catch (_: Exception) {
      }
    }
    touchWatcher = null
  }

  /** The detect-pass progress animation, drawn over the live screen itself: a transparent
   *  non-touchable window whose scan pills appear once the detector reports regions. */
  fun showScanOverlay(
    state: SelectionSurfaceState,
    region: Rect,
  ) {
    if (scanHost != null) return
    val host = WindowComposeHost(service)
    host.setContent {
      state.regions.value?.let {
        ScanAnimationOverlay(regions = it, modifier = Modifier.fillMaxSize())
      }
    }
    val params =
      WindowManager.LayoutParams(
        region.width(),
        region.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.START
    params.x = region.left
    params.y = region.top
    params.usePhysicalDisplayCoordinates()
    params.windowAnimations = 0
    windowManager.addView(host.view, params)
    scanHost = host
  }

  private fun removeScanOverlay() {
    scanHost?.let {
      try {
        windowManager.removeView(it.view)
      } catch (_: Exception) {
      }
      it.dispose()
    }
    scanHost = null
  }

  fun showOverlayMessage(message: String) {
    val textView = TextView(service)
    textView.text = message
    textView.setTextColor(Color.WHITE)
    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    val pad = dpToPx(16)
    textView.setPadding(pad, pad, pad, pad)
    val bg = GradientDrawable()
    bg.setColor(Color.parseColor("#DD333333"))
    bg.cornerRadius = dpToPx(8).toFloat()
    textView.background = bg

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    params.y = dpToPx(80)

    windowManager.addView(textView, params)
    handler.postDelayed({
      try {
        windowManager.removeView(textView)
      } catch (_: Exception) {
      }
    }, 3000)
  }

  fun hasToolbar(): Boolean = toolbarView != null

  fun removeTranslationOverlays() {
    for (view in translationOverlays) {
      try {
        windowManager.removeView(view)
      } catch (_: Exception) {
      }
    }
    translationOverlays.clear()
    removeScanOverlay()
    removeTouchWatcher()
  }

  fun getStatusBarHeight(): Int = OverlayInsets.topInset(windowManager, service.resources)

  fun getNavBarHeight(): Int {
    val resourceId = service.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (resourceId > 0) service.resources.getDimensionPixelSize(resourceId) else 0
  }

  fun showBorderWave() {
    if (borderView != null) return
    val view = BorderWaveView.create(service)
    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    windowManager.addView(view, params)
    borderView = view
    view.startAnimation()
  }

  fun removeBorderWave() {
    borderView?.stopAnimation()
    borderView?.let {
      try {
        windowManager.removeView(it)
      } catch (_: Exception) {
      }
    }
    borderView = null
  }

  fun cleanup() {
    handler.removeCallbacksAndMessages(null)
    launcherBubble?.remove()
    launcherBubble = null
  }

  internal fun dpToPx(dp: Int): Int = (dp * service.resources.displayMetrics.density).toInt()

  private fun WindowManager.LayoutParams.usePhysicalDisplayCoordinates() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      setFitInsetsTypes(0)
    }
  }
}
