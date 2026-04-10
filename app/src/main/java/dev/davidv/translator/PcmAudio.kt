package dev.davidv.translator

data class PcmAudio(
  val sampleRate: Int,
  val pcmSamples: ShortArray,
) {
  companion object {
    fun silence(
      sampleRate: Int,
      durationMs: Int,
    ): PcmAudio {
      val sampleCount = ((sampleRate.toLong() * durationMs) / 1000L).toInt().coerceAtLeast(1)
      return PcmAudio(sampleRate = sampleRate, pcmSamples = ShortArray(sampleCount))
    }
  }
}
