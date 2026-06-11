package dev.davidv.translator;

import dev.davidv.translator.TextLineResult;

parcelable TextBlockResult {
    String sourceText;
    String translatedText;
    int left;
    int top;
    int right;
    int bottom;
    int backgroundArgb;
    int foregroundArgb;
    List<TextLineResult> lines;
}