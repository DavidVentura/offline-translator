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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.davidv.translator.DocumentTranslationService
import dev.davidv.translator.DocumentTranslationServiceState
import dev.davidv.translator.DownloadService
import dev.davidv.translator.FileEvent
import dev.davidv.translator.FilePathManager
import dev.davidv.translator.InputType
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.LaunchMode
import dev.davidv.translator.PcmAudio
import dev.davidv.translator.ReadingOrder
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.SpeechSynthesisResult
import dev.davidv.translator.TranslatedText
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslationResult
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.TtsVoiceOption
import dev.davidv.translator.WordWithTaggedEntries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

  private val _inputTransliterated = MutableStateFlow<String?>(null)
  val inputTransliterated: StateFlow<String?> = _inputTransliterated.asStateFlow()

  private val _output = MutableStateFlow<TranslatedText?>(null)
  val output: StateFlow<TranslatedText?> = _output.asStateFlow()

  private val _from = MutableStateFlow<Language?>(null)
  val from: StateFlow<Language?> = _from.asStateFlow()

  private val _to = MutableStateFlow<Language?>(null)
  val to: StateFlow<Language?> = _to.asStateFlow()

  private val _displayImage = MutableStateFlow<Bitmap?>(null)
  val displayImage: StateFlow<Bitmap?> = _displayImage.asStateFlow()

  private val originalImage = MutableStateFlow<Bitmap?>(null)

  private val _ocrReadingOrder = MutableStateFlow(ReadingOrder.LEFT_TO_RIGHT)
  val ocrReadingOrder: StateFlow<ReadingOrder> = _ocrReadingOrder.asStateFlow()

  private val _inputType = MutableStateFlow(InputType.TEXT)
  val inputType: StateFlow<InputType> = _inputType.asStateFlow()

  private val _currentDetectedLanguage = MutableStateFlow<Language?>(null)
  val currentDetectedLanguage: StateFlow<Language?> = _currentDetectedLanguage.asStateFlow()

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

  private val _ttsVoices = MutableStateFlow<Map<String, List<TtsVoiceOption>>>(emptyMap())
  val ttsVoices: StateFlow<Map<String, List<TtsVoiceOption>>> = _ttsVoices.asStateFlow()

  private val _documentTranslation = MutableStateFlow<DocumentTranslationUiState?>(null)
  val documentTranslation: StateFlow<DocumentTranslationUiState?> = _documentTranslation.asStateFlow()
  private var dismissedInProgressDocumentTaskId: Long? = null

  // One-shot UI events (Toast, errors, etc.)
  private val _uiEvents = MutableSharedFlow<UiEvent>()
  val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

  private val _pendingSharedImage = MutableSharedFlow<Uri>(replay = 1, extraBufferCapacity = 1)
  val pendingSharedImage: SharedFlow<Uri> = _pendingSharedImage.asSharedFlow()

  val navigationState: StateFlow<NavigationState> =
    combine(languageStateManager.languageState, _from, _to) { langState, fromLang, toLang ->
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
        if (_to.value != null) return@collect
        val settings = settingsManager.settings.value
        _to.value = catalog.languageByCode(settings.defaultTargetLanguageCode) ?: catalog.english
      }
    }

    viewModelScope.launch {
      languageStateManager.languageState.collect { languageState ->
        if (!languageState.hasLanguages) return@collect
        val catalog = languageStateManager.catalog.value ?: return@collect
        val curSettings = settingsManager.settings.value
        val targetLang = catalog.languageByCode(curSettings.defaultTargetLanguageCode)
        if (targetLang != null && languageState.availabilityFor(targetLang)?.translatorFiles != true) {
          _to.value = catalog.english
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
          val currentTo = _to.value
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
      combine(_from, _to) { f, t -> f to t }.collect { (f, t) ->
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
        val toLang = _to.value
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
      Log.d("HandleMessage", "Handle: $message")
    }

    when (message) {
      is TranslatorMessage.TextInput -> {
        if (_inputType.value != InputType.TEXT) {
          _displayImage.value = null
          originalImage.value = null
          _inputType.value = InputType.TEXT
        }
        _input.value = message.text
        if (message.text.isBlank()) {
          _currentDetectedLanguage.value = null
        }
        val settings = settingsManager.settings.value
        val fromLang = _from.value
        if (settings.showTransliterationOnInput && fromLang != null) {
          _inputTransliterated.value = translationCoordinator.transliterate(message.text, fromLang)
        }
        triggerTranslation()
      }

      is TranslatorMessage.FromLang -> {
        _isAutoSource.value = false
        val newFrom = message.language
        if (newFrom == _to.value) {
          val newTarget = pickAlternateTarget(newFrom)
          if (newTarget != null) {
            _to.value = newTarget
          }
        }
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
        _to.value = message.language
        _output.value = null
        triggerTranslation()
      }

      is TranslatorMessage.SetImageUri -> {
        viewModelScope.launch {
          val bm = translationCoordinator.correctBitmap(message.uri, message.deleteAfterLoad)
          originalImage.value = bm
          _displayImage.value = bm
          _inputType.value = InputType.IMAGE
          _currentDetectedLanguage.value = null
          _output.value = null
          val fromLang = _from.value
          val toLang = _to.value
          if (fromLang != null && toLang != null) {
            val result =
              translationCoordinator.translateImageWithOverlay(
                fromLang,
                toLang,
                bm,
                onMessage = { imageTextDetected ->
                  _input.value = imageTextDetected.extractedText
                },
                readingOrder = currentReadingOrderFor(fromLang),
              )
            result?.let {
              _displayImage.value = it.correctedBitmap
              _output.value = TranslatedText(it.translatedText, null)
            }
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
        val oldTo = _to.value ?: return
        if (!languageStateManager.canSwapLanguages(oldFrom, oldTo)) return
        _isAutoSource.value = false
        _from.value = oldTo
        _to.value = oldFrom
        _output.value = null
        triggerTranslation()
      }

      TranslatorMessage.ClearInput -> {
        _displayImage.value = null
        _output.value = null
        _input.value = ""
        _inputType.value = InputType.TEXT
        originalImage.value = null
        _currentDetectedLanguage.value = null
      }

      TranslatorMessage.ToggleJapaneseOcrMode -> {
        _ocrReadingOrder.value =
          when (_ocrReadingOrder.value) {
            ReadingOrder.LEFT_TO_RIGHT -> ReadingOrder.TOP_TO_BOTTOM_LEFT_TO_RIGHT
            ReadingOrder.TOP_TO_BOTTOM_LEFT_TO_RIGHT -> ReadingOrder.LEFT_TO_RIGHT
          }
        val fromLang = _from.value
        if (_inputType.value == InputType.IMAGE && fromLang?.code == "ja") {
          triggerTranslation()
        }
      }

      is TranslatorMessage.InitializeLanguages -> {
        _from.value = message.from
        _to.value = message.to
      }

      is TranslatorMessage.ImageTextDetected -> {
        _input.value = message.extractedText
      }

      is TranslatorMessage.DictionaryLookup -> {
        handleDictionaryLookup(message.str, message.language)
      }

      is TranslatorMessage.SpeakTranslatedText -> {
        viewModelScope.launch {
          _uiEvents.emit(UiEvent.AudioLoadingStarted)
          when (val result = translationCoordinator.synthesizeSpeech(message.language, message.text)) {
            is SpeechSynthesisResult.Success -> _uiEvents.emit(UiEvent.PlayAudio(result.audioChunks))
            is SpeechSynthesisResult.Error -> {
              _uiEvents.emit(UiEvent.AudioLoadingStopped)
              _uiEvents.emit(UiEvent.ShowToast(result.message))
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

  fun setModalVisible(visible: Boolean) {
    _modalVisible.value = visible
  }

  fun refreshTtsVoices(language: Language) {
    viewModelScope.launch {
      _ttsVoices.value = _ttsVoices.value + (language.code to translationCoordinator.availableTtsVoices(language))
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

  private fun triggerTranslation() {
    val toLang = _to.value ?: return

    if (translationCoordinator.isTranslating.value) return

    viewModelScope.launch {
      val settings = settingsManager.settings.value
      if (!settings.disableCLD) {
        if (_input.value.isBlank()) {
          _currentDetectedLanguage.value = null
        } else {
          val detected = translationCoordinator.detectLanguage(_input.value, _from.value)
          if (detected != null) {
            _currentDetectedLanguage.value = detected
          }
        }
        if (_isAutoSource.value) {
          val detected = _currentDetectedLanguage.value
          if (detected != null && languageStateManager.languageState.value.availabilityFor(detected)?.translatorFiles == true) {
            if (detected != _to.value) {
              _from.value = detected
            }
          }
        }
      }
      val fromLang = _from.value ?: return@launch
      translateWithLanguages(fromLang, toLang)
    }
  }

  fun retranslateIfNeeded() {
    if (_inputType.value != InputType.TEXT) return
    val fromLang = _from.value ?: return
    val toLang = _to.value ?: return
    if (translationCoordinator.isTranslating.value) return
    if (translationCoordinator.lastTranslatedInput == _input.value) return

    viewModelScope.launch {
      translationCoordinator.translateText(fromLang, toLang, _input.value).let {
        _output.value =
          when (it) {
            is TranslationResult.Success -> it.result
            is TranslationResult.Error -> null
          }
      }
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
            _uiEvents.emit(UiEvent.ShowToast("Translation error: ${result.message}"))
          }
        }
      }

      InputType.IMAGE -> {
        originalImage.value?.let { bm ->
          val result =
            translationCoordinator.translateImageWithOverlay(
              fromLang,
              toLang,
              bm,
              onMessage = { imageTextDetected ->
                _input.value = imageTextDetected.extractedText
              },
              readingOrder = currentReadingOrderFor(fromLang),
            )
          result?.let {
            _displayImage.value = it.correctedBitmap
            _output.value = TranslatedText(it.translatedText, null)
          }
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
    if (_isAutoSource.value) {
      viewModelScope.launch {
        _uiEvents.emit(UiEvent.ShowToast("Please select source language first"))
      }
      return
    }
    _displayImage.value = null
    originalImage.value = null
    _output.value = null
    _input.value = ""
    _inputTransliterated.value = null
    _inputType.value = InputType.FILE
    _currentDetectedLanguage.value = null
    Log.d("SetDocumentPath", "Selected document for translation: $displayName ($path)")

    val fromLang = _from.value
    val toLang = _to.value
    if (fromLang == null || toLang == null) {
      _documentTranslation.value =
        DocumentTranslationUiState(
          taskId = System.currentTimeMillis(),
          fileName = displayName,
          fileSizeBytes = sizeBytes,
          errorMessage = "Languages are not ready yet",
        )
      viewModelScope.launch {
        _uiEvents.emit(UiEvent.ShowToast("Languages are not ready yet"))
      }
      return
    }

    val outputFile = translatedDocumentOutputFile(displayName, fromLang, toLang)
    DocumentTranslationService.startTranslation(
      context = appContext,
      inputPath = path,
      outputPath = outputFile.absolutePath,
      displayName = displayName,
      sizeBytes = sizeBytes,
      from = fromLang,
      to = toLang,
      deleteAfterLoad = deleteAfterLoad,
    )
  }

  private fun currentReadingOrderFor(fromLang: Language): ReadingOrder =
    if (fromLang.code == "ja") {
      _ocrReadingOrder.value
    } else {
      ReadingOrder.LEFT_TO_RIGHT
    }

  private suspend fun autoTranslateInitialText(
    initialText: String,
    languageState: dev.davidv.translator.LanguageAvailabilityState,
  ) {
    val settings = settingsManager.settings.value
    _currentDetectedLanguage.value =
      if (!settings.disableCLD) {
        translationCoordinator.detectLanguage(initialText, _from.value)
      } else {
        null
      }

    val detected = _currentDetectedLanguage.value
    val translated: TranslationResult?

    if (detected != null) {
      if (languageState.availabilityFor(detected)?.translatorFiles == true) {
        _from.value = detected
        var actualTo = _to.value!!
        if (_to.value == detected) {
          val other = languageStateManager.getFirstAvailableTargetLanguage(detected, excluding = detected)
          if (other != null) {
            _to.value = other
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
          translationCoordinator.translateText(_from.value!!, _to.value!!, initialText)
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
    Log.i("DictionaryLookup", "Looking up $str for $language")
    val catalog = languageStateManager.catalog.value
    val foundWord =
      try {
        catalog?.lookupDictionary(language, str)
      } catch (e: uniffi.bindings.CatalogException.MissingAsset) {
        viewModelScope.launch {
          _uiEvents.emit(UiEvent.ShowToast("No ${language.displayName} dictionary installed"))
        }
        return
      } catch (e: uniffi.bindings.CatalogException.Other) {
        Log.w("DictionaryLookup", "Lookup failed for ${language.displayName}", e)
        null
      }
    if (foundWord != null) {
      _dictionaryWord.value = foundWord
      _dictionaryLookupLanguage.value = language
      _dictionaryStack.value = _dictionaryStack.value + foundWord
      Log.d("DictionaryLookup", "From lookup got $foundWord")
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
        val currentTo = _to.value
        if (currentFrom == event.language || currentFrom == null) {
          _from.value =
            when {
              currentTo != null -> firstAvailableSourceLanguage(currentTo, langs, excluding = currentTo)
              else -> langs.firstOrNull()
            }
        }
        if (currentTo == event.language) {
          val actualFrom = _from.value
          _to.value =
            actualFrom?.let { firstAvailableTargetLanguage(it, langs, excluding = actualFrom) }
              ?: catalog?.english
        }
        Log.d("TranslatorViewModel", "Language deleted: ${event.language}")
      }
      is FileEvent.Error -> {
        viewModelScope.launch {
          _uiEvents.emit(UiEvent.ShowToast(event.message))
        }
        Log.w("TranslatorViewModel", "Error event: ${event.message}")
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    // Recycle bitmaps
    _displayImage.value?.let { if (!it.isRecycled) it.recycle() }
    originalImage.value?.let { if (!it.isRecycled) it.recycle() }
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

data class DocumentTranslationUiState(
  val taskId: Long,
  val fileName: String,
  val fileSizeBytes: Long,
  val outputPath: String? = null,
  val outputFileName: String? = null,
  val outputMimeType: String? = null,
  val errorMessage: String? = null,
  val progressLabel: String = "Preparing file",
  val progressCurrent: Int? = null,
  val progressTotal: Int? = null,
  val progressUnit: String? = null,
) {
  val isTranslating: Boolean
    get() = outputPath == null && errorMessage == null

  val progressFraction: Float?
    get() {
      val current = progressCurrent ?: return null
      val total = progressTotal ?: return null
      if (total <= 0) return null
      return (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
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
    progressLabel = progressLabel,
    progressCurrent = progressCurrent,
    progressTotal = progressTotal,
    progressUnit = progressUnit,
  )

private fun mimeTypeForDocumentPath(path: String): String =
  when (File(path).extension.lowercase()) {
    "pdf" -> "application/pdf"
    "odt" -> "application/vnd.oasis.opendocument.text"
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
