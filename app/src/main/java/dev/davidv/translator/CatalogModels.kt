package dev.davidv.translator

import android.graphics.Bitmap
import uniffi.bindings.CatalogException
import uniffi.bindings.CatalogHandle
import uniffi.bindings.DocumentProgressEvent
import uniffi.bindings.DocumentProgressSink
import uniffi.bindings.PdfImages
import uniffi.bindings.TxtLayout
import java.nio.ByteBuffer

typealias CatalogError = CatalogException

// / Caller-chosen layout for `.txt` translation. Ignored for other
// / document types. `Reflow.wrapColumns == null` means do not re-wrap.
sealed class TxtLayoutChoice {
  data object Preserve : TxtLayoutChoice()

  data class Reflow(val wrapColumns: Int?) : TxtLayoutChoice()

  fun toUniffi(): TxtLayout =
    when (this) {
      Preserve -> TxtLayout.Preserve
      is Reflow -> TxtLayout.Reflow(wrap = wrapColumns?.toUInt())
    }
}

sealed class DocumentTranslationProgress {
  data object Preparing : DocumentTranslationProgress()

  data class PdfPlan(
    val textPages: Int,
    val imageXobjects: Int,
    val rasterPages: Int,
  ) : DocumentTranslationProgress()

  /** Text-translation completion as a smooth fraction in [0, 1]. */
  data class TranslatingText(
    val fraction: Float,
  ) : DocumentTranslationProgress()

  /** Image-XObject OCR pass: real item counts. */
  data class TranslatingImages(
    val current: Int,
    val total: Int,
  ) : DocumentTranslationProgress()

  /** Page-raster overlay pass: real item counts. */
  data class TranslatingRasterPages(
    val current: Int,
    val total: Int,
  ) : DocumentTranslationProgress()

  data object Writing : DocumentTranslationProgress()
}

private fun DocumentProgressEvent.toDocumentTranslationProgress(): DocumentTranslationProgress =
  when (this) {
    DocumentProgressEvent.Preparing -> DocumentTranslationProgress.Preparing
    is DocumentProgressEvent.PdfPlan ->
      DocumentTranslationProgress.PdfPlan(
        textPages = textPages.toInt(),
        imageXobjects = imageXobjects.toInt(),
        rasterPages = rasterPages.toInt(),
      )
    is DocumentProgressEvent.TranslatingText ->
      DocumentTranslationProgress.TranslatingText(fraction = fraction)
    is DocumentProgressEvent.TranslatingImages ->
      DocumentTranslationProgress.TranslatingImages(
        current = current.toInt(),
        total = total.toInt(),
      )
    is DocumentProgressEvent.TranslatingRasterPages ->
      DocumentTranslationProgress.TranslatingRasterPages(
        current = current.toInt(),
        total = total.toInt(),
      )
    DocumentProgressEvent.Writing -> DocumentTranslationProgress.Writing
  }

private fun rgbaBytes(bitmap: Bitmap): ByteArray {
  val start = System.nanoTime()
  return ByteArray(bitmap.byteCount).also { bytes ->
    bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
    android.util.Log.i(
      "CatalogModels",
      "rgbaBytes extract: ${bitmap.width}x${bitmap.height} ${bytes.size / 1024}KB — ${(System.nanoTime() - start) / 1_000_000.0}ms",
    )
  }
}

data class LanguageTtsRegionV2(
  val displayName: String,
  val voices: List<String> = emptyList(),
)

data class LanguageAvailabilityEntry(
  val language: Language,
  val availability: LangAvailability,
)

data class CatalogFileEntry(
  val name: String,
  val sizeBytes: Long,
  val installPath: String,
  val url: String,
)

// Rows are copied out when the repository publishes; handle queries read the session live. A new
// LanguageCatalog follows every install-state change, so the two only diverge until that publish.
class LanguageCatalog private constructor(
  private val handle: CatalogHandle,
  val baseDir: String,
  val formatVersion: Int,
  val generatedAt: Long,
  val dictionaryVersion: Int,
  val languageRows: List<LanguageAvailabilityEntry>,
  val languageList: List<Language>,
  private val languagesByCode: Map<String, Language>,
  private val availabilityByCode: Map<String, LangAvailability>,
) {
  companion object {
    fun of(handle: CatalogHandle): LanguageCatalog {
      val rows = handle.languageRows()
      val languageRows =
        rows.map { row ->
          LanguageAvailabilityEntry(
            language =
              Language(
                code = row.language.code,
                displayName = row.language.displayName,
                shortDisplayName = row.language.shortDisplayName,
                script = row.language.script,
                writingSystem = row.language.writingSystem,
                dictionaryCode = row.language.dictionaryCode,
              ),
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
      val languageList = languageRows.map { it.language }
      val languagesByCode = languageList.associateBy { it.code }
      val availabilityByCode =
        languageRows.associate { row -> row.language.code to row.availability }
      return LanguageCatalog(
        handle = handle,
        baseDir = handle.baseDir(),
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
      DictionaryInfo(date = it.date, filename = it.filename, size = it.size.toLong(), type = it.typeName, wordCount = it.wordCount.toLong())
    }

  fun supportFilesByKind(kind: String): List<CatalogFileEntry> =
    handle.supportFilesByKind(kind).map { file ->
      CatalogFileEntry(
        name = file.name,
        sizeBytes = file.sizeBytes.toLong(),
        installPath = file.installPath,
        url = file.url,
      )
    }

  @Throws(CatalogException::class)
  fun lookupDictionary(
    language: Language,
    word: String,
  ): WordWithTaggedEntries? = handle.lookupDictionary(language.code, word)

  fun availabilityFor(language: Language?): LangAvailability? = language?.let { availabilityByCode[it.code] }

  fun hasTtsVoices(languageCode: String): Boolean = handle.hasTtsVoices(languageCode)

  fun installedOcrEngines(languageCode: String): List<String> = handle.installedOcrEngines(languageCode)

  fun availableOcrEngines(languageCode: String): List<String> = handle.availableOcrEngines(languageCode)

  fun planOcrEngineDownload(
    languageCode: String,
    engine: String,
  ): DownloadPlan? = handle.planOcrEngineDownload(languageCode, engine)

  fun planOcrEngineDownloads(
    languageCodes: List<String>,
    engine: String,
  ): DownloadPlan = handle.planOcrEngineDownloads(languageCodes, engine)

  fun planOcrEngineUpgrades(
    languageCodes: List<String>,
    engine: String,
  ): DownloadPlan = handle.planOcrEngineUpgrades(languageCodes, engine)

  fun planTranslationUpgrades(languageCodes: List<String>): DownloadPlan = handle.planTranslationUpgrades(languageCodes)

  fun translationUpgradeLanguageCodes(): List<String> = handle.translationUpgradeLanguageCodes()

  fun planTtsUpgrades(languageCodes: List<String>): DownloadPlan = handle.planTtsUpgrades(languageCodes)

  fun ttsUpgradeLanguageCodes(): List<String> = handle.ttsUpgradeLanguageCodes()

  fun planRepair(): DownloadPlan = handle.planRepair()

  fun ocrEngineReady(): Boolean = handle.ocrEngineReady()

  fun ttsVoicePickerRegions(languageCode: String): List<TtsVoicePickerRegion> = handle.ttsVoicePickerRegions(languageCode)

  fun installedTtsVoicePickerRegions(languageCode: String): List<TtsVoicePickerRegion> = handle.installedTtsVoicePickerRegions(languageCode)

  fun ttsSampleText(languageCode: String): String? = handle.ttsSampleText(languageCode)

  fun canSwapLanguages(
    from: Language,
    to: Language,
  ): Boolean = handle.canSwapLanguages(from.code, to.code)

  fun canTranslate(
    from: Language,
    to: Language,
  ): Boolean = handle.canTranslate(from.code, to.code)

  fun warmTranslationModels(
    from: Language,
    to: Language,
  ): Boolean = handle.warmTranslationModels(from.code, to.code)

  @Throws(CatalogException::class)
  fun translateText(
    from: Language,
    to: Language,
    text: String,
  ): String = handle.translateText(from.code, to.code, text)

  fun translateTextWithAlternatives(
    from: Language,
    to: Language,
    text: String,
  ): TranslationWithAlternatives = handle.translateTextWithAlternatives(from.code, to.code, text)

  fun steer(
    from: Language,
    to: Language,
    source: String,
    forcedPrefix: String,
  ): TranslationWithAlternatives = handle.steer(from.code, to.code, source, forcedPrefix)

  fun translateMixedTexts(
    inputs: List<String>,
    forcedSourceLanguage: Language?,
    targetLanguage: Language,
    availableLanguages: List<Language>,
  ): uniffi.translator_translate.MixedTextTranslationResult =
    handle.translateMixedTexts(
      inputs,
      forcedSourceLanguage?.code,
      targetLanguage.code,
      availableLanguages.map { it.code },
    )

  @Throws(CatalogException::class)
  fun translateHtmlFragments(
    from: Language,
    to: Language,
    fragments: List<String>,
  ): List<String> = handle.translateHtmlFragments(from.code, to.code, fragments)

  @Throws(CatalogException::class)
  fun translateImagePlan(
    bitmap: Bitmap,
    maxImageSize: Int,
    sourceSelection: uniffi.translator_core.OcrSourceSelection,
    to: Language,
    minConfidence: Int,
    readingOrder: ReadingOrder?,
    backgroundMode: BackgroundMode,
    detection: List<uniffi.translator_core.DetectedTextBox>? = null,
  ): uniffi.translator_core.PreparedImageOverlay =
    handle.translateImagePlan(
      rgbaBytes(bitmap),
      bitmap.width.toUInt(),
      bitmap.height.toUInt(),
      maxImageSize.toUInt(),
      sourceSelection,
      to.code,
      minConfidence.toUInt(),
      readingOrder,
      backgroundMode,
      detection,
    )

  @Throws(CatalogException::class)
  fun detectImageBoxes(
    bitmap: Bitmap,
    maxImageSize: Int,
  ): List<uniffi.translator_core.DetectedTextBox> =
    handle.detectImageBoxes(
      rgbaBytes(bitmap),
      bitmap.width.toUInt(),
      bitmap.height.toUInt(),
      maxImageSize.toUInt(),
    )

  @Throws(CatalogException::class)
  fun retranslateImagePlan(
    prepared: uniffi.translator_core.PreparedImageOverlay,
    from: Language,
    to: Language,
  ): uniffi.translator_core.PreparedImageOverlay = handle.retranslateImagePlan(prepared, from.code, to.code)

  @Throws(CatalogException::class)
  fun ocrImageDetect(
    img: uniffi.bindings.OcrImage,
    maxImageSize: Int,
  ): List<uniffi.translator_core.DetectedTextBox> = img.detect(handle, maxImageSize.toUInt())

  @Throws(CatalogException::class)
  fun ocrImagePlan(
    img: uniffi.bindings.OcrImage,
    maxImageSize: Int,
    sourceSelection: uniffi.translator_core.OcrSourceSelection,
    to: Language,
    minConfidence: Int,
    readingOrder: ReadingOrder?,
    backgroundMode: BackgroundMode,
    detection: List<uniffi.translator_core.DetectedTextBox>?,
  ): uniffi.translator_core.PreparedImageOverlay =
    img.ocr(
      handle,
      maxImageSize.toUInt(),
      sourceSelection,
      to.code,
      minConfidence.toUInt(),
      readingOrder,
      backgroundMode,
      detection,
    )

  @Throws(CatalogException::class)
  fun ocrImageRenderInto(
    img: uniffi.bindings.OcrImage,
    plan: uniffi.translator_core.PreparedImageOverlay,
    to: Language,
    minFontSizePx: Float,
    dstAddr: Long,
  ): List<uniffi.translator_core.PositionedWord> = img.renderInto(plan, to.code, minFontSizePx, dstAddr.toULong())

  @Throws(CatalogException::class)
  fun ocrImageRetranslateInto(
    img: uniffi.bindings.OcrImage,
    plan: uniffi.translator_core.PreparedImageOverlay,
    from: Language,
    to: Language,
    minFontSizePx: Float,
    dstAddr: Long,
  ): uniffi.bindings.RetranslateResult = img.retranslateInto(handle, plan, from.code, to.code, minFontSizePx, dstAddr.toULong())

  /** Internal accessor for the raw uniffi `CatalogHandle`. The live-
   *  overlay pipeline constructor takes this so it can hold an Arc to
   *  the underlying TranslatorSession for its async acquire/refresh
   *  worker, and the API server resolves the current session through
   *  it per request. */
  internal fun planarHandle(): CatalogHandle = handle

  @Throws(CatalogException::class)
  fun renderTranslatedOverlay(
    plan: uniffi.translator_core.PreparedImageOverlay,
    targetLanguage: Language,
    minFontSizePx: Float,
  ): uniffi.translator_render.RenderedOverlay = uniffi.bindings.renderTranslatedOverlay(plan, targetLanguage.code, minFontSizePx)

  @Throws(CatalogException::class)
  fun detectDocumentQuad(bitmap: Bitmap): uniffi.translator_align.DocumentDetection? =
    handle.detectDocumentQuad(rgbaBytes(bitmap), bitmap.width.toUInt(), bitmap.height.toUInt())

  @Throws(CatalogException::class)
  fun warpDocumentRgba(
    bitmap: Bitmap,
    quad: uniffi.translator_align.DocumentQuad,
    outWidth: Int? = null,
    outHeight: Int? = null,
    postprocess: Boolean = true,
  ): uniffi.translator_align.WarpedImageRgba =
    handle.warpDocumentRgba(
      rgbaBytes(bitmap),
      bitmap.width.toUInt(),
      bitmap.height.toUInt(),
      quad,
      outWidth?.toUInt(),
      outHeight?.toUInt(),
      postprocess,
    )

  @Throws(CatalogException::class)
  fun translateDocumentPath(
    inputPath: String,
    outputPath: String,
    from: Language,
    to: Language,
    pdfImages: PdfImages,
    txtLayout: TxtLayoutChoice,
    onProgress: (DocumentTranslationProgress) -> Unit = {},
    isCancelled: () -> Boolean = { false },
  ): String =
    handle.translateDocumentPathWithProgress(
      inputPath,
      outputPath,
      from.code,
      to.code,
      pdfImages,
      txtLayout.toUniffi(),
      object : DocumentProgressSink {
        override fun onProgress(event: DocumentProgressEvent) {
          onProgress(event.toDocumentTranslationProgress())
        }

        override fun isCancelled(): Boolean = isCancelled()
      },
    )

  /** Abort an in-flight document translation; workers stop within ~one batch. */
  fun cancelOngoingWork() = handle.cancelOngoingWork()

  /** ONNX→MNN migrations needed for this install (only entries whose `.onnx` is
   *  on disk). Run `needsConvert` jobs through [ModelConverterJni], then pass the
   *  finished/cleanup/discarded jobs to [CatalogRepository.discardMigration]. */
  fun planMigration(): List<uniffi.bindings.MigrationJobRecord> = handle.planMigration()

  fun planDownload(
    languageCode: String,
    feature: Feature,
    selectedTtsPackId: String? = null,
  ): DownloadPlan? = handle.planDownload(languageCode, feature, selectedTtsPackId)

  fun planSupportDownloadByKind(kind: String): DownloadPlan? = handle.planSupportDownloadByKind(kind)

  fun downloadTaskInstalled(task: DownloadTask): Boolean = handle.downloadTaskInstalled(task)

  fun prepareDelete(
    languageCode: String,
    feature: Feature,
  ): DeletePlan = handle.prepareDelete(languageCode, feature)

  fun prepareDeleteSupportByKind(kind: String): DeletePlan = handle.prepareDeleteSupportByKind(kind)

  fun prepareDeleteSupersededTts(
    languageCode: String,
    selectedPackId: String,
  ): DeletePlan = handle.prepareDeleteSupersededTts(languageCode, selectedPackId)

  fun prepareDeleteTtsPack(packId: String): DeletePlan = handle.prepareDeleteTtsPack(packId)

  fun defaultTtsPackIdForLanguage(languageCode: String): String? = handle.defaultTtsPackId(languageCode)

  fun sizeBytesForFeature(
    languageCode: String,
    feature: Feature,
  ): Long = handle.sizeBytes(languageCode, feature).toLong()

  fun supportSizeBytesByKind(kind: String): Long = handle.supportSizeBytesByKind(kind).toLong()

  fun supportInstalledByKind(kind: String): Boolean {
    val sizeBytes = supportSizeBytesByKind(kind)
    val plan = planSupportDownloadByKind(kind)
    val installed = plan != null && sizeBytes > 0 && plan.tasks.isEmpty()
    android.util.Log.i(
      "CatalogSupport",
      "supportInstalledByKind $kind: size=$sizeBytes tasks=${plan?.tasks?.size ?: -1} -> $installed",
    )
    return installed
  }

  fun availableTtsVoices(languageCode: String): List<TtsVoiceOption> =
    handle.availableTtsVoices(languageCode).map { voice ->
      TtsVoiceOption(
        name = voice.name,
        speakerId = voice.speakerId.toInt(),
        displayName = voice.displayName,
      )
    }

  fun installedTtsVoices(languageCode: String): List<uniffi.translator_core.InstalledTtsPack> = handle.installedTtsVoices(languageCode)

  @Throws(CatalogException::class)
  fun planSpeechChunks(
    languageCode: String,
    text: String,
    packId: String? = null,
    readUrlsAndHashtags: Boolean = false,
  ): List<SpeechChunkPlan> =
    handle
      .planSpeechChunks(
        languageCode,
        text,
        packId,
        if (readUrlsAndHashtags) UrlsAndHashtags.READ else UrlsAndHashtags.SKIP,
      ).map { chunk ->
        SpeechChunkPlan(
          content = chunk.content,
          isPhonemes = chunk.isPhonemes,
          pauseAfterMs = chunk.pauseAfterMs,
        )
      }

  @Throws(CatalogException::class)
  fun synthesizeSpeechPcm(
    languageCode: String,
    text: String,
    speechSpeed: Float,
    voiceName: String?,
    isPhonemes: Boolean,
    packId: String? = null,
  ): PcmAudio {
    val audio =
      handle.synthesizeSpeechPcm(languageCode, text, speechSpeed, voiceName, isPhonemes, packId)
    return PcmAudio(sampleRate = audio.sampleRate, pcmSamples = audio.pcmSamples.toShortArray())
  }
}
