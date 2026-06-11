package dev.davidv.translator;

import dev.davidv.translator.TextBlockResult;

parcelable ImageTranslationResult {
    String extractedText;
    String translatedText;
    List<TextBlockResult> blocks;
}