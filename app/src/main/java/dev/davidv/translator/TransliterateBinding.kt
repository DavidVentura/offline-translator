package dev.davidv.translator

import uniffi.bindings.transliterateWithPolicyRecord
import uniffi.translator.LanguageCode
import uniffi.translator.ScriptCode

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
      LanguageCode(code = languageCode),
      ScriptCode(code = sourceScript),
      ScriptCode(code = targetScript),
      japaneseDictPath,
      japaneseSpaced,
    )
}
