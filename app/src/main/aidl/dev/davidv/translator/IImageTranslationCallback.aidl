package dev.davidv.translator;

import dev.davidv.translator.ImageTranslationResult;
import dev.davidv.translator.TranslationError;

oneway interface IImageTranslationCallback {
    void onResult(in ImageTranslationResult result);
    void onError(in TranslationError error);
}