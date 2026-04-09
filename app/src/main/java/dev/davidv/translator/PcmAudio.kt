package dev.davidv.translator

data class PcmAudio(
  val sampleRate: Int,
  val pcmSamples: ShortArray,
)
