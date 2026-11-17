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

package dev.davidv.translator.ui

import android.content.Context
import android.media.AudioManager
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageCatalog
import dev.davidv.translator.MainSelection
import dev.davidv.translator.PcmAudioPlayer
import dev.davidv.translator.R
import dev.davidv.translator.SpeechError
import dev.davidv.translator.SpeechSynthesisResult
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.concatenatePcmAudio
import dev.davidv.translator.localizedName
import dev.davidv.translator.writeM4a
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class SpeechTarget { INPUT, OUTPUT }

sealed interface SpeechState {
  val target: SpeechTarget?

  data object Idle : SpeechState {
    override val target: SpeechTarget? get() = null
  }

  data class Loading(
    override val target: SpeechTarget,
  ) : SpeechState

  data class Playing(
    override val target: SpeechTarget,
  ) : SpeechState

  // Playback reports are posted to the main thread in order, so the previous utterance's stop
  // always lands before the next one starts. Only Loading→Playing and Playing→Idle are real
  // transitions; anything else is a late report from an utterance that was already replaced.
  fun afterPlaybackReport(playing: Boolean): SpeechState =
    when {
      playing && this is Loading -> Playing(target)
      !playing && this is Playing -> Idle
      else -> this
    }
}

class SpeechController(
  private val scope: CoroutineScope,
  private val appContext: Context,
  private val translationCoordinator: TranslationCoordinator,
  private val uiEvents: UiEvents,
  selection: StateFlow<MainSelection>,
  catalog: StateFlow<LanguageCatalog>,
) {
  @OptIn(ExperimentalCoroutinesApi::class)
  val ttsVoices: StateFlow<Map<String, List<uniffi.translator_core.InstalledTtsPack>>> =
    combine(selection, catalog) { current, _ -> current }
      .mapLatest { current ->
        when (current) {
          MainSelection.NoLanguages -> emptyMap()
          is MainSelection.Ready ->
            listOf(current.source, current.target)
              .distinct()
              .associate { it.code to translationCoordinator.installedTtsVoices(it) }
        }
      }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

  private val _speech = MutableStateFlow<SpeechState>(SpeechState.Idle)
  val speech: StateFlow<SpeechState> = _speech.asStateFlow()

  private var speechJob: Job? = null

  private val audioPlayer =
    PcmAudioPlayer(appContext.getSystemService(AudioManager::class.java)) { playing ->
      _speech.update { it.afterPlaybackReport(playing) }
    }

  fun speak(
    text: String,
    language: Language,
    target: SpeechTarget,
  ) {
    speechJob?.cancel()
    audioPlayer.stop()
    _speech.value = SpeechState.Loading(target)
    speechJob =
      scope.launch {
        when (val result = translationCoordinator.synthesizeSpeech(language, text)) {
          is SpeechSynthesisResult.Success ->
            audioPlayer.play(result.audioChunks) { message ->
              _speech.value = SpeechState.Idle
              uiEvents.send(UiEvent.ShowToast(appContext.getString(R.string.translator_audio_playback_failed, message)))
            }
          is SpeechSynthesisResult.Error -> {
            _speech.value = SpeechState.Idle
            uiEvents.send(UiEvent.ShowToast(speechErrorMessage(result.reason)))
          }
        }
      }
  }

  fun share(
    text: String,
    language: Language,
    target: SpeechTarget,
  ) {
    speechJob?.cancel()
    audioPlayer.stop()
    _speech.value = SpeechState.Loading(target)
    speechJob =
      scope.launch {
        when (val result = translationCoordinator.synthesizeSpeech(language, text)) {
          is SpeechSynthesisResult.Success -> {
            val file =
              try {
                withContext(Dispatchers.IO) {
                  val chunks = result.audioChunks.toList()
                  if (chunks.isEmpty()) return@withContext null
                  val audio = concatenatePcmAudio(chunks)
                  val directory = File(appContext.cacheDir, "shared_audio")
                  check(directory.isDirectory || directory.mkdirs()) { "Unable to create audio cache directory" }
                  File(
                    directory,
                    "offline-translator-${language.code}-${UUID.randomUUID()}.m4a",
                  ).also { writeM4a(it, audio) }
                }
              } catch (e: CancellationException) {
                throw e
              } catch (_: Exception) {
                _speech.value = SpeechState.Idle
                uiEvents.send(UiEvent.ShowToast(appContext.getString(R.string.tts_share_failed)))
                return@launch
              }
            if (file == null) {
              _speech.value = SpeechState.Idle
              uiEvents.send(UiEvent.ShowToast(speechErrorMessage(SpeechError.SynthesisFailed(language))))
            } else {
              _speech.value = SpeechState.Idle
              uiEvents.send(UiEvent.ShareAudio(file))
            }
          }
          is SpeechSynthesisResult.Error -> {
            _speech.value = SpeechState.Idle
            uiEvents.send(UiEvent.ShowToast(speechErrorMessage(result.reason)))
          }
        }
      }
  }

  fun stop() {
    speechJob?.cancel()
    audioPlayer.stop()
    _speech.value = SpeechState.Idle
  }

  fun release() {
    audioPlayer.release()
  }

  private fun speechErrorMessage(reason: SpeechError): String =
    when (reason) {
      SpeechError.NothingToSpeak -> appContext.getString(R.string.tts_nothing_to_speak)
      is SpeechError.NoVoiceInstalled ->
        appContext.getString(R.string.tts_no_voice, reason.language.localizedName())
      is SpeechError.SynthesisFailed ->
        appContext.getString(R.string.tts_synthesis_failed, reason.language.localizedName())
      is SpeechError.SynthesisError ->
        appContext.getString(R.string.tts_synthesis_error, reason.language.localizedName(), reason.reason)
    }
}
