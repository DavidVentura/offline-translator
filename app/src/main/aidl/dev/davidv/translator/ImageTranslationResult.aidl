package dev.davidv.translator;

import dev.davidv.translator.TextLineResult;

parcelable ImageTranslationResult {
    String extractedText;
    String translatedText;
    List<TextLineResult> textLines;
}