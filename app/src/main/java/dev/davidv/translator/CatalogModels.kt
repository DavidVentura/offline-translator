package dev.davidv.translator

import uniffi.bindings.CatalogHandle
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class LanguageTtsRegionV2(
  val displayName: String,
  val voices: List<String> = emptyList(),
)

data class TtsVoicePackInfo(
  val packId: String,
  val displayName: String,
  val quality: String? = null,
  val sizeBytes: Long,
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
  private val sharedHandle: SharedCatalogHandle,
  val formatVersion: Int,
  val generatedAt: Long,
  val dictionaryVersion: Int,
  val languageList: List<Language>,
  private val availabilityMap: Map<Language, LangAvailability>,
) : Closeable {
  private val isClosed = AtomicBoolean(false)

  companion object {
    fun open(
      bundledJson: String,
      diskJson: String?,
      baseDir: String,
    ): LanguageCatalog? {
      val handle =
        try {
          CatalogHandle.open(bundledJson, diskJson, baseDir)
        } catch (_: Exception) {
          return null
        }

      val languageList =
        handle.languages().map {
          Language(
            code = it.code,
            displayName = it.displayName,
            shortDisplayName = it.shortDisplayName,
            tessName = it.tessName,
            script = it.script,
            dictionaryCode = it.dictionaryCode,
            tessdataSizeBytes = it.tessdataSizeBytes,
          )
        }
      val languagesByCode = languageList.associateBy { it.code }
      val availabilityMap =
        buildMap {
          handle.languageAvailability().forEach { (code, avail) ->
            val language = languagesByCode[code] ?: return@forEach
            put(
              language,
              LangAvailability(
                hasFromEnglish = avail.hasFromEnglish,
                hasToEnglish = avail.hasToEnglish,
                ocrFiles = avail.ocrFiles,
                dictionaryFiles = avail.dictionaryFiles,
                ttsFiles = avail.ttsFiles,
              ),
            )
          }
        }
      return LanguageCatalog(
        sharedHandle = SharedCatalogHandle(handle),
        formatVersion = handle.formatVersion(),
        generatedAt = handle.generatedAt(),
        dictionaryVersion = handle.dictionaryVersion(),
        languageList = languageList,
        availabilityMap = availabilityMap,
      )
    }
  }

  val english: Language by lazy {
    languageList.first { it.code == "en" }
  }

  fun languageByCode(code: String): Language? = languageList.firstOrNull { it.code == code }

  fun dictionaryInfoFor(language: Language): DictionaryInfo? = dictionaryInfo(language.dictionaryCode)

  fun dictionaryInfo(dictionaryCode: String): DictionaryInfo? =
    handle().dictionaryInfo(dictionaryCode)?.let {
      DictionaryInfo(date = it.date, filename = it.filename, size = it.size, type = it.typeName, wordCount = it.wordCount)
    }

  fun computeLanguageAvailability(): Map<Language, LangAvailability> = availabilityMap

  fun ttsPackIdsForLanguage(languageCode: String): List<String> = handle().ttsPackIds(languageCode)

  fun orderedTtsRegionsForLanguage(languageCode: String): List<Pair<String, LanguageTtsRegionV2>> =
    handle().orderedTtsRegions(languageCode).map { it.code to LanguageTtsRegionV2(it.displayName, it.voices) }

  fun ttsVoicePackInfo(packId: String): TtsVoicePackInfo? =
    handle().ttsVoicePackInfo(packId)?.let { TtsVoicePackInfo(it.packId, it.displayName, it.quality, it.sizeBytes) }

  fun canSwapLanguages(
    from: Language,
    to: Language,
  ): Boolean = handle().canSwapLanguages(from.code, to.code)

  fun canTranslate(
    from: Language,
    to: Language,
  ): Boolean = handle().canTranslate(from.code, to.code)

  fun resolveTranslationPlan(
    from: Language,
    to: Language,
  ): TranslationPlan? =
    handle().resolveTranslationPlan(from.code, to.code)?.let { plan ->
      TranslationPlan(plan.steps.map { TranslationPlanStep(it.fromCode, it.toCode, it.cacheKey, it.config) })
    }

  fun planLanguageDownload(languageCode: String): DownloadPlan = handle().planLanguageDownload(languageCode).toDownloadPlan()

  fun planDictionaryDownload(languageCode: String): DownloadPlan? = handle().planDictionaryDownload(languageCode)?.toDownloadPlan()

  fun planTtsDownload(
    languageCode: String,
    selectedPackId: String? = null,
  ): DownloadPlan? = handle().planTtsDownload(languageCode, selectedPackId)?.toDownloadPlan()

  fun planDeleteLanguage(languageCode: String): DeletePlan = handle().planDeleteLanguage(languageCode).toDeletePlan()

  fun planDeleteDictionary(languageCode: String): DeletePlan = handle().planDeleteDictionary(languageCode).toDeletePlan()

  fun planDeleteTts(languageCode: String): DeletePlan = handle().planDeleteTts(languageCode).toDeletePlan()

  fun planDeleteSupersededTts(
    languageCode: String,
    selectedPackId: String,
  ): DeletePlan = handle().planDeleteSupersededTts(languageCode, selectedPackId).toDeletePlan()

  fun defaultTtsPackIdForLanguage(languageCode: String): String? = handle().defaultTtsPackId(languageCode)

  fun ttsSizeBytesForLanguage(languageCode: String): Long = handle().ttsSizeBytes(languageCode)

  fun translationSizeBytesForLanguage(languageCode: String): Long = handle().translationSizeBytes(languageCode)

  fun resolveTtsVoiceFiles(languageCode: String): TtsVoiceFiles? =
    handle().resolveTtsVoiceFiles(languageCode)?.let { files ->
      TtsVoiceFiles(
        engine = files.engine,
        model = File(files.modelPath),
        aux = File(files.auxPath),
        languageCode = files.languageCode,
        speakerId = files.speakerId,
      )
    }

  fun duplicate(): LanguageCatalog {
    check(!isClosed.get()) { "LanguageCatalog is closed" }
    sharedHandle.retain()
    return LanguageCatalog(
      sharedHandle = sharedHandle,
      formatVersion = formatVersion,
      generatedAt = generatedAt,
      dictionaryVersion = dictionaryVersion,
      languageList = languageList,
      availabilityMap = availabilityMap,
    )
  }

  @Synchronized
  override fun close() {
    if (isClosed.compareAndSet(false, true)) {
      sharedHandle.release()
    }
  }

  @Suppress("deprecation")
  protected fun finalize() {
    close()
  }

  private fun handle(): CatalogHandle {
    check(!isClosed.get()) { "LanguageCatalog is closed" }
    return sharedHandle.handle
  }
}

private class SharedCatalogHandle(
  val handle: CatalogHandle,
) {
  private val refCount = AtomicInteger(1)

  fun retain() {
    while (true) {
      val current = refCount.get()
      check(current > 0) { "Catalog handle already released" }
      if (refCount.compareAndSet(current, current + 1)) {
        return
      }
    }
  }

  fun release() {
    val remaining = refCount.decrementAndGet()
    check(remaining >= 0) { "Catalog handle reference count underflow" }
    if (remaining == 0) {
      handle.close()
    }
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
