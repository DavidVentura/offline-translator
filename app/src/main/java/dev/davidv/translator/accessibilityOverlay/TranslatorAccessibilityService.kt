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
import dev.davidv.bergamot.TokenAlignment
import dev.davidv.translator.BatchAlignedTranslationResult
import dev.davidv.translator.BatchTextTranslator
import dev.davidv.translator.FilePathManager
import dev.davidv.translator.ImageProcessor
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageDetector
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.OCRService
import dev.davidv.translator.OverlayTextTranslationHelper
import dev.davidv.translator.ReadingOrder
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.TranslatedStyledBlock
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslationSegment
import dev.davidv.translator.TranslationService
import dev.davidv.translator.canSwapLanguages
import dev.davidv.translator.clusterFragmentsIntoBlocks
import dev.davidv.translator.mapStylesToSegmentedTranslation
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
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private lateinit var settingsManager: SettingsManager
  private lateinit var ocrService: OCRService
  private lateinit var imageProcessor: ImageProcessor
  private lateinit var translationCoordinator: TranslationCoordinator
  private var ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT
  private var lastOcrBitmap: Bitmap? = null
  private var lastOcrRegion: Rect? = null
  private lateinit var overlayTextTranslationHelper: OverlayTextTranslationHelper
  lateinit var langStateManager: LanguageStateManager
    private set

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
    langStateManager = LanguageStateManager(serviceScope, filePathManager, null)
    val languageDetector = LanguageDetector(langStateManager::languageByCode)
    ocrService = OCRService(filePathManager)
    imageProcessor = ImageProcessor(this, ocrService)

    serviceScope.launch {
      langStateManager.catalog.collect { catalog ->
        if (catalog == null) return@collect
        val translationService = TranslationService(settingsManager, filePathManager, catalog.english)
        translationCoordinator = TranslationCoordinator(translationService, languageDetector, imageProcessor, settingsManager)
        val languagesFlow = kotlinx.coroutines.flow.MutableStateFlow(catalog.languageList)
        overlayTextTranslationHelper =
          OverlayTextTranslationHelper(
            settingsManager = settingsManager,
            batchTextTranslator = BatchTextTranslator(translationCoordinator),
            langStateManager = langStateManager,
            languageMetadataManager = LanguageMetadataManager(this@TranslatorAccessibilityService, languagesFlow),
          )
      }
    }

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
    if (this::ocrService.isInitialized) {
      ocrService.cleanup()
    }
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
    ui.showToolbar(forcedSourceLanguage, forcedTargetLanguage, currentReadingOrderFor(forcedSourceLanguage))
    ui.showBorderWave()
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      if (active) {
        handleTranslateVisible()
      }
    }
  }

  fun deactivate() {
    active = false
    serviceInfo = serviceInfo.apply { eventTypes = 0 }
    ui.removeBorderWave()
    ui.removeToolbar()
    input.removeTouchInterceptOverlay()
    ui.removeTranslationOverlays()
    input.removeSelectionRect()
    ui.dismissMenu()
    ui.restoreFloatingButton()
  }

  fun swapLanguages() {
    val oldSource = forcedSourceLanguage ?: return
    val oldTarget = forcedTargetLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: return
    if (!canSwapLanguages(oldSource, oldTarget)) return
    forcedSourceLanguage = oldTarget
    forcedTargetLanguage = oldSource
    syncReadingOrderForSource()
    ui.updateToolbarState(forcedSourceLanguage, forcedTargetLanguage, currentReadingOrderFor(forcedSourceLanguage))
    if (active) {
      retranslate()
    }
  }

  fun showLanguagePicker(isSource: Boolean) {
    val availableLangs = overlayTextTranslationHelper.availableLanguages(isSource)
    ui.showLanguagePicker(isSource, availableLangs) { lang ->
      if (isSource) {
        forcedSourceLanguage = lang
        syncReadingOrderForSource()
      } else {
        forcedTargetLanguage = lang
      }
      ui.updateToolbarState(forcedSourceLanguage, forcedTargetLanguage, currentReadingOrderFor(forcedSourceLanguage))
      if (active) {
        retranslate()
      }
    }
  }

  fun toggleJapaneseOcrMode() {
    if (forcedSourceLanguage?.code != "ja") return
    ocrReadingOrder =
      when (ocrReadingOrder) {
        ReadingOrder.LEFT_TO_RIGHT -> ReadingOrder.TOP_TO_BOTTOM_LEFT_TO_RIGHT
        ReadingOrder.TOP_TO_BOTTOM_LEFT_TO_RIGHT -> ReadingOrder.LEFT_TO_RIGHT
      }
    ui.updateToolbarState(forcedSourceLanguage, forcedTargetLanguage, ocrReadingOrder)
    if (active) {
      retranslate()
    }
  }

  private fun retranslate() {
    val bitmap = lastOcrBitmap
    val region = lastOcrRegion
    if (bitmap != null && region != null) {
      serviceScope.launch { translateRegionBitmap(bitmap, region) }
    } else {
      handleTranslateVisible()
    }
  }

  fun showDotsMenu() {
    ui.showDotsMenu()
  }

  fun startManualOcrSelection() {
    ui.removeTranslationOverlays()
    ui.setOcrButtonVisible(true)
    ui.setOcrButtonActive(true)
    input.startRegionSelection()
  }

  fun stopManualOcrSelection() {
    ui.setOcrButtonActive(false)
  }

  fun handleTranslateVisible() {
    lastOcrBitmap = null
    lastOcrRegion = null
    val root = rootInActiveWindow
    if (root == null) {
      ui.showOverlayMessage("No active window. Try OCR.")
      return
    }

    input.dumpA11yTree(root)
    val fragments = input.collectVisibleStyledFragments(root)
    if (fragments.isEmpty()) {
      ui.setOcrButtonVisible(true)
      ui.showOverlayMessage("No visible text found. Try OCR.")
      return
    }

    val blocks = clusterFragmentsIntoBlocks(fragments)
    if (blocks.isEmpty()) return

    withOptionalScreenshot { screenshot -> translateAndShowBlocks(blocks, screenshot) }
  }

  fun handleRegionCapture(region: Rect) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    ui.setOcrButtonActive(false)

    val sourceLang = forcedSourceLanguage
    if (sourceLang == null) {
      ui.setOcrButtonVisible(true)
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
            ui.showToolbar(forcedSourceLanguage, forcedTargetLanguage, currentReadingOrderFor(forcedSourceLanguage))

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

            lastOcrBitmap = croppedBitmap
            lastOcrRegion = region

            ui.showLoadingOverlay(region, colors)

            serviceScope.launch {
              translateRegionBitmap(croppedBitmap, region)
            }
          }

          override fun onFailure(errorCode: Int) {
            Log.w(tag, "Screenshot failed: $errorCode")
            input.showInteractionOverlay()
            ui.showToolbar(forcedSourceLanguage, forcedTargetLanguage, currentReadingOrderFor(forcedSourceLanguage))
            ui.setOcrButtonVisible(true)
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
    val targetLang = forcedTargetLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: return

    val result =
      withContext(Dispatchers.IO) {
        translationCoordinator.translateImageWithOverlay(
          sourceLang,
          targetLang,
          bitmap,
          onMessage = {},
          readingOrder = currentReadingOrderFor(sourceLang),
        )
      }

    if (result != null) {
      ui.removeTranslationOverlays()
      ui.setOcrButtonVisible(true)
      ui.showBitmapOverlay(result.correctedBitmap, region)
    } else {
      ui.removeTranslationOverlays()
      ui.setOcrButtonVisible(true)
    }
  }

  private fun currentReadingOrderFor(language: Language?): ReadingOrder =
    if (language?.code == "ja") {
      ocrReadingOrder
    } else {
      ReadingOrder.LEFT_TO_RIGHT
    }

  private fun syncReadingOrderForSource() {
    if (forcedSourceLanguage?.code != "ja") {
      ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT
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

    input.dumpA11yTree(root)
    val fragments = input.extractStyledFragmentsAtPoint(root, x, y)
    Log.d(tag, "extractStyledFragmentsAtPoint($x, $y) returned ${fragments.size} fragments")
    for ((idx, f) in fragments.withIndex()) {
      Log.d(
        tag,
        "  Fragment[$idx] text='${f.text.take(50)}' bounds=[${f.bounds.left},${f.bounds.top},${f.bounds.right},${f.bounds.bottom}]",
      )
    }
    if (fragments.isEmpty()) {
      Log.d(tag, "No text block at ($x, $y)")
      ui.setOcrButtonVisible(true)
      ui.showOverlayMessage("No element found at position. Try OCR.")
      return
    }

    val blocks = clusterFragmentsIntoBlocks(fragments)
    Log.d(tag, "Clustered into ${blocks.size} blocks")
    for ((idx, b) in blocks.withIndex()) {
      Log.d(tag, "  Block[$idx] ${b.bounds.width()}x${b.bounds.height()} text='${b.text.take(60)}'")
    }
    if (blocks.isEmpty()) return

    val unionBounds = Rect(blocks.first().bounds.left, blocks.first().bounds.top, blocks.first().bounds.right, blocks.first().bounds.bottom)
    for (block in blocks.drop(1)) {
      unionBounds.union(block.bounds.left, block.bounds.top, block.bounds.right, block.bounds.bottom)
    }

    withOptionalScreenshot { screenshot -> translateAndShowBlocks(blocks, screenshot) }
  }

  private fun translateAndShowBlocks(
    blocks: List<dev.davidv.translator.TranslatableBlock>,
    screenshot: Bitmap?,
  ) {
    ui.removeTranslationOverlays()
    ui.showCenteredLoading()

    serviceScope.launch {
      val targetLanguage = overlayTextTranslationHelper.awaitTargetLanguage(forcedTargetLanguage)
      val combinedText = blocks.joinToString(" ") { it.text }
      val availableLanguages = overlayTextTranslationHelper.awaitAvailableLanguages(isSource = false)
      val from = forcedSourceLanguage ?: translationCoordinator.detectLanguageRobust(combinedText, null, availableLanguages)
      if (from == null) {
        showOverlayTranslationMessage("Could not detect language — set source language manually")
        return@launch
      }

      if (from == targetLanguage) {
        showOverlayTranslationMessage("Already in ${targetLanguage.displayName}")
        return@launch
      }

      val allSegmentTexts = mutableListOf<String>()

      data class SegmentRef(val blockIdx: Int, val segment: TranslationSegment)
      val segmentRefs = mutableListOf<SegmentRef>()
      for ((blockIdx, block) in blocks.withIndex()) {
        for (segment in block.segments) {
          allSegmentTexts.add(block.text.substring(segment.start, segment.end))
          segmentRefs.add(SegmentRef(blockIdx, segment))
        }
      }

      when (val result = translationCoordinator.translateTextsWithAlignment(from, targetLanguage, allSegmentTexts.toTypedArray())) {
        is BatchAlignedTranslationResult.Success -> {
          val translatedBlocks =
            blocks.mapIndexed { blockIdx, sourceBlock ->
              val blockSegmentResults =
                result.results
                  .zip(segmentRefs)
                  .filter { it.second.blockIdx == blockIdx }

              val translatedText = StringBuilder()
              val segmentAlignments = mutableListOf<Pair<TranslationSegment, Array<TokenAlignment>>>()
              val translatedSegments = mutableListOf<Pair<TranslationSegment, String>>()

              for ((translation, ref) in blockSegmentResults) {
                translatedSegments.add(ref.segment to translation.target)
                segmentAlignments.add(ref.segment to translation.alignments)
                translatedText.append(translation.target)
              }

              val styleSpans = mapStylesToSegmentedTranslation(sourceBlock, segmentAlignments, translatedSegments)
              TranslatedStyledBlock(translatedText.toString(), sourceBlock.bounds, styleSpans)
            }
          ui.removeTranslationOverlays()
          ui.setOcrButtonVisible(true)
          ui.setOcrButtonActive(false)
          ui.showStyledTranslationOverlays(translatedBlocks, screenshot)
        }
        is BatchAlignedTranslationResult.Error -> {
          Log.e(tag, "Translation error: ${result.message}")
          ui.removeTranslationOverlays()
          ui.setOcrButtonVisible(false)
          ui.setOcrButtonActive(false)
        }
      }
    }
  }

  private fun withOptionalScreenshot(onResult: (Bitmap?) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      onResult(null)
      return
    }

    takeScreenshot(
      Display.DEFAULT_DISPLAY,
      mainExecutor,
      object : TakeScreenshotCallback {
        override fun onSuccess(screenshot: ScreenshotResult) {
          val hwBitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
          screenshot.hardwareBuffer.close()
          if (hwBitmap == null) {
            onResult(null)
            return
          }
          val swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
          hwBitmap.recycle()
          onResult(swBitmap)
        }

        override fun onFailure(errorCode: Int) {
          Log.w(tag, "Screenshot failed: $errorCode")
          onResult(null)
        }
      },
    )
  }

  private fun showOverlayTranslationMessage(message: String) {
    ui.removeTranslationOverlays()
    ui.showOverlayMessage(message)
  }
}
