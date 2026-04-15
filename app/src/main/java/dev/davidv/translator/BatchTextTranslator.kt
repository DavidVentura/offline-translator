package dev.davidv.translator

sealed class BatchTextTranslationOutput {
  data class Translated(
    val results: LinkedHashMap<String, String>,
  ) : BatchTextTranslationOutput()

  data class NothingToTranslate(
    val reason: NothingReason,
  ) : BatchTextTranslationOutput()
}

enum class NothingReason {
  ALREADY_TARGET_LANGUAGE,
  COULD_NOT_DETECT,
  NO_TRANSLATABLE_TEXT,
}
