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
import dev.davidv.translator.OCRService
import dev.davidv.translator.OverlayColors
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslationResult
import dev.davidv.translator.TranslationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslatorAccessibilityService : AccessibilityService() {
  private val tag = "TranslatorA11y"
  private lateinit var windowManager: WindowManager
  private var active = false
  var forcedSourceLanguage: Language? = null
  var forcedTargetLanguage: Language? = null
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private lateinit var settingsManager: SettingsManager
  private lateinit var translationCoordinator: TranslationCoordinator
  private lateinit var langStateManager: LanguageStateManager
  private lateinit var languageMetadataManager: LanguageMetadataManager

  lateinit var ui: OverlayUI
    private set
  lateinit var input: OverlayInput
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

  companion object {
    const val ACTION_DISABLE = "dev.davidv.translator.DISABLE_ACCESSIBILITY"
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    Log.d(tag, "Service connected")
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    settingsManager = SettingsManager(this)
    val filePathManager = FilePathManager(this, settingsManager.settings)
    val translationService = TranslationService(settingsManager, filePathManager)
    val languageDetector = LanguageDetector()
    val imageProcessor = ImageProcessor(this, OCRService(filePathManager))
    translationCoordinator = TranslationCoordinator(this, translationService, languageDetector, imageProcessor, settingsManager, false)
    langStateManager = LanguageStateManager(serviceScope, filePathManager, null)
    languageMetadataManager = LanguageMetadataManager(this)

    ui = OverlayUI(this, windowManager, settingsManager)
    input = OverlayInput(this, windowManager, ui, settingsManager)

    androidx.core.content.ContextCompat.registerReceiver(
      this,
      disableReceiver,
      IntentFilter(ACTION_DISABLE),
      androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
    )

    serviceInfo = serviceInfo.apply { eventTypes = 0 }

    ui.showFloatingButton()
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null || !ui.hasTranslationOverlays()) return
    when (event.eventType) {
      AccessibilityEvent.TYPE_VIEW_SCROLLED,
      AccessibilityEvent.TYPE_VIEW_CLICKED,
      -> ui.removeTranslationOverlays()
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
    serviceInfo =
      serviceInfo.apply {
        eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_VIEW_CLICKED
      }
    langStateManager.refreshLanguageAvailability()
    ui.removeFloatingButton()
    ui.removeTranslationOverlays()
    input.showInteractionOverlay()
    ui.showToolbar(forcedSourceLanguage, forcedTargetLanguage)
  }

  fun deactivate() {
    active = false
    serviceInfo = serviceInfo.apply { eventTypes = 0 }
    ui.removeToolbar()
    input.removeTouchInterceptOverlay()
    ui.removeTranslationOverlays()
    input.removeSelectionRect()
    ui.dismissMenu()
    ui.restoreFloatingButton()
  }

  fun swapLanguages() {
    val oldSource = forcedSourceLanguage
    val oldTarget = forcedTargetLanguage ?: settingsManager.settings.value.defaultTargetLanguage
    forcedSourceLanguage = oldTarget
    forcedTargetLanguage = if (oldSource != null) oldSource else null
    ui.updateToolbarLabels(forcedSourceLanguage, forcedTargetLanguage)
  }

  fun showLanguagePicker(isSource: Boolean) {
    val metadata = languageMetadataManager.metadata.value
    val availableLangs =
      langStateManager.languageState.value.availableLanguageMap
        .filterValues { it.translatorFiles && (!isSource || it.ocrFiles) }
        .keys
        .toList()
        .sortedWith(
          compareByDescending<Language> { metadata[it]?.favorite ?: false }
            .thenBy { it.displayName },
        )

    ui.showLanguagePicker(isSource, availableLangs) { lang ->
      if (isSource) {
        forcedSourceLanguage = lang
      } else {
        forcedTargetLanguage = lang
      }
      ui.updateToolbarLabels(forcedSourceLanguage, forcedTargetLanguage)
    }
  }

  fun showDotsMenu() {
    ui.showDotsMenu()
  }

  fun handleRegionCapture(region: Rect) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    val sourceLang = forcedSourceLanguage
    if (sourceLang == null) {
      ui.showOverlayMessage("Set source language first")
      return
    }

    input.removeTouchInterceptOverlay()
    ui.removeToolbar()

    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      takeScreenshot(
        Display.DEFAULT_DISPLAY,
        mainExecutor,
        object : TakeScreenshotCallback {
          override fun onSuccess(screenshot: ScreenshotResult) {
            input.showInteractionOverlay()
            ui.showToolbar(forcedSourceLanguage, forcedTargetLanguage)

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
            val colors = input.sampleColorsFromScreenshot(fullBitmap, region)
            fullBitmap.recycle()

            ui.showLoadingOverlay(region, colors)

            serviceScope.launch {
              translateRegionBitmap(croppedBitmap, region)
            }
          }

          override fun onFailure(errorCode: Int) {
            Log.w(tag, "Screenshot failed: $errorCode")
            input.showInteractionOverlay()
            ui.showToolbar(forcedSourceLanguage, forcedTargetLanguage)
          }
        },
      )
    }, 100)
  }

  private suspend fun translateRegionBitmap(
    bitmap: Bitmap,
    region: Rect,
  ) {
    val sourceLang = forcedSourceLanguage ?: return
    val targetLang = forcedTargetLanguage ?: settingsManager.settings.value.defaultTargetLanguage

    val result =
      withContext(Dispatchers.IO) {
        translationCoordinator.translateImageWithOverlay(sourceLang, targetLang, bitmap) {}
      }

    if (result != null) {
      ui.removeTranslationOverlays()
      ui.showBitmapOverlay(result.correctedBitmap, region)
    } else {
      ui.removeTranslationOverlays()
    }
  }

  fun handleTouchAtPoint(
    x: Int,
    y: Int,
  ) {
    val root = rootInActiveWindow
    if (root == null) {
      Log.w(tag, "No active window")
      return
    }

    val node = input.findNodeAtPoint(root, x, y)
    if (node == null) {
      Log.d(tag, "No text node at ($x, $y)")
      return
    }

    val text = input.extractText(node)
    if (text.isNullOrBlank()) {
      Log.d(tag, "No text at ($x, $y)")
      return
    }

    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    Log.d(tag, "Found text: '$text' at $bounds")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      takeScreenshot(
        Display.DEFAULT_DISPLAY,
        mainExecutor,
        object : TakeScreenshotCallback {
          override fun onSuccess(screenshot: ScreenshotResult) {
            val hwBitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
            screenshot.hardwareBuffer.close()
            if (hwBitmap != null) {
              val swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
              hwBitmap.recycle()
              val colors = input.sampleColorsFromScreenshot(swBitmap, bounds)
              swBitmap.recycle()
              translateAndShow(text, bounds, colors)
            } else {
              translateAndShow(text, bounds, null)
            }
          }

          override fun onFailure(errorCode: Int) {
            Log.w(tag, "Screenshot failed: $errorCode")
            translateAndShow(text, bounds, null)
          }
        },
      )
    } else {
      translateAndShow(text, bounds, null)
    }
  }

  private fun translateAndShow(
    text: String,
    bounds: Rect,
    colors: OverlayColors?,
  ) {
    ui.removeTranslationOverlays()
    ui.showLoadingOverlay(bounds, colors)

    serviceScope.launch {
      langStateManager.languageState.first { !it.isChecking }
      val langs =
        langStateManager.languageState.value.availableLanguageMap
          .filterValues { it.translatorFiles }
          .keys.toList()

      val from = forcedSourceLanguage ?: translationCoordinator.detectLanguageRobust(text, null, langs)
      if (from == null) {
        ui.removeTranslationOverlays()
        ui.showOverlayMessage("Could not detect language — set source language manually")
        return@launch
      }

      val to = forcedTargetLanguage ?: settingsManager.settings.value.defaultTargetLanguage
      if (from == to) {
        ui.removeTranslationOverlays()
        ui.showOverlayMessage("Already in ${to.displayName}")
        return@launch
      }

      when (val result = translationCoordinator.translateText(from, to, text)) {
        is TranslationResult.Success -> {
          ui.removeTranslationOverlays()
          ui.showTranslationOverlay(result.result.translated, bounds, colors)
        }
        is TranslationResult.Error -> {
          Log.e(tag, "Translation error: ${result.message}")
          ui.removeTranslationOverlays()
        }
      }
    }
  }
}
