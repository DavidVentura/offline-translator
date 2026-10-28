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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

// Only the listening socket is fixed at start; the server reads everything else per request.
private data class HttpServerSettings(
  val enabled: Boolean,
  val port: Int,
  val bindMode: HttpServerBindMode,
)

private data class TtsVoiceData(
  val locales: List<Locale>,
  val voices: List<TranslatorTtsVoice>,
  val overrides: Map<String, String>,
)

fun launchAppEffects(
  scope: CoroutineScope,
  context: Context,
  settingsManager: SettingsManager,
  catalogRepository: CatalogRepository,
) {
  val settings = settingsManager.settings

  // A default naming a deleted language, or one installed only in the other direction (a
  // language shipping just its X→en model can't be a target), would keep seeding every surface
  // with a pair that can't translate. Both defaults are always filled with a usable language
  // once any is installed: an empty "Default from" reads as broken, and surfaces like the live
  // screen tile can't start without a source.
  scope.launch {
    catalogRepository.catalog
      .map(LanguageAvailabilityState::of)
      .filter { it.hasLanguages }
      .collect { availability ->
        settingsManager.update { current -> usableDefaults(current, availability) }
      }
  }

  scope.launch(Dispatchers.IO) {
    settings
      .map { it.useExternalStorage }
      .distinctUntilChanged()
      .drop(1)
      .collect { catalogRepository.reloadIndex() }
  }

  scope.launch {
    settings
      .map { it.registerAsBrowser }
      .distinctUntilChanged()
      .collect { enabled ->
        val desired =
          if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
          } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
          }
        context.packageManager.setComponentEnabledSetting(
          ComponentName(context, "dev.davidv.translator.MainActivityBrowser"),
          desired,
          PackageManager.DONT_KILL_APP,
        )
      }
  }

  scope.launch {
    settings
      .map { it.tapToTranslateEnabled }
      .distinctUntilChanged()
      .collect { enabled ->
        if (enabled) {
          TapToTranslateNotification.show(context)
        } else {
          TapToTranslateNotification.hide(context)
        }
      }
  }

  scope.launch {
    settings
      .map {
        HttpServerSettings(
          enabled = it.httpServerEnabled,
          port = it.httpServerPort,
          bindMode = it.httpServerBindMode,
        )
      }.distinctUntilChanged()
      // Changes come from the settings screen or the server's own notification, both of which may
      // start and stop services; the value at process start is handled by the foreground effect.
      .drop(1)
      .collect { server ->
        if (server.enabled) {
          TranslationApiService.start(context, server.port, server.bindMode)
        } else {
          TranslationApiService.stop(context)
        }
      }
  }

  // The process also starts in the background for the assistant, TTS or tile services, where
  // Android refuses to start a foreground service, so the server is restored whenever the app
  // becomes visible instead of at process start. BootReceiver covers the boot case.
  scope.launch {
    ProcessLifecycleOwner
      .get()
      .lifecycle.currentStateFlow
      .map { it.isAtLeast(Lifecycle.State.STARTED) }
      .distinctUntilChanged()
      .filter { it }
      .collect { TranslationApiService.restore(context, settings.value) }
  }

  scope.launch {
    combine(catalogRepository.catalog, settings.map { it.ttsVoiceOverrides }.distinctUntilChanged()) { catalog, overrides ->
      TtsVoiceData(
        locales = TranslatorTtsEngine.installedTtsLocales(catalog),
        voices = TranslatorTtsEngine.systemTtsVoices(catalog),
        overrides = overrides,
      )
    }.distinctUntilChanged()
      .drop(1)
      .collect { context.sendBroadcast(Intent(TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED)) }
  }
}

internal fun usableDefaults(
  settings: AppSettings,
  availability: LanguageAvailabilityState,
): AppSettings {
  val targets = availability.targetLanguages().map { it.code }
  val sources = availability.sourceLanguages().map { it.code }
  val target =
    settings.defaultTargetLanguageCode.takeIf { it in targets }
      ?: "en".takeIf { it in targets }
      ?: checkNotNull(targets.firstOrNull()) { "installed languages offer no target" }
  // English pivots every pair, so any source other than the target reaches it.
  val source =
    settings.defaultSourceLanguageCode?.takeIf { it in sources && it != target }
      ?: checkNotNull(sources.firstOrNull { it != target }) { "installed languages offer no source for $target" }
  return settings.copy(defaultTargetLanguageCode = target, defaultSourceLanguageCode = source)
}
