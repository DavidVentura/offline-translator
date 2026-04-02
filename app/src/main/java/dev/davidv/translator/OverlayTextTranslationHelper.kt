package dev.davidv.translator

import kotlinx.coroutines.flow.first

sealed class OverlayTextTranslationResult {
  data class Success(
    val results: LinkedHashMap<String, String>,
  ) : OverlayTextTranslationResult()

  data class Message(
    val value: String,
  ) : OverlayTextTranslationResult()
}

class OverlayTextTranslationHelper(
  private val settingsManager: SettingsManager,
  private val batchTextTranslator: BatchTextTranslator,
  private val langStateManager: LanguageStateManager,
  private val languageMetadataManager: LanguageMetadataManager,
) {
  suspend fun translateTexts(
    inputs: List<String>,
    forcedSourceLanguage: Language?,
    forcedTargetLanguage: Language?,
  ): OverlayTextTranslationResult {
    val targetLanguage = awaitTargetLanguage(forcedTargetLanguage)
    if (forcedSourceLanguage == targetLanguage) {
      return OverlayTextTranslationResult.Message("Already in ${targetLanguage.displayName}")
    }

    val availableLanguages = awaitTranslatorLanguages()
    return when (
      val result =
        batchTextTranslator.translateTexts(
          inputs = inputs,
          forcedSourceLanguage = forcedSourceLanguage,
          targetLanguage = targetLanguage,
          availableLanguages = availableLanguages,
        )
    ) {
      is BatchTextTranslationOutput.NothingToTranslate ->
        OverlayTextTranslationResult.Message(
          when (result.reason) {
            NothingReason.ALREADY_TARGET_LANGUAGE -> "Already in ${targetLanguage.displayName}"
            NothingReason.COULD_NOT_DETECT -> "Could not detect language — set source language manually"
            NothingReason.NO_TRANSLATABLE_TEXT -> "No translatable visible text found"
          },
        )

      is BatchTextTranslationOutput.Translated ->
        OverlayTextTranslationResult.Success(result.results)
    }
  }

  suspend fun awaitTargetLanguage(forcedTargetLanguage: Language?): Language {
    awaitTranslatorLanguages()
    return forcedTargetLanguage ?: settingsManager.settings.value.defaultTargetLanguage
  }

  fun availableLanguages(isSource: Boolean): List<Language> {
    val metadata = languageMetadataManager.metadata.value
    return langStateManager.languageState.value.availableLanguageMap
      .filterValues { it.translatorFiles && (!isSource || it.ocrFiles) }
      .keys
      .toList()
      .sortedWith(
        compareByDescending<Language> { metadata[it]?.favorite ?: false }
          .thenBy { it.displayName },
      )
  }

  suspend fun awaitAvailableLanguages(isSource: Boolean): List<Language> {
    awaitTranslatorLanguages()
    return availableLanguages(isSource)
  }

  private suspend fun awaitTranslatorLanguages(): List<Language> {
    langStateManager.languageState.first { !it.isChecking }
    return langStateManager.languageState.value.availableLanguageMap
      .filterValues { it.translatorFiles }
      .keys
      .toList()
  }
}
