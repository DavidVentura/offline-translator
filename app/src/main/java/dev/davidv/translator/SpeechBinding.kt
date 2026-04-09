package dev.davidv.translator

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
  ): List<String>? = nativePhonemizeChunks(modelPath, configPath, espeakDataPath.orEmpty(), text)?.toList()

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
  ): Array<String>?
}
