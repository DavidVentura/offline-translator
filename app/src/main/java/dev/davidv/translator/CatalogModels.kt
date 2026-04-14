package dev.davidv.translator

import uniffi.bindings.CatalogHandle
import java.io.Closeable
import java.io.File

data class LanguageTtsRegionV2(
  val displayName: String,
  val voices: List<String> = emptyList(),
)

data class LanguageAvailabilityEntry(
  val language: Language,
  val availability: LangAvailability,
)

data class TtsVoicePackInfo(
  val packId: String,
  val displayName: String,
  val quality: String? = null,
  val sizeBytes: Long,
)

data class TtsVoicePickerRegion(
  val code: String,
  val displayName: String,
  val voices: List<TtsVoicePackInfo>,
)

data class TranslationPlanStep(
  val fromCode: String,
  val toCode: String,
  val cacheKey: String,
  val config: String,
)

data class TranslationPlan(
  val steps: List<TranslationPlanStep>,
)

data class DownloadTask(
  val packId: String,
  val installPath: String,
  val url: String,
  val sizeBytes: Long,
  val decompress: Boolean,
  val archiveFormat: String? = null,
  val extractTo: String? = null,
  val deleteAfterExtract: Boolean = false,
  val installMarkerPath: String? = null,
  val installMarkerVersion: Int? = null,
)

data class DownloadPlan(
  val totalSize: Long,
  val tasks: List<DownloadTask>,
)

data class DeletePlan(
  val filePaths: List<String>,
  val directoryPaths: List<String>,
)

class LanguageCatalog private constructor(
  private val handle: CatalogHandle,
  val formatVersion: Int,
  val generatedAt: Long,
  val dictionaryVersion: Int,
  val languageRows: List<LanguageAvailabilityEntry>,
  val languageList: List<Language>,
  private val languagesByCode: Map<String, Language>,
  private val availabilityByCode: Map<String, LangAvailability>,
) : Closeable {
  companion object {
    fun open(
      bundledJson: String,
      diskJson: String?,
      baseDir: String,
    ): LanguageCatalog? {
      val handle = CatalogHandle.open(bundledJson, diskJson, baseDir)
      val rows = handle.languageRows()
      val languageRows =
        rows.map { row ->
          row.language.let {
            val language =
              Language(
                code = it.code,
                displayName = it.displayName,
                shortDisplayName = it.shortDisplayName,
                tessName = it.tessName,
                script = it.script,
                dictionaryCode = it.dictionaryCode,
                tessdataSizeBytes = it.tessdataSizeBytes,
              )
            LanguageAvailabilityEntry(
              language = language,
              availability =
                LangAvailability(
                  hasFromEnglish = row.availability.hasFromEnglish,
                  hasToEnglish = row.availability.hasToEnglish,
                  ocrFiles = row.availability.ocrFiles,
                  dictionaryFiles = row.availability.dictionaryFiles,
                  ttsFiles = row.availability.ttsFiles,
                ),
            )
          }
        }
      val languageList = languageRows.map { it.language }
      val languagesByCode = languageList.associateBy { it.code }
      val availabilityByCode =
        languageRows.associate { row -> row.language.code to row.availability }
      return LanguageCatalog(
        handle = handle,
        formatVersion = handle.formatVersion(),
        generatedAt = handle.generatedAt(),
        dictionaryVersion = handle.dictionaryVersion(),
        languageRows = languageRows,
        languageList = languageList,
        languagesByCode = languagesByCode,
        availabilityByCode = availabilityByCode,
      )
    }
  }

  val english: Language by lazy {
    languagesByCode.getValue("en")
  }

  fun languageByCode(code: String): Language? = languagesByCode[code]

  fun dictionaryInfoFor(language: Language): DictionaryInfo? = dictionaryInfo(language.dictionaryCode)

  fun dictionaryInfo(dictionaryCode: String): DictionaryInfo? =
    handle.dictionaryInfo(dictionaryCode)?.let {
      DictionaryInfo(date = it.date, filename = it.filename, size = it.size, type = it.typeName, wordCount = it.wordCount)
    }

  fun availabilityFor(language: Language?): LangAvailability? = language?.let { availabilityByCode[it.code] }

  fun hasTtsVoices(languageCode: String): Boolean = handle.hasTtsVoices(languageCode)

  fun ttsVoicePickerRegions(languageCode: String): List<TtsVoicePickerRegion> =
    handle.ttsVoicePickerRegions(languageCode).map { region ->
      TtsVoicePickerRegion(
        code = region.code,
        displayName = region.displayName,
        voices = region.voices.map { TtsVoicePackInfo(it.packId, it.displayName, it.quality, it.sizeBytes) },
      )
    }

  fun canSwapLanguages(
    from: Language,
    to: Language,
  ): Boolean = handle.canSwapLanguages(from.code, to.code)

  fun canTranslate(
    from: Language,
    to: Language,
  ): Boolean = handle.canTranslate(from.code, to.code)

  fun resolveTranslationPlan(
    from: Language,
    to: Language,
  ): TranslationPlan? =
    handle.resolveTranslationPlan(from.code, to.code)?.let { plan ->
      TranslationPlan(plan.steps.map { TranslationPlanStep(it.fromCode, it.toCode, it.cacheKey, it.config) })
    }

  fun planLanguageDownload(languageCode: String): DownloadPlan = handle.planLanguageDownload(languageCode).toDownloadPlan()

  fun planDictionaryDownload(languageCode: String): DownloadPlan? = handle.planDictionaryDownload(languageCode)?.toDownloadPlan()

  fun planTtsDownload(
    languageCode: String,
    selectedPackId: String? = null,
  ): DownloadPlan? = handle.planTtsDownload(languageCode, selectedPackId)?.toDownloadPlan()

  fun planDeleteLanguage(languageCode: String): DeletePlan = handle.planDeleteLanguage(languageCode).toDeletePlan()

  fun planDeleteDictionary(languageCode: String): DeletePlan = handle.planDeleteDictionary(languageCode).toDeletePlan()

  fun planDeleteTts(languageCode: String): DeletePlan = handle.planDeleteTts(languageCode).toDeletePlan()

  fun planDeleteSupersededTts(
    languageCode: String,
    selectedPackId: String,
  ): DeletePlan = handle.planDeleteSupersededTts(languageCode, selectedPackId).toDeletePlan()

  fun defaultTtsPackIdForLanguage(languageCode: String): String? = handle.defaultTtsPackId(languageCode)

  fun ttsSizeBytesForLanguage(languageCode: String): Long = handle.ttsSizeBytes(languageCode)

  fun translationSizeBytesForLanguage(languageCode: String): Long = handle.translationSizeBytes(languageCode)

  fun resolveTtsVoiceFiles(languageCode: String): TtsVoiceFiles? =
    handle.resolveTtsVoiceFiles(languageCode)?.let { files ->
      TtsVoiceFiles(
        engine = files.engine,
        model = File(files.modelPath),
        aux = File(files.auxPath),
        languageCode = files.languageCode,
        speakerId = files.speakerId,
      )
    }

  @Synchronized
  override fun close() {
    handle.close()
  }
}

private fun uniffi.bindings.DownloadTask.toDownloadTask(): DownloadTask =
  DownloadTask(
    packId = packId,
    installPath = installPath,
    url = url,
    sizeBytes = sizeBytes,
    decompress = decompress,
    archiveFormat = archiveFormat,
    extractTo = extractTo,
    deleteAfterExtract = deleteAfterExtract,
    installMarkerPath = installMarkerPath,
    installMarkerVersion = installMarkerVersion,
  )

private fun uniffi.bindings.DownloadPlan.toDownloadPlan(): DownloadPlan =
  DownloadPlan(totalSize = totalSize, tasks = tasks.map { it.toDownloadTask() })

private fun uniffi.bindings.DeletePlan.toDeletePlan(): DeletePlan = DeletePlan(filePaths = filePaths, directoryPaths = directoryPaths)
