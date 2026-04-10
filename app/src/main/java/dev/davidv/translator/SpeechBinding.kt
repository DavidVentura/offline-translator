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
    modelPath: String,
    configPath: String,
    espeakDataPath: String?,
    text: String,
    speakerId: Int? = null,
    isPhonemes: Boolean = false,
  ): PcmAudio? =
    nativeSynthesizePcm(
      modelPath,
      configPath,
      espeakDataPath.orEmpty(),
      text,
      speakerId ?: -1,
      isPhonemes,
    )

  fun phonemizeChunks(
    modelPath: String,
    configPath: String,
    espeakDataPath: String?,
    text: String,
  ): List<PhonemeChunk>? =
    nativePhonemizeChunks(modelPath, configPath, espeakDataPath.orEmpty(), text)
      ?.map { chunk -> PhonemeChunk(content = chunk.content, boundaryAfter = SpeechChunkBoundary.fromNative(chunk.boundaryAfter)) }
      ?.toList()

  private external fun nativeSynthesizePcm(
    modelPath: String,
    configPath: String,
    espeakDataPath: String,
    text: String,
    speakerId: Int,
    isPhonemes: Boolean,
  ): PcmAudio?

  private external fun nativePhonemizeChunks(
    modelPath: String,
    configPath: String,
    espeakDataPath: String,
    text: String,
  ): Array<NativePhonemeChunk>?
}
