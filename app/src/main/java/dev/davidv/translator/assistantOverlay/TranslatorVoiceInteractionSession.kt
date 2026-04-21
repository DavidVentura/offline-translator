package dev.davidv.translator.assistantOverlay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import dev.davidv.translator.ImageProcessor
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.MainActivity
import dev.davidv.translator.OverlayTextTranslationHelper
import dev.davidv.translator.ReadingOrder
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.StructuredFragmentTranslationOutput
import dev.davidv.translator.StyledFragment
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.bounds
import dev.davidv.translator.overlayChrome.OverlayChromeFactory
import dev.davidv.translator.overlayChrome.OverlayMenuHost
import dev.davidv.translator.overlayChrome.OverlayMenuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslatorVoiceInteractionSession(
  context: Context,
  private val settingsManager: SettingsManager,
  private val imageProcessor: ImageProcessor,
  private val translationCoordinator: TranslationCoordinator,
  private val overlayTextTranslationHelper: OverlayTextTranslationHelper,
  private val langStateManager: LanguageStateManager,
) : VoiceInteractionSession(context) {
  companion object {
    private const val ASSIST_STRUCTURE_ENABLED_SETTING = "assist_structure_enabled"
    private const val ASSIST_SCREENSHOT_ENABLED_SETTING = "assist_screenshot_enabled"
  }

  private val tag = "TranslatorAssistant"
  private val assistCollectionTimeoutMs = 1500L
  private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val parser = AssistStructureParser(context.resources.displayMetrics.density)
  private val logger =
    AssistStructureLogger(
      tag,
      (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
      parser,
    )
  private val overlayRenderer = OverlayRenderer(context, ::dpToPx, settingsManager)

  private lateinit var rootView: FrameLayout
  private lateinit var screenshotView: ImageView
  private lateinit var overlayContainer: FrameLayout
  private lateinit var statusView: TextView
  private lateinit var loadingView: View
  private lateinit var topBarView: View
  private var sourceLabelView: TextView? = null
  private var targetLabelView: TextView? = null
  private var readingOrderButtonView: View? = null
  private var readingOrderIconView: ImageView? = null
  private var ocrButtonView: View? = null
  private var ocrIconView: ImageView? = null
  private var menuManager: OverlayMenuManager? = null
  private var borderView: BorderWaveView? = null
  private var manualOcrSelectionView: ManualOcrSelectionView? = null

  private val systemBarTop: Int by lazy {
    val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    if (id > 0) context.resources.getDimensionPixelSize(id) else 0
  }

  private val systemBarBottom: Int by lazy {
    val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    if (id > 0) context.resources.getDimensionPixelSize(id) else 0
  }

  private var screenshotBitmap: Bitmap? = null
  private var croppedBitmap: Bitmap? = null
  private var capturedFragments = mutableListOf<StyledFragment>()
  private var receivedAssistIndexes = mutableSetOf<Int>()
  private var expectedAssistCount: Int? = null
  private var processing = false
  private var translationJob: Job? = null
  private var forcedSourceLanguage: Language? = null
  private var forcedTargetLanguage: Language? = null
  private var ocrReadingOrder = ReadingOrder.LEFT_TO_RIGHT
  private var lastOcrBitmap: Bitmap? = null
  private var lastOcrRegion: Rect? = null
  private var assistFallbackMessageShown = false
  private var assistFallbackStatusPendingHide = false
  private var assistCollectionTimedOut = false
  private var assistCollectionTimeoutJob: Job? = null
  private var statusHideJob: Job? = null
  private var hasReceivedAssistCallback = false
  private var hasReceivedScreenshotCallback = false

  override fun onCreate() {
    super.onCreate()
    configureSessionWindow()
    langStateManager.refreshLanguageAvailability()
  }

  override fun onCreateContentView(): View {
    rootView = FrameLayout(context)
    rootView.setBackgroundColor(Color.TRANSPARENT)
    rootView.setOnApplyWindowInsetsListener { _, insets -> insets }
    menuManager =
      OverlayMenuManager(
        context,
        ::dpToPx,
        object : OverlayMenuHost {
          override fun addDismissLayer(view: View) {
            rootView.addView(
              view,
              FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
              ),
            )
          }

          override fun addMenuView(view: View) {
            rootView.addView(
              view,
              FrameLayout
                .LayoutParams(
                  dpToPx(180),
                  FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                  gravity = Gravity.TOP or Gravity.END
                  topMargin = dpToPx(48)
                  marginEnd = dpToPx(8)
                },
            )
          }

          override fun addPickerView(view: View) {
            rootView.addView(
              view,
              FrameLayout
                .LayoutParams(
                  dpToPx(250),
                  dpToPx(400),
                ).apply { gravity = Gravity.CENTER },
            )
          }

          override fun removeMenuChild(view: View) {
            rootView.removeView(view)
          }
        },
      )

    screenshotView =
      ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_START
        setBackgroundColor(Color.TRANSPARENT)
      }
    rootView.addView(
      screenshotView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )

    overlayContainer = FrameLayout(context)
    rootView.addView(
      overlayContainer,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )

    borderView = BorderWaveView.create(context)
    rootView.addView(
      borderView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )

    topBarView = buildTopBar()
    rootView.addView(
      topBarView,
      FrameLayout
        .LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          gravity = Gravity.TOP or Gravity.START
        },
    )

    statusView =
      TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12))
        background =
          GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(Color.parseColor("#CC202020"))
          }
      }
    val statusParams =
      FrameLayout
        .LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT,
          FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
          bottomMargin = systemBarBottom + dpToPx(24)
        }
    rootView.addView(statusView, statusParams)

    loadingView = buildLoadingView()
    rootView.addView(
      loadingView,
      FrameLayout
        .LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT,
          FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER },
    )

    showStatus("Invoke this assistant on top of text to translate it")
    showLoading(false)
    updateBackdrop()
    return rootView
  }

  override fun onShow(
    args: Bundle?,
    showFlags: Int,
  ) {
    super.onShow(args, showFlags)
    configureSessionWindow()
    clearCapture()
    overlayContainer.removeAllViews()
    dismissMenu()
    updateBackdrop()
    showStatus("Collecting screen context...")
    showLoading(true)
    setOcrButtonVisible(false)
    startBorderPulse()

    val assistStructureEnabled = isAssistStructureEnabled()
    val assistScreenshotEnabled = isAssistScreenshotEnabled()
    Log.d(
      tag,
      "Showing assistant session flags=$showFlags assistStructureEnabled=$assistStructureEnabled assistScreenshotEnabled=$assistScreenshotEnabled forcedSource=${forcedSourceLanguage?.code} forcedTarget=${forcedTargetLanguage?.code} defaultSource=${settingsManager.settings.value.defaultSourceLanguageCode} defaultTarget=${settingsManager.settings.value.defaultTargetLanguageCode}",
    )
    if (!assistStructureEnabled && !assistScreenshotEnabled) {
      Log.w(tag, "AssistStructure and screenshot capture are both disabled in system settings")
      showLoading(false)
      stopBorderPulse()
      showStatus("Screen access is disabled for this assistant. Enable 'Use text from screen' or use the floating shortcut.")
      return
    }

    if (!assistStructureEnabled) {
      val sourceLanguage =
        forcedSourceLanguage ?: settingsManager.settings.value.defaultSourceLanguageCode?.let {
          langStateManager.languageByCode(it)
        }
      if (!assistScreenshotEnabled || sourceLanguage == null) {
        Log.w(
          tag,
          "AssistStructure is disabled and OCR fallback is unavailable (screenshot=$assistScreenshotEnabled source=${sourceLanguage != null})",
        )
        showLoading(false)
        stopBorderPulse()
        showStatus("Text from screen is disabled. Enable it or set a default source language for OCR fallback.")
        return
      }
      showStatus("Text from screen is disabled. Try OCR.")
    }

    scheduleAssistCollectionTimeout()
  }

  override fun onComputeInsets(outInsets: Insets) {
    outInsets.contentInsets.set(0, 0, 0, 0)
    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
  }

  override fun onHide() {
    super.onHide()
    stopBorderPulse()
    clearCapture()
    overlayContainer.removeAllViews()
    dismissMenu()
    removeManualOcrSelection()
    screenshotView.setImageDrawable(null)
    updateBackdrop()
    showLoading(false)
  }

  override fun onDestroy() {
    stopBorderPulse()
    sessionScope.cancel()
    clearCapture()
    removeManualOcrSelection()
    super.onDestroy()
  }

  override fun onHandleAssist(state: AssistState) {
    super.onHandleAssist(state)
    hasReceivedAssistCallback = true
    expectedAssistCount = maxOf(expectedAssistCount ?: 0, state.count)
    if (state.index >= 0) {
      receivedAssistIndexes += state.index
    }

    val structure = state.assistStructure
    if (structure == null) {
      Log.w(tag, "AssistStructure missing for index=${state.index}")
      if (!isAssistScreenshotEnabled()) {
        cancelAssistCollectionTimeout()
        processing = false
        showLoading(false)
        stopBorderPulse()
        showStatus("This app did not provide screen text, and screenshot fallback is disabled.")
        return
      }
      if (!assistFallbackMessageShown) {
        assistFallbackMessageShown = true
        assistFallbackStatusPendingHide = true
        showStatus("App does not provide screen text. Try OCR.")
      }
      maybeProcessCapture()
      return
    }

    Log.d(tag, "AssistStructure received index=${state.index}/${state.count} windows=${structure.windowNodeCount}")
    logger.log(structure)
    capturedFragments += parser.parse(structure)
    maybeProcessCapture()
  }

  override fun onHandleScreenshot(screenshot: Bitmap?) {
    super.onHandleScreenshot(screenshot)
    hasReceivedScreenshotCallback = true
    Log.d(tag, "Screenshot callback received bitmap=${screenshot?.width}x${screenshot?.height}")
    val oldSs = screenshotBitmap
    val oldCr = croppedBitmap
    oldSs?.recycle()
    if (oldCr != null && oldCr !== oldSs) oldCr.recycle()
    screenshotBitmap = screenshot?.copy(Bitmap.Config.ARGB_8888, false)
    croppedBitmap = screenshotBitmap?.let { cropSystemBars(it) }
    screenshotView.setImageBitmap(croppedBitmap)
    updateBackdrop()
    maybeProcessCapture()
  }

  private fun maybeProcessCapture() {
    if (processing) return

    val expectedCount = expectedAssistCount
    val haveAllAssistStates = expectedCount != null && receivedAssistIndexes.size >= expectedCount
    val assistReady = haveAllAssistStates || assistCollectionTimedOut
    val screenshotReady = screenshotBitmap != null
    val noCaptureCallbacksArrived = !hasReceivedAssistCallback && !hasReceivedScreenshotCallback
    val screenshotExpected = isAssistScreenshotEnabled()
    Log.d(
      tag,
      "maybeProcessCapture blocks=${capturedFragments.size} expectedAssistCount=$expectedCount receivedAssist=${receivedAssistIndexes.size} assistReady=$assistReady screenshotReady=$screenshotReady screenshotExpected=$screenshotExpected timedOut=$assistCollectionTimedOut",
    )

    if (capturedFragments.isNotEmpty() && screenshotExpected && !screenshotReady && !assistCollectionTimedOut) {
      Log.d(tag, "Waiting for screenshot callback before rendering structured overlays")
      return
    }

    if (capturedFragments.isEmpty() && assistReady && screenshotExpected && !hasReceivedScreenshotCallback && !assistCollectionTimedOut) {
      Log.d(tag, "Waiting for screenshot callback before failing empty assist capture")
      return
    }

    if (capturedFragments.isNotEmpty() && (screenshotReady || assistReady)) {
      cancelAssistCollectionTimeout()
      processing = true
      translateStructuredFragments(capturedFragments.toList(), screenshotBitmap)
      return
    }

    if (capturedFragments.isEmpty() && screenshotReady && assistReady) {
      cancelAssistCollectionTimeout()
      processing = false
      showLoading(false)
      setOcrButtonVisible(true)
      showStatus("Can't detect anything. Try OCR.")
      return
    }

    if (assistReady && capturedFragments.isEmpty() && !screenshotReady) {
      cancelAssistCollectionTimeout()
      Log.w(tag, "Assist capture completed without usable text or screenshot")
      processing = false
      showLoading(false)
      stopBorderPulse()
      showStatus("No usable screen data received. Enable screenshots or try OCR.")
      return
    }

    if (assistCollectionTimedOut && noCaptureCallbacksArrived) {
      cancelAssistCollectionTimeout()
      Log.w(tag, "No AssistStructure or screenshot callback arrived before timeout")
      processing = false
      showLoading(false)
      showStatus("No screen data received. Enable 'Use text from screen' for this assistant or use the floating shortcut.")
      stopBorderPulse()
    }
  }

  private fun scheduleAssistCollectionTimeout() {
    cancelAssistCollectionTimeout()
    assistCollectionTimedOut = false
    assistCollectionTimeoutJob =
      sessionScope.launch {
        delay(assistCollectionTimeoutMs)
        assistCollectionTimedOut = true
        if (!processing) {
          when {
            screenshotBitmap != null -> {
              Log.w(tag, "Timed out waiting for complete assist data; continuing with available capture")
            }

            !hasReceivedAssistCallback && !hasReceivedScreenshotCallback -> {
              Log.w(tag, "Timed out waiting for any assist capture callback")
            }
          }
        }
        maybeProcessCapture()
      }
  }

  private fun cancelAssistCollectionTimeout() {
    assistCollectionTimeoutJob?.cancel()
    assistCollectionTimeoutJob = null
  }

  private fun translateStructuredFragments(
    fragments: List<StyledFragment>,
    screenshot: Bitmap?,
  ) {
    translationJob =
      sessionScope.launch {
        val targetLanguage = overlayTextTranslationHelper.awaitTargetLanguage(forcedTargetLanguage)
        when (
          val result =
            translationCoordinator.translateStructuredFragments(
              fragments = fragments,
              forcedSourceLanguage = forcedSourceLanguage,
              targetLanguage = targetLanguage,
              availableLanguages = overlayTextTranslationHelper.availableLanguages(true),
              screenshot = screenshot,
            )
        ) {
          is StructuredFragmentTranslationOutput.Success -> {
            ensureActive()
            overlayRenderer.renderStyledBlocks(overlayContainer, result.blocks, systemBarTop)
            processing = false
            showLoading(false)
            setOcrButtonVisible(true)
            hideStatus()
          }
          is StructuredFragmentTranslationOutput.NothingToTranslate -> {
            processing = false
            showLoading(false)
            setOcrButtonVisible(screenshot != null)
            showStatus(
              when (result.reason) {
                dev.davidv.translator.NothingReason.ALREADY_TARGET_LANGUAGE -> "Already in ${targetLanguage.displayName}"
                dev.davidv.translator.NothingReason.COULD_NOT_DETECT -> "Could not detect language — set source language manually"
                dev.davidv.translator.NothingReason.NO_TRANSLATABLE_TEXT -> "No visible structured text found. Try OCR."
              },
            )
          }
          is StructuredFragmentTranslationOutput.Error -> {
            processing = false
            showLoading(false)
            setOcrButtonVisible(screenshot != null)
            showStatus(result.message)
          }
        }
      }
  }

  private fun runOcrFallback(screenshot: Bitmap) {
    setOcrButtonVisible(false)
    val sourceLanguage =
      forcedSourceLanguage
        ?: settingsManager.settings.value.defaultSourceLanguageCode?.let { langStateManager.languageByCode(it) }
    if (sourceLanguage == null) {
      processing = false
      showLoading(false)
      showStatus("No AssistStructure text. Set a default source language for OCR fallback.")
      return
    }

    val targetLanguage = forcedTargetLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: return
    val maxImageSize = settingsManager.settings.value.maxImageSize
    val cropped = cropSystemBars(screenshot)
    val copy = cropped.copy(Bitmap.Config.ARGB_8888, false)
    if (cropped !== screenshot) cropped.recycle()
    val workingBitmap = imageProcessor.downscaleImage(copy, maxImageSize)
    if (workingBitmap !== copy) copy.recycle()
    translationJob =
      sessionScope.launch {
        val result =
          withContext(Dispatchers.IO) {
            translationCoordinator.translateImageWithOverlay(sourceLanguage, targetLanguage, workingBitmap, onMessage = {})
          }
        ensureActive()
        processing = false
        showLoading(false)
        if (result == null) {
          showStatus("OCR fallback failed")
          return@launch
        }
        val oldCropped = croppedBitmap
        if (oldCropped != null) {
          if (oldCropped === screenshotBitmap) screenshotBitmap = null
          oldCropped.recycle()
        }
        croppedBitmap = result.correctedBitmap
        screenshotView.setImageBitmap(croppedBitmap)
        updateBackdrop()
        if (assistFallbackStatusPendingHide) {
          assistFallbackStatusPendingHide = false
          showStatus("App does not provide data, falling back to OCR", autoHideAfterMs = 3000)
        } else {
          hideStatus()
        }
      }
  }

  private fun startBorderPulse() {
    borderView?.startAnimation()
  }

  private fun stopBorderPulse() {
    borderView?.stopAnimation()
  }

  private fun buildLoadingView(): View {
    val container =
      FrameLayout(context).apply {
        background =
          GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16).toFloat()
            setColor(Color.parseColor("#CC202020"))
          }
        val padding = dpToPx(16)
        setPadding(padding, padding, padding, padding)
      }

    val progress = ProgressBar(context)
    container.addView(
      progress,
      FrameLayout
        .LayoutParams(
          dpToPx(48),
          dpToPx(48),
        ).apply { gravity = Gravity.CENTER },
    )
    return container
  }

  private fun startManualOcrSelection() {
    if (processing) return
    val screenshot = screenshotBitmap
    if (screenshot == null) {
      showStatus("No screenshot available for OCR")
      return
    }
    if (manualOcrSelectionView != null) return

    dismissMenu()
    hideStatus()
    overlayContainer.removeAllViews()
    setOcrButtonActive(true)

    val selectionView = ManualOcrSelectionView(context)
    selectionView.setOnTouchListener(
      object : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(
          v: View,
          event: MotionEvent,
        ): Boolean =
          when (event.action) {
            MotionEvent.ACTION_DOWN -> {
              startX = event.x.toInt()
              startY = event.y.toInt()
              dragging = false
              selectionView.clearRect()
              true
            }

            MotionEvent.ACTION_MOVE -> {
              val currentX = event.x.toInt()
              val currentY = event.y.toInt()
              val dx = kotlin.math.abs(currentX - startX)
              val dy = kotlin.math.abs(currentY - startY)
              if (dx > dpToPx(10) || dy > dpToPx(10)) {
                dragging = true
                selectionView.setRect(
                  minOf(startX, currentX).toFloat(),
                  minOf(startY, currentY).toFloat(),
                  maxOf(startX, currentX).toFloat(),
                  maxOf(startY, currentY).toFloat(),
                )
              }
              true
            }

            MotionEvent.ACTION_UP -> {
              val endX = event.x.toInt()
              val endY = event.y.toInt()
              removeManualOcrSelection()
              if (dragging) {
                val region =
                  Rect(
                    minOf(startX, endX),
                    minOf(startY, endY),
                    maxOf(startX, endX),
                    maxOf(startY, endY),
                  )
                if (region.width() > dpToPx(20) && region.height() > dpToPx(20)) {
                  runManualOcrRegion(region, screenshot)
                }
              }
              true
            }

            else -> false
          }
      },
    )
    rootView.addView(
      selectionView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )
    manualOcrSelectionView = selectionView
  }

  private fun removeManualOcrSelection() {
    manualOcrSelectionView?.let {
      rootView.removeView(it)
      manualOcrSelectionView = null
    }
    setOcrButtonActive(false)
  }

  private fun runManualOcrRegion(
    region: Rect,
    screenshot: Bitmap,
  ) {
    val cropLeft = region.left.coerceIn(0, screenshot.width - 1)
    val cropTop = (region.top + systemBarTop).coerceIn(0, screenshot.height - 1)
    val cropWidth = region.width().coerceAtMost(screenshot.width - cropLeft)
    val cropHeight = region.height().coerceAtMost(screenshot.height - cropTop)
    if (cropWidth <= 0 || cropHeight <= 0) {
      showStatus("Invalid OCR region")
      return
    }

    val workingBitmap = Bitmap.createBitmap(screenshot, cropLeft, cropTop, cropWidth, cropHeight)
    lastOcrBitmap = workingBitmap
    lastOcrRegion = region
    translateOcrBitmap(workingBitmap, region)
  }

  private fun translateOcrBitmap(
    bitmap: Bitmap,
    region: Rect,
  ) {
    val sourceLanguage =
      forcedSourceLanguage ?: settingsManager.settings.value.defaultSourceLanguageCode?.let {
        langStateManager.languageByCode(it)
      }
    if (sourceLanguage == null) {
      showStatus("Set source language first for OCR")
      return
    }
    val targetLanguage = forcedTargetLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: return

    overlayContainer.removeAllViews()
    processing = true
    showLoading(true)
    setOcrButtonVisible(false)
    setOcrButtonActive(false)
    hideStatus()

    translationJob =
      sessionScope.launch {
        val result =
          withContext(Dispatchers.IO) {
            translationCoordinator.translateImageWithOverlay(
              sourceLanguage,
              targetLanguage,
              bitmap,
              onMessage = {},
              readingOrder = currentReadingOrderFor(sourceLanguage),
            )
          }
        ensureActive()
        processing = false
        showLoading(false)
        if (result == null) {
          setOcrButtonVisible(true)
          showStatus("OCR failed")
          return@launch
        }
        setOcrButtonVisible(true)
        setOcrButtonActive(false)
        showBitmapOverlay(result.correctedBitmap, region)
      }
  }

  private fun showBitmapOverlay(
    bitmap: Bitmap,
    bounds: Rect,
  ) {
    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels - systemBarTop
    val left = bounds.left.coerceAtLeast(0)
    val top = bounds.top.coerceAtLeast(0)
    val width = minOf(bitmap.width, screenWidth - left)
    val height = minOf(bitmap.height, screenHeight - top)
    if (width <= 0 || height <= 0) return

    val displayBitmap =
      if (width == bitmap.width && height == bitmap.height) {
        bitmap
      } else {
        Bitmap.createBitmap(bitmap, 0, 0, width, height)
      }

    val imageView =
      ImageView(context).apply {
        setImageBitmap(displayBitmap)
        scaleType = ImageView.ScaleType.FIT_XY
      }
    overlayContainer.addView(
      imageView,
      FrameLayout.LayoutParams(width, height).apply {
        leftMargin = left
        topMargin = top
      },
    )
  }

  @Suppress("DEPRECATION")
  private fun configureSessionWindow() {
    val dialog = window ?: return
    val win = dialog.window ?: return
    win.setLayout(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
    )
    win.setGravity(Gravity.TOP or Gravity.START)
    win.setBackgroundDrawable(null)
    win.decorView.setBackgroundColor(Color.TRANSPARENT)
    win.decorView.setPadding(0, 0, 0, 0)
    win.setWindowAnimations(0)
    val contentFrame = win.decorView.findViewById<View>(android.R.id.content)
    contentFrame?.setBackgroundColor(Color.TRANSPARENT)
    (contentFrame?.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
  }

  private fun showStatus(
    message: String,
    autoHideAfterMs: Long? = null,
  ) {
    if (!::statusView.isInitialized) return
    statusHideJob?.cancel()
    statusHideJob = null
    statusView.text = message
    statusView.visibility = View.VISIBLE
    if (autoHideAfterMs != null) {
      statusHideJob =
        sessionScope.launch {
          delay(autoHideAfterMs)
          hideStatus()
        }
    }
  }

  private fun hideStatus() {
    if (!::statusView.isInitialized) return
    statusHideJob?.cancel()
    statusHideJob = null
    statusView.visibility = View.GONE
  }

  private fun showLoading(visible: Boolean) {
    if (!::loadingView.isInitialized) return
    loadingView.visibility = if (visible) View.VISIBLE else View.GONE
  }

  private fun cropSystemBars(source: Bitmap): Bitmap {
    val top = systemBarTop.coerceIn(0, source.height - 1)
    if (top == 0) return source
    return Bitmap.createBitmap(source, 0, top, source.width, source.height - top)
  }

  private fun clearCapture() {
    translationJob?.cancel()
    translationJob = null
    val ss = screenshotBitmap
    val cr = croppedBitmap
    ss?.recycle()
    if (cr != null && cr !== ss) cr.recycle()
    screenshotBitmap = null
    croppedBitmap = null
    capturedFragments.clear()
    receivedAssistIndexes.clear()
    expectedAssistCount = null
    processing = false
    assistFallbackMessageShown = false
    assistFallbackStatusPendingHide = false
    assistCollectionTimedOut = false
    hasReceivedAssistCallback = false
    hasReceivedScreenshotCallback = false
    setOcrButtonVisible(false)
    cancelAssistCollectionTimeout()
    statusHideJob?.cancel()
    statusHideJob = null
  }

  private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

  private fun updateBackdrop() {
    rootView.setBackgroundColor(Color.TRANSPARENT)
    screenshotView.setBackgroundColor(Color.TRANSPARENT)
  }

  private fun isAssistStructureEnabled(): Boolean =
    Settings.Secure.getInt(context.contentResolver, ASSIST_STRUCTURE_ENABLED_SETTING, 1) != 0

  private fun isAssistScreenshotEnabled(): Boolean =
    Settings.Secure.getInt(context.contentResolver, ASSIST_SCREENSHOT_ENABLED_SETTING, 1) != 0

  private fun buildTopBar(): View {
    val toolbarViews =
      OverlayChromeFactory.createLanguageToolbar(
        context = context,
        dpToPx = ::dpToPx,
        forcedSourceLanguage = forcedSourceLanguage,
        forcedTargetLanguage = forcedTargetLanguage,
        defaultTargetLanguage = langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: langStateManager.languageByCode("en")!!,
        onClose = { hide() },
        onSourceClick = { showLanguagePicker(true) },
        onSwap = { swapLanguages() },
        onTargetClick = { showLanguagePicker(false) },
        showReadingOrderButton = forcedSourceLanguage?.code == "ja",
        readingOrder = currentReadingOrderFor(forcedSourceLanguage),
        onReadingOrderClick = { toggleJapaneseOcrMode() },
        showOcrButton = true,
        onOcrClick = { startManualOcrSelection() },
        onMenuClick = { showDotsMenu() },
      )
    sourceLabelView = toolbarViews.sourceLabel
    targetLabelView = toolbarViews.targetLabel
    readingOrderButtonView = toolbarViews.readingOrderButton
    readingOrderIconView = toolbarViews.readingOrderIcon
    ocrButtonView = toolbarViews.ocrButton
    ocrIconView = toolbarViews.ocrIcon
    return toolbarViews.root
  }

  private fun setOcrButtonVisible(visible: Boolean) {
    ocrButtonView?.visibility = View.VISIBLE
    if (!visible) {
      setOcrButtonActive(false)
    }
  }

  private fun setOcrButtonActive(active: Boolean) {
    OverlayChromeFactory.setOcrButtonActive(ocrButtonView, ocrIconView, active)
  }

  private fun shouldUseOcrFallback(): Boolean {
    val fragments = capturedFragments
    if (fragments.isEmpty()) return true

    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels
    val screenArea = screenWidth.toLong() * screenHeight.toLong()

    val veryLargeFragments =
      fragments.count { fragment ->
        val area = fragment.bounds.width().toLong() * fragment.bounds.height().toLong()
        area >= screenArea / 4
      }
    if (veryLargeFragments > 0 && fragments.size <= 3) return true

    val averageChars = fragments.sumOf { it.text.length }.toFloat() / fragments.size
    val veryTallFragments =
      fragments.count { fragment ->
        fragment.bounds.height() > screenHeight / 3 && fragment.bounds.width() > screenWidth / 2
      }
    if (veryTallFragments > fragments.size / 2 && averageChars > 40f) return true

    return false
  }

  private fun swapLanguages() {
    val oldSource = forcedSourceLanguage
    val oldTarget = forcedTargetLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode) ?: return
    forcedSourceLanguage = oldTarget
    forcedTargetLanguage = oldSource
    syncReadingOrderForSource()
    updateToolbarLabels()
    retranslate()
  }

  private fun retranslate() {
    translationJob?.cancel()
    translationJob = null
    processing = false
    val bitmap = lastOcrBitmap
    val region = lastOcrRegion
    if (bitmap != null && region != null) {
      translateOcrBitmap(bitmap, region)
    } else {
      overlayContainer.removeAllViews()
      setOcrButtonVisible(false)
      showLoading(true)
      hideStatus()
      maybeProcessCapture()
    }
  }

  private fun updateToolbarLabels() {
    sourceLabelView?.text = forcedSourceLanguage?.shortDisplayName ?: "Auto"
    val currentTarget = forcedTargetLanguage ?: langStateManager.languageByCode(settingsManager.settings.value.defaultTargetLanguageCode)
    targetLabelView?.text = currentTarget?.shortDisplayName ?: "?"
    OverlayChromeFactory.updateReadingOrderButtonState(
      readingButton = readingOrderButtonView,
      readingIcon = readingOrderIconView,
      visible = forcedSourceLanguage?.code == "ja",
      readingOrder = currentReadingOrderFor(forcedSourceLanguage),
    )
  }

  private fun showLanguagePicker(isSource: Boolean) {
    sessionScope.launch {
      langStateManager.refreshLanguageAvailability()
      val availableLangs = overlayTextTranslationHelper.awaitAvailableLanguages(isSource)
      menuManager?.showLanguagePicker(
        isSource = isSource,
        availableLangs = availableLangs,
      ) { language ->
        if (isSource) {
          forcedSourceLanguage = language
          syncReadingOrderForSource()
        } else {
          forcedTargetLanguage = language
        }
        updateToolbarLabels()
        retranslate()
      }
    }
  }

  private fun toggleJapaneseOcrMode() {
    if (forcedSourceLanguage?.code != "ja") return
    ocrReadingOrder =
      when (ocrReadingOrder) {
        ReadingOrder.LEFT_TO_RIGHT -> ReadingOrder.TOP_TO_BOTTOM_LEFT_TO_RIGHT
        ReadingOrder.TOP_TO_BOTTOM_LEFT_TO_RIGHT -> ReadingOrder.LEFT_TO_RIGHT
      }
    updateToolbarLabels()
    retranslate()
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

  private fun showDotsMenu() {
    menuManager?.showDotsMenu(
      listOf(
        "Open App" to { openMainApp() },
      ),
    )
  }

  private fun openMainApp() {
    startAssistantActivity(Intent(context, MainActivity::class.java))
    hide()
  }

  private fun dismissMenu() {
    menuManager?.dismiss()
  }

  private class ManualOcrSelectionView(
    context: Context,
  ) : View(context) {
    private val fillPaint =
      Paint().apply {
        color = Color.parseColor("#220088FF")
        style = Paint.Style.FILL
      }
    private val strokePaint =
      Paint().apply {
        color = Color.parseColor("#4488FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
      }
    private val rect = RectF()
    private val cornerRadius = 8f

    init {
      setBackgroundColor(Color.TRANSPARENT)
    }

    fun setRect(
      left: Float,
      top: Float,
      right: Float,
      bottom: Float,
    ) {
      rect.set(left, top, right, bottom)
      invalidate()
    }

    fun clearRect() {
      rect.setEmpty()
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
