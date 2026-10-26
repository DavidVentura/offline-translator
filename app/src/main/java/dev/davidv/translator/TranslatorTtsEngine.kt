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

import android.app.Activity
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

private sealed interface SpeechStep {
  data class Speak(
    val chunk: SpeechChunkPlan,
  ) : SpeechStep

  data class Silence(
    val durationMs: Int,
  ) : SpeechStep
}

class TranslatorTtsService : TextToSpeechService() {
  @Volatile
  private var stopped = false

  @Volatile
  private var loadedVoice: TranslatorTtsVoice? = null

  override fun onCreate() {
    // TextToSpeechService.onCreate loads the default language, which already reads the catalog.
    (application as TranslatorApplication).ensureServicesInitialized()
    super.onCreate()
  }

  override fun onGetVoices(): List<Voice> {
    val ttsVoices = TranslatorTtsEngine.systemTtsVoices(catalog())
    Log.i(
      "TranslatorTtsService",
      "onGetVoices: returning ${ttsVoices.size} voices: " +
        ttsVoices.joinToString { "${it.androidName}@${it.locale}" },
    )
    return ttsVoices.map { ttsVoice ->
      Voice(
        ttsVoice.androidName,
        ttsVoice.locale,
        ttsVoice.quality,
        Voice.LATENCY_NORMAL,
        false,
        setOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS),
      )
    }
  }

  override fun onIsValidVoiceName(voiceName: String): Int =
    if (TranslatorTtsEngine.voiceNamed(catalog(), voiceName) != null) {
      TextToSpeech.SUCCESS
    } else {
      TextToSpeech.ERROR
    }

  override fun onLoadVoice(voiceName: String): Int {
    val voice = TranslatorTtsEngine.voiceNamed(catalog(), voiceName) ?: return TextToSpeech.ERROR
    loadedVoice = voice
    return TextToSpeech.SUCCESS
  }

  override fun onGetDefaultVoiceNameFor(
    lang: String,
    country: String,
    variant: String,
  ): String? = TranslatorTtsEngine.defaultVoice(catalog(), voiceOverrides(), lang, country)?.androidName

  override fun onIsLanguageAvailable(
    lang: String,
    country: String,
    variant: String,
  ): Int = TranslatorTtsEngine.languageAvailability(catalog(), lang, country)

  override fun onLoadLanguage(
    lang: String,
    country: String,
    variant: String,
  ): Int {
    val catalog = catalog()
    TranslatorTtsEngine.defaultVoice(catalog, voiceOverrides(), lang, country)?.let { loadedVoice = it }
    return TranslatorTtsEngine.languageAvailability(catalog, lang, country)
  }

  override fun onGetLanguage(): Array<String> {
    val locale = loadedVoice?.locale ?: return arrayOf("", "", "")
    return arrayOf(
      locale.safeIso3Language(),
      locale.safeIso3Country(),
      locale.variant.orEmpty(),
    )
  }

  override fun onSynthesizeText(
    request: SynthesisRequest,
    callback: SynthesisCallback,
  ) {
    stopped = false
    val text = request.charSequenceText?.toString().orEmpty()
    if (text.isBlank()) {
      callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
      callback.done()
      return
    }
    val segments =
      when (val parsed = parseSystemTtsText(text)) {
        is SystemTtsText.Segments -> parsed.segments
        is SystemTtsText.MalformedSsml -> {
          Log.w("TranslatorTtsService", "Rejecting malformed SSML: ${parsed.reason}")
          callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
          callback.done()
          return
        }
      }

    val voice =
      TranslatorTtsEngine.voiceForRequest(
        catalog = catalog(),
        voiceOverrides = voiceOverrides(),
        voiceName = request.voiceName,
        iso3Language = request.language.orEmpty(),
        iso3Country = request.country.orEmpty(),
      )
    if (voice == null) {
      Log.w(
        "TranslatorTtsService",
        "No installed voice for voice=${request.voiceName} locale=${request.language}-${request.country}",
      )
      callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
      callback.done()
      return
    }

    val settings = (application as TranslatorApplication).settingsManager.settings.value
    val baseSpeed = settings.ttsPlaybackSpeedOverrides["${voice.languageCode}:${voice.speakerName}"] ?: settings.ttsPlaybackSpeed
    val finalSpeed = (baseSpeed * (request.speechRate / 100f)).coerceIn(0.5f, 2.0f)

    try {
      synthesizeVoice(
        voice = voice,
        segments = segments,
        speechSpeed = finalSpeed,
        callback = callback,
      )
    } catch (error: Exception) {
      Log.e("TranslatorTtsService", "System TTS synthesis failed", error)
      callback.error()
    } finally {
      callback.done()
    }
  }

  override fun onStop() {
    stopped = true
  }

  private fun synthesizeVoice(
    voice: TranslatorTtsVoice,
    segments: List<SystemTtsSegment>,
    speechSpeed: Float,
    callback: SynthesisCallback,
  ) {
    val catalog = catalog()
    val readUrlsAndHashtags = (application as TranslatorApplication).settingsManager.settings.value.ttsReadUrlsAndHashtags
    val steps =
      segments.flatMap { segment ->
        when (segment) {
          is SystemTtsSegment.Text ->
            catalog
              .planSpeechChunks(voice.languageCode, segment.text, voice.packId, readUrlsAndHashtags)
              .flatMap { chunk -> listOfNotNull(SpeechStep.Speak(chunk), chunk.pauseAfterMs?.let { SpeechStep.Silence(it) }) }
          is SystemTtsSegment.Pause -> listOf(SpeechStep.Silence(segment.durationMs))
        }
      }
    if (steps.none { it is SpeechStep.Speak }) {
      error("No speech chunks planned")
    }

    var sampleRate: Int? = null
    // Silence requested before the first chunk can only be written once callback.start has fixed the sample rate.
    var leadingSilenceMs = 0
    for (step in steps) {
      if (stopped) return
      when (step) {
        is SpeechStep.Silence -> {
          val rate = sampleRate
          if (rate == null) {
            leadingSilenceMs += step.durationMs
          } else if (!writeSilence(rate, step.durationMs, callback)) {
            return
          }
        }
        is SpeechStep.Speak -> {
          val audio =
            catalog.synthesizeSpeechPcm(
              languageCode = voice.languageCode,
              text = step.chunk.content,
              speechSpeed = speechSpeed,
              voiceName = voice.speakerName,
              isPhonemes = step.chunk.isPhonemes,
              packId = voice.packId,
            )

          if (sampleRate == null) {
            callback.start(audio.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            sampleRate = audio.sampleRate
            if (!writeSilence(audio.sampleRate, leadingSilenceMs, callback)) return
          } else if (sampleRate != audio.sampleRate) {
            error("TTS sample rate changed from $sampleRate to ${audio.sampleRate}")
          }

          if (!writePcm(audio.pcmSamples, callback)) return
        }
      }
    }
  }

  private fun writeSilence(
    sampleRate: Int,
    durationMs: Int,
    callback: SynthesisCallback,
  ): Boolean {
    if (durationMs <= 0) return true
    return writePcm(PcmAudio.silence(sampleRate, durationMs).pcmSamples, callback)
  }

  private fun writePcm(
    samples: ShortArray,
    callback: SynthesisCallback,
  ): Boolean {
    val maxSamples = (callback.maxBufferSize / Short.SIZE_BYTES).coerceAtLeast(1)
    var offset = 0
    while (offset < samples.size) {
      if (stopped) return false
      val count = minOf(maxSamples, samples.size - offset)
      val buffer = ByteArray(count * Short.SIZE_BYTES)
      var byteIndex = 0
      for (sampleIndex in offset until offset + count) {
        val sample = samples[sampleIndex].toInt()
        buffer[byteIndex++] = sample.toByte()
        buffer[byteIndex++] = (sample shr 8).toByte()
      }
      if (callback.audioAvailable(buffer, 0, buffer.size) != TextToSpeech.SUCCESS) {
        return false
      }
      offset += count
    }
    return true
  }

  private fun catalog(): LanguageCatalog = (application as TranslatorApplication).catalogRepository.catalog.value

  private fun voiceOverrides(): Map<String, String> =
    (application as TranslatorApplication).settingsManager.settings.value.ttsVoiceOverrides
}

class TranslatorTtsDataActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    (application as TranslatorApplication).ensureServicesInitialized()
    when (intent?.action) {
      TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> finishCheckTtsData()
      TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> finishSampleText()
      TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA -> finishInstallTtsData()
      else -> finish()
    }
  }

  private fun catalog(): LanguageCatalog = (application as TranslatorApplication).catalogRepository.catalog.value

  private fun finishCheckTtsData() {
    val voices =
      TranslatorTtsEngine
        .installedTtsLocales(catalog())
        .mapNotNull { it.toTtsDataVoiceName() }
        .distinct()
        .let(::ArrayList)

    val data =
      Intent()
        .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, voices)
        .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
    setResult(
      if (voices.isEmpty()) TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL else TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
      data,
    )
    finish()
  }

  private fun finishSampleText() {
    val sample =
      TranslatorTtsEngine.sampleText(
        catalog = catalog(),
        iso3Language = intent?.getStringExtra("language").orEmpty(),
        iso3Country = intent?.getStringExtra("country").orEmpty(),
      ) ?: getString(R.string.tts_sample_fallback)
    val data = Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample)
    setResult(TextToSpeech.LANG_AVAILABLE, data)
    finish()
  }

  private fun finishInstallTtsData() {
    val intent =
      Intent(this, MainActivity::class.java)
        .addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ).putExtra(MainActivity.EXTRA_OPEN_LANGUAGE_MANAGER, true)
    startActivity(intent)
    setResult(Activity.RESULT_OK)
    finish()
  }
}

class TranslatorTtsSettingsRelayActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    startActivity(
      Intent(this, MainActivity::class.java)
        .addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ).putExtra(MainActivity.EXTRA_OPEN_LANGUAGE_MANAGER, true),
    )
    finish()
  }
}

data class TranslatorTtsVoice(
  val androidName: String,
  val languageCode: String,
  val packId: String,
  val speakerName: String,
  val locale: Locale,
  val quality: Int,
)

// The framework speaks ISO 639-2/T + ISO 3166 alpha-3 codes; every lookup here takes those and
// answers only from installed voices, so availability, default voice and synthesis can't disagree.
object TranslatorTtsEngine {
  private const val VOICE_SEPARATOR = "|"

  fun installedTtsLocales(catalog: LanguageCatalog): List<Locale> = systemTtsVoices(catalog).map { it.locale }.distinct()

  fun systemTtsVoices(catalog: LanguageCatalog): List<TranslatorTtsVoice> =
    catalog.languageRows
      .filter { it.availability.ttsFiles }
      .flatMap { row ->
        val languageCode = row.language.code
        val regionByPack =
          catalog
            .installedTtsVoicePickerRegions(languageCode)
            .flatMap { region -> region.voices.map { pack -> pack.packId to region.code } }
            .toMap()
        catalog.installedTtsVoices(languageCode).flatMap { pack ->
          val locale = localeFor(languageCode, regionByPack[pack.packId])
          pack.voices.map { speaker ->
            TranslatorTtsVoice(
              androidName = "$languageCode$VOICE_SEPARATOR${pack.packId}$VOICE_SEPARATOR${speaker.name}",
              languageCode = languageCode,
              packId = pack.packId,
              speakerName = speaker.name,
              locale = locale,
              quality = qualityFor(speaker.name),
            )
          }
        }
      }

  fun voiceNamed(
    catalog: LanguageCatalog,
    voiceName: String,
  ): TranslatorTtsVoice? = systemTtsVoices(catalog).firstOrNull { it.androidName == voiceName }

  fun languageAvailability(
    catalog: LanguageCatalog,
    iso3Language: String,
    iso3Country: String,
  ): Int {
    val voices = voicesForLanguage(catalog, iso3Language)
    if (voices.isEmpty()) {
      val downloadable =
        catalog.languageRows.any { row ->
          localeFor(row.language.code, null).safeIso3Language() == iso3Language && catalog.hasTtsVoices(row.language.code)
        }
      return if (downloadable) TextToSpeech.LANG_MISSING_DATA else TextToSpeech.LANG_NOT_SUPPORTED
    }
    if (voices.any { it.isInCountry(iso3Country) }) {
      return TextToSpeech.LANG_COUNTRY_AVAILABLE
    }
    return TextToSpeech.LANG_AVAILABLE
  }

  // A matching country picks the catalog language first (zho-TWN is zh_hant, zho-CHN is zh); within
  // it the user's in-app voice choice wins over the requested country, since apps mostly pass the
  // device locale rather than an accent they actually want.
  fun defaultVoice(
    catalog: LanguageCatalog,
    voiceOverrides: Map<String, String>,
    iso3Language: String,
    iso3Country: String,
  ): TranslatorTtsVoice? {
    val voices = voicesForLanguage(catalog, iso3Language)
    val languageCode = (voices.firstOrNull { it.isInCountry(iso3Country) } ?: voices.firstOrNull() ?: return null).languageCode
    val candidates = voices.filter { it.languageCode == languageCode }
    val override = parseVoiceOverride(voiceOverrides[languageCode])
    return candidates.firstOrNull { voice ->
      override != null && voice.speakerName == override.voiceName && (override.packId == null || override.packId == voice.packId)
    }
      ?: candidates.firstOrNull { it.isInCountry(iso3Country) }
      ?: candidates.first()
  }

  fun voiceForRequest(
    catalog: LanguageCatalog,
    voiceOverrides: Map<String, String>,
    voiceName: String?,
    iso3Language: String,
    iso3Country: String,
  ): TranslatorTtsVoice? =
    if (voiceName.isNullOrEmpty()) {
      defaultVoice(catalog, voiceOverrides, iso3Language, iso3Country)
    } else {
      voiceNamed(catalog, voiceName)
    }

  fun sampleText(
    catalog: LanguageCatalog,
    iso3Language: String,
    iso3Country: String,
  ): String? =
    defaultVoice(catalog, emptyMap(), iso3Language, iso3Country)
      ?.languageCode
      ?.let(catalog::ttsSampleText)

  private fun voicesForLanguage(
    catalog: LanguageCatalog,
    iso3Language: String,
  ): List<TranslatorTtsVoice> {
    if (iso3Language.isBlank()) return emptyList()
    return systemTtsVoices(catalog).filter { it.locale.safeIso3Language() == iso3Language }
  }

  private fun TranslatorTtsVoice.isInCountry(iso3Country: String): Boolean =
    iso3Country.isNotBlank() && locale.safeIso3Country() == iso3Country

  private fun localeFor(
    languageCode: String,
    regionCode: String?,
  ): Locale {
    val languageTag = languageCode.replace('_', '-')
    val tag = listOfNotNull(languageTag, regionCode).joinToString("-")
    return Locale.forLanguageTag(tag)
  }

  private fun qualityFor(voiceName: String): Int =
    when {
      voiceName.contains("-high") -> Voice.QUALITY_HIGH
      voiceName.contains("-x-low") -> Voice.QUALITY_LOW
      else -> Voice.QUALITY_NORMAL
    }
}

private fun Locale.safeIso3Language(): String =
  try {
    getISO3Language().orEmpty()
  } catch (_: Exception) {
    ""
  }

private fun Locale.safeIso3Country(): String =
  try {
    getISO3Country().orEmpty()
  } catch (_: Exception) {
    ""
  }

private fun Locale.toTtsDataVoiceName(): String? {
  val language = safeIso3Language()
  if (language.isBlank()) return null

  val country = safeIso3Country()
  val variant = variant.orEmpty()
  return listOf(language, country, variant)
    .filter(String::isNotBlank)
    .joinToString("-")
}
