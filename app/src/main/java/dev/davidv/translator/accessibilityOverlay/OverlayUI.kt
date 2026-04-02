package dev.davidv.translator.accessibilityOverlay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.davidv.translator.Language
import dev.davidv.translator.MainActivity
import dev.davidv.translator.OverlayColors
import dev.davidv.translator.R
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.overlayChrome.OverlayChromeFactory

class OverlayUI(
  private val service: TranslatorAccessibilityService,
  private val windowManager: WindowManager,
  private val settingsManager: SettingsManager,
) {
  private val handler = Handler(Looper.getMainLooper())
  private var floatingButton: View? = null
  private var toolbarView: View? = null
  private var menuOverlay: View? = null
  private var menuDismissLayer: View? = null
  private var sourceLabelView: TextView? = null
  private var targetLabelView: TextView? = null
  private val translationOverlays = mutableListOf<View>()

  var savedButtonX = 0
  var savedButtonY = 0

  fun showFloatingButton() {
    if (floatingButton != null) return

    val buttonSize = dpToPx(48)
    val padding = dpToPx(10)

    val container = FrameLayout(service)
    val bg = GradientDrawable()
    bg.shape = GradientDrawable.OVAL
    bg.setColor(Color.parseColor("#F1AB7F"))
    container.background = bg

    val icon = ImageView(service)
    icon.setImageResource(R.drawable.ic_translate_button)
    icon.setPadding(padding, padding, padding, padding)
    container.addView(icon, FrameLayout.LayoutParams(buttonSize, buttonSize))

    val params =
      WindowManager.LayoutParams(
        buttonSize,
        buttonSize,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.END
    params.x = dpToPx(16)
    params.y = dpToPx(200)

    var initialX = 0
    var initialY = 0
    var initialTouchX = 0f
    var initialTouchY = 0f
    var moved = false

    container.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          initialX = params.x
          initialY = params.y
          initialTouchX = event.rawX
          initialTouchY = event.rawY
          moved = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = (initialTouchX - event.rawX).toInt()
          val dy = (event.rawY - initialTouchY).toInt()
          if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
          params.x = initialX + dx
          params.y = initialY + dy
          windowManager.updateViewLayout(container, params)
          true
        }
        MotionEvent.ACTION_UP -> {
          if (!moved) service.activate()
          true
        }
        else -> false
      }
    }

    windowManager.addView(container, params)
    floatingButton = container
  }

  fun removeFloatingButton() {
    floatingButton?.let {
      val params = it.layoutParams as? WindowManager.LayoutParams
      savedButtonX = params?.x ?: 0
      savedButtonY = params?.y ?: 0
      windowManager.removeView(it)
      floatingButton = null
    }
  }

  fun restoreFloatingButton() {
    showFloatingButton()
    val buttonParams = floatingButton?.layoutParams as? WindowManager.LayoutParams
    if (buttonParams != null) {
      buttonParams.x = savedButtonX
      buttonParams.y = savedButtonY
      windowManager.updateViewLayout(floatingButton, buttonParams)
    }
  }

  fun showToolbar(
    forcedSourceLanguage: Language?,
    forcedTargetLanguage: Language?,
  ) {
    if (toolbarView != null) return

    val toolbarViews =
      OverlayChromeFactory.createLanguageToolbar(
        context = service,
        dpToPx = ::dpToPx,
        forcedSourceLanguage = forcedSourceLanguage,
        forcedTargetLanguage = forcedTargetLanguage,
        defaultTargetLanguage = settingsManager.settings.value.defaultTargetLanguage,
        onClose = { service.deactivate() },
        onSourceClick = { service.showLanguagePicker(true) },
        onSwap = { service.swapLanguages() },
        onTargetClick = { service.showLanguagePicker(false) },
        onMenuClick = { service.showDotsMenu() },
      )
    val toolbar = toolbarViews.root
    sourceLabelView = toolbarViews.sourceLabel
    targetLabelView = toolbarViews.targetLabel

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

    windowManager.addView(toolbar, params)
    toolbarView = toolbar
  }

  fun removeToolbar() {
    toolbarView?.let {
      windowManager.removeView(it)
      toolbarView = null
      sourceLabelView = null
      targetLabelView = null
    }
  }

  fun updateToolbarLabels(
    forcedSourceLanguage: Language?,
    forcedTargetLanguage: Language?,
  ) {
    sourceLabelView?.text = forcedSourceLanguage?.shortDisplayName ?: "Auto"
    val currentTarget = forcedTargetLanguage ?: settingsManager.settings.value.defaultTargetLanguage
    targetLabelView?.text = currentTarget.shortDisplayName
  }

  fun showDotsMenu() {
    dismissMenu()

    val dismiss = View(service)
    dismiss.setBackgroundColor(Color.TRANSPARENT)
    val dismissParams =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    dismiss.setOnClickListener { dismissMenu() }
    windowManager.addView(dismiss, dismissParams)
    menuDismissLayer = dismiss

    val menuContainer = LinearLayout(service)
    menuContainer.orientation = LinearLayout.VERTICAL
    val menuBg = GradientDrawable()
    menuBg.setColor(Color.parseColor("#E0303030"))
    menuBg.cornerRadius = dpToPx(12).toFloat()
    menuContainer.background = menuBg
    val menuPad = dpToPx(8)
    menuContainer.setPadding(menuPad, menuPad, menuPad, menuPad)

    OverlayChromeFactory.addMenuItem(service, menuContainer, ::dpToPx, "Translate visible") {
      dismissMenu()
      service.handleTranslateVisible()
    }
    OverlayChromeFactory.addMenuItem(service, menuContainer, ::dpToPx, "Open App") {
      service.deactivate()
      val intent = Intent(service, MainActivity::class.java)
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      service.startActivity(intent)
    }
    OverlayChromeFactory.addMenuItem(service, menuContainer, ::dpToPx, "Disable Service") {
      service.deactivate()
      service.disableSelf()
    }

    val menuParams =
      WindowManager.LayoutParams(
        dpToPx(180),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
      )
    menuParams.gravity = Gravity.TOP or Gravity.END
    menuParams.x = dpToPx(8)
    menuParams.y = dpToPx(48)

    windowManager.addView(menuContainer, menuParams)
    menuOverlay = menuContainer
  }

  fun showLanguagePicker(
    isSource: Boolean,
    availableLangs: List<Language>,
    onPick: (Language?) -> Unit,
  ) {
    dismissMenu()

    val dismiss = View(service)
    dismiss.setBackgroundColor(Color.parseColor("#80000000"))
    val dm = service.resources.displayMetrics
    val dismissParams =
      WindowManager.LayoutParams(
        dm.widthPixels,
        dm.heightPixels + getStatusBarHeight() + getNavBarHeight(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
      )
    dismissParams.gravity = Gravity.TOP or Gravity.START
    dismiss.setOnClickListener { dismissMenu() }
    windowManager.addView(dismiss, dismissParams)
    menuDismissLayer = dismiss

    val scroll =
      OverlayChromeFactory.createLanguagePicker(
        context = service,
        dpToPx = ::dpToPx,
        isSource = isSource,
        availableLangs = availableLangs,
      ) { lang ->
        onPick(lang)
        dismissMenu()
      }

    val pickerParams =
      WindowManager.LayoutParams(
        dpToPx(250),
        dpToPx(400),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
      )
    pickerParams.gravity = Gravity.CENTER

    windowManager.addView(scroll, pickerParams)
    menuOverlay = scroll
  }

  fun dismissMenu() {
    menuDismissLayer?.let {
      windowManager.removeView(it)
      menuDismissLayer = null
    }
    menuOverlay?.let {
      windowManager.removeView(it)
      menuOverlay = null
    }
  }

  fun showLoadingOverlay(
    bounds: Rect,
    colors: OverlayColors?,
  ) {
    val bgColor = colors?.background ?: Color.parseColor("#E0FFFFFF")

    val container = FrameLayout(service)
    val overlayBg = GradientDrawable()
    overlayBg.setColor(bgColor)
    overlayBg.cornerRadius = dpToPx(8).toFloat()
    container.background = overlayBg

    val progress = ProgressBar(service)
    val lp = FrameLayout.LayoutParams(dpToPx(24), dpToPx(24))
    lp.gravity = Gravity.CENTER
    container.addView(progress, lp)

    val params =
      WindowManager.LayoutParams(
        maxOf(bounds.width(), dpToPx(48)),
        maxOf(bounds.height(), dpToPx(32)),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.START
    params.x = bounds.left
    params.y = bounds.top

    windowManager.addView(container, params)
    translationOverlays.add(container)
  }

  fun showTranslationOverlay(
    translatedText: String,
    bounds: Rect,
    colors: OverlayColors?,
  ) {
    val bgColor = colors?.background ?: Color.parseColor("#F0FFFFFF")
    val fgColor = colors?.foreground ?: Color.BLACK
    val overlayWidth = maxOf(bounds.width(), dpToPx(48))
    val overlayHeight = maxOf(bounds.height(), dpToPx(32))
    val screenBounds =
      Rect(
        0,
        0,
        service.resources.displayMetrics.widthPixels,
        service.resources.displayMetrics.heightPixels,
      )
    val overlayRect = Rect(bounds.left, bounds.top, bounds.left + overlayWidth, bounds.top + overlayHeight)
    val visibleBounds = Rect(overlayRect)
    if (!visibleBounds.intersect(screenBounds)) return

    // When text is at the bottom of the screen we don't know how much is cropped.
    // Take system font size as a minimum size to prevent all the block's text from
    // being crammed into 1-line-tall blocks.
    val screenHeight = screenBounds.height()
    val isClippedVertically =
      bounds.bottom >= screenHeight - getNavBarHeight() - dpToPx(4) ||
        bounds.top <= getStatusBarHeight() + dpToPx(4)
    val minTextSizePx =
      if (isClippedVertically) 16f * service.resources.displayMetrics.scaledDensity else 0f

    val fullBitmap =
      renderTextOverlayBitmap(
        translatedText = translatedText,
        width = overlayWidth,
        minHeight = overlayHeight,
        bgColor = bgColor,
        fgColor = fgColor,
        minTextSizePx = minTextSizePx,
      )
    val cropLeft = visibleBounds.left - overlayRect.left
    val cropTop = visibleBounds.top - overlayRect.top
    val croppedBitmap =
      Bitmap.createBitmap(
        fullBitmap,
        cropLeft,
        cropTop,
        visibleBounds.width(),
        visibleBounds.height(),
      )
    if (croppedBitmap != fullBitmap) {
      fullBitmap.recycle()
    }
    showBitmapOverlay(croppedBitmap, visibleBounds)
  }

  private fun renderTextOverlayBitmap(
    translatedText: String,
    width: Int,
    minHeight: Int,
    bgColor: Int,
    fgColor: Int,
    minTextSizePx: Float = 0f,
  ): Bitmap {
    val container = FrameLayout(service)
    val overlayBg = GradientDrawable()
    overlayBg.setColor(bgColor)
    overlayBg.cornerRadius = dpToPx(8).toFloat()
    container.background = overlayBg

    val sizingTextView = buildOverlayTextView(translatedText, fgColor)
    val exactWidth = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
    val exactMinHeight = View.MeasureSpec.makeMeasureSpec(minHeight, View.MeasureSpec.EXACTLY)
    sizingTextView.measure(exactWidth, exactMinHeight)
    sizingTextView.layout(0, 0, width, minHeight)
    val resolvedTextSizePx = maxOf(sizingTextView.textSize, minTextSizePx)

    val textView = buildOverlayTextView(translatedText, fgColor)
    textView.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
    textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, resolvedTextSizePx)
    container.addView(
      textView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )

    val screenHeight = service.resources.displayMetrics.heightPixels
    val renderHeightBudget = minOf(maxOf(minHeight * 6, dpToPx(256)), screenHeight * 2)
    val maxHeight = View.MeasureSpec.makeMeasureSpec(renderHeightBudget, View.MeasureSpec.AT_MOST)
    container.measure(exactWidth, maxHeight)
    val actualHeight = maxOf(container.measuredHeight, minHeight)
    container.layout(0, 0, width, actualHeight)

    val bitmap = Bitmap.createBitmap(width, actualHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    container.draw(canvas)
    return bitmap
  }

  private fun buildOverlayTextView(
    translatedText: String,
    fgColor: Int,
  ): TextView {
    return TextView(service).apply {
      text = translatedText
      setTextColor(fgColor)
      typeface = Typeface.DEFAULT
      setAutoSizeTextTypeUniformWithConfiguration(8, 48, 1, TypedValue.COMPLEX_UNIT_SP)
    }
  }

  fun showBitmapOverlay(
    bitmap: Bitmap,
    bounds: Rect,
  ) {
    val imageView = ImageView(service)
    imageView.setImageBitmap(bitmap)
    imageView.scaleType = ImageView.ScaleType.FIT_XY
    imageView.setOnClickListener { removeTranslationOverlays() }

    val params =
      WindowManager.LayoutParams(
        bounds.width(),
        bounds.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.START
    params.x = bounds.left
    params.y = bounds.top

    windowManager.addView(imageView, params)
    translationOverlays.add(imageView)
  }

  fun showCenteredLoading() {
    val container = FrameLayout(service)
    val bg = GradientDrawable()
    bg.setColor(Color.parseColor("#CC303030"))
    bg.cornerRadius = dpToPx(16).toFloat()
    container.background = bg

    val size = dpToPx(48)
    val progress = ProgressBar(service)
    val lp = FrameLayout.LayoutParams(size, size)
    lp.gravity = Gravity.CENTER
    val pad = dpToPx(16)
    container.setPadding(pad, pad, pad, pad)
    container.addView(progress, lp)

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.CENTER

    windowManager.addView(container, params)
    translationOverlays.add(container)
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

  fun hasTranslationOverlays(): Boolean = translationOverlays.isNotEmpty()

  fun removeTranslationOverlays() {
    for (view in translationOverlays) {
      try {
        windowManager.removeView(view)
      } catch (_: Exception) {
      }
    }
    translationOverlays.clear()
  }

  fun getStatusBarHeight(): Int {
    val resourceId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resourceId > 0) service.resources.getDimensionPixelSize(resourceId) else dpToPx(24)
  }

  fun getNavBarHeight(): Int {
    val resourceId = service.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (resourceId > 0) service.resources.getDimensionPixelSize(resourceId) else 0
  }

  fun cleanup() {
    handler.removeCallbacksAndMessages(null)
  }

  internal fun dpToPx(dp: Int): Int = (dp * service.resources.displayMetrics.density).toInt()
}
