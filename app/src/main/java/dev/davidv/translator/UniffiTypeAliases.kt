package dev.davidv.translator

typealias ReadingOrder = uniffi.translator.ReadingOrder
typealias NothingReason = uniffi.translator.NothingReason
typealias BackgroundMode = uniffi.translator.BackgroundMode
typealias TokenAlignment = uniffi.translator.TokenAlignment
typealias TranslationWithAlignment = uniffi.translator.TranslationWithAlignment
typealias Feature = uniffi.translator.Feature
typealias DownloadPlan = uniffi.translator.DownloadPlan
typealias DownloadTask = uniffi.translator.DownloadTask
typealias DeletePlan = uniffi.translator.DeletePlan
typealias TtsVoicePackInfo = uniffi.translator.TtsVoicePackInfo
typealias TtsVoicePickerRegion = uniffi.translator.TtsVoicePickerRegion

val BackgroundMode.displayName: String
  get() =
    when (this) {
      BackgroundMode.WHITE_ON_BLACK -> "White on Black"
      BackgroundMode.BLACK_ON_WHITE -> "Black on White"
      BackgroundMode.AUTO_DETECT -> "Auto-detect Colors"
    }
