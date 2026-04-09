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

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.davidv.translator.DownloadService
import dev.davidv.translator.FileEvent
import dev.davidv.translator.FilePathManager
import dev.davidv.translator.InputType
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.LaunchMode
import dev.davidv.translator.PcmAudio
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.SpeechSynthesisResult
import dev.davidv.translator.TarkkaBinding
import dev.davidv.translator.TranslatedText
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslationResult
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.WordWithTaggedEntries
import dev.davidv.translator.canSwapLanguages
import dev.davidv.translator.ui.screens.openDictionary
import dev.davidv.translator.ui.screens.toggleFirstLetterCase
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

class TranslatorViewModel(
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

  private val _inputType = MutableStateFlow(InputType.TEXT)
  val inputType: StateFlow<InputType> = _inputType.asStateFlow()

  private val _currentDetectedLanguage = MutableStateFlow<Language?>(null)
  val currentDetectedLanguage: StateFlow<Language?> = _currentDetectedLanguage.asStateFlow()

  private val _currentLaunchMode = MutableStateFlow(initialLaunchMode)
  val currentLaunchMode: StateFlow<LaunchMode> = _currentLaunchMode.asStateFlow()

  private val _modalVisible = MutableStateFlow(initialLaunchMode == LaunchMode.Normal)
  val modalVisible: StateFlow<Boolean> = _modalVisible.asStateFlow()

  // Dictionary state
  private val _dictionaryBindings = MutableStateFlow<Map<Language, TarkkaBinding>>(emptyMap())
  val dictionaryBindings: StateFlow<Map<Language, TarkkaBinding>> = _dictionaryBindings.asStateFlow()

  private val _dictionaryWord = MutableStateFlow<WordWithTaggedEntries?>(null)
  val dictionaryWord: StateFlow<WordWithTaggedEntries?> = _dictionaryWord.asStateFlow()

  private val _dictionaryStack = MutableStateFlow<List<WordWithTaggedEntries>>(emptyList())
  val dictionaryStack: StateFlow<List<WordWithTaggedEntries>> = _dictionaryStack.asStateFlow()

  private val _dictionaryLookupLanguage = MutableStateFlow<Language?>(null)
  val dictionaryLookupLanguage: StateFlow<Language?> = _dictionaryLookupLanguage.asStateFlow()

  // One-shot UI events (Toast, errors, etc.)
  private val _uiEvents = MutableSharedFlow<UiEvent>()
  val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

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
      languageStateManager.languageIndex.collect { index ->
        if (index == null) return@collect
        if (_to.value != null) return@collect
        val settings = settingsManager.settings.value
        _to.value = index.languageByCode(settings.defaultTargetLanguageCode) ?: index.english
      }
    }

    viewModelScope.launch {
      languageStateManager.languageState.collect { languageState ->
        languageState.availableLanguageMap.forEach { (language, availability) ->
          if (availability.dictionaryFiles && !_dictionaryBindings.value.containsKey(language)) {
            openDictionary(
              language,
              filePathManager,
              onSuccess = { tarkkaBinding ->
                _dictionaryBindings.value = _dictionaryBindings.value + (language to tarkkaBinding)
                Log.d("DictionaryLookup", "Loaded existing dictionary for ${language.displayName}")
              },
              onError = { error ->
                Log.w("DictionaryLookup", error)
              },
            )
          }
        }
      }
    }

    viewModelScope.launch {
      languageStateManager.languageState.collect { languageState ->
        if (!languageState.hasLanguages) return@collect
        val index = languageStateManager.languageIndex.value ?: return@collect
        val curSettings = settingsManager.settings.value
        val targetLang = index.languageByCode(curSettings.defaultTargetLanguageCode)
        if (targetLang != null && languageState.availableLanguageMap[targetLang]?.translatorFiles == false) {
          _to.value = index.english
          settingsManager.updateSettings(curSettings.copy(defaultTargetLanguageCode = "en"))
        }
        val sourceLang = curSettings.defaultSourceLanguageCode?.let { index.languageByCode(it) }
        if (sourceLang != null && languageState.availableLanguageMap[sourceLang]?.translatorFiles == false) {
          _from.value = index.english
          settingsManager.updateSettings(curSettings.copy(defaultSourceLanguageCode = "en"))
        }
      }
    }

    viewModelScope.launch {
      languageStateManager.languageState.collect { languageState ->
        if (!languageState.hasLanguages) return@collect
        val index = languageStateManager.languageIndex.value ?: return@collect
        val curSettings = settingsManager.settings.value
        val preferredSource = curSettings.defaultSourceLanguageCode?.let { index.languageByCode(it) }
        val preferredAvail = preferredSource != null && languageState.availableLanguageMap[preferredSource]?.translatorFiles == true

        if (_from.value == null) {
          val currentTo = _to.value
          val sourceLanguage =
            if (preferredSource != null && preferredAvail && preferredSource != currentTo) {
              preferredSource
            } else {
              languageStateManager.getFirstAvailableFromLanguage(excluding = currentTo)
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
        _input.value = message.text
        val settings = settingsManager.settings.value
        val fromLang = _from.value
        if (settings.showTransliterationOnInput && fromLang != null) {
          _inputTransliterated.value = translationCoordinator.transliterate(message.text, fromLang)
        }
        triggerTranslation()
      }

      is TranslatorMessage.FromLang -> {
        _from.value = message.language
        _output.value = null
        triggerTranslation()
      }

      is TranslatorMessage.ToLang -> {
        _to.value = message.language
        _output.value = null
        triggerTranslation()
      }

      is TranslatorMessage.SetImageUri -> {
        val bm = translationCoordinator.correctBitmap(message.uri)
        originalImage.value = bm
        _displayImage.value = bm
        _inputType.value = InputType.IMAGE
        _currentDetectedLanguage.value = null
        _output.value = null
        val fromLang = _from.value
        val toLang = _to.value
        if (fromLang != null && toLang != null) {
          viewModelScope.launch {
            val result =
              translationCoordinator.translateImageWithOverlay(
                fromLang,
                toLang,
                bm,
              ) { imageTextDetected ->
                _input.value = imageTextDetected.extractedText
              }
            result?.let {
              _displayImage.value = it.correctedBitmap
              _output.value = TranslatedText(it.translatedText, null)
            }
          }
        }
      }

      TranslatorMessage.SwapLanguages -> {
        val oldFrom = _from.value ?: return
        val oldTo = _to.value ?: return
        if (!canSwapLanguages(oldFrom, oldTo)) return
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
    handleMessage(TranslatorMessage.SetImageUri(uri))
  }

  fun setModalVisible(visible: Boolean) {
    _modalVisible.value = visible
  }

  private fun triggerTranslation() {
    val fromLang = _from.value ?: return
    val toLang = _to.value ?: return

    if (translationCoordinator.isTranslating.value) return

    viewModelScope.launch {
      val settings = settingsManager.settings.value
      if (!settings.disableCLD) {
        _currentDetectedLanguage.value = translationCoordinator.detectLanguage(_input.value, fromLang)
      }
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
            ) { imageTextDetected ->
              _input.value = imageTextDetected.extractedText
            }
          result?.let {
            _displayImage.value = it.correctedBitmap
            _output.value = TranslatedText(it.translatedText, null)
          }
        }
      }
    }
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
      if (languageState.availableLanguageMap[detected]?.translatorFiles == true) {
        _from.value = detected
        var actualTo = _to.value!!
        if (_to.value == detected) {
          val other = languageStateManager.getFirstAvailableFromLanguage(detected)
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
    val tarkkaBinding = _dictionaryBindings.value[language]
    if (tarkkaBinding != null) {
      val res = tarkkaBinding.lookup(str.trim())
      val foundWord =
        if (res == null) {
          val toggledWord = toggleFirstLetterCase(str)
          tarkkaBinding.lookup(toggledWord.trim())
        } else {
          res
        }

      if (foundWord != null) {
        _dictionaryWord.value = foundWord
        _dictionaryLookupLanguage.value = language
        _dictionaryStack.value = _dictionaryStack.value + foundWord
      } else {
        viewModelScope.launch {
          _uiEvents.emit(UiEvent.ShowToast("'$str' not found in ${language.code} dictionary"))
        }
      }
      Log.d("DictionaryLookup", "From lookup got $foundWord")
    } else {
      viewModelScope.launch {
        _uiEvents.emit(UiEvent.ShowToast("Dictionary for ${language.displayName} not available"))
      }
      Log.w("DictionaryLookup", "No dictionary binding for ${language.displayName}")
    }
  }

  private fun handleFileEvent(event: FileEvent) {
    when (event) {
      is FileEvent.LanguageDeleted -> {
        val index = languageStateManager.languageIndex.value
        val langs = languageStateManager.languageState.value.availableLanguageMap
        val validLangs = langs.filter { it.key != event.language }.filter { it.value.translatorFiles }
        val currentFrom = _from.value
        val currentTo = _to.value
        if (currentFrom == event.language || currentFrom == null) {
          _from.value = validLangs.filterNot { it.key == currentTo }.keys.firstOrNull()
        }
        if (currentTo == event.language) {
          _to.value = validLangs.filterNot { it.key == currentFrom }.keys.firstOrNull()
            ?: index?.english
        }
        if (event.language.code == "ja") {
          translationCoordinator.setMucabBinding(null)
        }
        Log.d("TranslatorViewModel", "Language deleted: ${event.language}")
      }
      is FileEvent.DictionaryIndexLoaded -> {
        Log.d("TranslatorViewModel", "Dictionary index loaded from file: ${event.index}")
      }
      is FileEvent.MucabFileLoaded -> {
        translationCoordinator.setMucabBinding(event.mucabBinding)
        Log.d("TranslatorViewModel", "Mucab file loaded and set in TranslationCoordinator")
      }
      is FileEvent.DictionaryDeleted -> {
        _dictionaryBindings.value[event.language]?.close()
        _dictionaryBindings.value = _dictionaryBindings.value - event.language
        Log.d("TranslatorViewModel", "Dictionary deleted for language: ${event.language}")
      }
      is FileEvent.DictionaryAvailable -> {
        openDictionary(
          event.language,
          filePathManager,
          onSuccess = { tarkkaBinding ->
            _dictionaryBindings.value = _dictionaryBindings.value + (event.language to tarkkaBinding)
            Log.d("DictionaryLookup", "Loaded dictionary for ${event.language.displayName}")
          },
          onError = { error ->
            Log.e("DictionaryLookup", error)
          },
        )
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
    // Close dictionary bindings (JNI resources)
    _dictionaryBindings.value.values.forEach { it.close() }
    // Recycle bitmaps
    _displayImage.value?.let { if (!it.isRecycled) it.recycle() }
    originalImage.value?.let { if (!it.isRecycled) it.recycle() }
  }
}

sealed class UiEvent {
  data class ShowToast(val message: String) : UiEvent()

  data class ShareImage(val bitmap: Bitmap) : UiEvent()

  data object AudioLoadingStarted : UiEvent()

  data object AudioLoadingStopped : UiEvent()

  data class PlayAudio(val audioChunks: Flow<PcmAudio>) : UiEvent()
}

class TranslatorViewModelFactory(
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
      translationCoordinator = translationCoordinator,
      settingsManager = settingsManager,
      filePathManager = filePathManager,
      languageMetadataManager = languageMetadataManager,
      initialText = initialText,
      initialLaunchMode = initialLaunchMode,
    ) as T
}
