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

package dev.davidv.translator.screenTranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.IntentCompat
import dev.davidv.translator.LanguageAvailabilityState
import dev.davidv.translator.OverlayLanguageSelection
import dev.davidv.translator.OverlayLanguageSide
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorApplication
import dev.davidv.translator.overlayChrome.FloatingBubble
import dev.davidv.translator.overlayChrome.NormalizedRegion
import dev.davidv.translator.overlayChrome.OverlayChromeFactory
import dev.davidv.translator.overlayChrome.OverlayInsets
import dev.davidv.translator.overlayChrome.OverlayMenuHost
import dev.davidv.translator.overlayChrome.OverlayMenuManager
import dev.davidv.translator.overlayChrome.RegionSelectView
import dev.davidv.translator.overlayPickerLanguages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 *  Foreground service for "translate screen": holds the [MediaProjection],
 *  mirrors the display into a [ScreenCaptureGlWorker]'s capture surface, and
 *  floats a translucent overlay window. The overlay is a hardware Canvas
 *  [ScreenOverlayView] (not a SurfaceView) at α≈0.79 so it composites through
 *  the window — which both dims it under the untrusted-touch cap (taps fall
 *  through to the app underneath) and is non-secure (so the capture isn't
 *  blacked out). Stop is a notification action. Feedback (the overlay landing
 *  in the capture) is accepted for this iteration.
 */
class ScreenTranslateService : Service() {
  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  private var projection: MediaProjection? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var screenTracker: uniffi.bindings.LiveScreenTracker? = null
  private var overlayView: GpuOverlayTextureView? = null
  private var worker: ScreenCaptureGlWorker? = null
  private var windowManager: WindowManager? = null
  private var stopped = false

  private var displayManager: DisplayManager? = null

  // Live control state, mutated from the bubble menu and re-applied to the tracker.
  private var selection: OverlayLanguageSelection? = null
  private var currentRegion: NormalizedRegion? = null
  private var userPaused = false

  private var menuManager: OverlayMenuManager? = null
  private var bubble: FloatingBubble? = null
  private var regionView: View? = null
  private var toolbarView: View? = null
  private var sourceLabelView: TextView? = null
  private var targetLabelView: TextView? = null
  private var pauseIconView: ImageView? = null

  // Display size the current capture was built for; a rotation changes these and
  // triggers an in-place resize (the VirtualDisplay is fixed to its creation
  // dimensions, so a stale portrait capture letterboxes a landscape screen to an
  // unreadable strip — but on Android 14+ it can't be recreated, only resized).
  private var lastW = 0
  private var lastH = 0

  private val displayListener =
    object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) {}

      override fun onDisplayRemoved(displayId: Int) {}

      override fun onDisplayChanged(displayId: Int) {
        if (stopped || displayId != Display.DEFAULT_DISPLAY) return
        val wm = windowManager ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        if (w == lastW && h == lastH) return
        Log.i(TAG, "display changed (${lastW}x$lastH → ${w}x$h) → resize capture")
        resizeCapture(w, h, metrics.densityDpi)
      }
    }

  private val projectionCallback =
    object : MediaProjection.Callback() {
      override fun onStop() {
        Log.i(TAG, "MediaProjection stopped")
        stopEverything()
      }
    }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    if (intent == null || intent.action == ACTION_STOP) {
      stopEverything()
      return START_NOT_STICKY
    }

    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
    val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
    if (resultCode == 0 || data == null) {
      Log.e(TAG, "Missing projection token; stopping")
      stopEverything()
      return START_NOT_STICKY
    }

    startForegroundNotification()

    val app = applicationContext as TranslatorApplication
    val catalog = app.catalogRepository.catalog.value
    if (!catalog.ocrEngineReady()) {
      // Without the detector pack every frame fails OCR and the overlay just
      // sits there empty — refuse to start and say why (issue #246).
      Log.e(TAG, "OCR detector pack not installed; stopping")
      Toast.makeText(this, getString(R.string.ocr_models_missing), Toast.LENGTH_LONG).show()
      stopEverything()
      return START_NOT_STICKY
    }

    val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val proj = pm.getMediaProjection(resultCode, data)
    if (proj == null) {
      Log.e(TAG, "getMediaProjection returned null; stopping")
      stopEverything()
      return START_NOT_STICKY
    }
    proj.registerCallback(projectionCallback, null)
    projection = proj

    val requested =
      parseSelection(
        app,
        sourceCode = intent.getStringExtra(EXTRA_SOURCE_LANG),
        targetCode = intent.getStringExtra(EXTRA_TARGET_LANG),
        isAutoSource = intent.getBooleanExtra(EXTRA_AUTO_SOURCE, true),
      )
    if (requested == null) {
      stopEverything()
      return START_NOT_STICKY
    }

    val tracker = uniffi.bindings.LiveScreenTracker(catalog.planarHandle())
    screenTracker = tracker
    applySelection(requested)

    setupOverlayAndCapture(tracker, proj)
    broadcastLiveState(active = true)

    if (displayManager == null) {
      val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
      displayManager = dm
      dm.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    return START_NOT_STICKY
  }

  private fun parseSelection(
    app: TranslatorApplication,
    sourceCode: String?,
    targetCode: String?,
    isAutoSource: Boolean,
  ): OverlayLanguageSelection? {
    val catalog = app.catalogRepository.catalog.value
    val defaults = OverlayLanguageSelection.defaults(LanguageAvailabilityState.of(catalog), app.settingsManager.settings.value)
    // Target: the caller's choice (assistant selection) → app default → en.
    val target = targetCode?.let { catalog.languageByCode(it) } ?: defaults.target
    if (isAutoSource) {
      return defaults.withTarget(target)
    }
    val source = sourceCode?.let { catalog.languageByCode(it) }
    if (source == null) {
      Log.e(TAG, "fixed source requested with unknown language '$sourceCode'; stopping")
      return null
    }
    return defaults.withTarget(target).withSource(source)
  }

  /** Push the current language selection into the tracker (live, no restart). */
  private fun applySelection(next: OverlayLanguageSelection) {
    selection = next
    val tracker = checkNotNull(screenTracker) { "language selection applied without a tracker" }
    // A forced source skips the script classifier; under auto the `from` value is only a
    // placeholder the tracker ignores.
    val from = next.sourceLanguage ?: next.target
    tracker.setLanguages(from.code, next.target.code, next.isAutoSource)
    Log.i(TAG, "languages: from=${from.code} to=${next.target.code} auto=${next.isAutoSource}")
    OverlayChromeFactory.showSelection(sourceLabelView, targetLabelView, null, null, next)
  }

  private fun overlayWindowType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      @Suppress("DEPRECATION")
      WindowManager.LayoutParams.TYPE_PHONE
    }

  private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

  private fun setupOverlayAndCapture(
    tracker: uniffi.bindings.LiveScreenTracker,
    proj: MediaProjection,
  ) {
    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    windowManager = wm

    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealMetrics(metrics)
    val w = metrics.widthPixels
    val h = metrics.heightPixels
    val dpi = metrics.densityDpi
    lastW = w
    lastH = h

    val view = GpuOverlayTextureView(this)
    overlayView = view

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
      )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      params.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
    // Android 12+ untrusted-touch rule: a tap passing through an overlay owned
    // by another app is discarded if the overlay's opacity exceeds ~0.8
    // (maximum_obscuring_opacity_for_touch). Sit just under the cap — and
    // because the TextureView composites through the window (not a SurfaceView,
    // a separate full-opacity layer), the window alpha governs it, so taps fall
    // through. With an accessibility service the app is trusted and the cap
    // lifts; the only change is raising WINDOW_ALPHA — the GPU render path is
    // identical, so there is no second code path for that state.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      params.alpha = WINDOW_ALPHA
    }
    wm.addView(view, params)
    setupControls()

    val pipelinePtr = tracker.rawAddressForJni().toLong()
    val captureWorker = ScreenCaptureGlWorker(w, h)
    captureWorker.pipelinePtr = pipelinePtr
    worker = captureWorker
    // The worker turns the TextureView's SurfaceTexture into the EGL window
    // surface it presents into; hand each lifecycle edge straight to it.
    view.onSurfaceTexture = { st -> captureWorker.setOutputSurfaceTexture(st) }
    captureWorker.start()
    Log.i(TAG, "setup: ${w}x$h @${dpi}dpi, pipelinePtr=$pipelinePtr")

    scope.launch {
      val surface = withContext(Dispatchers.IO) { captureWorker.awaitSourceSurface() }
      if (stopped) return@launch
      if (surface == null) {
        Log.e(TAG, "GL capture surface never became ready; stopping")
        stopEverything()
        return@launch
      }
      // Size the SurfaceTexture now that it exists; a VirtualDisplay won't
      // produce frames into a 0×0 buffer.
      captureWorker.setSourceBufferSize(w, h)
      virtualDisplay =
        proj.createVirtualDisplay(
          "translator-screen",
          w,
          h,
          dpi,
          DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
          surface,
          null,
          null,
        )
      Log.i(TAG, "VirtualDisplay created ${w}x$h, surface=$surface")
    }
  }

  /** The draggable bubble + its popup menu, pickers and region editor. One shared
   *  control surface (the `overlayChrome` widgets) over touchable overlay windows;
   *  the render overlay stays pass-through. */
  private fun setupControls() {
    val wm = windowManager ?: return
    val type = overlayWindowType()

    menuManager =
      OverlayMenuManager(
        this,
        ::dpToPx,
        object : OverlayMenuHost {
          override fun addDismissLayer(view: View) {
            val params =
              WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
              )
            params.windowAnimations = 0
            wm.addView(view, params)
          }

          override fun addMenuView(view: View) {
            // Open on the bubble's side, vertically near it (the window manager
            // clamps it back on-screen if the bubble sits near an edge).
            val onRight = bubble?.isOnRightSide() ?: true
            val params =
              WindowManager.LayoutParams(
                dpToPx(200),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
              )
            params.gravity = Gravity.TOP or (if (onRight) Gravity.END else Gravity.START)
            params.x = dpToPx(8)
            params.y = ((bubble?.anchorTop() ?: dpToPx(120)) - dpToPx(20)).coerceAtLeast(dpToPx(8))
            params.windowAnimations = 0
            wm.addView(view, params)
          }

          override fun addPickerView(view: View) {
            val params =
              WindowManager.LayoutParams(
                dpToPx(250),
                dpToPx(400),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
              )
            params.gravity = Gravity.CENTER
            params.windowAnimations = 0
            wm.addView(view, params)
          }

          override fun removeMenuChild(view: View) {
            runCatching { wm.removeView(view) }
          }
        },
      )

    bubble = FloatingBubble(this, wm, type, ::dpToPx) { showToolbar() }.also { it.show() }
  }

  /** Tap-the-bubble expands the collapsed dot into the shared flat toolbar (the
   *  same widget the still paths use): both language pills, region, pause, and ✕
   *  (stop). Tapping outside collapses back to the dot without stopping. */
  private fun showToolbar() {
    val wm = windowManager ?: return
    if (toolbarView != null) return
    val current = checkNotNull(selection) { "toolbar shown before languages were configured" }
    bubble?.setShown(false)

    val views =
      OverlayChromeFactory.createLanguageToolbar(
        context = this,
        dpToPx = ::dpToPx,
        selection = current,
        onClose = { stopEverything() },
        onSourceClick = { showPicker(OverlayLanguageSide.SOURCE) },
        onSwap = { swapLiveLanguages() },
        onTargetClick = { showPicker(OverlayLanguageSide.TARGET) },
        onRegionClick = {
          hideToolbar()
          showRegionEditor()
        },
        onPauseClick = {
          toggleUserPause()
          OverlayChromeFactory.setPauseIconState(pauseIconView, userPaused)
        },
        isPaused = userPaused,
      )
    sourceLabelView = views.sourceLabel
    targetLabelView = views.targetLabel
    pauseIconView = views.pauseIcon

    // A touch outside the toolbar strip collapses it back to the dot AND still
    // reaches the app underneath (WATCH_OUTSIDE_TOUCH + the implied NOT_TOUCH_MODAL),
    // so the user can keep scrolling/dragging — no full-screen catcher that would
    // swallow the gesture.
    views.root.setOnTouchListener { _, event ->
      if (event.action == MotionEvent.ACTION_OUTSIDE) {
        hideToolbar()
        true
      } else {
        false
      }
    }

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
      )
    params.gravity = Gravity.TOP or Gravity.START
    params.y = statusBarHeight()
    params.windowAnimations = 0
    wm.addView(views.root, params)
    toolbarView = views.root
  }

  private fun hideToolbar() {
    toolbarView?.let { runCatching { windowManager?.removeView(it) } }
    toolbarView = null
    sourceLabelView = null
    targetLabelView = null
    pauseIconView = null
    bubble?.setShown(true)
  }

  private fun swapLiveLanguages() {
    val current = checkNotNull(selection) { "swap before languages were configured" }
    val catalog = (applicationContext as TranslatorApplication).catalogRepository.catalog.value
    val swapped = current.swapped(catalog::canSwapLanguages) ?: return
    chooseSelection(swapped)
  }

  // Only picks made in this overlay are remembered for the next overlay; the pair it was started
  // with came from the caller.
  private fun chooseSelection(next: OverlayLanguageSelection) {
    (applicationContext as TranslatorApplication).settingsManager.update { it.copy(overlayLanguages = next.remembered()) }
    applySelection(next)
  }

  private fun statusBarHeight(): Int {
    val wm = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
    return OverlayInsets.topInset(wm, resources)
  }

  /** Manual pause/resume: freezes OCR + clears the on-screen overlays, without
   *  changing region/languages. Resuming re-acquires from scratch. */
  private fun toggleUserPause() {
    userPaused = !userPaused
    if (userPaused) {
      screenTracker?.clearOverlay()
      worker?.setPaused(true)
      worker?.clearOverlayOutput()
    } else {
      worker?.setPaused(false)
    }
  }

  private fun showPicker(side: OverlayLanguageSide) {
    val mm = menuManager ?: return
    val current = checkNotNull(selection) { "picker opened before languages were configured" }
    val app = applicationContext as TranslatorApplication
    val availability = LanguageAvailabilityState.of(app.catalogRepository.catalog.value)
    val langs = overlayPickerLanguages(availability, app.languageMetadataManager.metadata.value, side)
    // Live screen translate never runs in auto-source mode, so no Auto option.
    mm.showLanguagePicker(side, current, langs, allowAuto = false) { chooseSelection(it) }
  }

  private fun showRegionEditor() {
    val wm = windowManager ?: return
    if (regionView != null) return
    // Freeze live translation while picking the area — otherwise OCR runs on the
    // dimmed editor + our own UI.
    worker?.setPaused(true)
    val view =
      RegionSelectView(
        this,
        ::dpToPx,
        currentRegion,
        onConfirm = { r ->
          currentRegion = r
          screenTracker?.setRegion(uniffi.bindings.ScreenRegion(r.left, r.top, r.right, r.bottom))
          removeRegionEditor()
        },
        onReset = {
          currentRegion = null
          screenTracker?.setRegion(null)
          removeRegionEditor()
        },
        onCancel = { removeRegionEditor() },
      )
    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      params.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
    params.windowAnimations = 0
    wm.addView(view, params)
    regionView = view
  }

  private fun removeRegionEditor() {
    regionView?.let { runCatching { windowManager?.removeView(it) } }
    regionView = null
    // Resume unless a manual pause is in effect (then stay paused).
    worker?.setPaused(userPaused)
  }

  private fun teardownControls() {
    runCatching { menuManager?.dismiss() }
    menuManager = null
    hideToolbar()
    bubble?.remove()
    bubble = null
    removeRegionEditor()
  }

  private fun startForegroundNotification() {
    val channelId = "screen_translate"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(NotificationManager::class.java)
      if (nm.getNotificationChannel(channelId) == null) {
        nm.createNotificationChannel(
          NotificationChannel(
            channelId,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
          ),
        )
      }
    }
    val stopIntent =
      Intent(this, ScreenTranslateService::class.java).apply { action = ACTION_STOP }
    val stopPending =
      android.app.PendingIntent.getService(
        this,
        0,
        stopIntent,
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val notification: Notification =
      androidx.core.app.NotificationCompat.Builder(this, channelId)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.screen_translating_notification))
        .setSmallIcon(R.drawable.ic_translate_button)
        .setOngoing(true)
        .addAction(R.drawable.cancel, "Stop", stopPending)
        .build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  /** Rotation / display-geometry change: resize the capture **in place**. On
   *  Android 14+ a [MediaProjection] permits only one `createVirtualDisplay`, so
   *  the VirtualDisplay must never be recreated — `resize` it and the worker
   *  instead. The overlay window is MATCH_PARENT, so it follows the rotation and
   *  re-hands its SurfaceTexture (→ the worker recreates its present surface).
   *  Old portrait overlays are dropped; the native monitor rebuilds its lattice
   *  when the canonical dimensions change. */
  private fun resizeCapture(
    w: Int,
    h: Int,
    dpi: Int,
  ) {
    worker?.resize(w, h)
    runCatching { virtualDisplay?.resize(w, h, dpi) }
    runCatching { screenTracker?.clearOverlay() }
    lastW = w
    lastH = h
    Log.i(TAG, "capture resized to ${w}x$h @${dpi}dpi")
  }

  private fun broadcastLiveState(active: Boolean) {
    val intent =
      Intent(ACTION_LIVE_STATE).apply {
        setPackage(packageName)
        putExtra(EXTRA_LIVE_ACTIVE, active)
      }
    sendBroadcast(intent)
  }

  private fun stopEverything() {
    if (stopped) return
    stopped = true
    broadcastLiveState(active = false)
    teardownControls()
    runCatching { displayManager?.unregisterDisplayListener(displayListener) }
    displayManager = null
    runCatching { virtualDisplay?.release() }
    virtualDisplay = null
    runCatching {
      projection?.unregisterCallback(projectionCallback)
      projection?.stop()
    }
    projection = null
    runCatching { worker?.stop() }
    worker = null
    val wm = windowManager
    overlayView?.let { runCatching { wm?.removeView(it) } }
    overlayView = null
    runCatching { screenTracker?.clearOverlay() }
    runCatching { screenTracker?.destroy() }
    screenTracker = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    stopSelf()
  }

  override fun onDestroy() {
    stopEverything()
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "ScreenTranslateSvc"
    private const val NOTIFICATION_ID = 0x5C12

    /** Overlay window opacity. Sits just under the Android 12+ untrusted-touch
     *  cap (~0.8) so taps fall through to the app below; the GPU overlay is
     *  dimmed by this. With an accessibility service the cap lifts and this can
     *  go to 1.0 — the only change for that state, no separate render path. */
    private const val WINDOW_ALPHA = 0.79f
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_DATA = "data"
    const val EXTRA_SOURCE_LANG = "source_lang"
    const val EXTRA_TARGET_LANG = "target_lang"
    const val EXTRA_AUTO_SOURCE = "auto_source"
    const val ACTION_STOP = "dev.davidv.translator.STOP_SCREEN_TRANSLATE"

    /** Broadcast (in-package) so the accessibility service can hide its launcher
     *  bubble while live translate runs — otherwise two bubbles stack. */
    const val ACTION_LIVE_STATE = "dev.davidv.translator.LIVE_STATE"
    const val EXTRA_LIVE_ACTIVE = "live_active"

    fun startIntent(
      context: Context,
      resultCode: Int,
      data: Intent,
      sourceCode: String?,
      targetCode: String?,
      isAutoSource: Boolean,
    ): Intent =
      Intent(context, ScreenTranslateService::class.java).apply {
        putExtra(EXTRA_RESULT_CODE, resultCode)
        putExtra(EXTRA_DATA, data)
        sourceCode?.let { putExtra(EXTRA_SOURCE_LANG, it) }
        targetCode?.let { putExtra(EXTRA_TARGET_LANG, it) }
        putExtra(EXTRA_AUTO_SOURCE, isAutoSource)
      }
  }
}
