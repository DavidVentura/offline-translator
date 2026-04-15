package dev.davidv.translator

class TransliterateBinding {
  companion object {
    init {
      System.loadLibrary("bindings")
    }
  }

  fun transliterate(
    text: String,
    languageCode: String,
    sourceScript: String,
    targetScript: String = "Latn",
    japaneseDictPtr: Long = 0L,
    japaneseSpaced: Boolean = true,
  ): String? =
    nativeTransliterateWithPolicy(
      text,
      languageCode,
      sourceScript,
      targetScript,
      japaneseDictPtr,
      japaneseSpaced,
    )

  private external fun nativeTransliterateWithPolicy(
    text: String,
    languageCode: String,
    sourceScript: String,
    targetScript: String,
    japaneseDictPtr: Long,
    japaneseSpaced: Boolean,
  ): String?
}
