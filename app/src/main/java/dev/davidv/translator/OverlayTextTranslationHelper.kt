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
  private val translationService: TranslationService,
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
        translationService.translateMixedTexts(
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
    if (forcedTargetLanguage != null) return forcedTargetLanguage
    val code = settingsManager.settings.value.defaultTargetLanguageCode
    return langStateManager.languageByCode(code)
      ?: langStateManager.languageState.value.allLanguages().firstOrNull { it.isEnglish }
      ?: langStateManager.languageState.value.allLanguages().first()
  }

  fun availableLanguages(isSource: Boolean): List<Language> {
    val metadata = languageMetadataManager.metadata.value
    return langStateManager.languageState.value
      .translatorLanguages(requireOcr = isSource)
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
    return langStateManager.languageState.value.translatorLanguages()
  }
}
