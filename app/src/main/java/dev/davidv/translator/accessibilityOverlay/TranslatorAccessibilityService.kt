package dev.davidv.translator.accessibilityOverlay

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import dev.davidv.translator.CatalogRepository
import dev.davidv.translator.LanguageAvailabilityState
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.OverlayLanguageSelection
import dev.davidv.translator.OverlayLanguageSide
import dev.davidv.translator.R
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslatorApplication
import dev.davidv.translator.overlayChrome.OverlayInsets
import dev.davidv.translator.overlayPickerLanguages
import dev.davidv.translator.screenTranslate.ScreenTranslateService
import dev.davidv.translator.ui.components.DetectedRegions
import dev.davidv.translator.ui.components.ImageWordSelection
import dev.davidv.translator.ui.components.SelectionSurfaceState
import dev.davidv.translator.wordSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The manifest enables the service only from R (values-v30/bools.xml), where takeScreenshot exists.
@RequiresApi(Build.VERSION_CODES.R)
class TranslatorAccessibilityService : AccessibilityService() {
  private val tag = "TranslatorA11y"
  private lateinit var windowManager: WindowManager
  private var active = false
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private data class TranslatedScreen(
    val original: Bitmap,
    val translated: Bitmap,
    val words: ImageWordSelection,
    val region: Rect,
  )

  private var selectMode = false
  private var lastResult: TranslatedScreen? = null
  private val surfaceState = SelectionSurfaceState()

  private lateinit var settingsManager: SettingsManager
  private lateinit var catalogRepository: CatalogRepository
  private lateinit var languageMetadataManager: LanguageMetadataManager
  private lateinit var translationCoordinator: TranslationCoordinator
  private lateinit var selection: OverlayLanguageSelection

  lateinit var ui: OverlayUI
    private set

  private val disableReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        deactivate()
        disableSelf()
      }
    }

  // Hide the launcher bubble while live screen-translate runs so we don't stack
  // two bubbles; restore it when live stops (unless a still capture is active).
  private val liveStateReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        val live = intent?.getBooleanExtra(ScreenTranslateService.EXTRA_LIVE_ACTIVE, false) ?: false
        if (live) {
          ui.removeFloatingButton()
        } else if (!active) {
          ui.restoreFloatingButton()
        }
      }
    }

  companion object {
    const val ACTION_DISABLE = "dev.davidv.translator.DISABLE_ACCESSIBILITY"
    private const val SCREENSHOT_RENDER_DELAY_MS = 120L
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    Log.d(tag, "Service connected")
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    val app = application as TranslatorApplication
    app.ensureServicesInitialized()
    settingsManager = app.settingsManager
    catalogRepository = app.catalogRepository
    languageMetadataManager = app.languageMetadataManager
    translationCoordinator = app.translationCoordinator
    selection = OverlayLanguageSelection.initial(LanguageAvailabilityState.of(catalogRepository.catalog.value), settingsManager.settings.value)

    ui = OverlayUI(this, windowManager)
    // This service outlives any one overlay use, so a default target changed in settings must
    // reach it; the most recent explicit choice wins over an earlier pick in the overlay.
    serviceScope.launch {
      settingsManager.settings
        .map { it.defaultTargetLanguageCode }
        .distinctUntilChanged()
        .drop(1)
        .collect {
          val availability = LanguageAvailabilityState.of(catalogRepository.catalog.value)
          applySelection(selection.withTarget(OverlayLanguageSelection.defaults(availability, settingsManager.settings.value).target))
        }
    }

    androidx.core.content.ContextCompat.registerReceiver(
      this,
      disableReceiver,
      IntentFilter(ACTION_DISABLE),
      androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    androidx.core.content.ContextCompat.registerReceiver(
      this,
      liveStateReceiver,
      IntentFilter(ScreenTranslateService.ACTION_LIVE_STATE),
      androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
    )

    serviceInfo = serviceInfo.apply { eventTypes = 0 }

    ui.showFloatingButton()
  }

  // Scroll/click events from other packages mean the screen content actually changed, so the
  // stored capture is stale. The touch watcher's clear is visual-only (a touch-down on our own
  // toolbar also triggers it) and must not invalidate `lastResult` — select mode reuses it.
  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null || event.packageName == packageName) return
    when (event.eventType) {
      AccessibilityEvent.TYPE_VIEW_SCROLLED,
      AccessibilityEvent.TYPE_VIEW_CLICKED,
      -> {
        lastResult = null
        ui.removeTranslationOverlays()
      }
    }
  }

  override fun onInterrupt() {
    Log.d(tag, "Service interrupted")
  }

  override fun onDestroy() {
    try {
      unregisterReceiver(disableReceiver)
    } catch (_: Exception) {
    }
    try {
      unregisterReceiver(liveStateReceiver)
    } catch (_: Exception) {
    }
    deactivate()
    ui.removeFloatingButton()
    ui.dismissMenu()
    ui.cleanup()
    serviceScope.cancel()
    super.onDestroy()
  }

  fun activate() {
    if (active) return
    active = true
    serviceInfo = serviceInfo.apply { eventTypes = liveEventTypes() }
    ui.removeFloatingButton()
    ui.removeTranslationOverlays()
    ui.showBorderWave()
    ui.showToolbar(selection)
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      if (active) {
        handleFullScreenOcr()
      }
    }
  }

  fun deactivate() {
    active = false
    selectMode = false
    serviceInfo = serviceInfo.apply { eventTypes = 0 }
    ui.removeSelectSurface()
    surfaceState.clear()
    lastResult = null
    ui.removeBorderWave()
    ui.removeToolbar()
    ui.removeTranslationOverlays()
    ui.dismissMenu()
    ui.restoreFloatingButton()
  }

  fun toggleSelectMode() {
    if (selectMode) {
      exitSelectMode()
      return
    }
    selectMode = true
    serviceInfo = serviceInfo.apply { eventTypes = 0 }
    ui.setSelectModeUi(true)
    val res = lastResult
    if (res != null) {
      ui.removeTranslationOverlays()
      surfaceState.showResult(res.translated, res.original, res.words)
      ui.showSelectSurface(surfaceState, res.region) { onSelectSurfaceDismissed() }
    } else {
      handleFullScreenOcr()
    }
  }

  // The live overlay goes up before the frozen dialog comes down: later-added windows stack on
  // top, so the swap happens under an always-covered region instead of flashing the app through.
  private fun exitSelectMode() {
    selectMode = false
    if (active) {
      serviceInfo = serviceInfo.apply { eventTypes = liveEventTypes() }
      lastResult?.let { ui.showBitmapOverlay(it.translated, it.region) }
    }
    ui.removeSelectSurface()
    surfaceState.clear()
    ui.setSelectModeUi(false)
  }

  private fun liveEventTypes(): Int = AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_VIEW_CLICKED

  private fun onSelectSurfaceDismissed() {
    if (selectMode) exitSelectMode()
  }

  fun toggleFlipOriginal() {
    if (!selectMode) return
    ui.setFlipActive(surfaceState.toggleShowOriginal())
  }

  fun swapLanguages() {
    val swapped = selection.swapped(catalogRepository.catalog.value::canSwapLanguages) ?: return
    applySelection(swapped)
  }

  fun showLanguagePicker(side: OverlayLanguageSide) {
    val availability = LanguageAvailabilityState.of(catalogRepository.catalog.value)
    val languages = overlayPickerLanguages(availability, languageMetadataManager.metadata.value, side)
    ui.showLanguagePicker(side, selection, languages) { applySelection(it) }
  }

  fun toggleJapaneseOcrMode() {
    applySelection(selection.withNextReadingOrder())
  }

  private fun applySelection(next: OverlayLanguageSelection) {
    selection = next
    settingsManager.update { it.copy(overlayLanguages = next.remembered()) }
    ui.updateToolbarState(selection)
    if (active) {
      handleFullScreenOcr()
    }
  }

  fun showDotsMenu() {
    ui.showDotsMenu()
  }

  fun handleFullScreenOcr() {
    // In select mode the frozen surface covers the screen, so a fresh screenshot would capture
    // ourselves — retranslate the stored capture instead (language/reading-order changes).
    val res = lastResult
    if (selectMode && ui.hasSelectSurface() && res != null) {
      surfaceState.showProcessing(res.original)
      serviceScope.launch {
        translateRegionBitmap(res.original, res.region)
      }
      return
    }
    ui.removeTranslationOverlays()

    val content = OverlayInsets.contentBounds(windowManager)
    val region =
      Rect(
        content.left,
        content.top + ui.dpToPx(48),
        content.right,
        content.bottom,
      )

    // Let any open picker/menu finish dismissing before grabbing the screen,
    // otherwise the popup lands in the screenshot.
    ui.dismissMenu()
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      captureFullScreen(region)
    }, SCREENSHOT_RENDER_DELAY_MS)
  }

  private fun captureFullScreen(region: Rect) {
    takeScreenshot(
      Display.DEFAULT_DISPLAY,
      mainExecutor,
      object : TakeScreenshotCallback {
        override fun onSuccess(screenshot: ScreenshotResult) {
          val hwBitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
          screenshot.hardwareBuffer.close()
          if (hwBitmap == null) return
          val fullBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
          hwBitmap.recycle()

          val cropLeft = region.left.coerceIn(0, fullBitmap.width - 1)
          val cropTop = region.top.coerceIn(0, fullBitmap.height - 1)
          val cropWidth = region.width().coerceAtMost(fullBitmap.width - cropLeft)
          val cropHeight = region.height().coerceAtMost(fullBitmap.height - cropTop)
          if (cropWidth <= 0 || cropHeight <= 0) {
            fullBitmap.recycle()
            return
          }

          val croppedBitmap = Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropWidth, cropHeight)
          fullBitmap.recycle()

          surfaceState.regions.value = null
          ui.showScanOverlay(surfaceState, region)
          serviceScope.launch {
            translateRegionBitmap(croppedBitmap, region)
          }
        }

        override fun onFailure(errorCode: Int) {
          Log.w(tag, "Screenshot failed: $errorCode")
        }
      },
    )
  }

  private suspend fun translateRegionBitmap(
    bitmap: Bitmap,
    region: Rect,
  ) {
    val requested = selection
    var ocrUnavailable = false
    val result =
      withContext(Dispatchers.IO) {
        translationCoordinator.translateImageWithOverlay(
          requested.source,
          requested.target,
          bitmap,
          onOcrUnavailable = { ocrUnavailable = true },
          onDetectedRegions = { boxes, w, h ->
            surfaceState.regions.value = DetectedRegions(w, h, boxes)
          },
        )
      }

    ui.removeTranslationOverlays()
    if (result == null) {
      if (selectMode) surfaceState.processing.value = false
      if (ocrUnavailable) {
        ui.showOverlayMessage(getString(R.string.ocr_models_missing))
      }
      return
    }

    val words = result.wordSelection(requested.target)
    lastResult = TranslatedScreen(original = bitmap, translated = result.correctedBitmap, words = words, region = region)
    if (selectMode) {
      surfaceState.showResult(result.correctedBitmap, bitmap, words)
      if (!ui.hasSelectSurface()) {
        ui.showSelectSurface(surfaceState, region) { onSelectSurfaceDismissed() }
      }
    } else {
      ui.showBitmapOverlay(result.correctedBitmap, region)
    }
  }
}
