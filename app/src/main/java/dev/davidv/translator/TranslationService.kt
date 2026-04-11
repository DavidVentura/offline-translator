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
import dev.davidv.bergamot.NativeLib
import dev.davidv.bergamot.TranslationWithAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

class TranslationService(
  private val settingsManager: SettingsManager,
  private val filePathManager: FilePathManager,
  private val english: Language,
) {
  companion object {
    private const val CLAUSE_PAUSE_MS = 120
    private const val SENTENCE_PAUSE_MS = 180
    private const val PARAGRAPH_PAUSE_MS = 320
    private const val MIN_PAUSE_SPLIT_CHARS = 12
    private val SENTENCE_BOUNDARY_CHARS = setOf('.', '?', '!', '。', '？', '！')
    private val CLAUSE_PAUSE_CHARS = setOf(',', ';', ':', '、', '，', '；', '：')

    @Volatile
    private var nativeLibInstance: NativeLib? = null

    private fun getNativeLib(): NativeLib =
      nativeLibInstance ?: synchronized(this) {
        nativeLibInstance ?: NativeLib().also {
          Log.d("TranslationService", "Initialized bergamot")
          nativeLibInstance = it
        }
      }

    fun cleanup() {
      synchronized(this) {
        nativeLibInstance?.cleanup()
        nativeLibInstance = null
      }
    }
  }

  private val nativeLib = getNativeLib()
  private val speechBinding = SpeechBinding()

  private var mucabBinding: MucabBinding? = null

  fun setMucabBinding(binding: MucabBinding?) {
    mucabBinding = binding
  }

  // / Requires the translation pairs to be available
  suspend fun preloadModel(
    from: Language,
    to: Language,
  ) = withContext(Dispatchers.IO) {
    val translationPairs = getTranslationPairs(from, to)
    for (pair in translationPairs) {
      val config = generateConfig(pair.first, pair.second)
      val languageCode = "${pair.first.code}${pair.second.code}"
      Log.d("TranslationService", "Preloading model with key: $languageCode")
      nativeLib.loadModelIntoCache(config, languageCode) // translate empty string to load the model
      Log.d("TranslationService", "Preloaded model for ${pair.first} -> ${pair.second} with key: $languageCode")
    }
  }

  suspend fun translateMultiple(
    from: Language,
    to: Language,
    texts: Array<String>,
  ): BatchTranslationResult =
    withContext(Dispatchers.IO) {
      if (from == to) {
        return@withContext BatchTranslationResult.Success(texts.map { TranslatedText(it, null) })
      }
      val translationPairs = getTranslationPairs(from, to)

      // TODO: do once
      for (pair in translationPairs) {
        val lang =
          if (pair.first.isEnglish) {
            pair.second
          } else {
            pair.first
          }
        val dataFiles =
          filePathManager
            .getDataDir()
            .listFiles()
            ?.map { it.name }
            ?.toSet() ?: emptySet()
        // TODO: this should be checked on startup and on update only
        if (missingFilesFrom(dataFiles, lang).second.isNotEmpty()) {
          return@withContext BatchTranslationResult.Error("Language pair ${pair.first} -> ${pair.second} not installed")
        }
      }
      preloadModel(from, to)

      val result: Array<String>
      val elapsed =
        measureTimeMillis {
          result = performTranslations(translationPairs, texts)
        }
      Log.d("TranslationService", "bulk translation took ${elapsed}ms")
      val translated =
        result.map { translatedText ->
          val transliterated =
            if (settingsManager.settings.value.enableOutputTransliteration) {
              transliterate(translatedText, to)
            } else {
              null
            }
          TranslatedText(translatedText, transliterated)
        }
      return@withContext BatchTranslationResult.Success(translated)
    }

  suspend fun translate(
    from: Language,
    to: Language,
    text: String,
  ): TranslationResult =
    withContext(Dispatchers.IO) {
      if (from == to) {
        return@withContext TranslationResult.Success(TranslatedText(text, null))
      }
      // numbers don't translate :^)
      if (text.trim().toFloatOrNull() != null) {
        return@withContext TranslationResult.Success(TranslatedText(text, null))
      }

      if (text.isBlank()) {
        return@withContext TranslationResult.Success(TranslatedText("", null))
      }

      val translationPairs = getTranslationPairs(from, to)

      // Validate all required language pairs are available
      for (pair in translationPairs) {
        val lang =
          if (pair.first.isEnglish) {
            pair.second
          } else {
            pair.first
          }
        val dataFiles =
          filePathManager
            .getDataDir()
            .listFiles()
            ?.map { it.name }
            ?.toSet() ?: emptySet()
        // TODO: this should be checked on startup and on update only
        if (missingFilesFrom(dataFiles, lang).second.isNotEmpty()) {
          return@withContext TranslationResult.Error("Language pair ${pair.first} -> ${pair.second} not installed")
        }
      }

      try {
        val result: String
        val elapsed =
          measureTimeMillis {
            result = performTranslations(translationPairs, arrayOf(text)).first()
          }
        Log.d("TranslationService", "Translation took ${elapsed}ms")
        val transliterated =
          if (settingsManager.settings.value.enableOutputTransliteration) {
            transliterate(result, to)
          } else {
            null
          }
        TranslationResult.Success(TranslatedText(result, transliterated))
      } catch (e: Exception) {
        Log.e("TranslationService", "Translation failed", e)
        TranslationResult.Error("Translation failed: ${e.message}")
      }
    }

  suspend fun translateMultipleWithAlignment(
    from: Language,
    to: Language,
    texts: Array<String>,
  ): BatchAlignedTranslationResult =
    withContext(Dispatchers.IO) {
      if (from == to) {
        return@withContext BatchAlignedTranslationResult.Success(
          texts.map { TranslationWithAlignment(it, it, emptyArray()) },
        )
      }
      val translationPairs = getTranslationPairs(from, to)

      for (pair in translationPairs) {
        val lang =
          if (pair.first.isEnglish) {
            pair.second
          } else {
            pair.first
          }
        val dataFiles =
          filePathManager
            .getDataDir()
            .listFiles()
            ?.map { it.name }
            ?.toSet() ?: emptySet()
        if (missingFilesFrom(dataFiles, lang).second.isNotEmpty()) {
          return@withContext BatchAlignedTranslationResult.Error(
            "Language pair ${pair.first} -> ${pair.second} not installed",
          )
        }
      }
      preloadModel(from, to)

      try {
        val results: Array<TranslationWithAlignment>
        val elapsed =
          measureTimeMillis {
            results = performTranslationsWithAlignment(translationPairs, texts)
          }
        Log.d("TranslationService", "aligned translation took ${elapsed}ms")
        BatchAlignedTranslationResult.Success(results.toList())
      } catch (e: Exception) {
        Log.e("TranslationService", "Aligned translation failed", e)
        BatchAlignedTranslationResult.Error("Translation failed: ${e.message}")
      }
    }

  private fun getTranslationPairs(
    from: Language,
    to: Language,
  ): List<Pair<Language, Language>> =
    when {
      from.isEnglish && to.isEnglish -> emptyList()
      from.isEnglish -> listOf(from to to)
      to.isEnglish -> listOf(from to to)
      else -> listOf(from to english, english to to)
    }

  // pairs can be len 1 or len 2 only
  private fun performTranslations(
    pairs: List<Pair<Language, Language>>,
    texts: Array<String>,
  ): Array<String> {
    pairs.forEach { pair ->
      val config = generateConfig(pair.first, pair.second)
      val languageCode = "${pair.first.code}${pair.second.code}"
      measureTimeMillis {
        nativeLib.loadModelIntoCache(config, languageCode)
      }
    }
    if (pairs.count() == 1) {
      val code = "${pairs[0].first.code}${pairs[0].second.code}"
      return nativeLib.translateMultiple(texts, code)
    } else if (pairs.count() == 2) {
      val toEng = "${pairs[0].first.code}${pairs[0].second.code}"
      val fromEng = "${pairs[1].first.code}${pairs[1].second.code}"
      return nativeLib.pivotMultiple(toEng, fromEng, texts)
    }
    return emptyArray()
  }

  private fun performTranslationsWithAlignment(
    pairs: List<Pair<Language, Language>>,
    texts: Array<String>,
  ): Array<TranslationWithAlignment> {
    pairs.forEach { pair ->
      val config = generateConfig(pair.first, pair.second)
      val languageCode = "${pair.first.code}${pair.second.code}"
      nativeLib.loadModelIntoCache(config, languageCode)
    }
    if (pairs.count() == 1) {
      val code = "${pairs[0].first.code}${pairs[0].second.code}"
      return nativeLib.translateMultipleWithAlignment(texts, code)
    } else if (pairs.count() == 2) {
      val toEng = "${pairs[0].first.code}${pairs[0].second.code}"
      val fromEng = "${pairs[1].first.code}${pairs[1].second.code}"
      return nativeLib.pivotMultipleWithAlignment(toEng, fromEng, texts)
    }
    return emptyArray()
  }

  private fun generateConfig(
    fromLang: Language,
    toLang: Language,
  ): String {
    val dataPath = filePathManager.getDataDir()
    val languageFiles =
      if (fromLang.isEnglish) {
        toLang.fromEnglish
      } else {
        fromLang.toEnglish
      } ?: throw IllegalArgumentException("No language files found for $fromLang -> $toLang")

    return """
models:
  - $dataPath/${languageFiles.model.name}
vocabs:
  - $dataPath/${languageFiles.srcVocab.name}
  - $dataPath/${languageFiles.tgtVocab.name}
beam-size: 1
normalize: 1.0
word-penalty: 0
max-length-break: 128
mini-batch-words: 1024
max-length-factor: 2.0
skip-cost: true
cpu-threads: 1
quiet: true
quiet-translation: true
gemm-precision: int8shiftAlphaAll
alignment: soft
"""
  }

  fun transliterate(
    text: String,
    from: Language,
  ): String? =
    TransliterationService.transliterate(
      text,
      from,
      mucabBinding = mucabBinding,
      japaneseSpaced = settingsManager.settings.value.addSpacesForJapaneseTransliteration,
    )

  suspend fun synthesizeSpeech(
    language: Language,
    text: String,
  ): SpeechSynthesisResult =
    withContext(Dispatchers.IO) {
      if (text.isBlank()) {
        return@withContext SpeechSynthesisResult.Error("Nothing to speak")
      }

      val voiceFiles =
        filePathManager.getTtsVoiceFiles(language)
          ?: return@withContext SpeechSynthesisResult.Error(
            "No TTS voice installed for ${language.displayName}",
          )

      val supportDataPath = filePathManager.getTtsSupportDataRoot()?.absolutePath
      val speechSpeed = settingsManager.settings.value.ttsPlaybackSpeed.coerceIn(0.5f, 2.0f)
      val selectedVoiceName = settingsManager.settings.value.ttsVoiceOverrides[voiceFiles.languageCode]
      val speakerId = voiceFiles.speakerId
      Log.d(
        "TranslationService",
        "Using TTS speakerId=$speakerId voiceName=$selectedVoiceName speechSpeed=$speechSpeed engine=${voiceFiles.engine} language=${voiceFiles.languageCode}",
      )
      var phonemizeFailed = false
      val phonemizeText: (String) -> List<PhonemeChunk> = { chunkText: String ->
        speechBinding.phonemizeChunks(
          engine = voiceFiles.engine,
          modelPath = voiceFiles.model.absolutePath,
          auxPath = voiceFiles.aux.absolutePath,
          supportDataPath = supportDataPath,
          languageCode = voiceFiles.languageCode,
          text = chunkText,
        ) ?: run {
          phonemizeFailed = true
          emptyList()
        }
      }

      val chunkRequests = buildSpeechChunkRequests(text = text, phonemizeText = phonemizeText)
      if (phonemizeFailed || chunkRequests.isEmpty()) {
        return@withContext SpeechSynthesisResult.Error(
          "Speech synthesis failed for ${language.displayName}",
        )
      }

      SpeechSynthesisResult.Success(
        flow {
          for ((index, chunkRequest) in chunkRequests.withIndex()) {
            currentCoroutineContext().ensureActive()
            Log.d(
              "TranslationService",
              "Speech chunk ${index + 1}/${chunkRequests.size}: synth start isPhonemes=${chunkRequest.isPhonemes} textLen=${chunkRequest.content.length} boundary=${chunkRequest.boundaryAfter} pauseOverride=${chunkRequest.pauseAfterMsOverride}",
            )
            val pcmAudio =
              speechBinding.synthesizePcm(
                engine = voiceFiles.engine,
                modelPath = voiceFiles.model.absolutePath,
                auxPath = voiceFiles.aux.absolutePath,
                supportDataPath = supportDataPath,
                languageCode = voiceFiles.languageCode,
                text = chunkRequest.content,
                speechSpeed = speechSpeed,
                voiceName = selectedVoiceName,
                speakerId = speakerId,
                isPhonemes = chunkRequest.isPhonemes,
              ) ?: throw IllegalStateException(
                "Speech synthesis failed for ${language.displayName}",
              )
            val audioDurationMs = (pcmAudio.pcmSamples.size * 1000L) / pcmAudio.sampleRate
            Log.d(
              "TranslationService",
              "Speech chunk ${index + 1}/${chunkRequests.size}: synth ready samples=${pcmAudio.pcmSamples.size} sampleRate=${pcmAudio.sampleRate} audioMs=$audioDurationMs",
            )
            currentCoroutineContext().ensureActive()
            Log.d("TranslationService", "Speech chunk ${index + 1}/${chunkRequests.size}: emit start")
            emit(pcmAudio)
            Log.d("TranslationService", "Speech chunk ${index + 1}/${chunkRequests.size}: emit returned")

            val silenceChunk = pauseChunkFor(chunkRequest, pcmAudio.sampleRate)
            if (silenceChunk != null) {
              val silenceMs = (silenceChunk.pcmSamples.size * 1000L) / silenceChunk.sampleRate
              Log.d(
                "TranslationService",
                "Speech chunk ${index + 1}/${chunkRequests.size}: silence emit start audioMs=$silenceMs",
              )
              emit(silenceChunk)
              Log.d("TranslationService", "Speech chunk ${index + 1}/${chunkRequests.size}: silence emit returned")
            }
          }
        },
      )
    }

  suspend fun availableTtsVoices(language: Language): List<TtsVoiceOption> =
    withContext(Dispatchers.IO) {
      val voiceFiles = filePathManager.getTtsVoiceFiles(language) ?: return@withContext emptyList()
      val supportDataPath = filePathManager.getTtsSupportDataRoot()?.absolutePath
      speechBinding.listVoices(
        engine = voiceFiles.engine,
        modelPath = voiceFiles.model.absolutePath,
        auxPath = voiceFiles.aux.absolutePath,
        supportDataPath = supportDataPath,
        languageCode = voiceFiles.languageCode,
      ) ?: emptyList()
    }

  private fun buildSpeechChunkRequests(
    text: String,
    phonemizeText: (String) -> List<PhonemeChunk>,
  ): List<SpeechChunkRequest> = clearFinalBoundary(buildSpeechChunkRequestsInternal(text, phonemizeText))

  private fun buildSpeechChunkRequestsInternal(
    text: String,
    phonemizeText: (String) -> List<PhonemeChunk>,
  ): List<SpeechChunkRequest> {
    val sourceChunks = splitTextIntoSpeechChunks(text)
    if (sourceChunks.size > 1) {
      val requests =
        buildList {
          for (sourceChunk in sourceChunks) {
            addAll(buildSpeechChunkRequestsInternal(sourceChunk, phonemizeText))
          }
        }
      Log.d(
        "TranslationService",
        "Built speech requests from ${sourceChunks.size} source chunk(s) into ${requests.size} playback chunk(s)",
      )
      return requests
    }

    val phonemeChunks = phonemizeText(text).filter { it.content.isNotBlank() }
    if (phonemeChunks.isEmpty()) {
      return emptyList()
    }

    if (phonemeChunks.size > 1) {
      return phonemeChunks.map { chunk ->
        SpeechChunkRequest(
          content = chunk.content,
          isPhonemes = true,
          boundaryAfter = chunk.boundaryAfter,
          pauseAfterMsOverride = null,
        )
      }
    }

    val splitRequests = buildSplitChunkRequests(text, phonemeChunks.single(), phonemizeText)
    if (splitRequests != null) {
      return splitRequests
    }

    return listOf(
      SpeechChunkRequest(
        content = text,
        isPhonemes = false,
        boundaryAfter = SpeechChunkBoundary.None,
        pauseAfterMsOverride = null,
      ),
    )
  }

  private fun buildSplitChunkRequests(
    sourceChunk: String,
    phonemeChunk: PhonemeChunk,
    phonemizeText: (String) -> List<PhonemeChunk>,
  ): List<SpeechChunkRequest>? {
    if (phonemeChunk.content.length <= 100) {
      return null
    }

    val splitText = splitAtBestPause(sourceChunk) ?: return null
    val remainingRequests = buildSpeechChunkRequestsInternal(splitText.second, phonemizeText)
    if (remainingRequests.isEmpty()) {
      return null
    }

    Log.d(
      "TranslationService",
      "Forcing fast speech chunk at best pause for long utterance (${phonemeChunk.content.length} phoneme chars); remainder re-chunked into ${remainingRequests.size} chunk(s)",
    )

    return buildList {
      add(
        SpeechChunkRequest(
          content = splitText.first,
          isPhonemes = false,
          boundaryAfter = SpeechChunkBoundary.None,
          pauseAfterMsOverride = CLAUSE_PAUSE_MS,
        ),
      )
      addAll(remainingRequests)
    }
  }

  private fun splitTextIntoSpeechChunks(text: String): List<String> =
    splitIntoParagraphs(text).flatMap { paragraph -> splitParagraphIntoSentenceishSegments(paragraph) }

  private fun splitIntoParagraphs(text: String): List<String> {
    val paragraphs = mutableListOf<String>()
    val current = StringBuilder()
    var previousLineEndedSentence = false

    for (line in text.lines()) {
      val trimmed = line.trim()
      if (trimmed.isEmpty()) {
        if (current.isNotEmpty()) {
          paragraphs.add(current.toString())
          current.clear()
        }
        previousLineEndedSentence = false
        continue
      }

      if (current.isNotEmpty() && previousLineEndedSentence) {
        paragraphs.add(current.toString())
        current.clear()
      } else if (current.isNotEmpty()) {
        current.append(' ')
      }

      current.append(trimmed)
      previousLineEndedSentence = trimmed.lastOrNull()?.let(::isSentenceBoundaryChar) == true
    }

    if (current.isNotEmpty()) {
      paragraphs.add(current.toString())
    }

    return paragraphs
  }

  private fun splitParagraphIntoSentenceishSegments(paragraph: String): List<String> {
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    var index = 0

    while (index < paragraph.length) {
      val ch = paragraph[index]
      current.append(ch)
      index += 1

      if (isSentenceBoundaryChar(ch)) {
        while (index < paragraph.length && isSentenceBoundaryChar(paragraph[index])) {
          current.append(paragraph[index])
          index += 1
        }

        val isBoundary = index == paragraph.length || paragraph[index].isWhitespace()
        if (isBoundary) {
          val segment = current.toString().trim()
          if (segment.isNotEmpty()) {
            segments.add(segment)
          }
          current.clear()
          while (index < paragraph.length && paragraph[index].isWhitespace()) {
            index += 1
          }
        }
      }
    }

    val tail = current.toString().trim()
    if (tail.isNotEmpty()) {
      segments.add(tail)
    }

    if (segments.isEmpty()) {
      val trimmed = paragraph.trim()
      if (trimmed.isNotEmpty()) {
        segments.add(trimmed)
      }
    }

    return segments
  }

  private fun isSentenceBoundaryChar(ch: Char): Boolean = ch in SENTENCE_BOUNDARY_CHARS

  private fun clearFinalBoundary(chunks: List<SpeechChunkRequest>): List<SpeechChunkRequest> {
    if (chunks.isEmpty()) {
      return chunks
    }

    return chunks.mapIndexed { index, chunk ->
      if (index == chunks.lastIndex && chunk.boundaryAfter != SpeechChunkBoundary.None) {
        chunk.copy(boundaryAfter = SpeechChunkBoundary.None)
      } else {
        chunk
      }
    }
  }

  private fun pauseChunkFor(
    chunkRequest: SpeechChunkRequest,
    sampleRate: Int,
  ): PcmAudio? {
    val pauseMs =
      chunkRequest.pauseAfterMsOverride
        ?: when (chunkRequest.boundaryAfter) {
          SpeechChunkBoundary.None -> return null
          SpeechChunkBoundary.Sentence -> SENTENCE_PAUSE_MS
          SpeechChunkBoundary.Paragraph -> PARAGRAPH_PAUSE_MS
        }

    return PcmAudio.silence(sampleRate, pauseMs)
  }

  private fun splitAtBestPause(text: String): Pair<String, String>? {
    val minSideChars = maxOf(MIN_PAUSE_SPLIT_CHARS, text.length / 4)
    val midpoint = text.length / 2.0

    return text.indices
      .asSequence()
      .filter { text[it] in CLAUSE_PAUSE_CHARS }
      .mapNotNull { splitIndex ->
        val firstChunk = text.substring(0, splitIndex + 1).trim()
        val secondChunk = text.substring(splitIndex + 1).trim()
        if (firstChunk.length < minSideChars || secondChunk.length < minSideChars) {
          return@mapNotNull null
        }
        val balancePenalty = kotlin.math.abs(firstChunk.length - secondChunk.length)
        val midpointPenalty = kotlin.math.abs(midpoint - splitIndex)
        Triple(balancePenalty, midpointPenalty, firstChunk to secondChunk)
      }
      .minWithOrNull(compareBy<Triple<Int, Double, Pair<String, String>>>({ it.first }, { it.second }))
      ?.third
  }
}

sealed class TranslationResult {
  data class Success(
    val result: TranslatedText,
  ) : TranslationResult()

  data class Error(
    val message: String,
  ) : TranslationResult()
}

sealed class BatchTranslationResult {
  data class Success(
    val result: List<TranslatedText>,
  ) : BatchTranslationResult()

  data class Error(
    val message: String,
  ) : BatchTranslationResult()
}

sealed class BatchAlignedTranslationResult {
  data class Success(
    val results: List<TranslationWithAlignment>,
  ) : BatchAlignedTranslationResult()

  data class Error(
    val message: String,
  ) : BatchAlignedTranslationResult()
}

sealed class SpeechSynthesisResult {
  data class Success(
    val audioChunks: Flow<PcmAudio>,
  ) : SpeechSynthesisResult()

  data class Error(
    val message: String,
  ) : SpeechSynthesisResult()
}

internal data class SpeechChunkRequest(
  val content: String,
  val isPhonemes: Boolean,
  val boundaryAfter: SpeechChunkBoundary,
  val pauseAfterMsOverride: Int?,
)
