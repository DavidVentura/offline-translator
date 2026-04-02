package dev.davidv.translator.assistantOverlay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
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
import dev.davidv.bergamot.TokenAlignment
import dev.davidv.translator.BatchAlignedTranslationResult
import dev.davidv.translator.ImageProcessor
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.MainActivity
import dev.davidv.translator.OverlayTextTranslationHelper
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.StyledFragment
import dev.davidv.translator.TranslatedStyledBlock
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslationSegment
import dev.davidv.translator.clusterFragmentsIntoBlocks
import dev.davidv.translator.mapStylesToSegmentedTranslation
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
  private var menuManager: OverlayMenuManager? = null
  private var borderView: BorderWaveView? = null

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
      if (screenshotReady && shouldUseOcrFallback()) {
        processing = true
        Log.d(tag, "Using OCR fallback because parsed AssistStructure is too coarse")
        runOcrFallback(screenshotBitmap ?: return)
        return
      }
      processing = true
      translateStructuredFragments(capturedFragments.toList(), screenshotBitmap)
      return
    }

    if (capturedFragments.isEmpty() && screenshotReady && assistReady) {
      cancelAssistCollectionTimeout()
      processing = true
      runOcrFallback(screenshotBitmap ?: return)
      return
    }

    if (assistReady && capturedFragments.isEmpty() && !screenshotReady) {
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

  private fun translateStructuredFragments(
    fragments: List<StyledFragment>,
    screenshot: Bitmap?,
  ) {
    translationJob =
      sessionScope.launch {
        for ((idx, f) in fragments.withIndex()) {
          Log.d(tag, "Fragment[$idx] text='${f.text.take(60)}' bounds=[${f.bounds.left},${f.bounds.top},${f.bounds.right},${f.bounds.bottom}]")
        }
        val blocks = clusterFragmentsIntoBlocks(fragments)
        for ((idx, b) in blocks.withIndex()) {
          Log.d(tag, "Block[$idx] bounds=[${b.bounds.left},${b.bounds.top},${b.bounds.right},${b.bounds.bottom}] ${b.bounds.width()}x${b.bounds.height()} text='${b.text.take(80)}'")
        }
        if (blocks.isEmpty()) {
          processing = false
          showLoading(false)
          showStatus("No visible structured text found")
          return@launch
        }

        val targetLanguage = overlayTextTranslationHelper.awaitTargetLanguage(forcedTargetLanguage)
        val combinedText = blocks.joinToString(" ") { it.text }
        val sourceLanguage =
          forcedSourceLanguage
            ?: translationCoordinator.detectLanguageRobust(
              combinedText,
              null,
              overlayTextTranslationHelper.availableLanguages(true),
            )

        if (sourceLanguage == null) {
          processing = false
          showLoading(false)
          showStatus("Could not detect language — set source language manually")
          return@launch
        }
        if (sourceLanguage == targetLanguage) {
          processing = false
          showLoading(false)
          showStatus("Already in ${targetLanguage.displayName}")
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

        when (val result = translationCoordinator.translateTextsWithAlignment(sourceLanguage, targetLanguage, allSegmentTexts.toTypedArray())) {
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
            ensureActive()
            overlayRenderer.renderStyledBlocks(overlayContainer, translatedBlocks, screenshot, systemBarTop)
            processing = false
            showLoading(false)
            hideStatus()
          }
          is BatchAlignedTranslationResult.Error -> {
            processing = false
            showLoading(false)
            showStatus("Translation error")
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
    if (veryTallFragments > 0 && averageChars > 40f) return true

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
}
