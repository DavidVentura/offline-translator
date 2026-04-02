package dev.davidv.translator.assistantOverlay

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import dev.davidv.translator.BatchTextTranslationOutput
import dev.davidv.translator.BatchTextTranslator
import dev.davidv.translator.FilePathManager
import dev.davidv.translator.ImageProcessor
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageDetector
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.MainActivity
import dev.davidv.translator.NothingReason
import dev.davidv.translator.OCRService
import dev.davidv.translator.OverlayColors
import dev.davidv.translator.R
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslationService
import dev.davidv.translator.getOverlayColors
import dev.davidv.translator.overlayChrome.OverlayChromeFactory
import dev.davidv.translator.overlayChrome.OverlayMenuHost
import dev.davidv.translator.overlayChrome.OverlayMenuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.davidv.translator.Rect as TranslatorRect

class TranslatorVoiceInteractionSession(
  context: Context,
) : VoiceInteractionSession(context) {
  private val tag = "TranslatorAssistant"
  private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val parser = AssistStructureParser()

  private val settingsManager = SettingsManager(context)
  private val filePathManager = FilePathManager(context, settingsManager.settings)
  private val languageMetadataManager = LanguageMetadataManager(context)
  private val imageProcessor = ImageProcessor(context, OCRService(filePathManager))
  private val translationCoordinator =
    TranslationCoordinator(
      context = context,
      translationService = TranslationService(settingsManager, filePathManager),
      languageDetector = LanguageDetector(),
      imageProcessor = imageProcessor,
      settingsManager = settingsManager,
      enableToast = false,
    )
  private val batchTextTranslator = BatchTextTranslator(translationCoordinator)
  private val langStateManager = LanguageStateManager(sessionScope, filePathManager, null)

  private lateinit var rootView: FrameLayout
  private lateinit var screenshotView: ImageView
  private lateinit var overlayContainer: FrameLayout
  private lateinit var statusView: TextView
  private lateinit var loadingView: View
  private lateinit var topBarView: View
  private var sourceLabelView: TextView? = null
  private var targetLabelView: TextView? = null
  private var menuManager: OverlayMenuManager? = null
  private var measureTextView: TextView? = null

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
              FrameLayout.LayoutParams(
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
              FrameLayout.LayoutParams(
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
      FrameLayout.LayoutParams(
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
      FrameLayout.LayoutParams(
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
      FrameLayout.LayoutParams(
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

    logAssistStructure(state, structure)
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
    val screenshotReady = screenshotBitmap != null

    if (capturedBlocks.isNotEmpty() && (screenshotReady || haveAllAssistStates)) {
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

    if (capturedBlocks.isEmpty() && screenshotReady && haveAllAssistStates) {
      processing = true
      runOcrFallback(screenshotBitmap ?: return)
    }
  }

  private fun translateStructuredBlocks(
    blocks: List<CapturedTextBlock>,
    screenshot: Bitmap?,
  ) {
    translationJob =
      sessionScope.launch {
        val (langs, targetLanguage) = awaitTranslationSetup()
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

        val forcedSource = forcedSourceLanguage ?: settingsManager.settings.value.defaultSourceLanguage
        val result =
          batchTextTranslator.translateTexts(
            inputs = blocksByText.keys.toList(),
            forcedSourceLanguage = forcedSource,
            targetLanguage = targetLanguage,
            availableLanguages = langs,
          )

        when (result) {
          is BatchTextTranslationOutput.NothingToTranslate -> {
            processing = false
            showLoading(false)
            val message =
              when (result.reason) {
                NothingReason.ALREADY_TARGET_LANGUAGE -> "Already in ${targetLanguage.displayName}"
                NothingReason.COULD_NOT_DETECT -> "Could not detect source language"
                NothingReason.NO_TRANSLATABLE_TEXT -> "No translatable visible text found"
              }
            showStatus(message)
          }
          is BatchTextTranslationOutput.Translated -> {
            overlayContainer.removeAllViews()
            val screenHeight = context.resources.displayMetrics.heightPixels
            val items = mutableListOf<OverlayItem>()
            for ((originalText, groupedBlocks) in blocksByText) {
              val translatedText = result.results[originalText] ?: continue
              for (block in groupedBlocks) {
                val adjustedTop = block.bounds.top - systemBarTop
                val adjustedBottom = block.bounds.bottom - systemBarTop
                val visibleHeight = minOf(adjustedBottom, screenHeight) - maxOf(adjustedTop, 0)
                if (visibleHeight < block.bounds.height() / 2) continue
                items.add(OverlayItem(translatedText, block, resolveColors(block, screenshot)))
              }
            }
            val groups = groupOverlapping(items)
            for (group in groups) {
              if (group.size == 1) {
                val item = group.first()
                addOverlayBlock(item.translatedText, item.block, item.colors)
              } else {
                addOverlayGroup(group)
              }
            }

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
        processing = false
        showLoading(false)
        if (result == null) {
          showStatus("OCR fallback failed")
          return@launch
        }
        croppedBitmap?.recycle()
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

  private fun resolveColors(
    block: CapturedTextBlock,
    screenshot: Bitmap?,
  ): OverlayColors {
    val sampledColors =
      screenshot?.let {
        val translatorRect =
          TranslatorRect(
            block.bounds.left,
            block.bounds.top,
            block.bounds.right,
            block.bounds.bottom,
          )
        getOverlayColors(it, translatorRect, settingsManager.settings.value.backgroundMode)
      }

    val style = block.style
    val styleBg = normalizeStyleColor(style?.textBackgroundColor)
    val styleFg = normalizeStyleColor(style?.textColor)
    if (styleBg == null && styleFg != null) {
      val lum = (Color.red(styleFg) * 299 + Color.green(styleFg) * 587 + Color.blue(styleFg) * 114) / 255000f
      val bg = if (lum > 0.5f) Color.parseColor("#AA000000") else Color.parseColor("#AAF0F0F0")
      return OverlayColors(bg, styleFg)
    }
    val backgroundColor = styleBg ?: sampledColors?.background ?: Color.parseColor("#F0FFFFFF")
    val foregroundColor = styleFg ?: sampledColors?.foreground ?: Color.BLACK
    return OverlayColors(backgroundColor, foregroundColor)
  }

  private fun addOverlayBlock(
    translatedText: String,
    block: CapturedTextBlock,
    colors: OverlayColors,
  ) {
    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels
    val targetHeight = block.bounds.height().coerceAtLeast(1)
    val left = block.bounds.left.coerceIn(0, screenWidth - 1)
    val top = (block.bounds.top - systemBarTop).coerceIn(0, screenHeight - 1)
    val width = maxOf(dpToPx(48), minOf(block.bounds.width(), screenWidth - left))
    if (width <= 0) return

    val container =
      FrameLayout(context).apply {
        background =
          GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8).toFloat()
            setColor(colors.background)
          }
      }

    val textView =
      TextView(context).apply {
        text = translatedText
        setTextColor(colors.foreground)
        setPadding(0, 0, 0, 0)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        maxLines = 10

        val style = block.style
        val requestedTextSizePx =
          style?.textSizePx
            ?.takeIf { it > 0f }
            ?.let { normalizeReportedTextSizePx(it, block.fromWebView) }
            ?.times(settingsManager.settings.value.fontFactor)
        applyTextStyle(this, style?.styleBits ?: 0)
        setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        val initialTextSizePx =
          requestedTextSizePx
            ?: minOf(
              48f * context.resources.displayMetrics.scaledDensity,
              targetHeight.toFloat(),
            ).coerceAtLeast(12f)
        setTextSize(
          TypedValue.COMPLEX_UNIT_PX,
          findFittingTextSizePx(
            translatedText = translatedText,
            width = width,
            targetHeight = targetHeight,
            initialTextSizePx = initialTextSizePx,
            styleBits = style?.styleBits ?: 0,
          ),
        )
      }

    container.addView(
      textView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )

    val params =
      FrameLayout.LayoutParams(
        width,
        targetHeight,
      ).apply {
        leftMargin = left
        topMargin = top
      }
    overlayContainer.addView(container, params)
  }

  private fun groupOverlapping(items: List<OverlayItem>): List<List<OverlayItem>> {
    val groups = mutableListOf<MutableList<OverlayItem>>()
    for (item in items) {
      val itemBg = item.block.style?.textBackgroundColor
      val overlapping =
        groups.filter { group ->
          group.any { existing ->
            Rect.intersects(existing.block.bounds, item.block.bounds) &&
              existing.block.style?.textBackgroundColor == itemBg
          }
        }
      if (overlapping.isEmpty()) {
        groups.add(mutableListOf(item))
      } else {
        val merged = mutableListOf<OverlayItem>()
        for (group in overlapping) {
          merged.addAll(group)
          groups.remove(group)
        }
        merged.add(item)
        groups.add(merged)
      }
    }
    return groups
  }

  private fun addOverlayGroup(group: List<OverlayItem>) {
    val unionBounds = Rect(group.first().block.bounds)
    for (item in group.drop(1)) unionBounds.union(item.block.bounds)

    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels
    val left = unionBounds.left.coerceIn(0, screenWidth - 1)
    val top = (unionBounds.top - systemBarTop).coerceIn(0, screenHeight - 1)
    val width = maxOf(dpToPx(48), minOf(unionBounds.width(), screenWidth - left))
    val targetHeight = unionBounds.height().coerceAtLeast(1)

    val ssb = SpannableStringBuilder()
    var representativeStyle: CapturedTextStyle? = null
    for (item in group) {
      if (ssb.isNotEmpty() && !ssb.endsWith(' ') && !item.translatedText.startsWith(' ')) {
        ssb.append(' ')
      }
      val start = ssb.length
      ssb.append(item.translatedText)
      val end = ssb.length
      ssb.setSpan(ForegroundColorSpan(item.colors.foreground), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      val styleBits = item.block.style?.styleBits ?: 0
      if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_BOLD != 0) {
        ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_ITALIC != 0) {
        ssb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_UNDERLINE != 0) {
        ssb.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_STRIKE_THRU != 0) {
        ssb.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      if (representativeStyle == null) representativeStyle = item.block.style
    }

    val style = representativeStyle
    val initialTextSizePx =
      style?.textSizePx
        ?.takeIf { it > 0f }
        ?.let { normalizeReportedTextSizePx(it, group.first().block.fromWebView) }
        ?.times(settingsManager.settings.value.fontFactor)
        ?: minOf(48f * context.resources.displayMetrics.scaledDensity, targetHeight.toFloat())
          .coerceAtLeast(12f)

    val container =
      FrameLayout(context).apply {
        background =
          GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8).toFloat()
            setColor(group.first().colors.background)
          }
      }

    val textView =
      TextView(context).apply {
        text = ssb
        setPadding(0, 0, 0, 0)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        maxLines = 20
        setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        setTextSize(
          TypedValue.COMPLEX_UNIT_PX,
          findFittingTextSizePx(
            translatedText = ssb.toString(),
            width = width,
            targetHeight = targetHeight,
            initialTextSizePx = initialTextSizePx,
            styleBits = style?.styleBits ?: 0,
          ),
        )
      }

    container.addView(
      textView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )

    val params =
      FrameLayout.LayoutParams(
        width,
        targetHeight,
      ).apply {
        leftMargin = left
        topMargin = top
      }
    overlayContainer.addView(container, params)
  }

  private data class OverlayItem(
    val translatedText: String,
    val block: CapturedTextBlock,
    val colors: OverlayColors,
  )

  private fun applyTextStyle(
    textView: TextView,
    styleBits: Int,
  ) {
    var typefaceStyle = Typeface.NORMAL
    if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_BOLD != 0) {
      typefaceStyle = typefaceStyle or Typeface.BOLD
    }
    if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_ITALIC != 0) {
      typefaceStyle = typefaceStyle or Typeface.ITALIC
    }
    textView.typeface = Typeface.create(Typeface.DEFAULT, typefaceStyle)

    var flags = textView.paintFlags
    if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_UNDERLINE != 0) {
      flags = flags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
    }
    if (styleBits and AssistStructure.ViewNode.TEXT_STYLE_STRIKE_THRU != 0) {
      flags = flags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
    }
    textView.paintFlags = flags
  }

  private suspend fun awaitTranslationSetup(): Pair<List<Language>, Language> {
    langStateManager.languageState.first { !it.isChecking }
    val langs =
      langStateManager.languageState.value.availableLanguageMap
        .filterValues { it.translatorFiles }
        .keys
        .toList()
    val targetLanguage = forcedTargetLanguage ?: settingsManager.settings.value.defaultTargetLanguage
    return langs to targetLanguage
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
      FrameLayout.LayoutParams(
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
    statusHideJob?.cancel()
    statusHideJob = null
  }

  private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

  private fun normalizeStyleColor(color: Int?): Int? {
    if (color == null) return null
    if (Color.alpha(color) == 0) return null
    return color
  }

  private fun normalizeReportedTextSizePx(
    reportedSize: Float,
    fromWebView: Boolean,
  ): Float {
    if (!fromWebView) return reportedSize
    return reportedSize * context.resources.displayMetrics.density
  }

  private fun findFittingTextSizePx(
    translatedText: String,
    width: Int,
    targetHeight: Int,
    initialTextSizePx: Float,
    styleBits: Int,
  ): Float {
    val minTextSizePx = 8f * context.resources.displayMetrics.scaledDensity
    var sizePx = initialTextSizePx
    while (sizePx > minTextSizePx) {
      if (measuredTextHeight(translatedText, width, sizePx, styleBits) <= targetHeight) {
        return sizePx
      }
      sizePx -= 1f
    }
    return minTextSizePx
  }

  private fun getOrCreateMeasureView(): TextView {
    measureTextView?.let { return it }
    val tv =
      TextView(context).apply {
        layoutParams =
          FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
          )
        setPadding(0, 0, 0, 0)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        maxLines = 10
        setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
      }
    measureTextView = tv
    return tv
  }

  private fun measuredTextHeight(
    translatedText: String,
    width: Int,
    textSizePx: Float,
    styleBits: Int,
  ): Int {
    val tv = getOrCreateMeasureView()
    tv.text = translatedText
    tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
    applyTextStyle(tv, styleBits)
    val exactWidth = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
    val unspecifiedHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    tv.measure(exactWidth, unspecifiedHeight)
    return tv.measuredHeight
  }

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

  private fun logAssistStructure(
    state: AssistState,
    structure: AssistStructure,
  ) {
    Log.d(tag, "AssistStructure index=${state.index}/${state.count} windows=${structure.windowNodeCount}")
    for (windowIndex in 0 until structure.windowNodeCount) {
      val window = structure.getWindowNodeAt(windowIndex)
      Log.d(
        tag,
        "Window[$windowIndex] left=${window.left} top=${window.top} width=${window.width} height=${window.height} title=${window.title}",
      )
      logViewNode(window.rootViewNode, window.left, window.top, depth = 0)
    }

    val parsedBlocks = parser.parse(structure)
    Log.d(tag, "Parsed text blocks: ${parsedBlocks.size}")
    parsedBlocks.forEachIndexed { index, block ->
      Log.d(
        tag,
        "Parsed[$index] text=${block.text} bounds=${block.bounds.toShortString()} size=${block.style?.textSizePx} color=${block.style?.textColor} bg=${block.style?.textBackgroundColor} style=${block.style?.styleBits}",
      )
    }
  }

  private fun logViewNode(
    node: AssistStructure.ViewNode,
    baseLeft: Int,
    baseTop: Int,
    depth: Int,
  ) {
    val indent = "  ".repeat(depth)
    val left = baseLeft + node.left - node.scrollX
    val top = baseTop + node.top - node.scrollY
    val bounds = Rect(left, top, left + node.width, top + node.height)
    val line =
      buildString {
        append(indent)
        append("node class=")
        append(node.className)
        append(" id=")
        append(node.idEntry)
        append(" text=")
        append(node.text)
        append(" hint=")
        append(node.hint)
        append(" desc=")
        append(node.contentDescription)
        append(" bounds=")
        append(bounds.toShortString())
        append(" size=")
        append(node.textSize)
        append(" color=")
        append(node.textColor)
        append(" bg=")
        append(node.textBackgroundColor)
        append(" style=")
        append(node.textStyle)
        append(" baselines=")
        append(node.textLineBaselines?.contentToString())
        append(" charOffsets=")
        append(node.textLineCharOffsets?.contentToString())
        append(" scroll=(")
        append(node.scrollX)
        append(",")
        append(node.scrollY)
        append(")")
        append(" transform=")
        append(node.transformation)
        append(" alpha=")
        append(node.alpha)
        append(" visibility=")
        append(visibilityName(node.visibility))
      }
    Log.d(tag, line)

    for (childIndex in 0 until node.childCount) {
      val child = node.getChildAt(childIndex) ?: continue
      logViewNode(child, left, top, depth + 1)
    }
  }

  private fun visibilityName(value: Int): String =
    when (value) {
      View.VISIBLE -> "VISIBLE"
      View.INVISIBLE -> "INVISIBLE"
      View.GONE -> "GONE"
      else -> value.toString()
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
      availableLangs = availableLanguages(isSource),
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

  private fun availableLanguages(isSource: Boolean): List<Language> {
    val metadata = languageMetadataManager.metadata.value
    return langStateManager.languageState.value.availableLanguageMap
      .filterValues { it.translatorFiles && (!isSource || it.ocrFiles) }
      .keys
      .toList()
      .sortedWith(
        compareByDescending<Language> { metadata[it]?.favorite ?: false }
          .thenBy { it.displayName },
      )
  }
}
