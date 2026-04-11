package dev.davidv.translator

enum class SpeechChunkBoundary(
  val nativeValue: Int,
) {
  None(0),
  Sentence(1),
  Paragraph(2),
  ;

  companion object {
    fun fromNative(value: Int): SpeechChunkBoundary = entries.firstOrNull { it.nativeValue == value } ?: None
  }
}

data class NativePhonemeChunk(
  val content: String,
  val boundaryAfter: Int,
)

data class NativeTtsVoice(
  val name: String,
  val speakerId: Int,
  val displayName: String,
)

data class PhonemeChunk(
  val content: String,
  val boundaryAfter: SpeechChunkBoundary,
)

data class TtsVoiceOption(
  val name: String,
  val speakerId: Int,
  val displayName: String,
)

class SpeechBinding {
  companion object {
    init {
      System.loadLibrary("bindings")
    }
  }

  fun synthesizePcm(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String?,
    languageCode: String,
    text: String,
    speechSpeed: Float = 1.0f,
    voiceName: String? = null,
    speakerId: Int? = null,
    isPhonemes: Boolean = false,
  ): PcmAudio? =
    nativeSynthesizePcm(
      engine,
      modelPath,
      auxPath,
      supportDataPath.orEmpty(),
      languageCode,
      text,
      speechSpeed,
      voiceName.orEmpty(),
      speakerId ?: -1,
      isPhonemes,
    )

  fun phonemizeChunks(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String?,
    languageCode: String,
    text: String,
  ): List<PhonemeChunk>? =
    nativePhonemizeChunks(
      engine,
      modelPath,
      auxPath,
      supportDataPath.orEmpty(),
      languageCode,
      text,
    )
      ?.map { chunk -> PhonemeChunk(content = chunk.content, boundaryAfter = SpeechChunkBoundary.fromNative(chunk.boundaryAfter)) }
      ?.toList()

  fun listVoices(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String?,
    languageCode: String,
  ): List<TtsVoiceOption>? =
    nativeListVoices(
      engine,
      modelPath,
      auxPath,
      supportDataPath.orEmpty(),
      languageCode,
    )?.map { voice ->
      TtsVoiceOption(name = voice.name, speakerId = voice.speakerId, displayName = voice.displayName)
    }

  private external fun nativeSynthesizePcm(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String,
    languageCode: String,
    text: String,
    speechSpeed: Float,
    voiceName: String,
    speakerId: Int,
    isPhonemes: Boolean,
  ): PcmAudio?

  private external fun nativePhonemizeChunks(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String,
    languageCode: String,
    text: String,
  ): Array<NativePhonemeChunk>?

  private external fun nativeListVoices(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String,
    languageCode: String,
  ): Array<NativeTtsVoice>?
}
