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

fun concatenatePcmAudio(chunks: List<PcmAudio>): PcmAudio {
  require(chunks.isNotEmpty()) { "At least one audio chunk is required" }
  val sampleRate = chunks.first().sampleRate
  require(sampleRate > 0) { "Sample rate must be positive" }
  require(chunks.all { it.sampleRate == sampleRate }) { "Audio chunks must share a sample rate" }

  val sampleCount = chunks.sumOf { it.pcmSamples.size }
  val samples = ShortArray(sampleCount)
  var offset = 0
  chunks.forEach { chunk ->
    chunk.pcmSamples.copyInto(samples, offset)
    offset += chunk.pcmSamples.size
  }
  return PcmAudio(sampleRate = sampleRate, pcmSamples = samples)
}
