package dev.davidv.translator.assistantOverlay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
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
import dev.davidv.translator.OverlayTextTranslationResult
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.TranslationCoordinator
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
  private val parser = AssistStructureParser()
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
  private var menuManager: OverlayMenuManager? = null
  private var borderView: BorderWaveView? = null

  private var systemBarTop: Int = fallbackSystemBarTop()
  private var systemBarBottom: Int = fallbackSystemBarBottom()

  private var screenshotBitmap: Bitmap? = null
  private var croppedBitmap: Bitmap? = null
  private var capturedBlocks = mutableListOf<CapturedTextBlock>()
  private var receivedAssistIndexes = mutableSetOf<Int>()
  private var expectedAssistCount: Int? = null
  private var processing = false
  private var translationJob: Job? = null
  private var forcedSourceLanguage: Language? = null
  private var forcedTargetLanguage: Language? = null
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
    rootView.setOnApplyWindowInsetsListener { _, insets ->
      updateSystemBarInsets(insets)
      insets
    }
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
        scaleType = ImageView.ScaleType.FIT_XY
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
    rootView.requestApplyInsets()
    return rootView
  }

  override fun onShow(
    args: Bundle?,
    showFlags: Int,
  ) {
    super.onShow(args, showFlags)
    configureSessionWindow()
    refreshSystemBarInsets()
    clearCapture()
    overlayContainer.removeAllViews()
    dismissMenu()
    updateBackdrop()
    showStatus("Collecting screen context...")
    showLoading(true)
    startBorderPulse()

    val assistStructureEnabled = isAssistStructureEnabled()
    val assistScreenshotEnabled = isAssistScreenshotEnabled()
    Log.d(
      tag,
      "Showing assistant session flags=$showFlags assistStructureEnabled=$assistStructureEnabled assistScreenshotEnabled=$assistScreenshotEnabled forcedSource=${forcedSourceLanguage?.code} forcedTarget=${forcedTargetLanguage?.code} defaultSource=${settingsManager.settings.value.defaultSourceLanguage?.code} defaultTarget=${settingsManager.settings.value.defaultTargetLanguage.code}",
    )
    if (!assistStructureEnabled && !assistScreenshotEnabled) {
      Log.w(tag, "AssistStructure and screenshot capture are both disabled in system settings")
      showLoading(false)
      stopBorderPulse()
      showStatus("Screen access is disabled for this assistant. Enable 'Use text from screen' or use the floating shortcut.")
      return
    }

    if (!assistStructureEnabled) {
      val sourceLanguage = forcedSourceLanguage ?: settingsManager.settings.value.defaultSourceLanguage
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
      showStatus("Text from screen is disabled, waiting for screenshot OCR fallback...")
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
    screenshotView.setImageDrawable(null)
    updateBackdrop()
    showLoading(false)
  }

  override fun onDestroy() {
    stopBorderPulse()
    sessionScope.cancel()
    clearCapture()
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
        showStatus("App does not provide data, falling back to OCR")
      }
      maybeProcessCapture()
      return
    }

    Log.d(tag, "AssistStructure received index=${state.index}/${state.count} windows=${structure.windowNodeCount}")
    logger.log(state, structure)
    capturedBlocks += parser.parse(structure)
    maybeProcessCapture()
  }

  override fun onHandleScreenshot(screenshot: Bitmap?) {
    super.onHandleScreenshot(screenshot)
    refreshSystemBarInsets()
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
      "maybeProcessCapture blocks=${capturedBlocks.size} expectedAssistCount=$expectedCount receivedAssist=${receivedAssistIndexes.size} assistReady=$assistReady screenshotReady=$screenshotReady screenshotExpected=$screenshotExpected timedOut=$assistCollectionTimedOut",
    )

    if (capturedBlocks.isNotEmpty() && screenshotExpected && !screenshotReady && !assistCollectionTimedOut) {
      Log.d(tag, "Waiting for screenshot callback before rendering structured overlays")
      return
    }

    if (capturedBlocks.isNotEmpty() && (screenshotReady || assistReady)) {
      cancelAssistCollectionTimeout()
      if (screenshotReady && shouldUseOcrFallback(capturedBlocks)) {
        processing = true
        Log.d(tag, "Using OCR fallback because parsed AssistStructure is too coarse")
        runOcrFallback(screenshotBitmap ?: return)
        return
      }
      processing = true
      translateStructuredBlocks(capturedBlocks.toList(), screenshotBitmap)
      return
    }

    if (capturedBlocks.isEmpty() && screenshotReady && assistReady) {
      cancelAssistCollectionTimeout()
      processing = true
      runOcrFallback(screenshotBitmap ?: return)
      return
    }

    if (assistReady && capturedBlocks.isEmpty() && !screenshotReady) {
      cancelAssistCollectionTimeout()
      Log.w(tag, "Assist capture completed without usable text or screenshot")
      processing = false
      showLoading(false)
      stopBorderPulse()
      showStatus("No usable screen data received. Enable screenshots for the assistant or use the floating shortcut.")
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

  private fun translateStructuredBlocks(
    blocks: List<CapturedTextBlock>,
    screenshot: Bitmap?,
  ) {
    translationJob =
      sessionScope.launch {
        val blocksByText = linkedMapOf<String, MutableList<CapturedTextBlock>>()
        for (block in blocks) {
          if (block.text.isBlank()) continue
          blocksByText.getOrPut(block.text) { mutableListOf() }.add(block)
        }

        if (blocksByText.isEmpty()) {
          processing = false
          showLoading(false)
          showStatus("No visible structured text found")
          return@launch
        }

        val result =
          overlayTextTranslationHelper.translateTexts(
            inputs = blocksByText.keys.toList(),
            forcedSourceLanguage = forcedSourceLanguage,
            forcedTargetLanguage = forcedTargetLanguage,
          )

        ensureActive()
        when (result) {
          is OverlayTextTranslationResult.Message -> {
            processing = false
            showLoading(false)
            showStatus(result.value)
          }
          is OverlayTextTranslationResult.Success -> {
            overlayRenderer.renderTranslatedBlocks(
              overlayContainer,
              blocksByText,
              result.results,
              screenshot,
              systemBarTop,
            )
            processing = false
            showLoading(false)
            hideStatus()
          }
        }
      }
  }

  private fun runOcrFallback(screenshot: Bitmap) {
    val sourceLanguage =
      forcedSourceLanguage
        ?: settingsManager.settings.value.defaultSourceLanguage
    if (sourceLanguage == null) {
      processing = false
      showLoading(false)
      showStatus("No AssistStructure text. Set a default source language for OCR fallback.")
      return
    }

    val targetLanguage = forcedTargetLanguage ?: settingsManager.settings.value.defaultTargetLanguage
    val maxImageSize = settingsManager.settings.value.maxImageSize
    val copy = screenshot.copy(Bitmap.Config.ARGB_8888, false)
    val workingBitmap = imageProcessor.downscaleImage(copy, maxImageSize)
    if (workingBitmap !== copy) copy.recycle()
    translationJob =
      sessionScope.launch {
        val result =
          withContext(Dispatchers.IO) {
            translationCoordinator.translateImageWithOverlay(sourceLanguage, targetLanguage, workingBitmap) {}
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
        croppedBitmap = cropSystemBars(result.correctedBitmap)
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      win.attributes =
        win.attributes.apply {
          layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    val contentFrame = win.decorView.findViewById<View>(android.R.id.content)
    contentFrame?.setBackgroundColor(Color.TRANSPARENT)
    (contentFrame?.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
  }

  private fun refreshSystemBarInsets() {
    if (!::rootView.isInitialized) return
    rootView.rootWindowInsets?.let { updateSystemBarInsets(it) }
  }

  @Suppress("DEPRECATION")
  private fun updateSystemBarInsets(insets: WindowInsets) {
    val topInset =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insets.getInsets(WindowInsets.Type.statusBars()).top
      } else {
        insets.systemWindowInsetTop
      }
    val bottomInset =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insets.getInsets(WindowInsets.Type.navigationBars()).bottom
      } else {
        insets.systemWindowInsetBottom
      }

    val resolvedTop = topInset.takeIf { it > 0 } ?: fallbackSystemBarTop()
    val resolvedBottom = bottomInset.takeIf { it > 0 } ?: fallbackSystemBarBottom()
    if (resolvedTop == systemBarTop && resolvedBottom == systemBarBottom) return

    systemBarTop = resolvedTop
    systemBarBottom = resolvedBottom
    updateStatusBottomMargin()
  }

  private fun updateStatusBottomMargin() {
    if (!::statusView.isInitialized) return
    val params = statusView.layoutParams as? FrameLayout.LayoutParams ?: return
    val targetBottomMargin = systemBarBottom + dpToPx(24)
    if (params.bottomMargin == targetBottomMargin) return
    params.bottomMargin = targetBottomMargin
    statusView.layoutParams = params
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
    capturedBlocks.clear()
    receivedAssistIndexes.clear()
    expectedAssistCount = null
    processing = false
    assistFallbackMessageShown = false
    assistFallbackStatusPendingHide = false
    assistCollectionTimedOut = false
    hasReceivedAssistCallback = false
    hasReceivedScreenshotCallback = false
    cancelAssistCollectionTimeout()
    statusHideJob?.cancel()
    statusHideJob = null
  }

  private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

  private fun fallbackSystemBarTop(): Int {
    val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
  }

  private fun fallbackSystemBarBottom(): Int {
    val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
  }

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
        defaultTargetLanguage = settingsManager.settings.value.defaultTargetLanguage,
        onClose = { hide() },
        onSourceClick = { showLanguagePicker(true) },
        onSwap = { swapLanguages() },
        onTargetClick = { showLanguagePicker(false) },
        onMenuClick = { showDotsMenu() },
      )
    sourceLabelView = toolbarViews.sourceLabel
    targetLabelView = toolbarViews.targetLabel
    return toolbarViews.root
  }

  private fun shouldUseOcrFallback(blocks: List<CapturedTextBlock>): Boolean {
    if (blocks.isEmpty()) return true

    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels
    val screenArea = screenWidth.toLong() * screenHeight.toLong()

    val veryLargeBlocks =
      blocks.count { block ->
        val area = block.bounds.width().toLong() * block.bounds.height().toLong()
        area >= screenArea / 4
      }
    if (veryLargeBlocks > 0 && blocks.size <= 3) return true

    val averageChars = blocks.sumOf { it.text.length }.toFloat() / blocks.size
    val veryTallThinBlocks =
      blocks.count { block ->
        block.bounds.height() > screenHeight / 3 && block.bounds.width() > screenWidth / 2
      }
    if (veryTallThinBlocks > 0 && averageChars > 40f) return true

    return false
  }

  private fun swapLanguages() {
    val oldSource = forcedSourceLanguage
    val oldTarget = forcedTargetLanguage ?: settingsManager.settings.value.defaultTargetLanguage
    forcedSourceLanguage = oldTarget
    forcedTargetLanguage = oldSource
    updateToolbarLabels()
    retranslate()
  }

  private fun retranslate() {
    translationJob?.cancel()
    translationJob = null
    processing = false
    overlayContainer.removeAllViews()
    showLoading(true)
    hideStatus()
    maybeProcessCapture()
  }

  private fun updateToolbarLabels() {
    sourceLabelView?.text = forcedSourceLanguage?.shortDisplayName ?: "Auto"
    val currentTarget = forcedTargetLanguage ?: settingsManager.settings.value.defaultTargetLanguage
    targetLabelView?.text = currentTarget.shortDisplayName
  }

  private fun showLanguagePicker(isSource: Boolean) {
    menuManager?.showLanguagePicker(
      isSource = isSource,
      availableLangs = overlayTextTranslationHelper.availableLanguages(isSource),
    ) { language ->
      if (isSource) {
        forcedSourceLanguage = language
      } else {
        forcedTargetLanguage = language
      }
      updateToolbarLabels()
      retranslate()
    }
  }

  private fun showDotsMenu() {
    menuManager?.showDotsMenu(
      listOf(
        "Open App" to { startAssistantActivity(Intent(context, MainActivity::class.java)) },
      ),
    )
  }

  private fun dismissMenu() {
    menuManager?.dismiss()
  }
}
