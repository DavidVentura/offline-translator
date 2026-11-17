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

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

private const val M4A_BIT_RATE = 64_000
private const val M4A_MAX_INPUT_BYTES = 16 * 1024
private const val M4A_TIMEOUT_US = 10_000L

fun writeM4a(
  file: File,
  audio: PcmAudio,
) {
  require(audio.pcmSamples.isNotEmpty()) { "Audio must not be empty" }
  require(audio.sampleRate > 0) { "Sample rate must be positive" }
  file.parentFile?.mkdirs()

  val format =
    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audio.sampleRate, 1).apply {
      setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      setInteger(MediaFormat.KEY_BIT_RATE, M4A_BIT_RATE)
      setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, M4A_MAX_INPUT_BYTES)
    }

  val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
  val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  var muxerStarted = false
  var trackIndex = -1
  try {
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()

    val bufferInfo = MediaCodec.BufferInfo()
    var sampleOffset = 0
    var presentationTimeUs = 0L
    var inputDone = false
    var outputDone = false

    while (!outputDone) {
      if (!inputDone) {
        val inputIndex = codec.dequeueInputBuffer(M4A_TIMEOUT_US)
        if (inputIndex >= 0) {
          val inputBuffer =
            checkNotNull(codec.getInputBuffer(inputIndex)) { "Encoder input buffer is unavailable" }
          inputBuffer.clear()
          val remainingSamples = audio.pcmSamples.size - sampleOffset
          val sampleCount = minOf(remainingSamples, inputBuffer.remaining() / Short.SIZE_BYTES)
          if (remainingSamples == 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
          } else {
            check(sampleCount > 0) { "Encoder input buffer is too small" }
            for (i in 0 until sampleCount) {
              val sample = audio.pcmSamples[sampleOffset + i].toInt()
              inputBuffer.put((sample and 0xFF).toByte())
              inputBuffer.put(((sample ushr 8) and 0xFF).toByte())
            }
            codec.queueInputBuffer(inputIndex, 0, sampleCount * Short.SIZE_BYTES, presentationTimeUs, 0)
            presentationTimeUs += sampleCount * 1_000_000L / audio.sampleRate
            sampleOffset += sampleCount
          }
        }
      }

      when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, M4A_TIMEOUT_US)) {
        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          check(!muxerStarted) { "Encoder output format changed twice" }
          trackIndex = muxer.addTrack(codec.outputFormat)
          muxer.start()
          muxerStarted = true
        }
        else ->
          if (outputIndex >= 0) {
            val outputBuffer =
              checkNotNull(codec.getOutputBuffer(outputIndex)) { "Encoder output buffer is unavailable" }
            val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (!isCodecConfig && bufferInfo.size > 0) {
              check(muxerStarted) { "Encoder produced data before the output format was known" }
              outputBuffer.position(bufferInfo.offset)
              outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
              muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
            }
            codec.releaseOutputBuffer(outputIndex, false)
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              outputDone = true
            }
          }
      }
    }
  } finally {
    try {
      codec.stop()
    } catch (_: IllegalStateException) {
    } finally {
      codec.release()
    }
    try {
      if (muxerStarted) muxer.stop()
    } catch (_: IllegalStateException) {
    } finally {
      muxer.release()
    }
  }
}
