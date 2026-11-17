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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavAudioTest {
  @Test
  fun concatenatesChunksInOrder() {
    val audio =
      concatenatePcmAudio(
        listOf(
          PcmAudio(sampleRate = 8_000, pcmSamples = shortArrayOf(1, 2)),
          PcmAudio(sampleRate = 8_000, pcmSamples = shortArrayOf(3)),
        ),
      )

    assertEquals(8_000, audio.sampleRate)
    assertArrayEquals(shortArrayOf(1, 2, 3), audio.pcmSamples)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsEmptyChunks() {
    concatenatePcmAudio(emptyList())
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsMixedSampleRates() {
    concatenatePcmAudio(
      listOf(
        PcmAudio(sampleRate = 8_000, pcmSamples = shortArrayOf(1)),
        PcmAudio(sampleRate = 16_000, pcmSamples = shortArrayOf(2)),
      ),
    )
  }

  @Test
  fun encodesMono16BitWav() {
    val bytes =
      encodeWav(
        PcmAudio(
          sampleRate = 8_000,
          pcmSamples = shortArrayOf(0x1234, -1),
        ),
      )

    assertEquals("RIFF", ascii(bytes, 0, 4))
    assertEquals(40, readLe32(bytes, 4))
    assertEquals("WAVE", ascii(bytes, 8, 4))
    assertEquals("fmt ", ascii(bytes, 12, 4))
    assertEquals(16, readLe32(bytes, 16))
    assertEquals(1, readLe16(bytes, 20))
    assertEquals(1, readLe16(bytes, 22))
    assertEquals(8_000, readLe32(bytes, 24))
    assertEquals(16_000, readLe32(bytes, 28))
    assertEquals(2, readLe16(bytes, 32))
    assertEquals(16, readLe16(bytes, 34))
    assertEquals("data", ascii(bytes, 36, 4))
    assertEquals(4, readLe32(bytes, 40))
    assertEquals(0x34, bytes[44].toInt() and 0xFF)
    assertEquals(0x12, bytes[45].toInt() and 0xFF)
    assertEquals(0xFF, bytes[46].toInt() and 0xFF)
    assertEquals(0xFF, bytes[47].toInt() and 0xFF)
  }

  private fun ascii(
    bytes: ByteArray,
    offset: Int,
    length: Int,
  ): String = String(bytes, offset, length, Charsets.US_ASCII)

  private fun readLe16(
    bytes: ByteArray,
    offset: Int,
  ): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

  private fun readLe32(
    bytes: ByteArray,
    offset: Int,
  ): Int =
    (bytes[offset].toInt() and 0xFF) or
      ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
      ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
      ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
