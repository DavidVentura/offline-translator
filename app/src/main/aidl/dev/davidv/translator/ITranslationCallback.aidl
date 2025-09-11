package dev.davidv.translator;

oneway interface ITranslationCallback {
    void onTranslationResult(String translatedText);
    void onTranslationError(String errorMessage);
}
