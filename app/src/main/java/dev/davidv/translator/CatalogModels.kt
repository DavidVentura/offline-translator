package dev.davidv.translator

import android.graphics.Bitmap
import uniffi.bindings.CatalogHandle
import uniffi.bindings.sampleOverlayColorsRgba
import java.nio.ByteBuffer

private fun rgbaBytes(bitmap: Bitmap): ByteArray =
  ByteArray(bitmap.byteCount).also { bytes ->
    bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
  }

private data class CroppedOverlayScreenshot(
  val screenshot: uniffi.translator.OverlayScreenshot,
  val left: Int,
  val top: Int,
)

private fun cropOverlayScreenshot(
  bitmap: Bitmap,
  fragments: List<StyledFragment>,
  marginPx: Int = 4,
): CroppedOverlayScreenshot? {
  if (fragments.isEmpty()) return null

  var left = Int.MAX_VALUE
  var top = Int.MAX_VALUE
  var right = Int.MIN_VALUE
  var bottom = Int.MIN_VALUE
  fragments.forEach { fragment ->
    val bounds = fragment.bounds
    left = minOf(left, bounds.left)
    top = minOf(top, bounds.top)
    right = maxOf(right, bounds.right)
    bottom = maxOf(bottom, bounds.bottom)
  }

  if (left == Int.MAX_VALUE || top == Int.MAX_VALUE) return null

  val cropLeft = (left - marginPx).coerceIn(0, bitmap.width)
  val cropTop = (top - marginPx).coerceIn(0, bitmap.height)
  val cropRight = (right + marginPx).coerceIn(cropLeft, bitmap.width)
  val cropBottom = (bottom + marginPx).coerceIn(cropTop, bitmap.height)
  val cropWidth = cropRight - cropLeft
  val cropHeight = cropBottom - cropTop
  if (cropWidth <= 0 || cropHeight <= 0) return null

  val croppedBitmap = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
  try {
    return CroppedOverlayScreenshot(
      screenshot =
        uniffi.translator.OverlayScreenshot(
          rgbaBytes(croppedBitmap),
          croppedBitmap.width.toUInt(),
          croppedBitmap.height.toUInt(),
        ),
      left = cropLeft,
      top = cropTop,
    )
  } finally {
    croppedBitmap.recycle()
  }
}

private fun shiftStyledFragment(
  fragment: StyledFragment,
  dx: Int,
  dy: Int,
): StyledFragment {
  val bounds = fragment.bounds
  return StyledFragment(
    text = fragment.text,
    boundingBox =
      Rect(
        left = (bounds.left - dx).coerceAtLeast(0),
        top = (bounds.top - dy).coerceAtLeast(0),
        right = (bounds.right - dx).coerceAtLeast(0),
        bottom = (bounds.bottom - dy).coerceAtLeast(0),
      ).toUniffiRect(),
    style = fragment.style,
    layoutGroup = fragment.layoutGroup,
    translationGroup = fragment.translationGroup,
    clusterGroup = fragment.clusterGroup,
  )
}

private fun shiftStructuredTranslationResult(
  result: uniffi.translator.StructuredTranslationResult,
  dx: Int,
  dy: Int,
): uniffi.translator.StructuredTranslationResult =
  uniffi.translator.StructuredTranslationResult(
    blocks =
      result.blocks.map { block ->
        val bounds =
          Rect(
            block.boundingBox.left.toInt() + dx,
            block.boundingBox.top.toInt() + dy,
            block.boundingBox.right.toInt() + dx,
            block.boundingBox.bottom.toInt() + dy,
          ).toUniffiRect()
        uniffi.translator.TranslatedStyledBlock(
          text = block.text,
          boundingBox = bounds,
          styleSpans = block.styleSpans,
          backgroundArgb = block.backgroundArgb,
          foregroundArgb = block.foregroundArgb,
        )
      },
    nothingReason = result.nothingReason,
    errorMessage = result.errorMessage,
  )

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
) {
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
          LanguageAvailabilityEntry(
            language =
              Language(
                code = row.language.code,
                displayName = row.language.displayName,
                shortDisplayName = row.language.shortDisplayName,
                tessName = row.language.tessName,
                script = row.language.script,
                dictionaryCode = row.language.dictionaryCode,
                tessdataSizeBytes = row.language.tessdataSizeBytes.toLong(),
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

  fun lookupDictionary(
    language: Language,
    word: String,
  ): WordWithTaggedEntries? = handle.lookupDictionary(language.code, word)

  fun availabilityFor(language: Language?): LangAvailability? = language?.let { availabilityByCode[it.code] }

  fun hasTtsVoices(languageCode: String): Boolean = handle.hasTtsVoices(languageCode)

  fun ttsVoicePickerRegions(languageCode: String): List<TtsVoicePickerRegion> =
    handle.ttsVoicePickerRegions(languageCode).map { region ->
      TtsVoicePickerRegion(
        code = region.code,
        displayName = region.displayName,
        voices = region.voices.map { TtsVoicePackInfo(it.packId, it.displayName, it.quality, it.sizeBytes.toLong()) },
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

  fun warmTranslationModels(
    from: Language,
    to: Language,
  ): Boolean = handle.warmTranslationModels(from.code, to.code)

  fun translateText(
    from: Language,
    to: Language,
    text: String,
  ): String? = handle.translateText(from.code, to.code, text)

  fun translateMixedTexts(
    inputs: List<String>,
    forcedSourceLanguage: Language?,
    targetLanguage: Language,
    availableLanguages: List<Language>,
  ): uniffi.translator.MixedTextTranslationResult =
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
  ): uniffi.translator.StructuredTranslationResult {
    val needsScreenshotSampling =
      backgroundMode == BackgroundMode.AUTO_DETECT &&
        fragments.any { !(it.style?.hasRealBackground() ?: false) }
    val croppedScreenshot = screenshot?.takeIf { needsScreenshotSampling }?.let { cropOverlayScreenshot(it, fragments) }
    val nativeFragments =
      if (croppedScreenshot != null) {
        fragments.map { shiftStyledFragment(it, croppedScreenshot.left, croppedScreenshot.top) }
      } else {
        fragments
      }
    val result =
      handle.translateStructuredFragments(
        nativeFragments,
        forcedSourceLanguage?.code,
        targetLanguage.code,
        availableLanguages.map { it.code },
        croppedScreenshot?.screenshot,
        backgroundMode,
      )
    return if (croppedScreenshot != null) {
      shiftStructuredTranslationResult(result, croppedScreenshot.left, croppedScreenshot.top)
    } else {
      result
    }
  }

  fun translateImagePlan(
    bitmap: Bitmap,
    from: Language,
    to: Language,
    minConfidence: Int,
    readingOrder: ReadingOrder,
    backgroundMode: BackgroundMode,
  ): uniffi.translator.ImageTranslationOutcome {
    return handle.translateImagePlan(
      rgbaBytes(bitmap),
      bitmap.width.toUInt(),
      bitmap.height.toUInt(),
      from.code,
      to.code,
      minConfidence.toUInt(),
      readingOrder,
      backgroundMode,
    )
  }

  fun planLanguageDownload(languageCode: String): DownloadPlan = handle.planLanguageDownload(languageCode).toDownloadPlan()

  fun planDictionaryDownload(languageCode: String): DownloadPlan? = handle.planDictionaryDownload(languageCode)?.toDownloadPlan()

  fun planTtsDownload(
    languageCode: String,
    selectedPackId: String? = null,
  ): DownloadPlan? = handle.planTtsDownload(languageCode, selectedPackId)?.toDownloadPlan()

  fun planDeleteLanguage(languageCode: String): DeletePlan =
    handle.planDeleteLanguage(languageCode).let {
      DeletePlan(it.filePaths, it.directoryPaths)
    }

  fun planDeleteDictionary(languageCode: String): DeletePlan =
    handle.planDeleteDictionary(languageCode).let {
      DeletePlan(it.filePaths, it.directoryPaths)
    }

  fun planDeleteTts(languageCode: String): DeletePlan =
    handle.planDeleteTts(
      languageCode,
    ).let { DeletePlan(it.filePaths, it.directoryPaths) }

  fun planDeleteSupersededTts(
    languageCode: String,
    selectedPackId: String,
  ): DeletePlan = handle.planDeleteSupersededTts(languageCode, selectedPackId).let { DeletePlan(it.filePaths, it.directoryPaths) }

  fun defaultTtsPackIdForLanguage(languageCode: String): String? = handle.defaultTtsPackId(languageCode)

  fun ttsSizeBytesForLanguage(languageCode: String): Long = handle.ttsSizeBytes(languageCode).toLong()

  fun translationSizeBytesForLanguage(languageCode: String): Long = handle.translationSizeBytes(languageCode).toLong()

  fun availableTtsVoices(languageCode: String): List<TtsVoiceOption> =
    handle.availableTtsVoices(languageCode).map { voice ->
      TtsVoiceOption(
        name = voice.name,
        speakerId = voice.speakerId.toInt(),
        displayName = voice.displayName,
      )
    }

  fun planSpeechChunks(
    languageCode: String,
    text: String,
  ): List<SpeechChunkPlan> =
    handle.planSpeechChunks(languageCode, text).map { chunk ->
      SpeechChunkPlan(
        content = chunk.content,
        isPhonemes = chunk.isPhonemes,
        pauseAfterMs = chunk.pauseAfterMs,
      )
    }

  fun synthesizeSpeechPcm(
    languageCode: String,
    text: String,
    speechSpeed: Float,
    voiceName: String?,
    isPhonemes: Boolean,
  ): PcmAudio? =
    handle.synthesizeSpeechPcm(languageCode, text, speechSpeed, voiceName, isPhonemes)?.let { audio ->
      PcmAudio(sampleRate = audio.sampleRate, pcmSamples = audio.pcmSamples.toShortArray())
    }
}

private fun uniffi.translator.DownloadTask.toDownloadTask(): DownloadTask =
  DownloadTask(
    packId = packId,
    installPath = installPath,
    url = url,
    sizeBytes = sizeBytes.toLong(),
    decompress = decompress,
    archiveFormat = archiveFormat,
    extractTo = extractTo,
    deleteAfterExtract = deleteAfterExtract,
    installMarkerPath = installMarkerPath,
    installMarkerVersion = installMarkerVersion,
  )

private fun uniffi.translator.DownloadPlan.toDownloadPlan(): DownloadPlan =
  DownloadPlan(totalSize = totalSize.toLong(), tasks = tasks.map { it.toDownloadTask() })

fun sampleOverlayColors(
  bitmap: Bitmap,
  bounds: Rect,
  backgroundMode: BackgroundMode,
  wordRects: Array<Rect>? = null,
): OverlayColors {
  val sampleMargin = 4
  val cropLeft = (bounds.left - sampleMargin).coerceAtLeast(0)
  val cropTop = (bounds.top - sampleMargin).coerceAtLeast(0)
  val cropRight = (bounds.right + sampleMargin).coerceAtMost(bitmap.width)
  val cropBottom = (bounds.bottom + sampleMargin).coerceAtMost(bitmap.height)
  val cropWidth = cropRight - cropLeft
  val cropHeight = cropBottom - cropTop
  if (cropWidth <= 0 || cropHeight <= 0) {
    return OverlayColors(
      background = android.graphics.Color.WHITE,
      foreground = android.graphics.Color.BLACK,
    )
  }

  val croppedBitmap = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
  val localBounds =
    uniffi.translator.Rect(
      left = (bounds.left - cropLeft).coerceIn(0, cropWidth).toUInt(),
      top = (bounds.top - cropTop).coerceIn(0, cropHeight).toUInt(),
      right = (bounds.right - cropLeft).coerceIn(0, cropWidth).toUInt(),
      bottom = (bounds.bottom - cropTop).coerceIn(0, cropHeight).toUInt(),
    )
  val localWordRects =
    wordRects?.mapNotNull { rect ->
      val left = (rect.left - cropLeft).coerceIn(0, cropWidth)
      val top = (rect.top - cropTop).coerceIn(0, cropHeight)
      val right = (rect.right - cropLeft).coerceIn(0, cropWidth)
      val bottom = (rect.bottom - cropTop).coerceIn(0, cropHeight)
      if (right <= left || bottom <= top) {
        null
      } else {
        uniffi.translator.Rect(
          left = left.toUInt(),
          top = top.toUInt(),
          right = right.toUInt(),
          bottom = bottom.toUInt(),
        )
      }
    }
  val colors =
    sampleOverlayColorsRgba(
      rgbaBytes(croppedBitmap),
      croppedBitmap.width.toUInt(),
      croppedBitmap.height.toUInt(),
      localBounds,
      backgroundMode,
      localWordRects,
    )
  croppedBitmap.recycle()
  return OverlayColors(
    background = colors?.backgroundArgb?.toInt() ?: android.graphics.Color.WHITE,
    foreground = colors?.foregroundArgb?.toInt() ?: android.graphics.Color.BLACK,
  )
}
