package dev.davidv.translator

import uniffi.bindings.transliterateWithPolicyRecord

class TransliterateBinding {
  fun transliterate(
    text: String,
    languageCode: String,
    sourceScript: String,
    targetScript: String = "Latn",
    japaneseDictPath: String? = null,
    japaneseSpaced: Boolean = true,
  ): String? =
    transliterateWithPolicyRecord(
      text,
      languageCode,
      sourceScript,
      targetScript,
      japaneseDictPath,
      japaneseSpaced,
    )
}
