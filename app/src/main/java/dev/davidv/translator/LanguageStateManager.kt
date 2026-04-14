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
  val availableLanguageMap: Map<Language, LangAvailability> = emptyMap(),
  val isChecking: Boolean = true,
)

fun isDictionaryAvailable(
  filePathManager: FilePathManager,
  language: Language,
): Boolean = filePathManager.getDictionaryFile(language).exists()

fun isDictionaryAvailable(
  dictFiles: Set<String>,
  language: Language,
): Boolean = "${language.dictionaryCode}.dict" in dictFiles

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
    loadMucabFile()
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
              loadMucabFile()
            }

            is DownloadEvent.NewDictionaryAvailable -> {
              setCatalog(withContext(Dispatchers.IO) { filePathManager.reloadCatalog() })
              refreshLanguageAvailability()
              _fileEvents.emit(FileEvent.DictionaryAvailable(event.language))
            }

            is DownloadEvent.NewTtsAvailable -> {
              setCatalog(withContext(Dispatchers.IO) { filePathManager.reloadCatalog() })
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
    scope.launch {
      _languageState.value = _languageState.value.copy(isChecking = true)

      val catalog = withContext(Dispatchers.IO) { filePathManager.reloadCatalog() } ?: return@launch
      setCatalog(catalog)

      Log.i("LanguageStateManager", "Refreshing language availability")
      val availabilityMap = catalog.computeLanguageAvailability()

      val hasLanguages = availabilityMap.any { !it.key.isEnglish && it.value.translatorFiles }
      Log.i("LanguageStateManager", "hasLanguages = $hasLanguages")
      _languageState.value =
        LanguageAvailabilityState(
          hasLanguages = hasLanguages,
          availableLanguageMap = availabilityMap,
          isChecking = false,
        )
    }
  }

  fun deleteDict(language: Language) {
    val catalog = catalogState.value ?: filePathManager.loadCatalog() ?: return
    filePathManager.applyDeletePlan(catalog.planDeleteDictionary(language.code))

    refreshLanguageAvailability()
    scope.launch { _fileEvents.emit(FileEvent.DictionaryDeleted(language)) }
    Log.i("LanguageStateManager", "Removed dictionary for language: ${language.displayName}")
  }

  fun deleteLanguage(language: Language) {
    val catalog = catalogState.value ?: filePathManager.loadCatalog() ?: return
    filePathManager.applyDeletePlan(catalog.planDeleteLanguage(language.code))
    refreshLanguageAvailability()
    scope.launch { _fileEvents.emit(FileEvent.LanguageDeleted(language)) }
    Log.i("LanguageStateManager", "Removed language: ${language.displayName}")
  }

  fun deleteTts(language: Language) {
    val catalog = catalogState.value ?: filePathManager.loadCatalog() ?: return
    filePathManager.applyDeletePlan(catalog.planDeleteTts(language.code))
    refreshLanguageAvailability()
    Log.i("LanguageStateManager", "Removed TTS for language: ${language.displayName}")
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
    return state.availableLanguageMap
      .filterNot { it.key == excluding }
      .filter { it.value.translatorFiles }
      .keys
      .firstOrNull()
  }

  fun getFirstAvailableSourceLanguage(
    target: Language,
    excluding: Language? = null,
  ): Language? {
    val state = _languageState.value
    return state.availableLanguageMap.keys
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
    return state.availableLanguageMap.keys
      .asSequence()
      .filterNot { it == excluding }
      .filter { canTranslate(source, it) }
      .firstOrNull()
  }

  private fun loadMucabFile() {
    scope.launch {
      withContext(Dispatchers.IO) {
        val mucabFile = filePathManager.getMucabFile()
        if (mucabFile.exists()) {
          val binding = MucabBinding()
          val success = binding.open(mucabFile.absolutePath)
          if (success) {
            _fileEvents.emit(FileEvent.MucabFileLoaded(binding))
            Log.i("LanguageStateManager", "Mucab file loaded successfully")
          } else {
            Log.w("LanguageStateManager", "Failed to open mucab file")
          }
        } else {
          Log.i("LanguageStateManager", "Mucab file not found")
        }
      }
    }
  }

  private fun setCatalog(newCatalog: LanguageCatalog?) {
    if (catalogState.value === newCatalog) return
    catalogState.value = newCatalog
  }
}
