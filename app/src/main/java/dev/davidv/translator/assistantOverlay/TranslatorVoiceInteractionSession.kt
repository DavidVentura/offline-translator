package dev.davidv.translator.assistantOverlay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
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

  override fun onCreate() {
    super.onCreate()
    configureSessionWindow()
    langStateManager.refreshLanguageAvailability()
  }

  override fun onCreateContentView(): View {
    rootView = FrameLayout(context)
    rootView.setBackgroundColor(Color.BLACK)
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
        scaleType = ImageView.ScaleType.FIT_XY
        setBackgroundColor(Color.BLACK)
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
    showStatus("Collecting screen context...")
    showLoading(true)
    scheduleAssistCollectionTimeout()
  }

  override fun onComputeInsets(outInsets: Insets) {
    outInsets.contentInsets.set(0, 0, 0, 0)
    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
  }

  override fun onHide() {
    super.onHide()
    clearCapture()
    overlayContainer.removeAllViews()
    dismissMenu()
    screenshotView.setImageDrawable(null)
    showLoading(false)
  }

  override fun onDestroy() {
    sessionScope.cancel()
    clearCapture()
    super.onDestroy()
  }

  override fun onHandleAssist(state: AssistState) {
    super.onHandleAssist(state)
    expectedAssistCount = maxOf(expectedAssistCount ?: 0, state.count)
    if (state.index >= 0) {
      receivedAssistIndexes += state.index
    }

    val structure = state.assistStructure
    if (structure == null) {
      Log.w(tag, "AssistStructure missing for index=${state.index}")
      if (!assistFallbackMessageShown) {
        assistFallbackMessageShown = true
        assistFallbackStatusPendingHide = true
        showStatus("App does not provide data, falling back to OCR")
      }
      maybeProcessCapture()
      return
    }

    logger.log(state, structure)
    capturedBlocks += parser.parse(structure)
    maybeProcessCapture()
  }

  override fun onHandleScreenshot(screenshot: Bitmap?) {
    super.onHandleScreenshot(screenshot)
    val oldSs = screenshotBitmap
    val oldCr = croppedBitmap
    oldSs?.recycle()
    if (oldCr != null && oldCr !== oldSs) oldCr.recycle()
    screenshotBitmap = screenshot?.copy(Bitmap.Config.ARGB_8888, false)
    croppedBitmap = screenshotBitmap?.let { cropSystemBars(it) }
    screenshotView.setImageBitmap(croppedBitmap)
    maybeProcessCapture()
  }

  private fun maybeProcessCapture() {
    if (processing) return

    val expectedCount = expectedAssistCount
    val haveAllAssistStates = expectedCount != null && receivedAssistIndexes.size >= expectedCount
    val assistReady = haveAllAssistStates || assistCollectionTimedOut
    val screenshotReady = screenshotBitmap != null

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
    }
  }

  private fun scheduleAssistCollectionTimeout() {
    cancelAssistCollectionTimeout()
    assistCollectionTimedOut = false
    assistCollectionTimeoutJob =
      sessionScope.launch {
        delay(assistCollectionTimeoutMs)
        assistCollectionTimedOut = true
        if (!processing && screenshotBitmap != null) {
          Log.w(tag, "Timed out waiting for complete assist data; continuing with available capture")
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
              overlayContainer, blocksByText, result.results, screenshot, systemBarTop,
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
        if (assistFallbackStatusPendingHide) {
          assistFallbackStatusPendingHide = false
          showStatus("App does not provide data, falling back to OCR", autoHideAfterMs = 3000)
        } else {
          hideStatus()
        }
      }
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

  private fun configureSessionWindow() {
    val dialog = window ?: return
    val win = dialog.window ?: return
    win.setLayout(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
    )
    win.setGravity(Gravity.TOP or Gravity.START)
    win.setBackgroundDrawableResource(android.R.color.transparent)
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
    cancelAssistCollectionTimeout()
    statusHideJob?.cancel()
    statusHideJob = null
  }

  private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

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
