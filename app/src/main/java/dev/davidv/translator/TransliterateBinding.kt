package dev.davidv.translator

class TransliterateBinding {
  companion object {
    init {
      System.loadLibrary("bindings")
    }
  }

  fun transliterate(
    text: String,
    sourceScript: String,
  ): String? = nativeTransliterate(text, sourceScript)

  private external fun nativeTransliterate(
    text: String,
    sourceScript: String,
  ): String?
}
