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

data class PhonemeChunk(
  val content: String,
  val boundaryAfter: SpeechChunkBoundary,
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

  private external fun nativeSynthesizePcm(
    engine: String,
    modelPath: String,
    auxPath: String,
    supportDataPath: String,
    languageCode: String,
    text: String,
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
}
