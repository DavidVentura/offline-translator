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

package dev.davidv.translator.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageAvailabilityState
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.MainSelection
import dev.davidv.translator.MainSelectionRules
import dev.davidv.translator.OverlaySource
import dev.davidv.translator.PreparedImageOverlay
import dev.davidv.translator.R
import dev.davidv.translator.ReadingOrder
import dev.davidv.translator.Script
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.TranslatedText
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslationResult
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.nextReadingOrder
import dev.davidv.translator.ui.components.DetectedRegions
import dev.davidv.translator.ui.components.ImageWordSelection
import dev.davidv.translator.wordSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MainInput {
  val text: String

  data class Text(
    override val text: String,
  ) : MainInput

  // `display` starts as the original and becomes the rendered translation once OCR has run;
  // `text` is what OCR extracted from it.
  data class Image(
    val original: Bitmap,
    val display: Bitmap,
    override val text: String,
    val words: ImageWordSelection?,
  ) : MainInput

  data object Document : MainInput {
    override val text: String get() = ""
  }
}

// A text detection only stays valid while the input is at least as long as
// the text that produced it; deleting below that point drops the detection
// so a stale suggestion can't outlive the words it was based on.
internal data class LanguageDetection(
  val language: Language,
  val inputLength: Int,
)

internal fun refreshedDetection(
  prior: LanguageDetection?,
  text: String,
  detected: Language?,
): LanguageDetection? =
  when {
    text.isBlank() -> null
    detected != null -> LanguageDetection(detected, text.length)
    else -> prior?.takeIf { text.length >= it.inputLength }
  }

// Text, image and steer runs all write the same output for the same pair, so they share one
// in-flight job: starting any of them cancels the previous one, and so does every write to the
// selection or the input from outside the job. A running job can therefore trust the pair and
// input it was started with.
class TranslationSession(
  private val scope: CoroutineScope,
  private val appContext: Context,
  private val translationCoordinator: TranslationCoordinator,
  private val settingsManager: SettingsManager,
  private val languageStateManager: LanguageStateManager,
  private val languageMetadataManager: LanguageMetadataManager,
  private val uiEvents: UiEvents,
  initialText: String,
) {
  private val _selection = MutableStateFlow(selectionRules().initial())
  val selection: StateFlow<MainSelection> = _selection.asStateFlow()

  private val _isAutoSource = MutableStateFlow(false)
  val isAutoSource: StateFlow<Boolean> = _isAutoSource.asStateFlow()

  private val _input = MutableStateFlow<MainInput>(MainInput.Text(initialText))
  val input: StateFlow<MainInput> = _input.asStateFlow()

  private val _output = MutableStateFlow<TranslatedText?>(null)
  val output: StateFlow<TranslatedText?> = _output.asStateFlow()

  private val detection = MutableStateFlow<LanguageDetection?>(null)
  val currentDetectedLanguage: StateFlow<Language?> =
    detection
      .map { it?.language }
      .stateIn(scope, SharingStarted.Eagerly, null)

  private val _ocrReadingOrder = MutableStateFlow<ReadingOrder?>(null)
  val ocrReadingOrder: StateFlow<ReadingOrder?> = _ocrReadingOrder.asStateFlow()

  private data class OcrCacheEntry(
    val plan: PreparedImageOverlay,
    val imageRef: Bitmap,
    val sourceScript: Script,
    val readingOrder: ReadingOrder?,
    // Owns the source pixels rust-side so a language switch re-renders without re-OCR or any
    // image copy across the FFI. Null for images translated via the legacy copying path.
    val ocrImage: uniffi.bindings.OcrImage?,
  )

  private var ocrCache: OcrCacheEntry? = null

  private var translationJob: Job? = null

  // A cancelled image run's cleanup can land after its replacement has started, so each run
  // clears only its own token instead of a shared boolean. The regions it found die with it.
  private class ImageRun {
    val regions = MutableStateFlow<DetectedRegions?>(null)
  }

  private val activeImageRun = MutableStateFlow<ImageRun?>(null)
  val isImageProcessing: StateFlow<Boolean> =
    activeImageRun.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

  @OptIn(ExperimentalCoroutinesApi::class)
  val detectedRegions: StateFlow<DetectedRegions?> =
    activeImageRun
      .flatMapLatest { it?.regions ?: flowOf(null) }
      .stateIn(scope, SharingStarted.Eagerly, null)

  // japaneseSpaced is read by TranslationService itself; it is carried here only so
  // toggling the setting invalidates the request and re-runs the transliteration.
  private data class TransliterationRequest(
    val text: String,
    val from: Language,
    val japaneseSpaced: Boolean,
  )

  @OptIn(ExperimentalCoroutinesApi::class)
  val inputTransliterated: StateFlow<String?> =
    combine(_input, _selection, settingsManager.settings) { input, selection, settings ->
      val enabled = settings.showTransliterationOnInput && input is MainInput.Text
      if (!enabled || selection !is MainSelection.Ready || input.text.isBlank()) {
        null
      } else {
        TransliterationRequest(input.text, selection.source, settings.addSpacesForJapaneseTransliteration)
      }
    }.distinctUntilChanged()
      .mapLatest { request ->
        if (request == null) {
          null
        } else {
          withContext(Dispatchers.Default) { translationCoordinator.transliterate(request.text, request.from) }
        }
      }.stateIn(scope, SharingStarted.Eagerly, null)

  init {
    scope.launch {
      languageStateManager.languageState.collect { availability ->
        val previous = _selection.value
        val next = selectionRules(availability).reconciled(previous)
        if (next == previous) return@collect
        _selection.value = next
        when (next) {
          // A language deletion can leave nothing to translate while a run is in flight.
          MainSelection.NoLanguages -> translationJob?.cancel()
          // A deleted language can take the pair the output was produced for with it.
          is MainSelection.Ready -> if (previous is MainSelection.Ready) retranslate(next)
        }
      }
    }

    // Owned by translationJob so text typed before it finishes cancels it instead of being
    // overwritten by the initial text's late result.
    if (initialText.isNotBlank()) {
      scope.launch {
        val start = _selection.filterIsInstance<MainSelection.Ready>().first()
        replaceJob { translateInitialText(initialText, start) }
      }
    }

    scope.launch {
      _selection
        .filterIsInstance<MainSelection.Ready>()
        .map { it.source to it.target }
        .distinctUntilChanged()
        .collect { (from, to) -> translationCoordinator.preloadModel(from, to) }
    }
  }

  fun handle(message: TranslatorMessage.PairMessage) {
    val selection = currentPair(message)
    when (message) {
      is TranslatorMessage.TextInput -> setText(message.text, selection)
      is TranslatorMessage.FromLang -> {
        _isAutoSource.value = false
        select(selectionRules().withSource(selection, message.language, message.change))
      }
      TranslatorMessage.EnableAutoSource -> {
        _isAutoSource.value = true
        select(selection)
      }
      is TranslatorMessage.ToLang ->
        select(selectionRules().normalized(selection.source, selection.targets.select(message.language)))
      is TranslatorMessage.AddTab ->
        select(selectionRules().normalized(selection.source, selection.targets.add(message.language)))
      is TranslatorMessage.RemoveTab -> removeTab(message.language, selection)
      TranslatorMessage.SwapLanguages ->
        selectionRules().swapped(selection)?.let { swapped ->
          _isAutoSource.value = false
          select(swapped)
        }
      is TranslatorMessage.SetImageUri ->
        replaceJob {
          val bitmap = translationCoordinator.correctBitmap(message.uri, message.deleteAfterLoad)
          ocrCache = null
          val image = MainInput.Image(bitmap, bitmap, "", null)
          _input.value = image
          detection.value = null
          _output.value = null
          runImageTranslation(image, selection)
        }
      TranslatorMessage.ToggleJapaneseOcrMode -> {
        _ocrReadingOrder.value = nextReadingOrder(_ocrReadingOrder.value)
        if (_input.value is MainInput.Image && selection.source.code == "ja") {
          retranslate(selection)
        }
      }
      is TranslatorMessage.Steer -> steer(message.forcedPrefix, selection)
    }
  }

  fun setDefaultTarget(language: Language) {
    settingsManager.update { it.copy(defaultTargetLanguageCode = language.code) }
    when (val selection = _selection.value) {
      // Settings is reachable before any pair is installed; the saved default is what the
      // first selection starts from once one is.
      MainSelection.NoLanguages -> Unit
      is MainSelection.Ready ->
        select(selectionRules().normalized(selection.source, selection.targets.select(language)))
    }
  }

  fun clearInput() {
    resetInput(MainInput.Text(""))
  }

  fun showDocument() {
    resetInput(MainInput.Document)
  }

  fun leaveDocument() {
    if (_input.value == MainInput.Document) resetInput(MainInput.Text(""))
  }

  fun shareImage() {
    val image = _input.value as? MainInput.Image ?: return
    uiEvents.send(UiEvent.ShareImage(image.display))
  }

  // Run a steer without committing it, for previewing what an alternative would
  // produce. Returns the fully re-decoded translation (not a spliced tail).
  suspend fun steerPreview(forcedPrefix: String): String? {
    val selection = currentPair("steer preview")
    val source = _output.value?.source
    if (source.isNullOrBlank()) return null
    return when (val r = translationCoordinator.steer(selection.source, selection.target, source, forcedPrefix)) {
      is TranslationResult.Success -> r.result.translated
      is TranslationResult.Error -> null
    }
  }

  fun release() {
    val image = _input.value as? MainInput.Image ?: return
    if (!image.display.isRecycled) image.display.recycle()
    if (!image.original.isRecycled) image.original.recycle()
  }

  // The screens that need a pair only exist while one is selected, and the selection only
  // drops to NoLanguages by navigating away from them.
  private fun currentPair(request: Any): MainSelection.Ready {
    val selection = _selection.value
    check(selection is MainSelection.Ready) { "$request needs a language pair but none is selected" }
    return selection
  }

  private fun setText(
    text: String,
    selection: MainSelection.Ready,
  ) {
    val current = _input.value
    if (current is MainInput.Text && current.text == text) return
    ocrCache = null
    _input.value = MainInput.Text(text)
    if (text.isBlank()) detection.value = null
    retranslate(selection)
  }

  private fun removeTab(
    language: Language,
    selection: MainSelection.Ready,
  ) {
    val targets = selection.targets.remove(language)
    if (targets == selection.targets) return
    val next = selection.copy(targets = targets)
    _selection.value = next
    if (next.target != selection.target) {
      _output.value = null
      retranslate(next)
    }
  }

  private fun steer(
    forcedPrefix: String,
    selection: MainSelection.Ready,
  ) {
    val source = _output.value?.source
    if (source.isNullOrBlank()) return
    replaceJob {
      when (val r = translationCoordinator.steer(selection.source, selection.target, source, forcedPrefix)) {
        is TranslationResult.Success -> _output.value = r.result
        is TranslationResult.Error -> Log.e("Steer", r.message)
      }
    }
  }

  private fun select(next: MainSelection.Ready) {
    _selection.value = next
    _output.value = null
    retranslate(next)
  }

  private fun resetInput(next: MainInput) {
    translationJob?.cancel()
    ocrCache = null
    _input.value = next
    _output.value = null
    detection.value = null
  }

  private fun replaceJob(block: suspend () -> Unit) {
    translationJob?.cancel()
    translationJob = scope.launch { block() }
  }

  private fun retranslate(selection: MainSelection.Ready) {
    replaceJob { translate(withDetectedSource(selection)) }
  }

  private suspend fun withDetectedSource(selection: MainSelection.Ready): MainSelection.Ready {
    if (settingsManager.settings.value.disableCLD) return selection
    val text = _input.value.text
    val detected =
      if (text.isBlank()) {
        null
      } else {
        translationCoordinator.detectLanguageRobust(text, selection.source, languageStateManager.languageState.value.allLanguages())
      }
    detection.value = refreshedDetection(detection.value, text, detected)
    val source = detection.value?.language?.takeIf { _isAutoSource.value && canBeSource(it) }
    if (source == null || source == selection.target) return selection
    return selectionRules().normalized(source, selection.targets).also { _selection.value = it }
  }

  private suspend fun translate(selection: MainSelection.Ready) {
    when (val input = _input.value) {
      is MainInput.Text ->
        when (val result = translationCoordinator.translateText(selection.source, selection.target, input.text.trim())) {
          is TranslationResult.Success -> _output.value = result.result
          is TranslationResult.Error -> {
            _output.value = null
            uiEvents.send(UiEvent.ShowToast(appContext.getString(R.string.translation_error, result.message)))
          }
        }
      is MainInput.Image -> runImageTranslation(input, selection)
      MainInput.Document -> _output.value = null
    }
  }

  private suspend fun translateInitialText(
    text: String,
    start: MainSelection.Ready,
  ) {
    val detected =
      if (!settingsManager.settings.value.disableCLD) {
        translationCoordinator.detectLanguageRobust(text, start.source, languageStateManager.languageState.value.allLanguages())
      } else {
        null
      }
    detection.value = detected?.let { LanguageDetection(it, text.length) }
    if (detected != null && !canBeSource(detected)) return
    val selection = detected?.let { selectionRules().normalized(it, start.targets).also { next -> _selection.value = next } } ?: start
    _output.value =
      when (val translated = translationCoordinator.translateText(selection.source, selection.target, text)) {
        is TranslationResult.Success -> translated.result
        is TranslationResult.Error -> null
      }
  }

  private suspend fun runImageTranslation(
    image: MainInput.Image,
    selection: MainSelection.Ready,
  ) {
    val run = ImageRun()
    activeImageRun.value = run
    try {
      translateImage(image, selection, run)
    } finally {
      activeImageRun.compareAndSet(run, null)
    }
  }

  private suspend fun translateImage(
    image: MainInput.Image,
    selection: MainSelection.Ready,
    run: ImageRun,
  ) {
    val fromLang = selection.source
    val toLang = selection.target
    _input.value = image.copy(words = null)
    val readingOrder = if (fromLang.code == "ja") _ocrReadingOrder.value else null
    val cached =
      ocrCache?.takeIf { entry ->
        !_isAutoSource.value &&
          entry.imageRef === image.original &&
          entry.readingOrder == readingOrder &&
          entry.sourceScript == fromLang.script
      }
    val result =
      if (cached != null) {
        Log.d("OCR", "reusing OCR result for source=${fromLang.code} (script=${fromLang.script})")
        translationCoordinator.retranslateImageWithOverlay(cached.plan, fromLang, toLang, ocrImage = cached.ocrImage)
      } else {
        val source =
          if (_isAutoSource.value && !settingsManager.settings.value.disableCLD) {
            OverlaySource.Auto(readingOrder)
          } else {
            OverlaySource.Fixed(fromLang, readingOrder)
          }
        translationCoordinator.translateImageWithOverlay(
          source,
          toLang,
          image.original,
          onMissingDetectedLanguage = { detected ->
            detection.value = LanguageDetection(detected, 0)
          },
          onDetectedRegions = { boxes, w, h ->
            run.regions.value = DetectedRegions(w, h, boxes)
          },
        )
      }
    result ?: return
    _input.value =
      image.copy(
        display = result.correctedBitmap,
        text = result.extractedText,
        words = result.wordSelection(toLang),
      )
    _output.value = TranslatedText(result.translatedText, null)
    ocrCache =
      OcrCacheEntry(
        plan = result.metadata,
        imageRef = image.original,
        sourceScript = fromLang.script,
        readingOrder = readingOrder,
        ocrImage = result.ocrImage,
      )
  }

  private fun canBeSource(language: Language): Boolean = languageStateManager.languageState.value.canBeSource(language)

  private fun selectionRules(availability: LanguageAvailabilityState = languageStateManager.languageState.value) =
    MainSelectionRules(
      availability,
      settingsManager.settings.value,
      languageMetadataManager.metadata.value,
      languageStateManager::canTranslate,
    )
}
