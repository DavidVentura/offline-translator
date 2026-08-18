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
import dev.davidv.translator.FilePathManager
import dev.davidv.translator.ImageProcessor
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageDetector
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.OverlayTextTranslationHelper
import dev.davidv.translator.R
import dev.davidv.translator.ReadingOrder
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.SpeechService
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslationService
import dev.davidv.translator.screenTranslate.ScreenTranslateService
import dev.davidv.translator.ui.components.DetectedRegions
import dev.davidv.translator.ui.components.ImageWordSelection
import dev.davidv.translator.ui.components.SelectionSurfaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslatorAccessibilityService : AccessibilityService() {
  private val tag = "TranslatorA11y"
  private lateinit var windowManager: WindowManager
  private var active = false
  var forcedSourceLanguage: Language? = null
  var forcedTargetLanguage: Language? = null
  var isAutoSource: Boolean = true
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
  private lateinit var imageProcessor: ImageProcessor
  private lateinit var translationCoordinator: TranslationCoordinator
  private var ocrReadingOrder: ReadingOrder? = null
  private lateinit var overlayTextTranslationHelper: OverlayTextTranslationHelper
  lateinit var langStateManager: LanguageStateManager
    private set

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

    settingsManager = SettingsManager(this)
    val filePathManager = FilePathManager(this, settingsManager.settings)
    langStateManager = LanguageStateManager(serviceScope, filePathManager, null)
    val languageDetector = LanguageDetector(langStateManager::languageByCode)
    imageProcessor = ImageProcessor(this, filePathManager)

    serviceScope.launch {
      langStateManager.catalog.collect { catalog ->
        if (catalog == null) return@collect
        val translationService = TranslationService(settingsManager, filePathManager)
        val speechService = SpeechService(settingsManager, filePathManager)
        translationCoordinator = TranslationCoordinator(translationService, speechService, languageDetector, imageProcessor, settingsManager)
        val languagesFlow = kotlinx.coroutines.flow.MutableStateFlow(catalog.languageList)
        overlayTextTranslationHelper =
          OverlayTextTranslationHelper(
            langStateManager = langStateManager,
            languageMetadataManager = LanguageMetadataManager(this@TranslatorAccessibilityService, languagesFlow),
          )
      }
    }

    ui = OverlayUI(this, windowManager, settingsManager)

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
    langStateManager.refreshLanguageAvailability()
    ui.removeFloatingButton()
    ui.removeTranslationOverlays()
    ui.showBorderWave()
    ui.showToolbar(forcedSourceLanguage, forcedTargetLanguage, currentReadingOrderFor(forcedSourceLanguage), isAutoSource)
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
    val oldSource = forcedSourceLanguage ?: return
    val oldTarget = forcedTargetLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: return
    if (!langStateManager.canSwapLanguages(oldSource, oldTarget)) return
    isAutoSource = false
    forcedSourceLanguage = oldTarget
    forcedTargetLanguage = oldSource
    syncReadingOrderForSource()
    ui.updateToolbarState(forcedSourceLanguage, forcedTargetLanguage, currentReadingOrderFor(forcedSourceLanguage), isAutoSource)
    if (active) {
      handleFullScreenOcr()
    }
  }

  fun showLanguagePicker(isSource: Boolean) {
    serviceScope.launch {
      langStateManager.refreshLanguageAvailability()
      val availableLangs = overlayTextTranslationHelper.awaitAvailableLanguages(isSource)
      ui.showLanguagePicker(isSource, availableLangs) { lang ->
        if (isSource) {
          if (lang == null) {
            isAutoSource = true
          } else {
            isAutoSource = false
            forcedSourceLanguage = lang
          }
          syncReadingOrderForSource()
        } else {
          forcedTargetLanguage = lang
        }
        ui.updateToolbarState(forcedSourceLanguage, forcedTargetLanguage, currentReadingOrderFor(forcedSourceLanguage), isAutoSource)
        if (active) {
          handleFullScreenOcr()
        }
      }
    }
  }

  fun toggleJapaneseOcrMode() {
    if (forcedSourceLanguage?.code != "ja") return
    ocrReadingOrder =
      when (ocrReadingOrder) {
        null -> ReadingOrder.TOP_TO_BOTTOM_RIGHT_TO_LEFT
        ReadingOrder.TOP_TO_BOTTOM_RIGHT_TO_LEFT -> ReadingOrder.LEFT_TO_RIGHT
        ReadingOrder.LEFT_TO_RIGHT -> null
      }
    ui.updateToolbarState(forcedSourceLanguage, forcedTargetLanguage, ocrReadingOrder, isAutoSource)
    if (active) {
      handleFullScreenOcr()
    }
  }

  fun showDotsMenu() {
    ui.showDotsMenu()
  }

  fun handleFullScreenOcr() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

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

    val sourceLang = ocrSourceLanguage()
    if (!isAutoSource && sourceLang == null) {
      ui.showOverlayMessage("Set source language first")
      return
    }

    val windowBounds = windowManager.currentWindowMetrics.bounds
    val region =
      Rect(
        0,
        ui.getStatusBarHeight() + ui.dpToPx(48),
        windowBounds.width(),
        windowBounds.height() - ui.getNavBarHeight(),
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
    val sourceLang = ocrSourceLanguage()
    val targetLang = forcedTargetLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: return
    val ocrSourceLang = sourceLang ?: targetLang

    var ocrUnavailable = false
    val result =
      withContext(Dispatchers.IO) {
        translationCoordinator.translateImageWithOverlay(
          ocrSourceLang,
          targetLang,
          bitmap,
          onMessage = {},
          readingOrder = currentReadingOrderFor(sourceLang),
          isAutoSource = isAutoSource,
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

    val words =
      ImageWordSelection(
        imageWidth = result.metadata.width.toInt(),
        imageHeight = result.metadata.height.toInt(),
        sourceWords = result.metadata.sourceWords,
        translatedWords = result.translatedWords,
      )
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

  private fun ocrSourceLanguage(): Language? =
    forcedSourceLanguage ?: settingsManager.settings.value.defaultSourceLanguageCode?.let {
      langStateManager.languageByCode(it)
    }

  private fun currentReadingOrderFor(language: Language?): ReadingOrder? =
    if (language?.code == "ja") {
      ocrReadingOrder
    } else {
      null
    }

  private fun syncReadingOrderForSource() {
    if (forcedSourceLanguage?.code != "ja") {
      ocrReadingOrder = null
    }
  }
}
