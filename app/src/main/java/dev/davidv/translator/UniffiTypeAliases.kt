package dev.davidv.translator

typealias ReadingOrder = uniffi.translator.ReadingOrder
typealias NothingReason = uniffi.translator.NothingReason
typealias BackgroundMode = uniffi.translator.BackgroundMode

val BackgroundMode.displayName: String
  get() =
    when (this) {
      BackgroundMode.WHITE_ON_BLACK -> "White on Black"
      BackgroundMode.BLACK_ON_WHITE -> "Black on White"
      BackgroundMode.AUTO_DETECT -> "Auto-detect Colors"
    }
