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

package dev.davidv.translator.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.davidv.translator.FilePathManager
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageAvailabilityState
import dev.davidv.translator.LanguageMetadata
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.TranslationService
import dev.davidv.translator.TranslatorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class BrowserViewModel(
  settingsManager: SettingsManager,
  filePathManager: FilePathManager,
  languageMetadataManager: LanguageMetadataManager,
  private val translationService: TranslationService,
  initialUrl: String,
  initialSourceCode: String?,
  initialTargetCode: String?,
) : ViewModel() {
  val languageStateManager = LanguageStateManager(viewModelScope, filePathManager)

  val languageState: StateFlow<LanguageAvailabilityState> = languageStateManager.languageState
  val languageMetadata: StateFlow<Map<Language, LanguageMetadata>> = languageMetadataManager.metadata

  private val _from = MutableStateFlow<Language?>(null)
  val from: StateFlow<Language?> = _from.asStateFlow()

  private val _to = MutableStateFlow<Language?>(null)
  val to: StateFlow<Language?> = _to.asStateFlow()

  private val _url = MutableStateFlow(initialUrl)
  val url: StateFlow<String> = _url.asStateFlow()

  init {
    viewModelScope.launch {
      combine(languageStateManager.catalog, languageStateManager.languageState) { catalog, state ->
        catalog to state
      }.collect { (catalog, state) ->
        if (catalog == null) return@collect
        val settings = settingsManager.settings.value
        if (_to.value == null) {
          val tgtCode = initialTargetCode ?: settings.defaultTargetLanguageCode
          _to.value = catalog.languageByCode(tgtCode) ?: catalog.english
        }
        if (_from.value == null) {
          val srcCode = initialSourceCode ?: settings.defaultSourceLanguageCode
          val byCode = srcCode?.let { catalog.languageByCode(it) }
          _from.value = byCode ?: firstAvailableSourceLanguage(state, _to.value)
        }
        val fromLang = _from.value
        val toLang = _to.value
        if (fromLang != null && toLang != null && fromLang != toLang) {
          translationService.preloadModel(fromLang, toLang)
        }
      }
    }
  }

  private fun firstAvailableSourceLanguage(
    state: LanguageAvailabilityState,
    target: Language?,
  ): Language? =
    state.allLanguages().firstOrNull { lang ->
      lang != target &&
        (lang.isEnglish || state.availabilityFor(lang)?.hasToEnglish == true)
    }

  fun onMessage(message: TranslatorMessage) {
    when (message) {
      is TranslatorMessage.FromLang -> _from.value = message.language
      is TranslatorMessage.ToLang -> _to.value = message.language
      is TranslatorMessage.SwapLanguages -> {
        val prevFrom = _from.value
        val prevTo = _to.value
        if (prevFrom != null && prevTo != null && canSwapLanguages(prevFrom, prevTo)) {
          _from.value = prevTo
          _to.value = prevFrom
        }
      }
      else -> {}
    }
    val fromLang = _from.value
    val toLang = _to.value
    if (fromLang != null && toLang != null && fromLang != toLang) {
      viewModelScope.launch { translationService.preloadModel(fromLang, toLang) }
    }
  }

  fun loadUrl(value: String) {
    val normalized =
      when {
        value.startsWith("http://") || value.startsWith("https://") -> value
        value == "about:blank" -> value
        value.isBlank() -> return
        else -> "https://$value"
      }
    _url.value = normalized
  }

  fun canSwapLanguages(
    from: Language,
    to: Language,
  ): Boolean = languageStateManager.canSwapLanguages(from, to)
}

class BrowserViewModelFactory(
  private val settingsManager: SettingsManager,
  private val filePathManager: FilePathManager,
  private val languageMetadataManager: LanguageMetadataManager,
  private val translationService: TranslationService,
  private val initialUrl: String,
  private val initialSourceCode: String?,
  private val initialTargetCode: String?,
) : ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    @Suppress("UNCHECKED_CAST")
    return BrowserViewModel(
      settingsManager = settingsManager,
      filePathManager = filePathManager,
      languageMetadataManager = languageMetadataManager,
      translationService = translationService,
      initialUrl = initialUrl,
      initialSourceCode = initialSourceCode,
      initialTargetCode = initialTargetCode,
    ) as T
  }
}
