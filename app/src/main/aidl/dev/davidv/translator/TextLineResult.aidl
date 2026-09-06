package dev.davidv.translator;

parcelable TextLineResult {
    String sourceText;
    String translatedText;
    int left;
    int top;
    int right;
    int bottom;
    float orientedCenterX;
    float orientedCenterY;
    float orientedWidth;
    float orientedHeight;
    float orientedAngleRadians;
    float suggestedFontSizePx;
    int backgroundArgb;
    int foregroundArgb;
}