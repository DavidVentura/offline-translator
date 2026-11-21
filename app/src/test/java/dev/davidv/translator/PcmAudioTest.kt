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

class PcmAudioTest {
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
}
