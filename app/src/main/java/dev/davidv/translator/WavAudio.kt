/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.davidv.translator

import java.io.ByteArrayOutputStream
import java.io.OutputStream

private const val WAV_HEADER_BYTES = 44
private const val WAV_FMT_CHUNK_BYTES = 16
private const val WAV_MONO_CHANNELS = 1
private const val WAV_BITS_PER_SAMPLE = 16
private const val WAV_BLOCK_ALIGN = WAV_MONO_CHANNELS * (WAV_BITS_PER_SAMPLE / 8)

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

fun encodeWav(audio: PcmAudio): ByteArray {
  val output = ByteArrayOutputStream(wavSize(audio))
  writeWav(output, audio)
  return output.toByteArray()
}

private fun wavSize(audio: PcmAudio): Int {
  val dataBytes = audio.pcmSamples.size.toLong() * Short.SIZE_BYTES
  require(dataBytes <= Int.MAX_VALUE - WAV_HEADER_BYTES) { "Audio is too large for a WAV file" }
  return WAV_HEADER_BYTES + dataBytes.toInt()
}

private fun writeWav(
  output: OutputStream,
  audio: PcmAudio,
) {
  val dataBytes = audio.pcmSamples.size.toLong() * Short.SIZE_BYTES
  require(dataBytes <= Int.MAX_VALUE - WAV_HEADER_BYTES) { "Audio is too large for a WAV file" }
  val dataSize = dataBytes.toInt()
  val byteRate = audio.sampleRate.toLong() * WAV_BLOCK_ALIGN
  require(byteRate <= Int.MAX_VALUE) { "Sample rate is too high for a WAV file" }

  writeAscii(output, "RIFF")
  writeLe32(output, WAV_HEADER_BYTES - 8 + dataSize)
  writeAscii(output, "WAVE")
  writeAscii(output, "fmt ")
  writeLe32(output, WAV_FMT_CHUNK_BYTES)
  writeLe16(output, 1)
  writeLe16(output, WAV_MONO_CHANNELS)
  writeLe32(output, audio.sampleRate)
  writeLe32(output, byteRate.toInt())
  writeLe16(output, WAV_BLOCK_ALIGN)
  writeLe16(output, WAV_BITS_PER_SAMPLE)
  writeAscii(output, "data")
  writeLe32(output, dataSize)
  audio.pcmSamples.forEach { sample -> writeLe16(output, sample.toInt() and 0xFFFF) }
}

private fun writeAscii(
  output: OutputStream,
  value: String,
) {
  output.write(value.toByteArray(Charsets.US_ASCII))
}

private fun writeLe16(
  output: OutputStream,
  value: Int,
) {
  output.write(value and 0xFF)
  output.write((value ushr 8) and 0xFF)
}

private fun writeLe32(
  output: OutputStream,
  value: Int,
) {
  output.write(value and 0xFF)
  output.write((value ushr 8) and 0xFF)
  output.write((value ushr 16) and 0xFF)
  output.write((value ushr 24) and 0xFF)
}
