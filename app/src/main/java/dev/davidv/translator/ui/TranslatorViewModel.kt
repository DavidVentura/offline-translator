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
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.davidv.translator.DocumentTranslationService
import dev.davidv.translator.DocumentTranslationServiceState
import dev.davidv.translator.DownloadService
import dev.davidv.translator.FileEvent
import dev.davidv.translator.FilePathManager
import dev.davidv.translator.FromLangChange
import dev.davidv.translator.InputType
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.LaunchMode
import dev.davidv.translator.PcmAudio
import dev.davidv.translator.PdfPhaseProgress
import dev.davidv.translator.PreparedImageOverlay
import dev.davidv.translator.R
import dev.davidv.translator.ReadingOrder
import dev.davidv.translator.Script
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.SpeechError
import dev.davidv.translator.SpeechSynthesisResult
import dev.davidv.translator.TargetTabs
import dev.davidv.translator.TranslatedText
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslationResult
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.TxtLayoutChoice
import dev.davidv.translator.WordWithTaggedEntries
import dev.davidv.translator.copyDocumentUriToCache
import dev.davidv.translator.displayNameForUri
import dev.davidv.translator.localizedName
import dev.davidv.translator.sizeBytesForUri
import dev.davidv.translator.ui.components.DetectedRegions
import dev.davidv.translator.ui.components.ImageWordSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TranslatorViewModel(
  private val appContext: Context,
  val translationCoordinator: TranslationCoordinator,
  val settingsManager: SettingsManager,
  val filePathManager: FilePathManager,
  val languageMetadataManager: LanguageMetadataManager,
  initialText: String,
  initialLaunchMode: LaunchMode,
) : ViewModel() {
  val languageStateManager = LanguageStateManager(viewModelScope, filePathManager)

  // Navigation state derived from language availability and from/to selection
  // This eliminates the need for from!! force-unwraps in the composable
  enum class NavigationState { LOADING, NO_LANGUAGES, READY }

  // UI state
  private val _input = MutableStateFlow(initialText)
  val input: StateFlow<String> = _input.asStateFlow()

  private val _output = MutableStateFlow<TranslatedText?>(null)
  val output: StateFlow<TranslatedText?> = _output.asStateFlow()

  private val _from = MutableStateFlow<Language?>(null)
  val from: StateFlow<Language?> = _from.asStateFlow()

  private val _targets = MutableStateFlow<TargetTabs?>(null)
  val targets: StateFlow<TargetTabs?> = _targets.asStateFlow()

  // Active target, derived from the tab zipper. Everything that just needs "the
  // current target" reads this; the tab list is only for the multi-target header.
  val to: StateFlow<Language?> =
    _targets.map { it?.active }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

  private val activeTo: Language? get() = _targets.value?.active

  private val _displayImage = MutableStateFlow<Bitmap?>(null)
  val displayImage: StateFlow<Bitmap?> = _displayImage.asStateFlow()

  private val _originalImage = MutableStateFlow<Bitmap?>(null)
  val originalImage: StateFlow<Bitmap?> = _originalImage.asStateFlow()

  private val _imageWordSelection = MutableStateFlow<ImageWordSelection?>(null)
  val imageWordSelection: StateFlow<ImageWordSelection?> = _imageWordSelection.asStateFlow()

  private val _detectedRegions = MutableStateFlow<DetectedRegions?>(null)
  val detectedRegions: StateFlow<DetectedRegions?> = _detectedRegions.asStateFlow()

  private data class OcrCacheEntry(
    val plan: PreparedImageOverlay,
    val imageRef: Bitmap,
    val sourceCode: String,
    val sourceScript: Script,
    val readingOrder: ReadingOrder?,
    // Owns the source pixels rust-side so a language switch re-renders without re-OCR or any
    // image copy across the FFI. Null for images translated via the legacy copying path.
    val ocrImage: uniffi.bindings.OcrImage?,
  )

  private var ocrCache: OcrCacheEntry? = null

  private val _ocrReadingOrder = MutableStateFlow<ReadingOrder?>(null)
  val ocrReadingOrder: StateFlow<ReadingOrder?> = _ocrReadingOrder.asStateFlow()

  private val _inputType = MutableStateFlow(InputType.TEXT)
  val inputType: StateFlow<InputType> = _inputType.asStateFlow()

  // japaneseSpaced is read by TranslationService itself; it is carried here only so
  // toggling the setting invalidates the request and re-runs the transliteration.
  private data class TransliterationRequest(
    val text: String,
    val from: Language,
    val japaneseSpaced: Boolean,
  )

  @OptIn(ExperimentalCoroutinesApi::class)
  val inputTransliterated: StateFlow<String?> =
    combine(_input, _from, _inputType, settingsManager.settings) { text, fromLang, inputType, settings ->
      val enabled = settings.showTransliterationOnInput && inputType == InputType.TEXT
      if (!enabled || fromLang == null || text.isBlank()) {
        null
      } else {
        TransliterationRequest(text, fromLang, settings.addSpacesForJapaneseTransliteration)
      }
    }.distinctUntilChanged()
      .mapLatest { request ->
        if (request == null) {
          null
        } else {
          withContext(Dispatchers.Default) { translationCoordinator.transliterate(request.text, request.from) }
        }
      }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

  // A text detection only stays valid while the input is at least as long as
  // the text that produced it; deleting below that point drops the detection
  // so a stale suggestion can't outlive the words it was based on.
  private data class LanguageDetection(
    val language: Language,
    val inputLength: Int,
  )

  private val _currentDetectedLanguage = MutableStateFlow<LanguageDetection?>(null)
  val currentDetectedLanguage: StateFlow<Language?> =
    _currentDetectedLanguage
      .map { it?.language }
      .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  private val _isAutoSource = MutableStateFlow(false)
  val isAutoSource: StateFlow<Boolean> = _isAutoSource.asStateFlow()

  private val _currentLaunchMode = MutableStateFlow(initialLaunchMode)
  val currentLaunchMode: StateFlow<LaunchMode> = _currentLaunchMode.asStateFlow()

  private val _modalVisible = MutableStateFlow(initialLaunchMode == LaunchMode.Normal)
  val modalVisible: StateFlow<Boolean> = _modalVisible.asStateFlow()

  private val _dictionaryWord = MutableStateFlow<WordWithTaggedEntries?>(null)
  val dictionaryWord: StateFlow<WordWithTaggedEntries?> = _dictionaryWord.asStateFlow()

  private val _dictionaryStack = MutableStateFlow<List<WordWithTaggedEntries>>(emptyList())
  val dictionaryStack: StateFlow<List<WordWithTaggedEntries>> = _dictionaryStack.asStateFlow()

  private val _dictionaryLookupLanguage = MutableStateFlow<Language?>(null)
  val dictionaryLookupLanguage: StateFlow<Language?> = _dictionaryLookupLanguage.asStateFlow()

  private val _ttsVoices = MutableStateFlow<Map<String, List<uniffi.translator_core.InstalledTtsPack>>>(emptyMap())
  val ttsVoices: StateFlow<Map<String, List<uniffi.translator_core.InstalledTtsPack>>> = _ttsVoices.asStateFlow()

  private val _documentTranslation = MutableStateFlow<DocumentTranslationUiState?>(null)
  val documentTranslation: StateFlow<DocumentTranslationUiState?> = _documentTranslation.asStateFlow()
  private var dismissedInProgressDocumentTaskId: Long? = null

  // Picked-but-not-yet-started document. Drives the Configure sheet
  // (source/dest + per-type options) before the translation service is
  // launched; cleared once the user confirms or cancels.
  private val _pendingDocument = MutableStateFlow<PendingDocument?>(null)
  val pendingDocument: StateFlow<PendingDocument?> = _pendingDocument.asStateFlow()

  // One-shot UI events (Toast, errors, etc.)
  private val _uiEvents = MutableSharedFlow<UiEvent>()
  val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

  private val _pendingSharedImage = MutableSharedFlow<Uri>(replay = 1, extraBufferCapacity = 1)
  val pendingSharedImage: SharedFlow<Uri> = _pendingSharedImage.asSharedFlow()

  // Deep link from the system TTS settings ("install voice data"). Conflated so a
  // request raised before the UI is listening is still delivered, exactly once.
  private val _languageManagerRequests = Channel<Unit>(Channel.CONFLATED)
  val languageManagerRequests: Flow<Unit> = _languageManagerRequests.receiveAsFlow()

  val navigationState: StateFlow<NavigationState> =
    combine(languageStateManager.languageState, _from, to) { langState, fromLang, toLang ->
      when {
        langState.isChecking -> NavigationState.LOADING
        !langState.hasLanguages -> NavigationState.NO_LANGUAGES
        fromLang != null && toLang != null -> NavigationState.READY
        else -> NavigationState.LOADING
      }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NavigationState.LOADING)

  init {
    if (initialLaunchMode != LaunchMode.Normal) {
      _modalVisible.value = true
    }

    viewModelScope.launch {
      languageStateManager.fileEvents.collect { event ->
        handleFileEvent(event)
      }
    }

    viewModelScope.launch {
      DocumentTranslationService.documentTranslationState.collect { state ->
        if (state == null) {
          dismissedInProgressDocumentTaskId = null
          _documentTranslation.value = null
          return@collect
        }
        if (state.isTranslating && dismissedInProgressDocumentTaskId == state.taskId) {
          _documentTranslation.value = null
          return@collect
        }
        if (!state.isTranslating && dismissedInProgressDocumentTaskId == state.taskId) {
          dismissedInProgressDocumentTaskId = null
        }
        _documentTranslation.value = state.toUiState()
      }
    }

    viewModelScope.launch {
      languageStateManager.catalog.collect { catalog ->
        if (catalog == null) return@collect
        if (_targets.value != null) return@collect
        val settings = settingsManager.settings.value
        _targets.value = TargetTabs.of(catalog.languageByCode(settings.defaultTargetLanguageCode) ?: catalog.english)
      }
    }

    viewModelScope.launch {
      languageStateManager.languageState.collect { languageState ->
        if (!languageState.hasLanguages) return@collect
        val catalog = languageStateManager.catalog.value ?: return@collect
        val curSettings = settingsManager.settings.value
        val targetLang = catalog.languageByCode(curSettings.defaultTargetLanguageCode)
        if (targetLang != null && languageState.availabilityFor(targetLang)?.translatorFiles != true) {
          _targets.value = TargetTabs.of(catalog.english)
          settingsManager.updateSettings(curSettings.copy(defaultTargetLanguageCode = "en"))
        }
        val sourceLang = curSettings.defaultSourceLanguageCode?.let { catalog.languageByCode(it) }
        if (sourceLang != null && languageState.availabilityFor(sourceLang)?.translatorFiles != true) {
          _from.value = catalog.english
          settingsManager.updateSettings(curSettings.copy(defaultSourceLanguageCode = "en"))
        }
      }
    }

    viewModelScope.launch {
      languageStateManager.languageState.collect { languageState ->
        if (!languageState.hasLanguages) return@collect
        val catalog = languageStateManager.catalog.value ?: return@collect
        val curSettings = settingsManager.settings.value
        val preferredSource = curSettings.defaultSourceLanguageCode?.let { catalog.languageByCode(it) }
        val preferredAvail = preferredSource != null && languageState.availabilityFor(preferredSource)?.translatorFiles == true

        if (_from.value == null) {
          val currentTo = activeTo
          val sourceLanguage =
            if (preferredSource != null &&
              preferredAvail &&
              preferredSource != currentTo &&
              (currentTo == null || languageStateManager.canTranslate(preferredSource, currentTo))
            ) {
              preferredSource
            } else {
              if (currentTo != null) {
                languageStateManager.getFirstAvailableSourceLanguage(currentTo, excluding = currentTo)
              } else {
                languageStateManager.getFirstAvailableFromLanguage(excluding = currentTo)
              }
            }
          if (sourceLanguage != null) {
            _from.value = sourceLanguage
          }
        }
      }
    }

    // Auto-translate initial text
    if (initialText.isNotBlank()) {
      viewModelScope.launch {
        // Wait for languages to load
        languageStateManager.languageState.collect { languageState ->
          if (languageState.isChecking) return@collect
          if (!languageState.hasLanguages) return@collect
          autoTranslateInitialText(initialText, languageState)
          // Only run once
          return@collect
        }
      }
    }

    // Run pending image OCR once both languages become available
    viewModelScope.launch {
      combine(_from, to) { f, t -> f to t }.collect { (f, t) ->
        if (f == null || t == null) return@collect
        if (_inputType.value != InputType.IMAGE) return@collect
        if (originalImage.value == null) return@collect
        if (_output.value != null) return@collect
        if (translationCoordinator.isTranslating.value) return@collect
        triggerTranslation()
      }
    }

    // Preload model when languages change
    viewModelScope.launch {
      var prevFrom: Language? = null
      var prevTo: Language? = null
      from.collect { fromLang ->
        val toLang = activeTo
        if (fromLang != null && (fromLang != prevFrom || toLang != prevTo)) {
          prevFrom = fromLang
          prevTo = toLang
          translationCoordinator.preloadModel(fromLang, toLang!!)
        }
      }
    }
    viewModelScope.launch {
      var prevTo: Language? = null
      to.collect { toLang ->
        val fromLang = _from.value
        if (fromLang != null && toLang != null && toLang != prevTo) {
          prevTo = toLang
          translationCoordinator.preloadModel(fromLang, toLang)
        }
      }
    }
  }

  fun connectDownloadService(service: DownloadService) {
    languageStateManager.connectToDownloadEvents(service.downloadEvents)
  }

  fun handleMessage(message: TranslatorMessage) {
    if (message !is TranslatorMessage.TextInput) {
      Log.d("HandleMessage", "Handling ${message.javaClass.simpleName}")
    }

    when (message) {
      is TranslatorMessage.TextInput -> {
        if (_inputType.value == InputType.TEXT && _input.value == message.text) {
          return
        }
        if (_inputType.value != InputType.TEXT) {
          _displayImage.value = null
          _originalImage.value = null
          ocrCache = null
          _inputType.value = InputType.TEXT
        }
        _input.value = message.text
        if (message.text.isBlank()) {
          _currentDetectedLanguage.value = null
        }
        triggerTranslation()
      }

      is TranslatorMessage.FromLang -> {
        _isAutoSource.value = false
        val newFrom = message.language
        val carriedTarget = previousSourceAsTarget(newFrom, message.change)
        if (carriedTarget != null) _targets.update { it?.select(carriedTarget) }
        _from.value = newFrom
        _output.value = null
        triggerTranslation()
      }

      TranslatorMessage.EnableAutoSource -> {
        _isAutoSource.value = true
        _output.value = null
        triggerTranslation()
      }

      is TranslatorMessage.ToLang -> {
        _targets.update { it?.select(message.language) }
        _output.value = null
        triggerTranslation()
      }

      is TranslatorMessage.AddTab -> {
        _targets.update { it?.add(message.language) }
        _output.value = null
        triggerTranslation()
      }

      is TranslatorMessage.RemoveTab -> {
        val cur = _targets.value ?: return
        if (message.language !in cur.tabs || cur.tabs.size <= 1) return
        val wasActive = cur.active == message.language
        _targets.value = cur.remove(message.language)
        if (wasActive) {
          _output.value = null
          triggerTranslation()
        }
      }

      is TranslatorMessage.SetImageUri -> {
        translationJob?.cancel()
        translationJob =
          viewModelScope.launch {
            val bm = translationCoordinator.correctBitmap(message.uri, message.deleteAfterLoad)
            ocrCache = null
            _originalImage.value = bm
            _displayImage.value = bm
            _inputType.value = InputType.IMAGE
            _currentDetectedLanguage.value = null
            _output.value = null
            val fromLang = _from.value
            val toLang = activeTo
            if (fromLang != null && toLang != null) {
              runImageTranslation(bm, fromLang, toLang)
            }
          }
      }

      is TranslatorMessage.SetDocumentPath -> {
        handleDocumentPath(
          path = message.path,
          displayName = message.displayName,
          sizeBytes = message.sizeBytes,
          deleteAfterLoad = message.deleteAfterLoad,
        )
      }

      TranslatorMessage.SwapLanguages -> {
        val oldFrom = _from.value ?: return
        val oldTo = activeTo ?: return
        if (!languageStateManager.canSwapLanguages(oldFrom, oldTo)) return
        _isAutoSource.value = false
        _from.value = oldTo
        _targets.update { it?.select(oldFrom) }
        _output.value = null
        triggerTranslation()
      }

      TranslatorMessage.ClearInput -> {
        _displayImage.value = null
        _output.value = null
        _input.value = ""
        _inputType.value = InputType.TEXT
        _originalImage.value = null
        ocrCache = null
        _currentDetectedLanguage.value = null
      }

      TranslatorMessage.ToggleJapaneseOcrMode -> {
        _ocrReadingOrder.value =
          when (_ocrReadingOrder.value) {
            null -> ReadingOrder.TOP_TO_BOTTOM_RIGHT_TO_LEFT
            ReadingOrder.TOP_TO_BOTTOM_RIGHT_TO_LEFT -> ReadingOrder.LEFT_TO_RIGHT
            ReadingOrder.LEFT_TO_RIGHT -> null
          }
        val fromLang = _from.value
        if (_inputType.value == InputType.IMAGE && fromLang?.code == "ja") {
          triggerTranslation()
        }
      }

      is TranslatorMessage.InitializeLanguages -> {
        _from.value = message.from
        _targets.value = TargetTabs.of(message.to)
      }

      is TranslatorMessage.ImageTextDetected -> {
        _input.value = message.extractedText
      }

      is TranslatorMessage.DictionaryLookup -> {
        handleDictionaryLookup(message.str, message.language)
      }

      is TranslatorMessage.Steer -> {
        val fromLang = _from.value ?: return
        val toLang = activeTo ?: return
        val src = _output.value?.source
        if (src.isNullOrBlank()) return
        viewModelScope.launch {
          when (val r = translationCoordinator.steer(fromLang, toLang, src, message.forcedPrefix)) {
            is TranslationResult.Success -> _output.value = r.result
            is TranslationResult.Error -> Log.e("Steer", "Translation steering failed")
          }
        }
      }

      is TranslatorMessage.SpeakTranslatedText -> {
        viewModelScope.launch {
          _uiEvents.emit(UiEvent.AudioLoadingStarted)
          when (val result = translationCoordinator.synthesizeSpeech(message.language, message.text)) {
            is SpeechSynthesisResult.Success -> _uiEvents.emit(UiEvent.PlayAudio(result.audioChunks))
            is SpeechSynthesisResult.Error -> {
              _uiEvents.emit(UiEvent.AudioLoadingStopped)
              _uiEvents.emit(UiEvent.ShowToast(speechErrorMessage(appContext, result.reason)))
            }
          }
        }
      }

      is TranslatorMessage.PopDictionary -> {
        if (_dictionaryStack.value.size > 1) {
          _dictionaryStack.value = _dictionaryStack.value.dropLast(1)
          _dictionaryWord.value = _dictionaryStack.value.lastOrNull()
        } else {
          _dictionaryStack.value = emptyList()
          _dictionaryWord.value = null
          _dictionaryLookupLanguage.value = null
        }
        Log.d("PopDictionary", "Popped dictionary, stack size: ${_dictionaryStack.value.size}")
      }

      TranslatorMessage.ClearDictionaryStack -> {
        _dictionaryStack.value = emptyList()
        _dictionaryWord.value = null
        _dictionaryLookupLanguage.value = null
        Log.d("ClearDictionaryStack", "Cleared dictionary stack")
      }

      is TranslatorMessage.ChangeLaunchMode -> {
        _currentLaunchMode.value = message.newLaunchMode
        _modalVisible.value = message.newLaunchMode == LaunchMode.Normal
        Log.d("ChangeLaunchMode", "Changed launch mode to: ${message.newLaunchMode}")
      }

      TranslatorMessage.ShareTranslatedImage -> {
        val di = _displayImage.value
        if (di != null) {
          viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShareImage(di))
          }
        }
      }
    }
  }

  fun setSharedImageUri(uri: Uri) {
    _pendingSharedImage.tryEmit(uri)
  }

  fun requestLanguageManager() {
    _languageManagerRequests.trySend(Unit)
  }

  // A shared document (pdf/odt/txt/epub) reaches the same drawer as a picked one:
  // copy the content URI into cache off the main thread, then drive the pending
  // document state the file picker also feeds.
  fun setSharedDocumentUri(uri: Uri) {
    viewModelScope.launch {
      try {
        val file = withContext(Dispatchers.IO) { copyDocumentUriToCache(appContext, uri) }
        handleDocumentPath(
          path = file.absolutePath,
          displayName = displayNameForUri(appContext, uri) ?: file.name,
          sizeBytes = sizeBytesForUri(appContext, uri) ?: file.length(),
          deleteAfterLoad = true,
        )
      } catch (e: Exception) {
        Log.e("SharedDocument", "Failed to import shared document: $uri", e)
      }
    }
  }

  fun setModalVisible(visible: Boolean) {
    _modalVisible.value = visible
  }

  fun refreshTtsVoices(language: Language) {
    viewModelScope.launch {
      _ttsVoices.value = _ttsVoices.value + (language.code to translationCoordinator.installedTtsVoices(language))
    }
  }

  fun clearTtsVoices(languageCode: String) {
    _ttsVoices.value = _ttsVoices.value - languageCode
  }

  fun dismissDocumentTranslation() {
    val current = _documentTranslation.value
    if (current?.isTranslating == true) {
      dismissedInProgressDocumentTaskId = current.taskId
    }
    _documentTranslation.value = null
  }

  fun cancelDocumentTranslation() {
    DocumentTranslationService.cancel(appContext)
    dismissedInProgressDocumentTaskId = null
    _documentTranslation.value = null
    _inputType.value = InputType.TEXT
  }

  private var translationJob: Job? = null

  private fun triggerTranslation() {
    if (activeTo == null) return

    translationJob?.cancel()
    translationJob =
      viewModelScope.launch {
        val settings = settingsManager.settings.value
        if (!settings.disableCLD) {
          val text = _input.value
          if (text.isBlank()) {
            _currentDetectedLanguage.value = null
          } else {
            val prior = _currentDetectedLanguage.value?.takeIf { text.length >= it.inputLength }
            val detected =
              translationCoordinator.detectLanguageRobust(
                text,
                _from.value,
                languageStateManager.languageState.value.allLanguages(),
              )
            _currentDetectedLanguage.value = detected?.let { LanguageDetection(it, text.length) } ?: prior
          }
          if (_isAutoSource.value) {
            val detected = _currentDetectedLanguage.value?.language
            if (detected != null && languageStateManager.languageState.value.availabilityFor(detected)?.translatorFiles == true) {
              if (detected != activeTo) {
                _from.value = detected
              }
            }
          }
        }
        reconcileTabsWithSource()
        val fromLang = _from.value ?: return@launch
        val toLang = activeTo ?: return@launch
        translateWithLanguages(fromLang, toLang)
      }
  }

  // A source language can never also be a target tab (it would translate to
  // itself). Whenever the source changes — manual pick, swap, or auto-detected —
  // drop it from the tabs; if it was the sole tab, replace it with an alternate.
  private fun reconcileTabsWithSource() {
    val from = _from.value ?: return
    val cur = _targets.value ?: return
    if (from !in cur.tabs) return
    _targets.value =
      if (cur.tabs.size == 1) {
        pickAlternateTarget(from)?.let { TargetTabs.of(it) } ?: cur
      } else {
        cur.remove(from)
      }
  }

  /**
   * Run a steer without committing it, for previewing what an alternative would
   * produce. Returns the fully re-decoded translation (not a spliced tail).
   */
  suspend fun steerPreview(forcedPrefix: String): String? {
    val fromLang = _from.value ?: return null
    val toLang = activeTo ?: return null
    val src = _output.value?.source
    if (src.isNullOrBlank()) return null
    return when (val r = translationCoordinator.steer(fromLang, toLang, src, forcedPrefix)) {
      is TranslationResult.Success -> r.result.translated
      is TranslationResult.Error -> null
    }
  }

  fun retranslateIfNeeded() {
    if (_inputType.value != InputType.TEXT) return
    val fromLang = _from.value ?: return
    val toLang = activeTo ?: return
    if (translationCoordinator.isTranslating.value) return
    val current = _input.value.trim()
    if (translationCoordinator.lastTranslatedInput == current) return

    viewModelScope.launch {
      translationCoordinator.translateText(fromLang, toLang, current).let {
        _output.value =
          when (it) {
            is TranslationResult.Success -> it.result
            is TranslationResult.Error -> null
          }
      }
    }
  }

  private fun isAutoSourceActive() = _isAutoSource.value && !settingsManager.settings.value.disableCLD

  private suspend fun runImageTranslation(
    bitmap: Bitmap,
    fromLang: Language,
    toLang: Language,
  ) {
    _imageWordSelection.value = null
    _detectedRegions.value = null
    val readingOrder = currentReadingOrderFor(fromLang)
    val cached =
      ocrCache?.takeIf { entry ->
        !_isAutoSource.value &&
          entry.imageRef === bitmap &&
          entry.readingOrder == readingOrder &&
          entry.sourceScript == fromLang.script
      }
    val onMessage: (TranslatorMessage.ImageTextDetected) -> Unit = { msg ->
      _input.value = msg.extractedText
    }
    val result =
      if (cached != null) {
        Log.d("OCR", "reusing OCR result for source=${fromLang.code} (script=${fromLang.script})")
        translationCoordinator.retranslateImageWithOverlay(
          cached.plan,
          fromLang,
          toLang,
          onMessage = onMessage,
          ocrImage = cached.ocrImage,
        )
      } else {
        translationCoordinator.translateImageWithOverlay(
          fromLang,
          toLang,
          bitmap,
          onMessage = onMessage,
          readingOrder = readingOrder,
          isAutoSource = isAutoSourceActive(),
          onMissingDetectedLanguage = { detected ->
            _currentDetectedLanguage.value = LanguageDetection(detected, 0)
          },
          onDetectedRegions = { boxes, w, h ->
            _detectedRegions.value = DetectedRegions(w, h, boxes)
          },
        )
      }
    _detectedRegions.value = null
    result?.let {
      _displayImage.value = it.correctedBitmap
      _output.value = TranslatedText(it.translatedText, null)
      _imageWordSelection.value =
        ImageWordSelection(
          imageWidth = it.metadata.width.toInt(),
          imageHeight = it.metadata.height.toInt(),
          sourceWords = it.metadata.sourceWords,
          translatedWords = it.translatedWords,
        )
      ocrCache =
        OcrCacheEntry(
          plan = it.metadata,
          imageRef = bitmap,
          sourceCode = fromLang.code,
          sourceScript = fromLang.script,
          readingOrder = readingOrder,
          ocrImage = it.ocrImage,
        )
    }
  }

  private suspend fun translateWithLanguages(
    fromLang: Language,
    toLang: Language,
  ) {
    when (_inputType.value) {
      InputType.TEXT -> {
        val result = translationCoordinator.translateText(fromLang, toLang, _input.value.trim())
        when (result) {
          is TranslationResult.Success -> _output.value = result.result
          is TranslationResult.Error -> {
            _output.value = null
            _uiEvents.emit(UiEvent.ShowToast(appContext.getString(R.string.translation_error, result.message)))
          }
        }
      }

      InputType.IMAGE -> {
        originalImage.value?.let { bm ->
          runImageTranslation(bm, fromLang, toLang)
        }
      }

      InputType.FILE -> {
        _output.value = null
      }
    }
  }

  private fun translatedDocumentOutputFile(
    inputName: String,
    from: Language,
    to: Language,
  ): File {
    val inputFile = File(inputName)
    val extension = inputFile.extension.ifBlank { "txt" }
    val baseName = inputFile.nameWithoutExtension.ifBlank { "document" }
    val safeBaseName = baseName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "document" }
    return File(filePathManager.getTranslatedDocumentsDir(), "$safeBaseName.${from.code}-${to.code}.$extension")
  }

  private fun handleDocumentPath(
    path: String,
    displayName: String,
    sizeBytes: Long,
    deleteAfterLoad: Boolean,
  ) {
    Log.d("SetDocumentPath", "Selected document for translation: $displayName ($path)")
    _pendingDocument.value =
      PendingDocument(
        path = path,
        displayName = displayName,
        sizeBytes = sizeBytes,
        deleteAfterLoad = deleteAfterLoad,
        extension = File(path).extension.lowercase(),
      )
  }

  fun dismissPendingDocument() {
    val pending = _pendingDocument.value ?: return
    if (pending.deleteAfterLoad) {
      runCatching { File(pending.path).delete() }
    }
    _pendingDocument.value = null
  }

  fun startPendingDocumentTranslation(
    from: Language,
    to: Language,
    txtLayout: TxtLayoutChoice,
    translatePdfImages: Boolean,
  ) {
    val pending = _pendingDocument.value ?: return
    _pendingDocument.value = null

    _displayImage.value = null
    _originalImage.value = null
    ocrCache = null
    _output.value = null
    _input.value = ""
    _inputType.value = InputType.FILE
    _currentDetectedLanguage.value = null

    val outputFile = translatedDocumentOutputFile(pending.displayName, from, to)
    DocumentTranslationService.startTranslation(
      context = appContext,
      inputPath = pending.path,
      outputPath = outputFile.absolutePath,
      displayName = pending.displayName,
      sizeBytes = pending.sizeBytes,
      from = from,
      to = to,
      deleteAfterLoad = pending.deleteAfterLoad,
      translatePdfImages = translatePdfImages,
      txtLayout = txtLayout,
    )
  }

  private fun currentReadingOrderFor(fromLang: Language): ReadingOrder? =
    if (fromLang.code == "ja") {
      _ocrReadingOrder.value
    } else {
      null
    }

  private suspend fun autoTranslateInitialText(
    initialText: String,
    languageState: dev.davidv.translator.LanguageAvailabilityState,
  ) {
    val settings = settingsManager.settings.value
    val detected =
      if (!settings.disableCLD) {
        translationCoordinator.detectLanguageRobust(initialText, _from.value, languageState.allLanguages())
      } else {
        null
      }
    _currentDetectedLanguage.value = detected?.let { LanguageDetection(it, initialText.length) }
    val translated: TranslationResult?

    if (detected != null) {
      if (languageState.availabilityFor(detected)?.translatorFiles == true) {
        _from.value = detected
        var actualTo = activeTo!!
        if (actualTo == detected) {
          val other = languageStateManager.getFirstAvailableTargetLanguage(detected, excluding = detected)
          if (other != null) {
            _targets.update { it?.select(other) }
            actualTo = other
          }
        }
        translated = translationCoordinator.translateText(detected, actualTo, initialText)
      } else {
        translated = null
      }
    } else {
      translated =
        if (_from.value != null) {
          translationCoordinator.translateText(_from.value!!, activeTo!!, initialText)
        } else {
          null
        }
    }
    translated?.let {
      _output.value =
        when (it) {
          is TranslationResult.Success -> it.result
          is TranslationResult.Error -> null
        }
    }
  }

  private fun handleDictionaryLookup(
    str: String,
    language: Language,
  ) {
    Log.i("DictionaryLookup", "Looking up word")
    val catalog = languageStateManager.catalog.value
    val foundWord =
      try {
        catalog?.lookupDictionary(language, str)
      } catch (e: uniffi.bindings.CatalogException.MissingAsset) {
        viewModelScope.launch {
          _uiEvents.emit(UiEvent.ShowToast("No ${language.localizedName()} dictionary installed"))
        }
        return
      } catch (e: uniffi.bindings.CatalogException.Other) {
        Log.w("DictionaryLookup", "Lookup failed")
        null
      }
    if (foundWord != null) {
      _dictionaryWord.value = foundWord
      _dictionaryLookupLanguage.value = language
      _dictionaryStack.value = _dictionaryStack.value + foundWord
      Log.d("DictionaryLookup", "Word found")
    } else {
      viewModelScope.launch {
        _uiEvents.emit(UiEvent.ShowToast("'$str' not found in ${language.code} dictionary"))
      }
      Log.w("DictionaryLookup", "Lookup failed for ${language.displayName}")
    }
  }

  private fun handleFileEvent(event: FileEvent) {
    when (event) {
      is FileEvent.LanguageDeleted -> {
        val catalog = languageStateManager.catalog.value
        val langs = languageStateManager.languageState.value.translatorLanguages().filter { it != event.language }
        val currentFrom = _from.value
        val currentTo = activeTo
        if (currentFrom == event.language || currentFrom == null) {
          _from.value =
            when {
              currentTo != null -> firstAvailableSourceLanguage(currentTo, langs, excluding = currentTo)
              else -> langs.firstOrNull()
            }
        }
        val cur = _targets.value
        if (cur != null && event.language in cur.tabs) {
          if (cur.tabs.size > 1) {
            _targets.value = cur.remove(event.language)
          } else {
            val actualFrom = _from.value
            val fallback =
              actualFrom?.let { firstAvailableTargetLanguage(it, langs, excluding = actualFrom) }
                ?: catalog?.english
            if (fallback != null) _targets.value = TargetTabs.of(fallback)
          }
        }
        Log.d("TranslatorViewModel", "Language deleted: ${event.language}")
      }
      is FileEvent.Error -> {
        viewModelScope.launch {
          _uiEvents.emit(UiEvent.ShowToast(event.message))
        }
        Log.w("TranslatorViewModel", "File operation failed")
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    // Recycle bitmaps
    _displayImage.value?.let { if (!it.isRecycled) it.recycle() }
    originalImage.value?.let { if (!it.isRecycled) it.recycle() }
  }

  private fun previousSourceAsTarget(
    newFrom: Language,
    change: FromLangChange,
  ): Language? {
    if (change != FromLangChange.MovePreviousToTarget) return null
    val oldFrom = _from.value ?: return null
    if (oldFrom == newFrom) return null
    if (!languageStateManager.canTranslate(newFrom, oldFrom)) return null
    return oldFrom
  }

  private fun pickAlternateTarget(newFrom: Language): Language? {
    val state = languageStateManager.languageState.value
    val catalog = languageStateManager.catalog.value
    val settings = settingsManager.settings.value
    val candidates =
      state.allLanguages().filter { it != newFrom && languageStateManager.canTranslate(newFrom, it) }
    val defaultTarget = catalog?.languageByCode(settings.defaultTargetLanguageCode)
    if (defaultTarget != null && defaultTarget in candidates) {
      return defaultTarget
    }
    val metadata = languageMetadataManager.metadata.value
    val starred = candidates.firstOrNull { metadata[it]?.favorite == true }
    if (starred != null) return starred
    return candidates.minByOrNull { it.displayName }
  }

  private fun firstAvailableSourceLanguage(
    target: Language,
    availableLanguages: List<Language>,
    excluding: Language? = null,
  ): Language? =
    availableLanguages
      .asSequence()
      .filterNot { it == excluding }
      .filter { languageStateManager.canTranslate(it, target) }
      .firstOrNull()

  private fun firstAvailableTargetLanguage(
    source: Language,
    availableLanguages: List<Language>,
    excluding: Language? = null,
  ): Language? =
    availableLanguages
      .asSequence()
      .filterNot { it == excluding }
      .filter { languageStateManager.canTranslate(source, it) }
      .firstOrNull()
}

sealed class UiEvent {
  data class ShowToast(val message: String) : UiEvent()

  data class ShareImage(val bitmap: Bitmap) : UiEvent()

  data object AudioLoadingStarted : UiEvent()

  data object AudioLoadingStopped : UiEvent()

  data class PlayAudio(val audioChunks: Flow<PcmAudio>) : UiEvent()
}

data class PendingDocument(
  val path: String,
  val displayName: String,
  val sizeBytes: Long,
  val deleteAfterLoad: Boolean,
  val extension: String,
)

data class DocumentTranslationUiState(
  val taskId: Long,
  val fileName: String,
  val fileSizeBytes: Long,
  val outputPath: String? = null,
  val outputFileName: String? = null,
  val outputMimeType: String? = null,
  val errorMessage: String? = null,
  @StringRes val progressLabelRes: Int = R.string.doc_progress_preparing,
  val pdfPhases: PdfPhaseProgress? = null,
  val progressFraction: Float? = null,
) {
  val isTranslating: Boolean
    get() = outputPath == null && errorMessage == null
}

private fun DocumentTranslationServiceState.toUiState(): DocumentTranslationUiState =
  DocumentTranslationUiState(
    taskId = taskId,
    fileName = fileName,
    fileSizeBytes = fileSizeBytes,
    outputPath = outputPath,
    outputFileName = outputPath?.let { File(it).name },
    outputMimeType = outputPath?.let { mimeTypeForDocumentPath(it) },
    errorMessage = errorMessage,
    progressLabelRes = progressLabelRes,
    pdfPhases = pdfPhases,
    progressFraction = progressFraction,
  )

private fun mimeTypeForDocumentPath(path: String): String =
  when (File(path).extension.lowercase()) {
    "pdf" -> "application/pdf"
    "odt" -> "application/vnd.oasis.opendocument.text"
    "epub" -> "application/epub+zip"
    "txt" -> "text/plain"
    else -> "application/octet-stream"
  }

class TranslatorViewModelFactory(
  private val appContext: Context,
  private val translationCoordinator: TranslationCoordinator,
  private val settingsManager: SettingsManager,
  private val filePathManager: FilePathManager,
  private val languageMetadataManager: LanguageMetadataManager,
  private val initialText: String,
  private val initialLaunchMode: LaunchMode,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T =
    TranslatorViewModel(
      appContext = appContext,
      translationCoordinator = translationCoordinator,
      settingsManager = settingsManager,
      filePathManager = filePathManager,
      languageMetadataManager = languageMetadataManager,
      initialText = initialText,
      initialLaunchMode = initialLaunchMode,
    ) as T
}

private fun speechErrorMessage(
  context: android.content.Context,
  reason: SpeechError,
): String =
  when (reason) {
    SpeechError.NothingToSpeak -> context.getString(R.string.tts_nothing_to_speak)
    SpeechError.CatalogUnavailable -> context.getString(R.string.tts_catalog_unavailable)
    is SpeechError.NoVoiceInstalled ->
      context.getString(R.string.tts_no_voice, reason.language.localizedName())
    is SpeechError.SynthesisFailed ->
      context.getString(R.string.tts_synthesis_failed, reason.language.localizedName())
    is SpeechError.SynthesisError ->
      context.getString(R.string.tts_synthesis_error, reason.language.localizedName(), reason.reason)
  }
