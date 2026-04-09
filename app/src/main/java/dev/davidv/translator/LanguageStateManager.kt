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
) {
  val translatorFiles: Boolean get() = hasFromEnglish || hasToEnglish
}

data class LanguageAvailabilityState(
  val hasLanguages: Boolean = false,
  val availableLanguageMap: Map<Language, LangAvailability> = emptyMap(),
  val isChecking: Boolean = true,
)

fun canSwapLanguages(
  from: Language,
  to: Language,
): Boolean {
  val toCanBeSource = to.isEnglish || to.toEnglish != null
  val fromCanBeTarget = from.isEnglish || from.fromEnglish != null
  return toCanBeSource && fromCanBeTarget
}

fun canSwapLanguages(
  from: Language,
  to: Language,
  availableLanguages: Map<Language, LangAvailability>,
): Boolean {
  val toCanBeSource = to.isEnglish || availableLanguages[to]?.hasToEnglish == true
  val fromCanBeTarget = from.isEnglish || availableLanguages[from]?.hasFromEnglish == true
  return toCanBeSource && fromCanBeTarget
}

fun canTranslate(
  from: Language,
  to: Language,
  availableLanguages: Map<Language, LangAvailability>,
): Boolean {
  if (from == to) return true
  return when {
    from.isEnglish -> availableLanguages[to]?.hasFromEnglish == true
    to.isEnglish -> availableLanguages[from]?.hasToEnglish == true
    else -> availableLanguages[from]?.hasToEnglish == true && availableLanguages[to]?.hasFromEnglish == true
  }
}

fun isDictionaryAvailable(
  filePathManager: FilePathManager,
  language: Language,
): Boolean = filePathManager.getDictionaryFile(language).exists()

fun isDictionaryAvailable(
  dictFiles: Set<String>,
  language: Language,
): Boolean = "${language.dictionaryCode}.dict" in dictFiles

fun missingFilesFrom(
  dataFiles: Set<String>,
  lang: Language,
): Pair<Long, List<ModelFile>> {
  val files = lang.fromEnglish?.allFiles() ?: return Pair(0L, emptyList())
  val missing = files.filter { it.name !in dataFiles }
  return Pair(missing.sumOf { it.sizeBytes }, missing)
}

fun missingFilesTo(
  dataFiles: Set<String>,
  lang: Language,
): Pair<Long, List<ModelFile>> {
  val files = lang.toEnglish?.allFiles() ?: return Pair(0L, emptyList())
  val missing = files.filter { it.name !in dataFiles }
  return Pair(missing.sumOf { it.sizeBytes }, missing)
}

fun missingFiles(
  dataFiles: Set<String>,
  lang: Language,
): Pair<Long, List<ModelFile>> {
  val (toSize, toFiles) = missingFilesTo(dataFiles, lang)
  val (fromSize, fromFiles) = missingFilesFrom(dataFiles, lang)
  return Pair(toSize + fromSize, toFiles + fromFiles)
}

class LanguageStateManager(
  private val scope: CoroutineScope,
  private val filePathManager: FilePathManager,
  downloadEvents: SharedFlow<DownloadEvent>? = null,
) {
  private val catalogState = MutableStateFlow<LanguageCatalogV2?>(null)
  private val _languageState = MutableStateFlow(LanguageAvailabilityState())
  val languageState: StateFlow<LanguageAvailabilityState> = _languageState.asStateFlow()

  private val _languageIndex = MutableStateFlow<LanguageIndex?>(null)
  val languageIndex: StateFlow<LanguageIndex?> = _languageIndex.asStateFlow()

  private val _dictionaryIndex = MutableStateFlow<DictionaryIndex?>(null)
  val dictionaryIndex: StateFlow<DictionaryIndex?> = _dictionaryIndex.asStateFlow()

  private val _dictionaryIndexVersion = MutableStateFlow(0)
  val dictionaryIndexVersion: StateFlow<Int> = _dictionaryIndexVersion.asStateFlow()

  private val _languageIndexVersion = MutableStateFlow(0)
  val languageIndexVersion: StateFlow<Int> = _languageIndexVersion.asStateFlow()

  private val _fileEvents = MutableSharedFlow<FileEvent>()
  val fileEvents: SharedFlow<FileEvent> = _fileEvents.asSharedFlow()

  private var downloadEventsJob: kotlinx.coroutines.Job? = null

  fun languageByCode(code: String): Language? = _languageIndex.value?.languageByCode(code)

  init {
    if (downloadEvents != null) {
      connectToDownloadEvents(downloadEvents)
    }
    loadLanguageIndex()
    loadDictionaryIndex()
    loadMucabFile()
  }

  private fun loadLanguageIndex() {
    scope.launch {
      withContext(Dispatchers.IO) {
        catalogState.value = filePathManager.loadLanguageCatalogV2()
        val index = filePathManager.loadLanguageIndex()
        _languageIndex.value = index
        Log.i("LanguageStateManager", "Language index loaded from file: ${index != null}")
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
              catalogState.value = filePathManager.loadLanguageCatalogV2()
              refreshLanguageAvailability()
              loadMucabFile()
            }

            is DownloadEvent.NewDictionaryAvailable -> {
              catalogState.value = filePathManager.loadLanguageCatalogV2()
              refreshLanguageAvailability()
              _fileEvents.emit(FileEvent.DictionaryAvailable(event.language))
            }

            is DownloadEvent.DictionaryIndexDownloaded -> {
              catalogState.value = filePathManager.loadLanguageCatalogV2()
              _dictionaryIndex.value = event.index
              _dictionaryIndexVersion.value++
              Log.i("LanguageStateManager", "Dictionary index downloaded: ${event.index}")
            }

            is DownloadEvent.LanguageIndexDownloaded -> {
              catalogState.value = filePathManager.loadLanguageCatalogV2()
              _languageIndex.value = event.index
              _languageIndexVersion.value++
              _dictionaryIndex.value = filePathManager.loadDictionaryIndex()
              _dictionaryIndexVersion.value++
              refreshLanguageAvailability()
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

      val languages = _languageIndex.value?.languages ?: return@launch
      val catalog = catalogState.value ?: withContext(Dispatchers.IO) { filePathManager.loadLanguageCatalogV2() } ?: return@launch

      Log.i("LanguageStateManager", "Refreshing language availability")
      val availabilityMap =
        withContext(Dispatchers.IO) {
          val resolver = PackResolver(catalog, filePathManager)

          buildMap {
            languages.forEach { lang ->
              if (lang.isEnglish) {
                val ocrPackId = catalog.languageEntry(lang.code)?.assets?.ocr?.get("tesseract")
                val dictionaryPackId = catalog.dictionaryPackIdForLanguage(lang.code)
                put(
                  lang,
                  LangAvailability(
                    hasFromEnglish = true,
                    hasToEnglish = true,
                    ocrFiles = ocrPackId != null && resolver.isInstalled(ocrPackId),
                    dictionaryFiles = dictionaryPackId != null && resolver.isInstalled(dictionaryPackId),
                  ),
                )
              } else {
                val fromPackId = catalog.translationPackId(from = "en", to = lang.code)
                val toPackId = catalog.translationPackId(from = lang.code, to = "en")
                val ocrPackId = catalog.languageEntry(lang.code)?.assets?.ocr?.get("tesseract")
                val dictionaryPackId = catalog.dictionaryPackIdForLanguage(lang.code)
                put(
                  lang,
                  LangAvailability(
                    hasFromEnglish = fromPackId != null && resolver.isInstalled(fromPackId),
                    hasToEnglish = toPackId != null && resolver.isInstalled(toPackId),
                    ocrFiles = ocrPackId != null && resolver.isInstalled(ocrPackId),
                    dictionaryFiles = dictionaryPackId != null && resolver.isInstalled(dictionaryPackId),
                  ),
                )
              }
            }
          }
        }

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
    val catalog = catalogState.value ?: filePathManager.loadLanguageCatalogV2() ?: return
    val targetPack = catalog.dictionaryPackIdForLanguage(language.code) ?: return
    val resolver = PackResolver(catalog, filePathManager)
    val keepRootPacks =
      (_languageIndex.value?.languages ?: emptyList())
        .filter { it.code != language.code }
        .mapNotNull { other ->
          catalog.dictionaryPackIdForLanguage(other.code)
            ?.takeIf { it != targetPack && resolver.isInstalled(it) }
        }
        .toSet()
    val packsToDelete = catalog.dependencyClosure(setOf(targetPack)) - catalog.dependencyClosure(keepRootPacks)
    filePathManager.deletePackFiles(catalog, packsToDelete)

    refreshLanguageAvailability()
    scope.launch { _fileEvents.emit(FileEvent.DictionaryDeleted(language)) }
    Log.i("LanguageStateManager", "Removed dictionary for language: ${language.displayName}")
  }

  fun deleteLanguage(language: Language) {
    val catalog = catalogState.value ?: filePathManager.loadLanguageCatalogV2() ?: return
    val resolver = PackResolver(catalog, filePathManager)
    val targetRootPacks = catalog.corePackIdsForLanguage(language.code)
    val keepRootPacks =
      (_languageIndex.value?.languages ?: emptyList())
        .filter { it.code != language.code }
        .flatMap { other -> catalog.corePackIdsForLanguage(other.code) }
        .filter { resolver.isInstalled(it) }
        .toSet()
    val packsToDelete = catalog.dependencyClosure(targetRootPacks) - catalog.dependencyClosure(keepRootPacks)
    filePathManager.deletePackFiles(catalog, packsToDelete)
    refreshLanguageAvailability()
    scope.launch { _fileEvents.emit(FileEvent.LanguageDeleted(language)) }
    Log.i("LanguageStateManager", "Removed language: ${language.displayName}")
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
      .filter { canTranslate(it, target, state.availableLanguageMap) }
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
      .filter { canTranslate(source, it, state.availableLanguageMap) }
      .firstOrNull()
  }

  private fun loadDictionaryIndex() {
    scope.launch {
      val index =
        withContext(Dispatchers.IO) {
          catalogState.value = catalogState.value ?: filePathManager.loadLanguageCatalogV2()
          filePathManager.loadDictionaryIndex()
        }
      _dictionaryIndex.value = index
      if (index != null) {
        _fileEvents.emit(FileEvent.DictionaryIndexLoaded(index))
      }
      Log.i("LanguageStateManager", "Dictionary index loaded from file: ${index != null}")
    }
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
}
