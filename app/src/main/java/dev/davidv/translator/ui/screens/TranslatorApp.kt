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

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import androidx.core.content.FileProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.davidv.translator.DownloadEvent
import dev.davidv.translator.DownloadService
import dev.davidv.translator.FileEvent
import dev.davidv.translator.FilePathManager
import dev.davidv.translator.InputType
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageStateManager
import dev.davidv.translator.LaunchMode
import dev.davidv.translator.SettingsManager
import dev.davidv.translator.TarkkaBinding
import dev.davidv.translator.TranslatedText
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslationResult
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.WordWithTaggedEntries
import dev.davidv.translator.fromEnglishFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

fun toggleFirstLetterCase(word: String): String {
  if (word.isEmpty()) return word

  val first = word.first()
  val toggled = if (first.isUpperCase()) first.lowercaseChar() else first.uppercaseChar()
  return toggled + word.drop(1)
}

fun openDictionary(
  language: Language,
  filePathManager: FilePathManager,
  onSuccess: (TarkkaBinding) -> Unit,
  onError: (String) -> Unit,
) {
  val tarkkaBinding = TarkkaBinding()
  val dictPath = filePathManager.getDictionaryFile(language).absolutePath
  val result = tarkkaBinding.open(dictPath)
  if (result) {
    onSuccess(tarkkaBinding)
  } else {
    onError("Failed to load dictionary for ${language.displayName}")
  }
}

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
        val date = Date()
        val sdf = SimpleDateFormat("yyyyMMdd-hhmmss")
        val dateString = sdf.format(date)
        val file = File(imagesFolder, "shared_image-"+dateString+".png")

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
  initialText: String,
  sharedImageUri: MutableState<Uri?>,
  translationCoordinator: TranslationCoordinator,
  settingsManager: SettingsManager,
  languageMetadataManager: dev.davidv.translator.LanguageMetadataManager,
  filePathManager: FilePathManager,
  downloadServiceState: StateFlow<DownloadService?>,
  initialLaunchMode: LaunchMode,
  onClose: () -> Unit = {},
) {
  val navController = rememberNavController()
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val languageMetadata by languageMetadataManager.metadata.collectAsState()

  // Launch mode state - make it mutable so it can be changed
  var currentLaunchMode by remember { mutableStateOf(initialLaunchMode) }

  // Modal animation state
  var modalVisible by remember { mutableStateOf(currentLaunchMode == LaunchMode.Normal) }

  // Animate in modal on launch
  LaunchedEffect(currentLaunchMode) {
    if (currentLaunchMode != LaunchMode.Normal) {
      modalVisible = true
    }
  }

  var dictionaryBindings by remember { mutableStateOf<Map<Language, TarkkaBinding>>(emptyMap()) }
  var dictionaryWord by remember { mutableStateOf<WordWithTaggedEntries?>(null) }
  var dictionaryStack by remember { mutableStateOf<List<WordWithTaggedEntries>>(emptyList()) }
  var dictionaryLookupLanguage by remember { mutableStateOf<Language?>(null) }

  val settings by settingsManager.settings.collectAsState()
  val downloadService by downloadServiceState.collectAsState()
  val languageStateManager =
    remember(filePathManager) {
      LanguageStateManager(scope, filePathManager)
    }

  LaunchedEffect(downloadService) {
    downloadService?.let { service ->
      languageStateManager.connectToDownloadEvents(service.downloadEvents)
    }
  }
  val languageState by languageStateManager.languageState.collectAsState()
  val downloadStates by downloadService?.downloadStates?.collectAsState() ?: remember {
    mutableStateOf(emptyMap())
  }

  // Move all persistent state to this level so it survives navigation
  var input by remember { mutableStateOf(initialText) }
  var inputTransliterated by remember { mutableStateOf<String?>(null) }
  var output by remember { mutableStateOf<TranslatedText?>(null) }
  val fromState = remember { mutableStateOf<Language?>(null) }
  val from by fromState
  val setFrom = { lang: Language? -> fromState.value = lang }

  val toState = remember { mutableStateOf(settings.defaultTargetLanguage) }
  val to by toState
  val setTo = { lang: Language -> toState.value = lang }
  var displayImage by remember { mutableStateOf<Bitmap?>(null) }
  var currentDetectedLanguage by remember { mutableStateOf<Language?>(null) }
  var inputType by remember { mutableStateOf(InputType.TEXT) }
  var originalImage by remember { mutableStateOf<Bitmap?>(null) }
  val isTranslating by translationCoordinator.isTranslating.collectAsState()

  LaunchedEffect(downloadService) {
    downloadService?.downloadEvents?.collect { event ->
      when (event) {
        is DownloadEvent.NewTranslationAvailable -> {}
        is DownloadEvent.NewDictionaryAvailable -> {
          openDictionary(
            event.language,
            filePathManager,
            onSuccess = { tarkkaBinding ->
              dictionaryBindings = dictionaryBindings + (event.language to tarkkaBinding)
              Log.d("DictionaryLookup", "Loaded dictionary for ${event.language.displayName}")
            },
            onError = { error ->
              Log.e("DictionaryLookup", error)
            },
          )
        }

        is DownloadEvent.DictionaryIndexDownloaded -> {
          Log.d("TranslatorApp", "Dictionary index downloaded: ${event.index}")
        }

        is DownloadEvent.DownloadError -> {
          Log.w("TranslatorApp", "DownloadError $event")
          Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  // Handle file events from LanguageStateManager
  LaunchedEffect(languageStateManager) {
    languageStateManager.fileEvents.collect { event ->
      when (event) {
        is FileEvent.LanguageDeleted -> {
          val langs = languageStateManager.languageState.value.availableLanguageMap
          val validLangs = langs.filter { it.key != event.language }.filter { it.value.translatorFiles }
          val currentFrom = fromState.value
          val currentTo = toState.value
          if (currentFrom == event.language || currentFrom == null) {
            setFrom(validLangs.filterNot { it.key == currentTo }.keys.firstOrNull())
          }
          if (currentTo == event.language) {
            setTo(validLangs.filterNot { it.key == currentFrom }.keys.firstOrNull() ?: Language.ENGLISH)
          }
          if (event.language == Language.JAPANESE) {
            translationCoordinator.setMucabBinding(null)
          }
          Log.d("TranslatorApp", "Language deleted: ${event.language}")
        }
        is FileEvent.DictionaryIndexLoaded -> {
          Log.d("TranslatorApp", "Dictionary index loaded from file: ${event.index}")
        }
        is FileEvent.MucabFileLoaded -> {
          translationCoordinator.setMucabBinding(event.mucabBinding)
          Log.d("TranslatorApp", "Mucab file loaded and set in TranslationCoordinator")
        }
        is FileEvent.DictionaryDeleted -> {
          dictionaryBindings[event.language]?.close()
          dictionaryBindings = dictionaryBindings - event.language
          Log.d("TranslatorApp", "Dictionary deleted for language: ${event.language}")
        }
      }
    }
  }

  // Load dictionary bindings for available languages
  LaunchedEffect(languageState.availableLanguageMap) {
    languageState.availableLanguageMap.forEach { (language, availability) ->
      if (availability.dictionaryFiles && !dictionaryBindings.containsKey(language)) {
        openDictionary(
          language,
          filePathManager,
          onSuccess = { tarkkaBinding ->
            dictionaryBindings = dictionaryBindings + (language to tarkkaBinding)
            Log.d("DictionaryLookup", "Loaded existing dictionary for ${language.displayName}")
          },
          onError = { error ->
            Log.w("DictionaryLookup", error)
          },
        )
      }
    }
  }

  // update prefs when deleting
  LaunchedEffect(languageState.availableLanguageMap, settings.defaultTargetLanguage, settings.defaultSourceLanguage) {
    if (!languageState.hasLanguages) {
      return@LaunchedEffect
    }
    // Deleted default target
    if (languageState.availableLanguageMap[settings.defaultTargetLanguage]?.translatorFiles == false) {
      setTo(Language.ENGLISH)
      settingsManager.updateSettings(settings.copy(defaultTargetLanguage = Language.ENGLISH))
    }
    // Deleted default source
    if (languageState.availableLanguageMap[settings.defaultSourceLanguage]?.translatorFiles == false) {
      setFrom(Language.ENGLISH)
      settingsManager.updateSettings(settings.copy(defaultSourceLanguage = Language.ENGLISH))
    }
  }

  // Initialize from language when languages become available
  LaunchedEffect(languageState.availableLanguageMap, settings.defaultTargetLanguage, settings.defaultSourceLanguage) {
    if (!languageState.hasLanguages) {
      return@LaunchedEffect
    }
    val preferredSource = settings.defaultSourceLanguage
    val preferredAvail = languageState.availableLanguageMap[preferredSource]?.translatorFiles == true
    if (from == null) {
      val sourceLanguage =
        if (preferredSource != null && preferredAvail && preferredSource != to) {
          preferredSource
        } else {
          languageStateManager.getFirstAvailableFromLanguage(excluding = to)
        }

      if (sourceLanguage != null) {
        setFrom(sourceLanguage)
      }
    }
  }

  // Auto-translate initial text if provided
  LaunchedEffect(initialText, languageState.availableLanguageMap) {
    if (initialText.isBlank()) {
      return@LaunchedEffect
    }
    currentDetectedLanguage =
      if (!settings.disableCLD) {
        translationCoordinator.detectLanguage(initialText, from)
      } else {
        null
      }
    val translated: TranslationResult?

    if (currentDetectedLanguage != null) {
      if (languageState.availableLanguageMap[currentDetectedLanguage!!]?.translatorFiles == true) {
        setFrom(currentDetectedLanguage!!)
        var actualTo = to
        if (to == currentDetectedLanguage!!) {
          val other = languageStateManager.getFirstAvailableFromLanguage(currentDetectedLanguage!!)
          if (other != null) {
            setTo(other)
            actualTo = other
          }
        }
        translated =
          translationCoordinator.translateText(
            currentDetectedLanguage!!,
            actualTo,
            initialText,
          )
      } else {
        translated = null
      }
    } else {
      translated =
        if (from != null) {
          translationCoordinator.translateText(from!!, to, initialText)
        } else {
          null
        }
    }
    translated?.let {
      output =
        when (it) {
          is TranslationResult.Success -> it.result
          is TranslationResult.Error -> null
        }
    }
  }

  // Reusable translation closure based on input type
  val translateWithLanguages: (Language, Language) -> Unit = { fromLang, toLang ->
    scope.launch {
      when (inputType) {
        InputType.TEXT -> {
          val result = translationCoordinator.translateText(fromLang, toLang, input.trim())
          result?.let {
            output =
              when (it) {
                is TranslationResult.Success -> it.result
                is TranslationResult.Error -> null
              }
          }
        }

        InputType.IMAGE -> {
          originalImage?.let { bm ->
            val result =
              translationCoordinator.translateImageWithOverlay(
                fromLang,
                toLang,
                bm,
              ) { imageTextDetected ->
                input = imageTextDetected.extractedText
              }
            result?.let {
              displayImage = it.correctedBitmap
              output = TranslatedText(it.translatedText, null)
            }
          }
        }
      }
    }
  }

  // Centralized message handler
  val handleMessage: (TranslatorMessage) -> Unit = { message ->
    if (message !is TranslatorMessage.TextInput) {
      Log.d("HandleMessage", "Handle: $message")
    }

    when (message) {
      is TranslatorMessage.TextInput -> {
        input = message.text
        if (settings.showTransliterationOnInput && from != null) {
          inputTransliterated = translationCoordinator.transliterate(message.text, from!!)
        }
      }

      is TranslatorMessage.FromLang -> {
        setFrom(message.language)
      }

      is TranslatorMessage.ToLang -> {
        setTo(message.language)
      }

      is TranslatorMessage.SetImageUri -> {
        val bm = translationCoordinator.correctBitmap(message.uri)
        originalImage = bm
        displayImage = bm
        inputType = InputType.IMAGE
        currentDetectedLanguage = null
        output = null
        if (from != null) {
          scope.launch {
            val result =
              translationCoordinator.translateImageWithOverlay(
                from!!,
                to,
                bm,
              ) { imageTextDetected ->
                input = imageTextDetected.extractedText
              }
            result?.let {
              displayImage = it.correctedBitmap
              output = TranslatedText(it.translatedText, null)
            }
          }
        }
      }

      TranslatorMessage.SwapLanguages -> {
        val oldFrom = from!!
        val oldTo = to
        setFrom(oldTo)
        setTo(oldFrom)
        output = null
      }

      TranslatorMessage.ClearInput -> {
        displayImage = null
        output = null
        input = ""
        inputType = InputType.TEXT
        originalImage = null
        currentDetectedLanguage = null
      }

      is TranslatorMessage.InitializeLanguages -> {
        setFrom(message.from)
        setTo(message.to)
      }

      is TranslatorMessage.ImageTextDetected -> {
        input = message.extractedText
      }

      is TranslatorMessage.DictionaryLookup -> {
        Log.i("DictionaryLookup", "Looking up ${message.str} for ${message.language}")
        val tarkkaBinding = dictionaryBindings[message.language]
        if (tarkkaBinding != null) {
          val res = tarkkaBinding.lookup(message.str.trim())
          // Try both capitalizations if not found -- sometimes capitalization is
          // important, so, if there's a hit, return that
          // basic case is 'monday' (no result) -> 'Monday'
          val foundWord =
            if (res == null) {
              val toggledWord = toggleFirstLetterCase(message.str)
              tarkkaBinding.lookup(toggledWord.trim())
            } else {
              res
            }

          if (foundWord != null) {
            dictionaryWord = foundWord
            dictionaryLookupLanguage = message.language
            dictionaryStack = dictionaryStack + foundWord
          } else {
            Toast.makeText(context, "'${message.str}' not found in ${message.language.code} dictionary", Toast.LENGTH_SHORT).show()
          }
          Log.d("DictionaryLookup", "From lookup got $foundWord")
        } else {
          Toast.makeText(context, "Dictionary for ${message.language.displayName} not available", Toast.LENGTH_SHORT).show()
          Log.w("DictionaryLookup", "No dictionary binding for ${message.language.displayName}")
        }
      }

      is TranslatorMessage.PopDictionary -> {
        if (dictionaryStack.size > 1) {
          dictionaryStack = dictionaryStack.dropLast(1)
          dictionaryWord = dictionaryStack.lastOrNull()
          // can't change lang while changing the stack
        } else {
          dictionaryStack = emptyList()
          dictionaryWord = null
          dictionaryLookupLanguage = null
        }
        Log.d("PopDictionary", "Popped dictionary, stack size: ${dictionaryStack.size}")
      }

      TranslatorMessage.ClearDictionaryStack -> {
        dictionaryStack = emptyList()
        dictionaryWord = null
        dictionaryLookupLanguage = null
        Log.d("ClearDictionaryStack", "Cleared dictionary stack")
      }

      is TranslatorMessage.ChangeLaunchMode -> {
        currentLaunchMode = message.newLaunchMode
        modalVisible = currentLaunchMode == LaunchMode.Normal
        Log.d("ChangeLaunchMode", "Changed launch mode to: ${message.newLaunchMode}")
      }

      TranslatorMessage.ShareTranslatedImage -> {
        scope.launch {
          val di = displayImage
          if (di != null) {
            val imageUri = saveImage(di, context)
            if (imageUri != null) {
              shareImageUri(imageUri, context)
            }
          }
        }
      }
    }
  }

  LaunchedEffect(isTranslating) {
    if (isTranslating || from == null) {
      return@LaunchedEffect
    }
    // don't go in a loop translating forever
    if (translationCoordinator.lastTranslatedInput == input) {
      return@LaunchedEffect
    }
    scope.launch {
      translationCoordinator.translateText(from!!, to, input).let {
        output =
          when (it) {
            is TranslationResult.Success -> it.result
            is TranslationResult.Error -> null
          }
      }
    }
  }

  LaunchedEffect(from, to) {
    if (from != null) {
      translationCoordinator.preloadModel(from!!, to)
    }
  }
  LaunchedEffect(input) {
    // a hack for images
    if (inputType == InputType.IMAGE) {
      return@LaunchedEffect
    }
    // don't check for empty, we may be translating an image
    scope.launch {
      currentDetectedLanguage =
        if (!settings.disableCLD) {
          translationCoordinator.detectLanguage(input, from)
        } else {
          null
        }
    }
    // don't enqueue a new translation if busy; when we stop
    // being busy, then-current state will be translated
    if (isTranslating) {
      return@LaunchedEffect
    }
    if (from != null) {
      translateWithLanguages(from!!, to)
    } else {
      output = null
    }
  }
  LaunchedEffect(from, to) {
    scope.launch {
      currentDetectedLanguage =
        if (!settings.disableCLD) {
          translationCoordinator.detectLanguage(input, from)
        } else {
          null
        }
    }
    // don't enqueue a new translation if busy; when we stop
    // being busy, then-current state will be translated
    if (isTranslating) {
      return@LaunchedEffect
    }
    if (from != null) {
      translateWithLanguages(from!!, to)
    } else {
      output = null
    }
  }

  // Process shared image when component loads
  LaunchedEffect(sharedImageUri.value) {
    val uri = sharedImageUri.value
    if (uri != null) {
      Log.d("SharedImage", "Processing shared image: $uri")
      handleMessage(TranslatorMessage.SetImageUri(uri))
    }
  }
  // Determine start destination based on initial language availability (fixed at first composition)
  val startDestination =
    remember {
      if (languageState.isChecking) {
        "loading"
      } else if (languageState.hasLanguages) {
        "main"
      } else {
        "no_languages"
      }
    }

  // Handle initial navigation from loading screen only
  LaunchedEffect(languageState.isChecking) {
    Log.i("TranslatorApp", "Checking ${languageState.isChecking}")
    val currentRoute = navController.currentDestination?.route

    if (!languageState.isChecking && currentRoute == "loading") {
      // Initial navigation from loading screen only
      val destination = if (languageState.hasLanguages) "main" else "no_languages"
      navController.navigate(destination) {
        popUpTo("loading") { inclusive = true }
      }
    }
  }

  val opacity by animateFloatAsState(
    targetValue = if (modalVisible) 0.4f else 0f,
    animationSpec = tween(300),
    label = "opacity",
  )

  val heightFactor by animateFloatAsState(
    targetValue = if (currentLaunchMode == LaunchMode.Normal) 1f else 0.6f,
    animationSpec = tween(300),
    label = "heightFactor",
  )
  val widthFactor by animateFloatAsState(
    targetValue = if (currentLaunchMode == LaunchMode.Normal) 1f else 0.9f,
    animationSpec = tween(300),
    label = "widthFactor",
  )
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    // Background scrim for modal modes
    if (currentLaunchMode != LaunchMode.Normal) {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(
              androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                .copy(alpha = opacity),
            ).clickable {
              modalVisible = false
              scope.launch {
                kotlinx.coroutines.delay(400) // Wait for animation to complete
                onClose()
              }
            },
      )
    }

    AnimatedVisibility(
      visible = modalVisible,
      enter =
        slideInVertically(
          animationSpec = tween(500, delayMillis = 100),
          initialOffsetY = { fullHeight -> fullHeight },
        ),
      exit =
        slideOutVertically(
          animationSpec = tween(300),
          targetOffsetY = { fullHeight -> (fullHeight * 1.5f).toInt() },
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
            // Simple loading screen while checking languages
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
                  // Only navigate if languages are available
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
                languageStateManager = languageStateManager,
                languageMetadataManager = languageMetadataManager,
                downloadService = currentDownloadService,
              )
            }
          }

          composable("main") {
            // Guard: redirect to no_languages if no languages available (only on initial load)
            if (!languageState.hasLanguages && !languageState.isChecking) {
              LaunchedEffect(Unit) {
                navController.navigate("no_languages") {
                  popUpTo("main") { inclusive = true }
                }
              }
            } else if (from != null) {
              MainScreen(
                // Navigation
                onSettings = { navController.navigate("settings") },
                // Current state (read-only)
                input = input,
                inputTransliteration = inputTransliterated,
                output = output,
                from = from!!,
                to = to,
                detectedLanguage = currentDetectedLanguage,
                displayImage = displayImage,
                isTranslating = translationCoordinator.isTranslating,
                isOcrInProgress = translationCoordinator.isOcrInProgress,
                dictionaryWord = dictionaryWord,
                dictionaryStack = dictionaryStack,
                dictionaryLookupLanguage = dictionaryLookupLanguage,
                // Action requests
                onMessage = handleMessage,
                // System integration
                availableLanguages = languageState.availableLanguageMap,
                languageMetadata = languageMetadata,
                downloadStates = downloadStates,
                settings = settings,
                launchMode = currentLaunchMode,
              )
            }
          }
          composable("language_manager") {
            if (downloadService != null) {
              val curDownloadService = downloadService!!
              val availLangs = languageState.availableLanguageMap.filterValues { it.translatorFiles }.keys
              val installedLanguages = availLangs.filter { it != Language.ENGLISH }.sortedBy { it.displayName }
              val availableLanguages =
                Language.entries
                  .filter { lang ->
                    fromEnglishFiles[lang] != null && !availLangs.contains(lang) && lang != Language.ENGLISH
                  }.sortedBy { it.displayName }
              val dictionaryDownloadStates by curDownloadService.dictionaryDownloadStates.collectAsState()
              val dictionaryIndex by languageStateManager.dictionaryIndex.collectAsState()
              Scaffold(
                modifier =
                  Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding(),
              ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                  TabbedLanguageManagerScreen(
                    context = context,
                    languageStateManager = languageStateManager,
                    languageMetadataManager = languageMetadataManager,
                    installedLanguages = installedLanguages,
                    availableLanguages = availableLanguages,
                    languageAvailabilityState = languageState,
                    downloadStates = downloadStates,
                    dictionaryDownloadStates = dictionaryDownloadStates,
                    dictionaryIndex = dictionaryIndex,
                  )
                }
              }
            }
          }
          composable("settings") {
            SettingsScreen(
              settings = settings,
              languageMetadataManager = languageMetadataManager,
              availableLanguages = (languageState.availableLanguageMap.filterValues { it.translatorFiles }.keys + Language.ENGLISH).toList(),
              onSettingsChange = { newSettings ->
                settingsManager.updateSettings(newSettings)
                // Update current target language if it changed
                if (newSettings.defaultTargetLanguage != settings.defaultTargetLanguage) {
                  setTo(newSettings.defaultTargetLanguage)
                }
                // Refresh language availability if storage location changed
                if (newSettings.useExternalStorage != settings.useExternalStorage) {
                  languageStateManager.refreshLanguageAvailability()
                }
              },
              onManageLanguages = {
                navController.navigate("language_manager")
              },
            )
          }
        }
      }
    }
  }
}
