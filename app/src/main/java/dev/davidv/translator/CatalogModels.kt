package dev.davidv.translator

import android.graphics.Bitmap
import uniffi.bindings.CatalogHandle
import uniffi.bindings.ImageTranslationPlan
import uniffi.bindings.MixedTextTranslationResult
import uniffi.bindings.OcrBackgroundMode
import uniffi.bindings.OcrReadingOrder
import uniffi.bindings.sampleOverlayColorsRgba
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

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

  fun translateTexts(
    from: Language,
    to: Language,
    texts: Array<String>,
  ): Array<String>? = handle.translateTexts(from.code, to.code, texts.toList())?.toTypedArray()

  fun translateTextsWithAlignment(
    from: Language,
    to: Language,
    texts: Array<String>,
  ): Array<TranslationWithAlignment>? =
    handle.translateTextsWithAlignment(from.code, to.code, texts.toList())?.map { value ->
      TranslationWithAlignment(
        source = value.sourceText,
        target = value.translatedText,
        alignments =
          value.alignments.map { alignment ->
            TokenAlignment(
              srcBegin = alignment.srcBegin.toInt(),
              srcEnd = alignment.srcEnd.toInt(),
              tgtBegin = alignment.tgtBegin.toInt(),
              tgtEnd = alignment.tgtEnd.toInt(),
            )
          }.toTypedArray(),
      )
    }?.toTypedArray()

  fun translateMixedTexts(
    inputs: List<String>,
    forcedSourceLanguage: Language?,
    targetLanguage: Language,
    availableLanguages: List<Language>,
  ): MixedTextTranslationResult =
    handle.translateMixedTexts(
      inputs,
      forcedSourceLanguage?.code,
      targetLanguage.code,
      availableLanguages.map { it.code },
    )

  fun translateStructuredFragments(
    fragments: List<StyledFragment>,
    forcedSourceLanguage: Language?,
    targetLanguage: Language,
    availableLanguages: List<Language>,
    screenshot: Bitmap?,
    backgroundMode: BackgroundMode,
  ): StructuredFragmentTranslationResult {
    val screenshotInput =
      screenshot?.let { bitmap ->
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        uniffi.bindings.OverlayScreenshot(buffer.array(), bitmap.width, bitmap.height)
      }
    return handle.translateStructuredFragments(
      fragments.map { fragment ->
        uniffi.bindings.StructuredFragment(
          text = fragment.text,
          boundingBox =
            uniffi.bindings.OcrRect(
              left = fragment.bounds.left.toLong(),
              top = fragment.bounds.top.toLong(),
              right = fragment.bounds.right.toLong(),
              bottom = fragment.bounds.bottom.toLong(),
            ),
          style =
            fragment.style?.let { style ->
              uniffi.bindings.StructuredTextStyle(
                textColor = style.textColor?.toLong(),
                bgColor = style.bgColor?.toLong(),
                textSize = style.textSize,
                bold = style.bold,
                italic = style.italic,
                underline = style.underline,
                strikethrough = style.strikethrough,
              )
            },
          layoutGroup = fragment.layoutGroup,
          translationGroup = fragment.translationGroup,
          clusterGroup = fragment.clusterGroup,
        )
      },
      forcedSourceLanguage?.code,
      targetLanguage.code,
      availableLanguages.map { it.code },
      screenshotInput,
      when (backgroundMode) {
        BackgroundMode.WHITE_ON_BLACK -> OcrBackgroundMode.WHITE_ON_BLACK
        BackgroundMode.BLACK_ON_WHITE -> OcrBackgroundMode.BLACK_ON_WHITE
        BackgroundMode.AUTO_DETECT -> OcrBackgroundMode.AUTO_DETECT
      },
    ).let { result ->
      StructuredFragmentTranslationResult(
        blocks =
          result.blocks.map { block ->
            TranslatedStyledBlock(
              text = block.text,
              bounds =
                Rect(
                  block.boundingBox.left.toInt(),
                  block.boundingBox.top.toInt(),
                  block.boundingBox.right.toInt(),
                  block.boundingBox.bottom.toInt(),
                ),
              styleSpans =
                block.styleSpans.map { span ->
                  StyleSpan(
                    start = span.start,
                    end = span.end,
                    style =
                      span.style?.let { style ->
                        TextStyle(
                          textColor = style.textColor?.toInt(),
                          bgColor = style.bgColor?.toInt(),
                          textSize = style.textSize,
                          bold = style.bold,
                          italic = style.italic,
                          underline = style.underline,
                          strikethrough = style.strikethrough,
                        )
                      },
                  )
                },
              backgroundArgb = block.backgroundArgb.toInt(),
              foregroundArgb = block.foregroundArgb.toInt(),
            )
          },
        nothingReason = result.nothingReason?.let(NothingReason::valueOf),
        errorMessage = result.errorMessage,
      )
    }
  }

  fun translateImagePlan(
    bitmap: Bitmap,
    from: Language,
    to: Language,
    minConfidence: Int,
    readingOrder: ReadingOrder,
    backgroundMode: BackgroundMode,
  ): ImageTranslationPlan? {
    val buffer = ByteBuffer.allocate(bitmap.byteCount)
    bitmap.copyPixelsToBuffer(buffer)
    return handle.translateImagePlan(
      buffer.array(),
      bitmap.width,
      bitmap.height,
      from.code,
      to.code,
      minConfidence,
      when (readingOrder) {
        ReadingOrder.LEFT_TO_RIGHT -> OcrReadingOrder.LEFT_TO_RIGHT
        ReadingOrder.TOP_TO_BOTTOM_LEFT_TO_RIGHT -> OcrReadingOrder.TOP_TO_BOTTOM_LEFT_TO_RIGHT
      },
      when (backgroundMode) {
        BackgroundMode.WHITE_ON_BLACK -> OcrBackgroundMode.WHITE_ON_BLACK
        BackgroundMode.BLACK_ON_WHITE -> OcrBackgroundMode.BLACK_ON_WHITE
        BackgroundMode.AUTO_DETECT -> OcrBackgroundMode.AUTO_DETECT
      },
    )
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

data class StructuredFragmentTranslationResult(
  val blocks: List<TranslatedStyledBlock>,
  val nothingReason: NothingReason?,
  val errorMessage: String?,
)

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

fun sampleOverlayColors(
  bitmap: Bitmap,
  bounds: Rect,
  backgroundMode: BackgroundMode,
  wordRects: Array<Rect>? = null,
): OverlayColors {
  val buffer = ByteBuffer.allocate(bitmap.byteCount)
  bitmap.copyPixelsToBuffer(buffer)
  val colors =
    sampleOverlayColorsRgba(
      buffer.array(),
      bitmap.width,
      bitmap.height,
      uniffi.bindings.OcrRect(bounds.left.toLong(), bounds.top.toLong(), bounds.right.toLong(), bounds.bottom.toLong()),
      when (backgroundMode) {
        BackgroundMode.WHITE_ON_BLACK -> OcrBackgroundMode.WHITE_ON_BLACK
        BackgroundMode.BLACK_ON_WHITE -> OcrBackgroundMode.BLACK_ON_WHITE
        BackgroundMode.AUTO_DETECT -> OcrBackgroundMode.AUTO_DETECT
      },
      wordRects?.map { rect ->
        uniffi.bindings.OcrRect(rect.left.toLong(), rect.top.toLong(), rect.right.toLong(), rect.bottom.toLong())
      },
    )
  return OverlayColors(
    background = colors?.backgroundArgb?.toInt() ?: android.graphics.Color.WHITE,
    foreground = colors?.foregroundArgb?.toInt() ?: android.graphics.Color.BLACK,
  )
}
