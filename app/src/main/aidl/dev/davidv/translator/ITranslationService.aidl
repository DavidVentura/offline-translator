package dev.davidv.translator;

import dev.davidv.translator.ITranslationCallback;
import dev.davidv.translator.IImageTranslationCallback;

interface ITranslationService {
    void translate(String textToTranslate, String fromLanguage, String toLanguage, ITranslationCallback callback);
    void translateImage(in ParcelFileDescriptor image, String fromLanguage, String toLanguage, IImageTranslationCallback callback);
}