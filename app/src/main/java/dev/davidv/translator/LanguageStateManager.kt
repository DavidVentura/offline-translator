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

package dev.davidv.translator

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LangAvailability(
  val hasFromEnglish: Boolean,
  val hasToEnglish: Boolean,
  val ocrFiles: Boolean,
  val dictionaryFiles: Boolean,
  val ttsFiles: Boolean = false,
) {
  val translatorFiles: Boolean get() = hasFromEnglish || hasToEnglish
}

data class LanguageAvailabilityState(
  val hasLanguages: Boolean = false,
  val availableLanguages: List<LanguageAvailabilityEntry> = emptyList(),
  val isChecking: Boolean = true,
) {
  fun availabilityFor(language: Language?): LangAvailability? =
    language?.let { target ->
      availableLanguages.firstOrNull { it.language.code == target.code }?.availability
    }

  fun allLanguages(): List<Language> = availableLanguages.map { it.language }

  fun translatorLanguages(requireOcr: Boolean = false): List<Language> =
    availableLanguages
      .asSequence()
      .filter { it.availability.translatorFiles && (!requireOcr || it.availability.ocrFiles) }
      .map { it.language }
      .toList()
}

class LanguageStateManager(
  private val scope: CoroutineScope,
  private val filePathManager: FilePathManager,
  downloadEvents: SharedFlow<DownloadEvent>? = null,
) {
  private val catalogState = MutableStateFlow<LanguageCatalog?>(null)
  val catalog: StateFlow<LanguageCatalog?> = catalogState.asStateFlow()
  private val _languageState = MutableStateFlow(LanguageAvailabilityState())
  val languageState: StateFlow<LanguageAvailabilityState> = _languageState.asStateFlow()

  private val _catalogRefreshToken = MutableStateFlow(0)
  val catalogRefreshToken: StateFlow<Int> = _catalogRefreshToken.asStateFlow()

  private val _fileEvents = MutableSharedFlow<FileEvent>()
  val fileEvents: SharedFlow<FileEvent> = _fileEvents.asSharedFlow()

  private var downloadEventsJob: kotlinx.coroutines.Job? = null

  fun languageByCode(code: String): Language? = catalogState.value?.languageByCode(code)

  init {
    if (downloadEvents != null) {
      connectToDownloadEvents(downloadEvents)
    }
    loadCatalog()
  }

  private fun loadCatalog() {
    scope.launch {
      withContext(Dispatchers.IO) {
        setCatalog(filePathManager.loadCatalog())
        Log.i("LanguageStateManager", "Catalog loaded from file: ${catalogState.value != null}")
      }
      refreshLanguageAvailability()
    }
  }

  fun connectToDownloadEvents(downloadEvents: SharedFlow<DownloadEvent>) {
    downloadEventsJob?.cancel()
    downloadEventsJob =
      scope.launch {
        downloadEvents.collect { event ->
          when (event) {
            is DownloadEvent.NewTranslationAvailable -> {
              setCatalog(withContext(Dispatchers.IO) { filePathManager.reloadCatalog() })
              refreshLanguageAvailability()
            }

            is DownloadEvent.NewDictionaryAvailable -> {
              setCatalog(withContext(Dispatchers.IO) { filePathManager.reloadCatalog() })
              refreshLanguageAvailability()
            }

            is DownloadEvent.NewTtsAvailable -> {
              setCatalog(withContext(Dispatchers.IO) { filePathManager.reloadCatalog() })
              refreshLanguageAvailability()
            }

            is DownloadEvent.NewSupportAvailable -> {
              setCatalog(withContext(Dispatchers.IO) { filePathManager.reloadCatalog() })
              _catalogRefreshToken.value++
              refreshLanguageAvailability()
            }

            is DownloadEvent.CatalogDownloaded -> {
              setCatalog(withContext(Dispatchers.IO) { filePathManager.reloadCatalog() })
              _catalogRefreshToken.value++
              refreshLanguageAvailability()
              Log.i("LanguageStateManager", "Catalog downloaded")
            }

            is DownloadEvent.DownloadError -> {
              Log.w("LanguageStateManager", "Download error: ${event.message}")
              _fileEvents.emit(FileEvent.Error(event.message))
            }
          }
        }
      }
  }

  fun refreshLanguageAvailability() {
    _languageState.value = _languageState.value.copy(isChecking = true)
    scope.launch {
      val catalog =
        withContext(Dispatchers.IO) { filePathManager.reloadCatalog() } ?: run {
          _languageState.value = _languageState.value.copy(isChecking = false)
          return@launch
        }
      setCatalog(catalog)

      Log.i("LanguageStateManager", "Refreshing language availability")
      val availableLanguages = catalog.languageRows
      val hasLanguages = availableLanguages.any { !it.language.isEnglish && it.availability.translatorFiles }
      Log.i("LanguageStateManager", "hasLanguages = $hasLanguages")
      _languageState.value =
        LanguageAvailabilityState(
          hasLanguages = hasLanguages,
          availableLanguages = availableLanguages,
          isChecking = false,
        )
    }
  }

  fun deleteDict(language: Language) {
    val catalog = catalogState.value ?: filePathManager.loadCatalog() ?: return
    filePathManager.applyDeletePlan(catalog.prepareDelete(language.code, Feature.DICTIONARY))

    refreshLanguageAvailability()
    Log.i("LanguageStateManager", "Removed dictionary for language: ${language.displayName}")
  }

  fun deleteLanguage(language: Language) {
    val catalog = catalogState.value ?: filePathManager.loadCatalog() ?: return
    filePathManager.applyDeletePlan(catalog.prepareDelete(language.code, Feature.CORE))
    refreshLanguageAvailability()
    scope.launch { _fileEvents.emit(FileEvent.LanguageDeleted(language)) }
    Log.i("LanguageStateManager", "Removed language: ${language.displayName}")
  }

  fun deleteTts(language: Language) {
    val catalog = catalogState.value ?: filePathManager.loadCatalog() ?: return
    filePathManager.applyDeletePlan(catalog.prepareDelete(language.code, Feature.TTS))
    refreshLanguageAvailability()
    Log.i("LanguageStateManager", "Removed TTS for language: ${language.displayName}")
  }

  fun deleteSupportByKind(kind: String) {
    val catalog = catalogState.value ?: filePathManager.loadCatalog() ?: return
    filePathManager.applyDeletePlan(catalog.prepareDeleteSupportByKind(kind))
    refreshLanguageAvailability()
    Log.i("LanguageStateManager", "Removed support files for kind: $kind")
  }

  fun canSwapLanguages(
    from: Language,
    to: Language,
  ): Boolean {
    val catalog = catalogState.value ?: return false
    return catalog.canSwapLanguages(from, to)
  }

  fun canTranslate(
    from: Language,
    to: Language,
  ): Boolean {
    val catalog = catalogState.value ?: return false
    return catalog.canTranslate(from, to)
  }

  fun getFirstAvailableFromLanguage(excluding: Language? = null): Language? {
    val state = _languageState.value
    return state.translatorLanguages().firstOrNull { it != excluding }
  }

  fun getFirstAvailableSourceLanguage(
    target: Language,
    excluding: Language? = null,
  ): Language? {
    val state = _languageState.value
    return state.allLanguages()
      .asSequence()
      .filterNot { it == excluding }
      .filter { canTranslate(it, target) }
      .firstOrNull()
  }

  fun getFirstAvailableTargetLanguage(
    source: Language,
    excluding: Language? = null,
  ): Language? {
    val state = _languageState.value
    return state.allLanguages()
      .asSequence()
      .filterNot { it == excluding }
      .filter { canTranslate(source, it) }
      .firstOrNull()
  }

  private fun setCatalog(newCatalog: LanguageCatalog?) {
    if (catalogState.value === newCatalog) return
    catalogState.value = newCatalog
  }
}
