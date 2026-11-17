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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import androidx.core.content.FileProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.davidv.translator.AppSettings
import dev.davidv.translator.DownloadId
import dev.davidv.translator.DownloadRequest
import dev.davidv.translator.Language
import dev.davidv.translator.LaunchMode
import dev.davidv.translator.MainSelection
import dev.davidv.translator.R
import dev.davidv.translator.ReadonlyPopupSize
import dev.davidv.translator.SupportKind
import dev.davidv.translator.encodeVoiceOverride
import dev.davidv.translator.parseVoiceOverride
import dev.davidv.translator.ui.DocumentTranslationUiState
import dev.davidv.translator.ui.MainInput
import dev.davidv.translator.ui.SpeechTarget
import dev.davidv.translator.ui.TranslatorViewModel
import dev.davidv.translator.ui.UiEvent
import dev.davidv.translator.ui.components.AttachmentSide
import dev.davidv.translator.ui.components.DetectedLanguageSection
import dev.davidv.translator.ui.components.DocumentConfigureSheet
import dev.davidv.translator.ui.components.LanguageEvent
import dev.davidv.translator.ui.components.PopupWithAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

private fun defaultVoiceNameForLanguage(packs: List<uniffi.translator_core.InstalledTtsPack>): String? =
  packs.firstOrNull()?.voices?.firstOrNull()?.name

private fun packsContainVoice(
  packs: List<uniffi.translator_core.InstalledTtsPack>,
  voiceName: String,
): Boolean = packs.any { pack -> pack.voices.any { it.name == voiceName } }

private data class PendingDocumentSave(
  val path: String,
  val mimeType: String,
)

private fun quantizePlaybackSpeed(speed: Float): Float = ((speed.coerceIn(0.5f, 2.0f) * 10.0f).roundToInt() / 10.0f)

private fun ttsPlaybackSpeedFor(
  settings: AppSettings,
  language: Language,
  voiceName: String?,
): Float =
  voiceName
    ?.let { settings.ttsPlaybackSpeedOverrides[ttsPlaybackSpeedOverrideKey(language, it)] }
    ?: settings.ttsPlaybackSpeed

private fun ttsPlaybackSpeedOverrideKey(
  language: Language,
  voiceName: String,
): String = "${language.code}:$voiceName"

fun shareImageUri(
  uri: Uri,
  context: Context,
) {
  val intent = Intent(Intent.ACTION_SEND)
  intent.putExtra(Intent.EXTRA_STREAM, uri)
  intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  intent.setType("image/jpeg")

  startActivity(context, intent, null)
}

fun shareAudioFile(
  file: File,
  context: Context,
) {
  val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  val intent =
    Intent(Intent.ACTION_SEND).apply {
      type = "audio/mp4"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  startActivity(context, Intent.createChooser(intent, context.getString(R.string.tts_share_audio)), null)
}

fun openDocumentPath(
  path: String,
  mimeType: String,
  context: Context,
) {
  val uri =
    FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      File(path),
    )
  val intent =
    Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, mimeType)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  startActivity(context, Intent.createChooser(intent, "Open translated document"), null)
}

suspend fun saveDocumentPathToUri(
  path: String,
  uri: Uri,
  context: Context,
) {
  withContext(Dispatchers.IO) {
    File(path).inputStream().use { input ->
      val output = requireNotNull(context.contentResolver.openOutputStream(uri)) { "Unable to open output file" }
      output.use {
        input.copyTo(it)
      }
    }
  }
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
      val date = Date()
      val sdf = SimpleDateFormat("yyyyMMdd-hhmmss")
      val dateString = sdf.format(date)
      val fileName = "shared_image-" + dateString + ".jpg"
      val file = File(imagesFolder, fileName)

      val stream = FileOutputStream(file)
      image.compress(Bitmap.CompressFormat.JPEG, 90, stream)
      stream.flush()
      stream.close()
      uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: IOException) {
      Log.e("Share", "IOException while trying to write file for sharing: " + e.message)
    }
    uri
  }

@Composable
private fun DocumentTranslationDialog(
  document: DocumentTranslationUiState,
  onDismiss: () -> Unit,
  onSave: (String, String, String) -> Unit,
  onOpen: (String, String) -> Unit,
) {
  val outputPath = document.outputPath
  val outputMimeType = document.outputMimeType
  val outputFileName = document.outputFileName ?: document.fileName

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text =
            if (document.isTranslating) {
              stringResource(R.string.doc_notif_translating)
            } else {
              stringResource(R.string.doc_notif_translated)
            },
          modifier = Modifier.weight(1f),
        )
        if (document.isTranslating) {
          IconButton(onClick = onDismiss) {
            Icon(
              painter = painterResource(R.drawable.cancel),
              contentDescription = stringResource(R.string.a11y_close),
            )
          }
        }
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = document.fileName,
            modifier = Modifier.weight(1f),
          )
        }
        if (document.isTranslating) {
          val phases = document.pdfPhases
          if (phases != null) {
            PdfPhaseFractionRow("Text", phases.textFraction)
            PdfPhaseRow("Images", phases.imageCurrent, phases.imageTotal)
            PdfPhaseRow("Bitmap pages", phases.rasterCurrent, phases.rasterTotal)
          } else {
            val progressFraction = document.progressFraction
            if (progressFraction != null) {
              LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.fillMaxWidth(),
              )
              Text(stringResource(R.string.translator_percent, (progressFraction * 100).toInt()))
            } else {
              Text(stringResource(document.progressLabelRes))
            }
          }
        }
        if (document.errorMessage != null) {
          Text(document.errorMessage)
        } else if (outputPath != null) {
          Text(stringResource(R.string.translator_translated_file, outputFileName ?: ""))
        }
      }
    },
    confirmButton = {
      if (outputPath != null && outputMimeType != null) {
        TextButton(onClick = { onOpen(outputPath, outputMimeType) }) {
          Text(stringResource(R.string.common_open))
        }
      } else if (document.errorMessage != null) {
        TextButton(onClick = onDismiss) {
          Text(stringResource(R.string.common_close))
        }
      }
    },
    dismissButton = {
      if (outputPath != null && outputMimeType != null) {
        TextButton(onClick = { onSave(outputPath, outputMimeType, outputFileName) }) {
          Text(stringResource(R.string.common_save))
        }
      }
    },
  )
}

@Composable
private fun PdfPhaseRow(
  label: String,
  current: Int,
  total: Int,
) {
  val fraction = if (total > 0) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, modifier = Modifier.weight(1f))
      Text("$current/$total")
    }
    LinearProgressIndicator(
      progress = { fraction },
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun PdfPhaseFractionRow(
  label: String,
  fraction: Float,
) {
  val f = fraction.coerceIn(0f, 1f)
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, modifier = Modifier.weight(1f))
      Text("${(f * 100).toInt()}%")
    }
    LinearProgressIndicator(
      progress = { f },
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
fun TranslatorApp(
  viewModel: TranslatorViewModel,
  startScreen: StartScreen = StartScreen.Main,
  onClose: () -> Unit = {},
) {
  val navController = rememberNavController()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var pendingDocumentSave by remember { mutableStateOf<PendingDocumentSave?>(null) }
  val saveTranslatedDocument =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val pending = pendingDocumentSave
      pendingDocumentSave = null
      val uri = result.data?.data
      if (result.resultCode == Activity.RESULT_OK && pending != null && uri != null) {
        scope.launch {
          try {
            saveDocumentPathToUri(pending.path, uri, context)
            Toast.makeText(context, context.getString(R.string.translator_document_saved), Toast.LENGTH_SHORT).show()
          } catch (e: Exception) {
            Log.e("DocumentSave", "Failed to save translated document", e)
            Toast.makeText(context, context.getString(R.string.translator_document_save_failed), Toast.LENGTH_SHORT).show()
          }
        }
      }
    }

  // Collect ViewModel state
  val translation = viewModel.translation
  val input by translation.input.collectAsState()
  val inputTransliterated by translation.inputTransliterated.collectAsState()
  val output by translation.output.collectAsState()
  val selection by translation.selection.collectAsState()
  val detectedRegions by translation.detectedRegions.collectAsState()
  val ocrReadingOrder by translation.ocrReadingOrder.collectAsState()
  val currentDetectedLanguage by translation.currentDetectedLanguage.collectAsState()
  val isAutoSource by translation.isAutoSource.collectAsState()
  val currentLaunchMode by viewModel.currentLaunchMode.collectAsState()
  val modalVisible by viewModel.modalVisible.collectAsState()
  val dictionary by viewModel.dictionary.state.collectAsState()
  val documentTranslation by viewModel.documents.documentTranslation.collectAsState()

  val settings by viewModel.settingsManager.settings.collectAsState()
  val languageMetadata by viewModel.languageMetadataManager.metadata.collectAsState()
  val languageState by viewModel.languageStateManager.languageState.collectAsState()
  val downloadManager = (context.applicationContext as dev.davidv.translator.TranslatorApplication).downloadManager
  val downloads by downloadManager.state.collectAsState()
  val isImageProcessing by translation.isImageProcessing.collectAsState()
  val ttsVoicesByLanguage by viewModel.speech.ttsVoices.collectAsState()
  val speech by viewModel.speech.speech.collectAsState()
  val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

  LaunchedEffect(downloadManager) {
    viewModel.languageStateManager.connectToDownloadErrors(downloadManager.errors)
  }

  // Collect UI events (toasts, share intents)
  LaunchedEffect(Unit) {
    viewModel.uiEvents.collect { event ->
      when (event) {
        is UiEvent.ShowToast -> {
          Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
        }
        is UiEvent.ShareImage -> {
          val imageUri = saveImage(event.bitmap, context)
          if (imageUri != null) {
            shareImageUri(imageUri, context)
          }
        }
        is UiEvent.ShareAudio -> {
          shareAudioFile(event.file, context)
        }
      }
    }
  }

  val pendingDocument by viewModel.documents.pendingDocument.collectAsState()
  pendingDocument?.let { pending ->
    val docSelection = selection
    if (docSelection is MainSelection.Ready) {
      DocumentConfigureSheet(
        fileName = pending.displayName,
        fileSizeBytes = pending.sizeBytes,
        extension = pending.extension,
        initialFrom = docSelection.source,
        initialTo = docSelection.target,
        sourceLanguages = languageState.sourceLanguages(),
        targetLanguages = languageState.targetLanguages(),
        languageMetadata = languageMetadata,
        defaultTranslatePdfImages = settings.translatePdfImages,
        onDismiss = viewModel.documents::dismissPending,
        onTranslate = viewModel.documents::start,
      )
    }
  }

  documentTranslation?.let { document ->
    DocumentTranslationDialog(
      document = document,
      onDismiss = {
        if (document.isTranslating) {
          viewModel.documents.cancel()
        } else {
          viewModel.documents.dismissResult()
        }
      },
      onSave = { path, mimeType, fileName ->
        pendingDocumentSave = PendingDocumentSave(path = path, mimeType = mimeType)
        saveTranslatedDocument.launch(
          Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
          },
        )
      },
      onOpen = { path, mimeType ->
        viewModel.documents.dismissResult()
        try {
          openDocumentPath(path, mimeType, context)
        } catch (e: Exception) {
          Toast.makeText(context, context.getString(R.string.translator_no_app_to_open), Toast.LENGTH_SHORT).show()
        }
      },
    )
  }

  val startDestination =
    remember {
      when (selection) {
        // Without an installed pair the language setup comes first, even from the camera shortcut.
        MainSelection.NoLanguages -> "no_languages"
        is MainSelection.Ready ->
          when (startScreen) {
            StartScreen.Main -> "main"
            StartScreen.LiveCamera -> "live_camera"
          }
      }
    }

  LaunchedEffect(Unit) {
    viewModel.languageManagerRequests.collect {
      // The NavHost lives inside an AnimatedVisibility that only composes its content on a
      // later frame, so the graph may not be set yet when the request arrives.
      val entry = navController.currentBackStackEntryFlow.first()
      if (entry.destination.route != "language_manager") {
        navController.navigate("language_manager")
      }
    }
  }

  val isReadonlyPopup = currentLaunchMode == LaunchMode.ReadonlyModal

  val heightFactor by animateFloatAsState(
    targetValue =
      when {
        currentLaunchMode == LaunchMode.Normal -> 1f
        isReadonlyPopup ->
          when (val size = settings.readonlyPopupSize) {
            is ReadonlyPopupSize.Fraction -> size.heightFactor
            ReadonlyPopupSize.Expanded -> 1f
          }
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
              scope.launch {
                delay(400)
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
      PopupWithAttachment(
        side = if (popupAlignment == Alignment.BottomCenter) AttachmentSide.Above else AttachmentSide.Below,
        attachment = {
          val popupSelection = selection
          val popupInputHidden =
            currentLaunchMode == LaunchMode.ReadonlyModal && settings.onlyShowOutputOnReadonlyModal && input !is MainInput.Image
          if (currentLaunchMode != LaunchMode.Normal && !popupInputHidden && currentRoute == "main" && popupSelection is MainSelection.Ready) {
            DetectedLanguageSection(
              detectedLanguage = currentDetectedLanguage,
              from = popupSelection.source,
              languageState = languageState,
              onMessage = viewModel::handleMessage,
              downloads = downloads,
              isAutoSource = isAutoSource,
              onEvent = { event ->
                when (event) {
                  is LanguageEvent.Download -> downloadManager.enqueue(DownloadRequest.Core(event.language))
                  is LanguageEvent.Cancel -> downloadManager.cancel(DownloadId.Core(event.language))
                  else -> error("Unexpected detected-language event: $event")
                }
              },
            )
          }
        },
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
            composable("no_languages") {
              NoLanguagesScreen(
                settingsManager = viewModel.settingsManager,
                onDone = {
                  if (selection is MainSelection.Ready) {
                    navController.navigate("main") {
                      popUpTo("no_languages") { inclusive = true }
                    }
                  }
                },
                onSettings = {
                  navController.navigate("settings")
                },
                languageStateManager = viewModel.languageStateManager,
                languageMetadataManager = viewModel.languageMetadataManager,
                downloadManager = downloadManager,
              )
            }

            composable("main") {
              when (val mainSelection = selection) {
                // Deleting the last language from the manager leaves main below it on the stack.
                MainSelection.NoLanguages ->
                  LaunchedEffect(Unit) {
                    navController.navigate("no_languages") {
                      popUpTo("main") { inclusive = true }
                    }
                  }
                is MainSelection.Ready -> {
                  val currentFrom = mainSelection.source
                  val currentTo = mainSelection.target
                  val availableSourceTtsVoices = ttsVoicesByLanguage[currentFrom.code].orEmpty()
                  val selectedSourceTtsVoiceName =
                    parseVoiceOverride(settings.ttsVoiceOverrides[currentFrom.code])
                      ?.voiceName
                      ?.takeIf { voiceName -> packsContainVoice(availableSourceTtsVoices, voiceName) }
                      ?: defaultVoiceNameForLanguage(availableSourceTtsVoices)
                  val sourceTtsPlaybackSpeed = ttsPlaybackSpeedFor(settings, currentFrom, selectedSourceTtsVoiceName)
                  val availableTtsVoices = ttsVoicesByLanguage[currentTo.code].orEmpty()
                  val selectedTtsVoiceName =
                    parseVoiceOverride(settings.ttsVoiceOverrides[currentTo.code])
                      ?.voiceName
                      ?.takeIf { voiceName -> packsContainVoice(availableTtsVoices, voiceName) }
                      ?: defaultVoiceNameForLanguage(availableTtsVoices)
                  val targetTtsPlaybackSpeed = ttsPlaybackSpeedFor(settings, currentTo, selectedTtsVoiceName)
                  val image = input as? MainInput.Image
                  MainScreen(
                    onSettings = { navController.navigate("settings") },
                    onLiveCamera = { navController.navigate("live_camera") },
                    input = input.text,
                    inputTransliteration = inputTransliterated,
                    output = output,
                    from = currentFrom,
                    to = currentTo,
                    targetTabs = mainSelection.targets.tabs,
                    detectedLanguage = currentDetectedLanguage,
                    displayImage = image?.display,
                    originalImage = image?.original,
                    imageWordSelection = image?.words,
                    detectedRegions = detectedRegions,
                    ocrReadingOrder = ocrReadingOrder,
                    isImageProcessing = isImageProcessing,
                    dictionary = dictionary,
                    speech = speech,
                    onMessage = viewModel::handleMessage,
                    onSteerPreview = translation::steerPreview,
                    canSwapLanguages = viewModel.languageStateManager.canSwapLanguages(currentFrom, currentTo),
                    onStopAudio = viewModel.speech::stop,
                    languageState = languageState,
                    languageMetadata = languageMetadata,
                    downloads = downloads,
                    onDownload = downloadManager::enqueue,
                    onCancelDownload = downloadManager::cancel,
                    settings = settings,
                    availableSourceTtsVoices = availableSourceTtsVoices,
                    selectedSourceTtsVoiceName = selectedSourceTtsVoiceName,
                    sourceTtsPlaybackSpeed = sourceTtsPlaybackSpeed,
                    availableTtsVoices = availableTtsVoices,
                    selectedTtsVoiceName = selectedTtsVoiceName,
                    targetTtsPlaybackSpeed = targetTtsPlaybackSpeed,
                    onInputSplitChange = { split ->
                      viewModel.settingsManager.update { it.copy(mainInputSplit = split) }
                    },
                    onTtsPlaybackSpeedChange = { newSpeed ->
                      val voiceName = selectedTtsVoiceName
                      val quantizedSpeed = quantizePlaybackSpeed(newSpeed)
                      viewModel.settingsManager.update { current ->
                        if (voiceName != null) {
                          current.copy(
                            ttsPlaybackSpeedOverrides =
                              current.ttsPlaybackSpeedOverrides +
                                (ttsPlaybackSpeedOverrideKey(currentTo, voiceName) to quantizedSpeed),
                          )
                        } else {
                          current.copy(ttsPlaybackSpeed = quantizedSpeed)
                        }
                      }
                    },
                    onSourceTtsPlaybackSpeedChange = { newSpeed ->
                      val voiceName = selectedSourceTtsVoiceName
                      val quantizedSpeed = quantizePlaybackSpeed(newSpeed)
                      viewModel.settingsManager.update { current ->
                        if (voiceName != null) {
                          current.copy(
                            ttsPlaybackSpeedOverrides =
                              current.ttsPlaybackSpeedOverrides +
                                (ttsPlaybackSpeedOverrideKey(currentFrom, voiceName) to quantizedSpeed),
                          )
                        } else {
                          current.copy(ttsPlaybackSpeed = quantizedSpeed)
                        }
                      }
                    },
                    onSourceTtsVoiceSelected = { packId, voiceName ->
                      viewModel.settingsManager.update { current ->
                        current.copy(
                          ttsVoiceOverrides =
                            current.ttsVoiceOverrides +
                              (currentFrom.code to encodeVoiceOverride(packId, voiceName)),
                        )
                      }
                    },
                    onTtsVoiceSelected = { packId, voiceName ->
                      viewModel.settingsManager.update { current ->
                        current.copy(
                          ttsVoiceOverrides =
                            current.ttsVoiceOverrides +
                              (currentTo.code to encodeVoiceOverride(packId, voiceName)),
                        )
                      }
                    },
                    onSpeakInput = { text, language -> viewModel.speech.speak(text, language, SpeechTarget.INPUT) },
                    onSpeakOutput = { text, language -> viewModel.speech.speak(text, language, SpeechTarget.OUTPUT) },
                    onShareInput = { text, language -> viewModel.speech.share(text, language, SpeechTarget.INPUT) },
                    onShareOutput = { text, language -> viewModel.speech.share(text, language, SpeechTarget.OUTPUT) },
                    launchMode = currentLaunchMode,
                    pendingSharedImage = viewModel.pendingSharedImage,
                    isAutoSource = isAutoSource,
                  )
                }
              }
            }
            composable("language_manager") {
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
                    settingsManager = viewModel.settingsManager,
                    downloadManager = downloadManager,
                  )
                }
              }
            }
            composable("settings") {
              val catalog = viewModel.languageStateManager.catalog.collectAsState().value
              val app = context.applicationContext as dev.davidv.translator.TranslatorApplication
              val adblockReady by app.adblockManager.ready.collectAsState()
              SettingsScreen(
                settings = settings,
                sourceLanguages = languageState.sourceLanguages(),
                targetLanguages = languageState.targetLanguages(),
                catalog = catalog,
                adblockDownload = downloads[DownloadId.Support(SupportKind.ADBLOCK)],
                onDownloadAdblockSupport = { downloadManager.enqueue(DownloadRequest.Support(SupportKind.ADBLOCK)) },
                onCancelAdblockDownload = { downloadManager.cancel(DownloadId.Support(SupportKind.ADBLOCK)) },
                adblockInstalled = adblockReady || catalog.supportInstalledByKind("adblock"),
                onSettingsChange = viewModel.settingsManager::update,
                onDefaultTargetLanguageSelected = translation::setDefaultTarget,
                onManageLanguages = {
                  navController.navigate("language_manager")
                },
                onDeleteAdblockSupport = {
                  viewModel.languageStateManager.deleteSupportByKind("adblock")
                  app.adblockManager.reload()
                },
                onHowToUse = {
                  navController.navigate("how_to_use")
                },
              )
            }
            composable("how_to_use") {
              HowToUseScreen(onBack = { navController.popBackStack() })
            }
            composable("live_camera") {
              val liveSelection = selection
              val liveCatalog by viewModel.languageStateManager.catalog.collectAsState()
              if (liveSelection is MainSelection.Ready) {
                val currentFrom = liveSelection.source
                val currentTo = liveSelection.target
                LiveCameraScreen(
                  from = currentFrom,
                  to = currentTo,
                  isAutoSource = isAutoSource,
                  canSwap = viewModel.languageStateManager.canSwapLanguages(currentFrom, currentTo),
                  languageState = languageState,
                  languageMetadata = languageMetadata,
                  onMessage = viewModel::handleMessage,
                  showAutoOption = !settings.disableCLD,
                  liveOverlayDefaultEnabled = settings.liveCameraOverlayEnabled,
                  catalog = liveCatalog,
                  // Opened from the launcher shortcut the camera is the root, so leaving it exits the
                  // app and a capture has to bring main up in its place.
                  onClose = {
                    if (!navController.popBackStack("main", inclusive = false)) onClose()
                  },
                  onImageCaptured = { uri ->
                    viewModel.setSharedImageUri(uri)
                    if (!navController.popBackStack("main", inclusive = false)) {
                      navController.navigate("main") {
                        popUpTo("live_camera") { inclusive = true }
                      }
                    }
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

enum class StartScreen {
  Main,
  LiveCamera,
}
