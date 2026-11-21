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

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class M4aAudioInstrumentedTest {
  @Test
  fun writesReadableM4aTrack() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File(context.cacheDir, "m4a-test-${System.nanoTime()}.m4a")
    try {
      val sampleRate = 16_000
      val samples =
        ShortArray(sampleRate / 2) { index ->
          (sin(2.0 * PI * 440.0 * index / sampleRate) * Short.MAX_VALUE * 0.5).toInt().toShort()
        }

      writeM4a(file, PcmAudio(sampleRate = sampleRate, pcmSamples = samples))

      assertTrue("M4A file was not created", file.isFile)
      assertTrue("M4A file is empty", file.length() > 0)
      assertEquals("ftyp", readAscii(file, offset = 4, length = 4))

      val extractor = MediaExtractor()
      try {
        extractor.setDataSource(file.absolutePath)
        val trackIndex =
          requireNotNull(
            (0 until extractor.trackCount).firstOrNull { index ->
              extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            },
          ) { "No AAC track found" }

        val format = extractor.getTrackFormat(trackIndex)
        assertEquals(sampleRate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
          assertTrue("Encoded track has no duration", format.getLong(MediaFormat.KEY_DURATION) > 0)
        }

        extractor.selectTrack(trackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val buffer = ByteBuffer.allocate(16 * 1024)
        assertTrue("No encoded samples were written", extractor.readSampleData(buffer, 0) > 0)
      } finally {
        extractor.release()
      }
    } finally {
      file.delete()
    }
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsEmptyAudio() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    writeM4a(
      File(context.cacheDir, "m4a-empty.m4a"),
      PcmAudio(sampleRate = 16_000, pcmSamples = ShortArray(0)),
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsInvalidSampleRate() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    writeM4a(
      File(context.cacheDir, "m4a-invalid-rate.m4a"),
      PcmAudio(sampleRate = 0, pcmSamples = shortArrayOf(1)),
    )
  }

  private fun readAscii(
    file: File,
    offset: Int,
    length: Int,
  ): String {
    val bytes = ByteArray(length)
    RandomAccessFile(file, "r").use { input ->
      input.seek(offset.toLong())
      input.readFully(bytes)
    }
    return String(bytes, Charsets.US_ASCII)
  }
}
