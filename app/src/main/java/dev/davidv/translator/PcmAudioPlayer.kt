package dev.davidv.translator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PcmAudioPlayer(
  private val onPlaybackStateChanged: (Boolean) -> Unit = {},
) {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var audioTrack: AudioTrack? = null
  private var playbackJob: Job? = null

  fun play(audio: PcmAudio) {
    play(flowOf(audio))
  }

  fun play(
    audioChunks: Flow<PcmAudio>,
    onError: (String) -> Unit = {},
  ) {
    stop()

    playbackJob =
      playbackScope.launch {
        var track: AudioTrack? = null
        var sampleRate: Int? = null
        var totalFrames = 0

        try {
          audioChunks.collect { audio ->
            ensureActive()

            val currentTrack =
              if (track == null) {
                buildAudioTrack(audio).also { newTrack ->
                  track = newTrack
                  sampleRate = audio.sampleRate
                  audioTrack = newTrack
                  notifyPlaybackStateChanged(true)
                }
              } else {
                if (audio.sampleRate != sampleRate) {
                  throw IllegalStateException(
                    "Audio stream sample rate changed from $sampleRate to ${audio.sampleRate}",
                  )
                }
                track!!
              }

            val written =
              currentTrack.write(
                audio.pcmSamples,
                0,
                audio.pcmSamples.size,
                AudioTrack.WRITE_BLOCKING,
              )
            if (written != audio.pcmSamples.size) {
              val context = currentCoroutineContext()
              if (!context.isActive || audioTrack !== currentTrack) {
                throw CancellationException()
              }
              currentTrack.release()
              throw IllegalStateException("AudioTrack wrote $written of ${audio.pcmSamples.size} samples")
            }

            totalFrames += audio.pcmSamples.size
            if (currentTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
              currentTrack.play()
            }
          }

          val finalTrack = track ?: return@launch
          waitForPlaybackDrain(finalTrack, totalFrames)
          finishPlayback(finalTrack)
        } catch (_: CancellationException) {
          Unit
        } catch (error: RuntimeException) {
          if (audioTrack === track) {
            audioTrack = null
            notifyPlaybackStateChanged(false)
          }
          playbackJob = null
          mainHandler.post {
            onError(error.message ?: "Audio playback failed")
          }
          cleanupTrack(track)
        }
      }
  }

  fun stop() {
    playbackJob?.cancel()
    playbackJob = null

    val track = audioTrack ?: return
    audioTrack = null
    runCatching {
      if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
        track.stop()
      }
    }
    track.release()
    notifyPlaybackStateChanged(false)
  }

  fun release() {
    stop()
    playbackScope.cancel()
  }

  private fun buildAudioTrack(audio: PcmAudio): AudioTrack {
    val minBufferSize =
      AudioTrack.getMinBufferSize(
        audio.sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    if (minBufferSize <= 0) {
      throw IllegalStateException("Invalid AudioTrack buffer size: $minBufferSize")
    }

    return AudioTrack
      .Builder()
      .setAudioAttributes(
        AudioAttributes
          .Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )
      .setAudioFormat(
        AudioFormat
          .Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(audio.sampleRate)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build(),
      )
      .setTransferMode(AudioTrack.MODE_STREAM)
      .setBufferSizeInBytes(maxOf(minBufferSize, audio.pcmSamples.size * Short.SIZE_BYTES))
      .build()
  }

  private suspend fun waitForPlaybackDrain(
    track: AudioTrack,
    totalFrames: Int,
  ) {
    while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
      currentCoroutineContext().ensureActive()
      if (track.playbackHeadPosition >= totalFrames) {
        break
      }
      delay(20)
    }
  }

  private fun finishPlayback(track: AudioTrack) {
    if (audioTrack === track) {
      audioTrack = null
      cleanupTrack(track)
      notifyPlaybackStateChanged(false)
    } else {
      cleanupTrack(track)
    }
    playbackJob = null
  }

  private fun cleanupTrack(track: AudioTrack?) {
    if (track == null) return
    runCatching {
      if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
        track.stop()
      }
    }
    track.release()
  }

  private fun notifyPlaybackStateChanged(playing: Boolean) {
    mainHandler.post {
      onPlaybackStateChanged(playing)
    }
  }
}
