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

package dev.davidv.translator.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import androidx.core.content.FileProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.davidv.translator.DownloadService
import dev.davidv.translator.DownloadState
import dev.davidv.translator.Language
import dev.davidv.translator.LaunchMode
import dev.davidv.translator.PcmAudioPlayer
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.TtsVoiceOption
import dev.davidv.translator.ui.TranslatorViewModel
import dev.davidv.translator.ui.UiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

private fun defaultVoiceNameForLanguage(voices: List<TtsVoiceOption>): String? = voices.firstOrNull()?.name

private fun quantizePlaybackSpeed(speed: Float): Float = ((speed.coerceIn(0.5f, 2.0f) * 10.0f).roundToInt() / 10.0f)

fun shareImageUri(
  uri: Uri,
  context: Context,
) {
  val intent = Intent(Intent.ACTION_SEND)
  intent.putExtra(Intent.EXTRA_STREAM, uri)
  intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  intent.setType("image/png")

  startActivity(context, intent, null)
}

suspend fun saveImage(
  image: Bitmap,
  context: Context,
): Uri? =
  withContext(Dispatchers.IO) {
    val imagesFolder: File =
      File(
        context.cacheDir,
        "images",
      )
    var uri: Uri? = null
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")

      val stream = FileOutputStream(file)
      image.compress(Bitmap.CompressFormat.PNG, 90, stream)
      stream.flush()
      stream.close()
      uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: IOException) {
      Log.e("Share", "IOException while trying to write file for sharing: " + e.message)
    }
    uri
  }

@Composable
fun TranslatorApp(
  viewModel: TranslatorViewModel,
  downloadServiceState: StateFlow<DownloadService?>,
  onClose: () -> Unit = {},
) {
  val navController = rememberNavController()
  val context = LocalContext.current
  var isAudioPlaying by remember { mutableStateOf(false) }
  var isAudioLoading by remember { mutableStateOf(false) }
  val pcmAudioPlayer =
    remember {
      PcmAudioPlayer { playing ->
        isAudioPlaying = playing
        if (playing) {
          isAudioLoading = false
        } else if (!playing) {
          isAudioLoading = false
        }
      }
    }

  // Collect ViewModel state
  val input by viewModel.input.collectAsState()
  val inputTransliterated by viewModel.inputTransliterated.collectAsState()
  val output by viewModel.output.collectAsState()
  val from by viewModel.from.collectAsState()
  val to by viewModel.to.collectAsState()
  val displayImage by viewModel.displayImage.collectAsState()
  val ocrReadingOrder by viewModel.ocrReadingOrder.collectAsState()
  val currentDetectedLanguage by viewModel.currentDetectedLanguage.collectAsState()
  val currentLaunchMode by viewModel.currentLaunchMode.collectAsState()
  val modalVisible by viewModel.modalVisible.collectAsState()
  val dictionaryWord by viewModel.dictionaryWord.collectAsState()
  val dictionaryStack by viewModel.dictionaryStack.collectAsState()
  val dictionaryLookupLanguage by viewModel.dictionaryLookupLanguage.collectAsState()

  val settings by viewModel.settingsManager.settings.collectAsState()
  val languageMetadata by viewModel.languageMetadataManager.metadata.collectAsState()
  val downloadService by downloadServiceState.collectAsState()
  val languageState by viewModel.languageStateManager.languageState.collectAsState()
  val downloadStates by downloadService?.downloadStates?.collectAsState() ?: remember {
    kotlinx.coroutines.flow.MutableStateFlow<Map<Language, DownloadState>>(emptyMap())
  }.collectAsState()
  val adblockDownloadState by downloadService?.adblockDownloadState?.collectAsState() ?: remember {
    kotlinx.coroutines.flow.MutableStateFlow(DownloadState())
  }.collectAsState()
  val isTranslating by viewModel.translationCoordinator.isTranslating.collectAsState()
  val ttsVoicesByLanguage by viewModel.ttsVoices.collectAsState()

  LaunchedEffect(to?.code, to?.let { languageState.availabilityFor(it)?.ttsFiles } == true) {
    val targetLanguage = to ?: return@LaunchedEffect
    if (languageState.availabilityFor(targetLanguage)?.ttsFiles == true) {
      viewModel.refreshTtsVoices(targetLanguage)
    } else {
      viewModel.clearTtsVoices(targetLanguage.code)
    }
  }

  // Connect/disconnect download service
  LaunchedEffect(downloadService) {
    downloadService?.let { service ->
      viewModel.connectDownloadService(service)
    }
  }

  // Collect UI events (toasts, share intents)
  LaunchedEffect(Unit) {
    viewModel.uiEvents.collect { event ->
      when (event) {
        is UiEvent.ShowToast -> {
          Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
        }
        UiEvent.AudioLoadingStarted -> {
          isAudioLoading = true
        }
        UiEvent.AudioLoadingStopped -> {
          isAudioLoading = false
        }
        is UiEvent.ShareImage -> {
          val imageUri = saveImage(event.bitmap, context)
          if (imageUri != null) {
            shareImageUri(imageUri, context)
          }
        }
        is UiEvent.PlayAudio -> {
          pcmAudioPlayer.play(event.audioChunks) { message ->
            isAudioLoading = false
            Toast.makeText(context, "Audio playback failed: $message", Toast.LENGTH_SHORT).show()
          }
        }
      }
    }
  }

  DisposableEffect(pcmAudioPlayer) {
    onDispose {
      pcmAudioPlayer.release()
    }
  }

  // Retranslate when isTranslating becomes false (queued translation)
  LaunchedEffect(isTranslating) {
    if (!isTranslating) {
      viewModel.retranslateIfNeeded()
    }
  }

  val navigationState by viewModel.navigationState.collectAsState()

  // Map navigation state to route
  val startDestination =
    remember {
      when (navigationState) {
        TranslatorViewModel.NavigationState.LOADING -> "loading"
        TranslatorViewModel.NavigationState.NO_LANGUAGES -> "no_languages"
        TranslatorViewModel.NavigationState.READY -> "main"
      }
    }

  LaunchedEffect(navigationState) {
    val currentRoute = navController.currentDestination?.route
    val targetRoute =
      when (navigationState) {
        TranslatorViewModel.NavigationState.LOADING -> null
        TranslatorViewModel.NavigationState.NO_LANGUAGES -> "no_languages"
        TranslatorViewModel.NavigationState.READY -> "main"
      }
    if (targetRoute != null && currentRoute != targetRoute && currentRoute == "loading") {
      navController.navigate(targetRoute) {
        popUpTo(currentRoute!!) { inclusive = true }
      }
    }
  }

  val isReadonlyPopup = currentLaunchMode == LaunchMode.ReadonlyModal

  val heightFactor by animateFloatAsState(
    targetValue =
      when {
        currentLaunchMode == LaunchMode.Normal -> 1f
        isReadonlyPopup -> settings.readonlyModalCompactHeightFactor
        else -> 0.6f
      },
    animationSpec = tween(300),
    label = "heightFactor",
  )
  val widthFactor by animateFloatAsState(
    targetValue = if (currentLaunchMode == LaunchMode.Normal) 1f else 0.9f,
    animationSpec = tween(300),
    label = "widthFactor",
  )
  val popupAlignment =
    when (settings.readonlyModalOutputAlignment) {
      dev.davidv.translator.ReadonlyModalOutputAlignment.TOP -> Alignment.TopCenter
      dev.davidv.translator.ReadonlyModalOutputAlignment.MIDDLE -> Alignment.Center
      dev.davidv.translator.ReadonlyModalOutputAlignment.BOTTOM -> Alignment.BottomCenter
    }

  SideEffect {
    val activity = context as? Activity ?: return@SideEffect
    val window = activity.window ?: return@SideEffect
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.setDimAmount(0f)
    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    window.setGravity(Gravity.CENTER)
  }

  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    // Click-outside-to-dismiss (invisible)
    if (currentLaunchMode != LaunchMode.Normal) {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
            ) {
              viewModel.setModalVisible(false)
              kotlinx.coroutines.MainScope().launch {
                kotlinx.coroutines.delay(400)
                onClose()
              }
            },
      )
    }

    val slideFromTop = popupAlignment != Alignment.BottomCenter
    val slideMultiplier = if (popupAlignment == Alignment.Center) 2.5f else 1.5f
    val modalTransitionState =
      remember { androidx.compose.animation.core.MutableTransitionState(false) }
    LaunchedEffect(modalVisible) { modalTransitionState.targetState = modalVisible }
    AnimatedVisibility(
      visibleState = modalTransitionState,
      modifier =
        Modifier
          .align(popupAlignment)
          .then(
            if (currentLaunchMode != LaunchMode.Normal) {
              Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(vertical = 8.dp)
            } else {
              Modifier
            },
          ),
      enter = androidx.compose.animation.EnterTransition.None,
      exit =
        slideOutVertically(
          animationSpec = tween(300),
          targetOffsetY = { fullHeight ->
            val sign = if (slideFromTop) -1 else 1
            (fullHeight * slideMultiplier).toInt() * sign
          },
        ),
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxHeight(heightFactor)
            .fillMaxWidth(widthFactor)
            .then(
              if (currentLaunchMode == LaunchMode.Normal) {
                Modifier
              } else {
                Modifier.clip(RoundedCornerShape(10.dp))
              },
            ),
      ) {
        NavHost(
          navController = navController,
          startDestination = startDestination,
        ) {
          composable("loading") {
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator()
            }
          }

          composable("no_languages") {
            val currentDownloadService = downloadService
            if (currentDownloadService != null) {
              NoLanguagesScreen(
                onDone = {
                  if (languageState.hasLanguages) {
                    MainScope().launch {
                      navController.navigate("main") {
                        popUpTo("no_languages") { inclusive = true }
                      }
                    }
                  }
                },
                onSettings = {
                  navController.navigate("settings")
                },
                languageStateManager = viewModel.languageStateManager,
                languageMetadataManager = viewModel.languageMetadataManager,
                downloadService = currentDownloadService,
              )
            }
          }

          composable("main") {
            // Safe to use from/to non-null here: navigationState is READY
            val currentFrom = from
            val currentTo = to
            if (currentFrom != null && currentTo != null) {
              val availableTtsVoices = ttsVoicesByLanguage[currentTo.code].orEmpty()
              val selectedTtsVoiceName =
                settings.ttsVoiceOverrides[currentTo.code]
                  ?.takeIf { voiceName -> availableTtsVoices.any { it.name == voiceName } }
                  ?: defaultVoiceNameForLanguage(availableTtsVoices)
              MainScreen(
                onSettings = { navController.navigate("settings") },
                input = input,
                inputTransliteration = inputTransliterated,
                output = output,
                from = currentFrom,
                to = currentTo,
                detectedLanguage = currentDetectedLanguage,
                displayImage = displayImage,
                ocrReadingOrder = ocrReadingOrder,
                isTranslating = viewModel.translationCoordinator.isTranslating,
                isOcrInProgress = viewModel.translationCoordinator.isOcrInProgress,
                dictionaryWord = dictionaryWord,
                dictionaryStack = dictionaryStack,
                dictionaryLookupLanguage = dictionaryLookupLanguage,
                isAudioPlaying = isAudioPlaying,
                isAudioLoading = isAudioLoading,
                onMessage = viewModel::handleMessage,
                canSwapLanguages = viewModel.languageStateManager.canSwapLanguages(currentFrom, currentTo),
                onStopAudio = {
                  isAudioLoading = false
                  pcmAudioPlayer.stop()
                },
                languageState = languageState,
                languageMetadata = languageMetadata,
                downloadStates = downloadStates,
                settings = settings,
                availableTtsVoices = availableTtsVoices,
                selectedTtsVoiceName = selectedTtsVoiceName,
                onTtsPlaybackSpeedChange = { newSpeed ->
                  viewModel.settingsManager.updateSettings(
                    settings.copy(ttsPlaybackSpeed = quantizePlaybackSpeed(newSpeed)),
                  )
                },
                onTtsVoiceSelected = { voiceName ->
                  viewModel.settingsManager.updateSettings(
                    settings.copy(
                      ttsVoiceOverrides = settings.ttsVoiceOverrides + (currentTo.code to voiceName),
                    ),
                  )
                },
                launchMode = currentLaunchMode,
              )
            }
          }
          composable("language_manager") {
            val curDownloadService = downloadService
            val catalog = viewModel.languageStateManager.catalog.collectAsState().value
            if (curDownloadService != null && catalog != null) {
              val dictionaryDownloadStates by curDownloadService.dictionaryDownloadStates.collectAsState()
              val ttsDownloadStates by curDownloadService.ttsDownloadStates.collectAsState()
              Scaffold(
                modifier =
                  Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding(),
              ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                  LanguageAssetManagerScreen(
                    context = context,
                    languageStateManager = viewModel.languageStateManager,
                    languageMetadataManager = viewModel.languageMetadataManager,
                    catalog = catalog,
                    languageAvailabilityState = languageState,
                    downloadStates = downloadStates,
                    dictionaryDownloadStates = dictionaryDownloadStates,
                    ttsDownloadStates = ttsDownloadStates,
                  )
                }
              }
            }
          }
          composable("settings") {
            val catalog = viewModel.languageStateManager.catalog.collectAsState().value
            val englishLang = catalog?.english
            val availableWithEnglish =
              if (englishLang != null) {
                (languageState.translatorLanguages() + englishLang).distinctBy { it.code }
              } else {
                languageState.translatorLanguages()
              }
            val app = context.applicationContext as dev.davidv.translator.TranslatorApplication
            val adblockReady by app.adblockManager.ready.collectAsState()
            SettingsScreen(
              settings = settings,
              languageMetadataManager = viewModel.languageMetadataManager,
              availableLanguages = availableWithEnglish,
              catalog = catalog,
              adblockDownloadState = adblockDownloadState,
              adblockInstalled = adblockReady || catalog?.supportInstalledByKind("adblock") == true,
              onSettingsChange = { newSettings ->
                viewModel.settingsManager.updateSettings(newSettings)
                if (newSettings.defaultTargetLanguageCode != settings.defaultTargetLanguageCode) {
                  val targetLang = catalog?.languageByCode(newSettings.defaultTargetLanguageCode)
                  if (targetLang != null) {
                    viewModel.handleMessage(dev.davidv.translator.TranslatorMessage.ToLang(targetLang))
                  }
                }
                if (newSettings.useExternalStorage != settings.useExternalStorage) {
                  viewModel.languageStateManager.refreshLanguageAvailability()
                }
              },
              onManageLanguages = {
                navController.navigate("language_manager")
              },
              onDeleteAdblockSupport = {
                viewModel.languageStateManager.deleteSupportByKind("adblock")
                app.adblockManager.reload()
              },
            )
          }
        }
      }
    }
  }
}
