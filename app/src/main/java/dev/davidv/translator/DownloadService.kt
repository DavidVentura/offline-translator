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

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.max

class TrackingInputStream(
  private val inputStream: InputStream,
  private val size: Long,
  private val onProgress: (Long) -> Unit,
) : InputStream() {
  private var totalBytesRead = 0L
  private var lastReportedBytes = 0L

  override fun read(): Int {
    val byte = inputStream.read()
    if (byte != -1) {
      totalBytesRead++
      checkProgress()
    }
    return byte
  }

  override fun read(
    b: ByteArray,
    off: Int,
    len: Int,
  ): Int {
    val bytesRead = inputStream.read(b, off, len)
    if (bytesRead > 0) {
      totalBytesRead += bytesRead
      checkProgress()
    }
    return bytesRead
  }

  private fun checkProgress() {
    if (size > 0) {
      val currentProgress = totalBytesRead
      val incrementalProgress = currentProgress - lastReportedBytes
      if (incrementalProgress > max(128 * 1024, size / 20)) { // 128KiB or 5%
        onProgress(incrementalProgress)
        lastReportedBytes = currentProgress
      }
    }
  }

  override fun close() {
    onProgress(size - lastReportedBytes)
    inputStream.close()
  }
}

class DownloadService : Service() {
  private val binder = DownloadBinder()
  private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val settingsManager by lazy { SettingsManager(this) }
  private val filePathManager by lazy { FilePathManager(this, settingsManager.settings) }

  private fun getCatalog(): LanguageCatalog? = filePathManager.loadCatalog()

  private val _downloadStates = MutableStateFlow<Map<Language, DownloadState>>(emptyMap())
  val downloadStates: StateFlow<Map<Language, DownloadState>> = _downloadStates

  private val _dictionaryDownloadStates = MutableStateFlow<Map<Language, DownloadState>>(emptyMap())
  val dictionaryDownloadStates: StateFlow<Map<Language, DownloadState>> = _dictionaryDownloadStates

  private val _ttsDownloadStates = MutableStateFlow<Map<Language, DownloadState>>(emptyMap())
  val ttsDownloadStates: StateFlow<Map<Language, DownloadState>> = _ttsDownloadStates

  private val _activeTtsPackIds = MutableStateFlow<Map<Language, String>>(emptyMap())
  val activeTtsPackIds: StateFlow<Map<Language, String>> = _activeTtsPackIds

  private val _queuedTtsPackIds = MutableStateFlow<Map<Language, List<String>>>(emptyMap())
  val queuedTtsPackIds: StateFlow<Map<Language, List<String>>> = _queuedTtsPackIds

  private val _adblockDownloadState = MutableStateFlow(DownloadState())
  val adblockDownloadState: StateFlow<DownloadState> = _adblockDownloadState

  private val _repairDownloadState = MutableStateFlow(DownloadState())
  val repairDownloadState: StateFlow<DownloadState> = _repairDownloadState

  private val _downloadEvents = MutableSharedFlow<DownloadEvent>()
  val downloadEvents: SharedFlow<DownloadEvent> = _downloadEvents.asSharedFlow()

  private val downloadJobs = mutableMapOf<Language, Job>()
  private val dictionaryDownloadJobs = mutableMapOf<Language, Job>()
  private val ttsDownloadJobs = mutableMapOf<Language, Job>()
  private var adblockDownloadJob: Job? = null
  private var repairDownloadJob: Job? = null
  private val fileDownloadLocksGuard = Any()
  private val fileDownloadLocks = mutableMapOf<String, Mutex>()
  private val baseDirPath: String
    get() = filePathManager.currentBaseDir().absolutePath

  companion object {
    fun startDownload(
      context: Context,
      language: Language,
    ) {
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "START_DOWNLOAD"
          putExtra("language_code", language.code)
        }
      context.startService(intent)
    }

    fun cancelDownload(
      context: Context,
      language: Language,
    ) {
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "CANCEL_DOWNLOAD"
          putExtra("language_code", language.code)
        }
      context.startService(intent)
    }

    fun startOcrEngineDownload(
      context: Context,
      language: Language,
      engine: String,
    ) {
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "START_OCR_ENGINE_DOWNLOAD"
          putExtra("language_code", language.code)
          putExtra("engine", engine)
        }
      context.startService(intent)
    }

    fun startOcrEngineDownloads(
      context: Context,
      languages: List<Language>,
      engine: String,
    ) {
      val languageCodes = ArrayList(languages.map { it.code })
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "START_OCR_ENGINE_DOWNLOADS"
          putStringArrayListExtra("language_codes", languageCodes)
          putExtra("engine", engine)
        }
      context.startService(intent)
    }

    fun startOcrEngineUpgrades(
      context: Context,
      languages: List<Language>,
      engine: String,
    ) {
      val languageCodes = ArrayList(languages.map { it.code })
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "START_OCR_ENGINE_UPGRADES"
          putStringArrayListExtra("language_codes", languageCodes)
          putExtra("engine", engine)
        }
      context.startService(intent)
    }

    fun startDictDownload(
      context: Context,
      language: Language,
      dictionaryInfo: DictionaryInfo?,
    ) {
      Log.d("Intent", "Send START_DICT_DOWNLOAD with ${language.code}")
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "START_DICT_DOWNLOAD"
          putExtra("language_code", language.code)
          putExtra("dictionary_size", dictionaryInfo?.size ?: 1000000L)
        }
      context.startService(intent)
    }

    fun cancelDictDownload(
      context: Context,
      language: Language,
    ) {
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "CANCEL_DICT_DOWNLOAD"
          putExtra("language_code", language.code)
        }
      context.startService(intent)
    }

    fun startTtsDownload(
      context: Context,
      language: Language,
      packId: String? = null,
    ) {
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "START_TTS_DOWNLOAD"
          putExtra("language_code", language.code)
          packId?.let { putExtra("pack_id", it) }
        }
      context.startService(intent)
    }

    fun cancelTtsDownload(
      context: Context,
      language: Language,
    ) {
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "CANCEL_TTS_DOWNLOAD"
          putExtra("language_code", language.code)
        }
      context.startService(intent)
    }

    fun startAdblockDownload(context: Context) {
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "START_ADBLOCK_DOWNLOAD"
        }
      context.startService(intent)
    }

    fun cancelAdblockDownload(context: Context) {
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "CANCEL_ADBLOCK_DOWNLOAD"
        }
      context.startService(intent)
    }

    fun fetchCatalog(context: Context) {
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "FETCH_CATALOG"
        }
      context.startService(intent)
    }

    fun startRepairDownload(context: Context) {
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "START_REPAIR_DOWNLOAD"
        }
      context.startService(intent)
    }
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    when (intent?.action) {
      "START_DOWNLOAD" -> {
        val languageCode = intent.getStringExtra("language_code") ?: return START_NOT_STICKY
        val catalog = getCatalog() ?: return START_NOT_STICKY
        val language = catalog.languageByCode(languageCode) ?: return START_NOT_STICKY
        startLanguageDownload(language)
      }

      "CANCEL_DOWNLOAD" -> {
        val languageCode = intent.getStringExtra("language_code") ?: return START_NOT_STICKY
        val catalog = getCatalog() ?: return START_NOT_STICKY
        val language = catalog.languageByCode(languageCode) ?: return START_NOT_STICKY
        cancelLanguageDownload(language)
      }

      "START_OCR_ENGINE_DOWNLOAD" -> {
        val languageCode = intent.getStringExtra("language_code") ?: return START_NOT_STICKY
        val engine = intent.getStringExtra("engine") ?: return START_NOT_STICKY
        val catalog = getCatalog() ?: return START_NOT_STICKY
        val language = catalog.languageByCode(languageCode) ?: return START_NOT_STICKY
        startOcrEngineDownload(language, engine)
      }

      "START_OCR_ENGINE_DOWNLOADS" -> {
        val languageCodes = intent.getStringArrayListExtra("language_codes").orEmpty()
        val engine = intent.getStringExtra("engine") ?: return START_NOT_STICKY
        val catalog = getCatalog() ?: return START_NOT_STICKY
        val languages = languageCodes.mapNotNull(catalog::languageByCode)
        startOcrEngineDownloads(languages, engine)
      }

      "START_OCR_ENGINE_UPGRADES" -> {
        val languageCodes = intent.getStringArrayListExtra("language_codes").orEmpty()
        val engine = intent.getStringExtra("engine") ?: return START_NOT_STICKY
        val catalog = getCatalog() ?: return START_NOT_STICKY
        val languages = languageCodes.mapNotNull(catalog::languageByCode)
        startOcrEngineUpgrades(languages, engine)
      }

      "START_DICT_DOWNLOAD" -> {
        val languageCode = intent.getStringExtra("language_code") ?: return START_NOT_STICKY
        val dictionarySize = intent.getLongExtra("dictionary_size", 1000000L)
        Log.d("onStartCommand", "Dict download for $languageCode")
        val catalog = getCatalog() ?: return START_NOT_STICKY
        val language = catalog.languageByCode(languageCode) ?: return START_NOT_STICKY
        startDictionaryDownload(language, dictionarySize)
      }

      "CANCEL_DICT_DOWNLOAD" -> {
        val languageCode = intent.getStringExtra("language_code") ?: return START_NOT_STICKY
        val catalog = getCatalog() ?: return START_NOT_STICKY
        val language = catalog.languageByCode(languageCode) ?: return START_NOT_STICKY
        cancelDictionaryDownload(language)
      }

      "START_TTS_DOWNLOAD" -> {
        val languageCode = intent.getStringExtra("language_code") ?: return START_NOT_STICKY
        val packId = intent.getStringExtra("pack_id")
        val catalog = getCatalog() ?: return START_NOT_STICKY
        val language = catalog.languageByCode(languageCode) ?: return START_NOT_STICKY
        startTtsDownload(language, packId)
      }

      "CANCEL_TTS_DOWNLOAD" -> {
        val languageCode = intent.getStringExtra("language_code") ?: return START_NOT_STICKY
        val catalog = getCatalog() ?: return START_NOT_STICKY
        val language = catalog.languageByCode(languageCode) ?: return START_NOT_STICKY
        cancelTtsDownload(language)
      }

      "START_ADBLOCK_DOWNLOAD" -> {
        startAdblockDownload()
      }

      "CANCEL_ADBLOCK_DOWNLOAD" -> {
        cancelAdblockDownload()
      }

      "FETCH_CATALOG" -> {
        fetchCatalog()
      }

      "START_REPAIR_DOWNLOAD" -> {
        startRepairDownload()
      }
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent): IBinder = binder

  private fun startPrimaryDownload(
    language: Language,
    actionLabel: String,
    @StringRes failureMessageRes: Int,
    planProvider: (LanguageCatalog) -> DownloadPlan,
    onSuccess: suspend (LanguageCatalog, LanguageCatalog) -> Unit = { _, _ -> },
  ) {
    if (_downloadStates.value[language]?.isDownloading == true) return
    updateDownloadState(language) {
      DownloadState(
        isDownloading = true,
        isCompleted = false,
        downloaded = 1,
      )
    }
    val job =
      serviceScope.launch {
        try {
          val catalog = getCatalog() ?: return@launch
          val downloadPlan = planProvider(catalog)
          val downloadTasks =
            downloadPlan.tasks.map { task ->
              suspend { downloadPackFile(task, language) }
            }

          var success = true
          if (downloadTasks.isNotEmpty()) {
            updateDownloadState(language) {
              it.copy(
                isDownloading = true,
                downloaded = 1,
                totalSize = downloadPlan.totalSize.toLong(),
              )
            }
            Log.i("DownloadService", "Starting $actionLabel for ${language.displayName}")
            val activeJobs = downloadTasks.map { task -> async { task() } }
            success = activeJobs.awaitAll().all { it }
          }
          updateDownloadState(language) {
            DownloadState(
              isDownloading = false,
              isCompleted = success,
            )
          }
          if (success) {
            val refreshedCatalog = filePathManager.reloadCatalog() ?: catalog
            onSuccess(catalog, refreshedCatalog)
            Log.i("DownloadService", "${actionLabel.replaceFirstChar(Char::titlecase)} complete: ${language.displayName}")
            _downloadEvents.emit(DownloadEvent.NewTranslationAvailable(language))
          } else {
            _downloadEvents.emit(DownloadEvent.DownloadError(getString(failureMessageRes, language.displayName)))
          }
        } catch (e: Exception) {
          Log.e("DownloadService", "${actionLabel.replaceFirstChar(Char::titlecase)} failed for ${language.displayName}", e)
          updateDownloadState(language) {
            it.copy(isDownloading = false, error = e.message)
          }
          _downloadEvents.emit(DownloadEvent.DownloadError(getString(failureMessageRes, language.displayName)))
        } finally {
          downloadJobs.remove(language)
        }
      }

    downloadJobs[language] = job
  }

  private fun startLanguageDownload(language: Language) {
    startPrimaryDownload(
      language = language,
      actionLabel = "download",
      failureMessageRes = R.string.download_failed_core,
      planProvider = { catalog ->
        val corePlan = catalog.planDownload(language.code, Feature.CORE) ?: DownloadPlan(0UL, emptyList())
        val docDetectPlan =
          if (catalog.supportInstalledByKind(DOC_DETECT_KIND)) {
            null
          } else {
            catalog.planSupportDownloadByKind(DOC_DETECT_KIND)
          }
        if (docDetectPlan == null) {
          corePlan
        } else {
          DownloadPlan(
            corePlan.totalSize + docDetectPlan.totalSize,
            corePlan.tasks + docDetectPlan.tasks,
          )
        }
      },
    )
  }

  private fun startOcrEngineDownload(
    language: Language,
    engine: String,
  ) {
    startPrimaryDownload(
      language = language,
      actionLabel = "OCR engine download",
      failureMessageRes = R.string.download_failed_ocr,
      planProvider = { catalog ->
        catalog.planOcrEngineDownload(language.code, engine) ?: DownloadPlan(0UL, emptyList())
      },
    )
  }

  private fun startOcrEngineDownloads(
    languages: List<Language>,
    engine: String,
  ) {
    startOcrEngineBatch(languages) { catalog, codes ->
      catalog.planOcrEngineDownloads(codes, engine)
    }
  }

  private fun startOcrEngineUpgrades(
    languages: List<Language>,
    engine: String,
  ) {
    startOcrEngineBatch(
      languages,
      onSuccess = {
        getCatalog()?.let { catalog ->
          filePathManager.applyDeletePlan(catalog.planDeleteSupersededFiles())
        }
      },
    ) { catalog, codes ->
      catalog.planOcrEngineUpgrades(codes, engine)
    }
  }

  private fun startOcrEngineBatch(
    languages: List<Language>,
    onSuccess: suspend () -> Unit = {},
    planProvider: (LanguageCatalog, List<String>) -> DownloadPlan,
  ) {
    val activeLanguages = languages.distinctBy { it.code }.filter { _downloadStates.value[it]?.isDownloading != true }
    if (activeLanguages.isEmpty()) return

    activeLanguages.forEach { language ->
      updateDownloadState(language) {
        DownloadState(
          isDownloading = true,
          isCompleted = false,
          downloaded = 1,
        )
      }
    }

    val job =
      serviceScope.launch {
        try {
          val catalog = getCatalog() ?: return@launch
          val downloadPlan = planProvider(catalog, activeLanguages.map { it.code })
          var success = true
          if (downloadPlan.tasks.isNotEmpty()) {
            activeLanguages.forEach { language ->
              updateDownloadState(language) {
                it.copy(
                  isDownloading = true,
                  downloaded = 1,
                  totalSize = downloadPlan.totalSize.toLong(),
                )
              }
            }
            Log.i(
              "DownloadService",
              "Starting OCR engine batch download for ${activeLanguages.size} languages",
            )
            val activeJobs =
              downloadPlan.tasks.map { task ->
                async {
                  downloadPackFile(task) { incrementalProgress ->
                    activeLanguages.forEach { language ->
                      incrementDownloadBytes(language, incrementalProgress)
                    }
                  }
                }
              }
            success = activeJobs.awaitAll().all { it }
          }

          activeLanguages.forEach { language ->
            updateDownloadState(language) {
              DownloadState(
                isDownloading = false,
                isCompleted = success,
              )
            }
          }
          if (success) {
            filePathManager.reloadCatalog()
            onSuccess()
            Log.i("DownloadService", "OCR engine batch download complete")
            activeLanguages.forEach { language ->
              _downloadEvents.emit(DownloadEvent.NewTranslationAvailable(language))
            }
          } else {
            _downloadEvents.emit(DownloadEvent.DownloadError(getString(R.string.download_failed_ocr_batch)))
          }
        } catch (e: Exception) {
          Log.e("DownloadService", "OCR engine batch download failed", e)
          activeLanguages.forEach { language ->
            updateDownloadState(language) {
              it.copy(isDownloading = false, error = e.message)
            }
          }
          _downloadEvents.emit(DownloadEvent.DownloadError("OCR batch download failed"))
        } finally {
          activeLanguages.forEach(downloadJobs::remove)
        }
      }

    activeLanguages.forEach { language ->
      downloadJobs[language] = job
    }
  }

  private fun startDictionaryDownload(
    language: Language,
    dictionarySize: Long,
  ) {
    if (_dictionaryDownloadStates.value[language]?.isDownloading == true) return
    Log.d("DictionaryDownload", "Starting for $language")
    updateDictionaryDownloadState(language) {
      DownloadState(
        isDownloading = true,
        isCompleted = false,
        downloaded = 1,
      )
    }
    val job =
      serviceScope.launch {
        val catalog = getCatalog() ?: return@launch
        val downloadPlan = catalog.planDownload(language.code, Feature.DICTIONARY) ?: return@launch
        val downloadTasks = mutableListOf<suspend () -> Boolean>()
        val toDownload = if (downloadPlan.tasks.isNotEmpty()) downloadPlan.totalSize.toLong() else dictionarySize

        downloadPlan.tasks.forEach { task ->
          downloadTasks.add {
            downloadPackFile(task, language, incrementDictionary = true)
          }
        }

        var success = true
        if (downloadTasks.isNotEmpty()) {
          updateDictionaryDownloadState(language) {
            it.copy(
              isDownloading = true,
              downloaded = 1,
              totalSize = toDownload,
            )
          }
          Log.i("DownloadService", "Starting dictionary download for ${language.displayName}")
          success = downloadTasks.all { task -> task() }
        }

        updateDictionaryDownloadState(language) {
          DownloadState(
            isDownloading = false,
            isCompleted = success,
          )
        }

        if (success) {
          filePathManager.reloadCatalog()
          Log.i("DownloadService", "Dictionary download complete: ${language.displayName}")
          _downloadEvents.emit(DownloadEvent.NewDictionaryAvailable(language))
        } else {
          _downloadEvents.emit(DownloadEvent.DownloadError(getString(R.string.download_failed_dictionary, language.displayName)))
          Log.e("DownloadService", "Dictionary download failed for ${language.displayName}")
          updateDictionaryDownloadState(language) {
            it.copy(isDownloading = false, error = "Dictionary download failed for ${language.displayName}")
          }
        }

        dictionaryDownloadJobs.remove(language)
      }

    dictionaryDownloadJobs[language] = job
  }

  private fun cancelDictionaryDownload(language: Language) {
    dictionaryDownloadJobs[language]?.cancel()
    dictionaryDownloadJobs.remove(language)

    updateDictionaryDownloadState(language) {
      it.copy(isDownloading = false, isCancelled = true, error = null)
    }

    Log.i("DownloadService", "Cancelled dictionary download for ${language.displayName}")
  }

  private fun startTtsDownload(
    language: Language,
    requestedPackId: String? = null,
  ) {
    if (_ttsDownloadStates.value[language]?.isDownloading == true) {
      val resolvedPackId = requestedPackId ?: getCatalog()?.defaultTtsPackIdForLanguage(language.code) ?: return
      if (_activeTtsPackIds.value[language] == resolvedPackId) return
      _queuedTtsPackIds.update { current ->
        val existing = current[language].orEmpty()
        if (resolvedPackId in existing) current else current + (language to existing + resolvedPackId)
      }
      return
    }
    updateTtsDownloadState(language) {
      DownloadState(
        isDownloading = true,
        isCompleted = false,
        downloaded = 1,
      )
    }
    val job =
      serviceScope.launch {
        try {
          val catalog = getCatalog() ?: return@launch
          val ttsPackId = requestedPackId ?: catalog.defaultTtsPackIdForLanguage(language.code) ?: return@launch
          _activeTtsPackIds.update { it + (language to ttsPackId) }
          val downloadPlan =
            catalog.planDownload(language.code, Feature.TTS, ttsPackId) ?: run {
              Log.w("DownloadService", "Ignoring invalid TTS pack $ttsPackId for ${language.code}")
              return@launch
            }
          val downloadTasks = mutableListOf<suspend () -> Boolean>()
          val toDownload = downloadPlan.totalSize.toLong()

          downloadPlan.tasks.forEach { task ->
            downloadTasks.add {
              downloadPackFile(task, language, incrementTts = true)
            }
          }

          var success = true
          if (downloadTasks.isNotEmpty()) {
            updateTtsDownloadState(language) {
              it.copy(
                isDownloading = true,
                downloaded = 1,
                totalSize = toDownload,
              )
            }
            Log.i("DownloadService", "Starting TTS download for ${language.displayName}")
            success = downloadTasks.all { task -> task() }
          }

          updateTtsDownloadState(language) {
            DownloadState(
              isDownloading = false,
              isCompleted = success,
            )
          }

          if (success) {
            filePathManager.reloadCatalog()
            TranslatorTtsEngine.notifyVoiceDataChanged(this@DownloadService)
            Log.i("DownloadService", "TTS download complete: ${language.displayName}")
            _downloadEvents.emit(DownloadEvent.NewTtsAvailable(language))
          } else {
            _downloadEvents.emit(DownloadEvent.DownloadError(getString(R.string.download_failed_tts, language.displayName)))
            updateTtsDownloadState(language) {
              it.copy(isDownloading = false, error = "TTS download failed for ${language.displayName}")
            }
          }
        } catch (e: Exception) {
          Log.e("DownloadService", "TTS download failed for ${language.displayName}", e)
          updateTtsDownloadState(language) {
            it.copy(isDownloading = false, error = e.message)
          }
          _downloadEvents.emit(DownloadEvent.DownloadError("${language.displayName} TTS download failed"))
        } finally {
          ttsDownloadJobs.remove(language)
          _activeTtsPackIds.update { it - language }
          val nextPackId =
            _queuedTtsPackIds.value[language]?.firstOrNull()?.also {
              _queuedTtsPackIds.update { current ->
                val rest = current[language].orEmpty().drop(1)
                if (rest.isEmpty()) current - language else current + (language to rest)
              }
            }
          if (nextPackId != null) {
            startTtsDownload(language, nextPackId)
          }
        }
      }

    ttsDownloadJobs[language] = job
  }

  private fun cancelTtsDownload(language: Language) {
    ttsDownloadJobs[language]?.cancel()
    ttsDownloadJobs.remove(language)
    _activeTtsPackIds.update { it - language }
    _queuedTtsPackIds.update { it - language }

    updateTtsDownloadState(language) {
      it.copy(isDownloading = false, isCancelled = true, error = null)
    }

    Log.i("DownloadService", "Cancelled TTS download for ${language.displayName}")
  }

  private fun startAdblockDownload() {
    if (_adblockDownloadState.value.isDownloading) return
    updateAdblockDownloadState {
      DownloadState(
        isDownloading = true,
        isCompleted = false,
        downloaded = 1,
      )
    }
    val job =
      serviceScope.launch {
        try {
          val catalog =
            getCatalog() ?: run {
              updateAdblockDownloadState {
                it.copy(isDownloading = false, error = "Catalog unavailable")
              }
              _downloadEvents.emit(DownloadEvent.DownloadError(getString(R.string.download_catalog_unavailable)))
              return@launch
            }
          val downloadPlan =
            catalog.planSupportDownloadByKind(ADBLOCK_KIND) ?: run {
              Log.w("DownloadService", "No adblock support pack in catalog")
              updateAdblockDownloadState {
                it.copy(isDownloading = false, error = "No adblock support pack in catalog")
              }
              return@launch
            }
          var success = true
          if (downloadPlan.tasks.isNotEmpty()) {
            updateAdblockDownloadState {
              it.copy(
                isDownloading = true,
                downloaded = 1,
                totalSize = downloadPlan.totalSize.toLong(),
              )
            }
            Log.i("DownloadService", "Starting adblock download")
            for (task in downloadPlan.tasks) {
              if (!downloadPackFile(task, ::incrementAdblockDownloadBytes)) {
                success = false
              }
            }
          }

          updateAdblockDownloadState {
            DownloadState(
              isDownloading = false,
              isCompleted = success,
            )
          }

          if (success) {
            filePathManager.reloadCatalog()
            (application as? TranslatorApplication)?.adblockManager?.reload()
            Log.i("DownloadService", "Adblock download complete")
            _downloadEvents.emit(DownloadEvent.NewSupportAvailable(ADBLOCK_KIND))
          } else {
            updateAdblockDownloadState {
              it.copy(isDownloading = false, error = "Adblock download failed")
            }
            _downloadEvents.emit(DownloadEvent.DownloadError(getString(R.string.download_failed_adblock)))
          }
        } catch (e: Exception) {
          Log.e("DownloadService", "Adblock download failed", e)
          updateAdblockDownloadState {
            it.copy(isDownloading = false, error = e.message)
          }
          _downloadEvents.emit(DownloadEvent.DownloadError(getString(R.string.download_failed_adblock)))
        } finally {
          adblockDownloadJob = null
        }
      }

    adblockDownloadJob = job
  }

  private fun startRepairDownload() {
    if (_repairDownloadState.value.isDownloading) return
    updateRepairDownloadState {
      DownloadState(
        isDownloading = true,
        isCompleted = false,
        downloaded = 1,
      )
    }
    val job =
      serviceScope.launch {
        try {
          val catalog =
            getCatalog() ?: run {
              updateRepairDownloadState {
                it.copy(isDownloading = false, error = "Catalog unavailable")
              }
              _downloadEvents.emit(DownloadEvent.DownloadError(getString(R.string.download_catalog_unavailable)))
              return@launch
            }
          val downloadPlan = catalog.planRepair()
          var success = true
          if (downloadPlan.tasks.isNotEmpty()) {
            updateRepairDownloadState {
              it.copy(
                isDownloading = true,
                downloaded = 1,
                totalSize = downloadPlan.totalSize.toLong(),
              )
            }
            Log.i("DownloadService", "Starting repair download: ${downloadPlan.tasks.size} files")
            for (task in downloadPlan.tasks) {
              if (!downloadPackFile(task, ::incrementRepairDownloadBytes)) {
                success = false
              }
            }
          }

          updateRepairDownloadState {
            DownloadState(
              isDownloading = false,
              isCompleted = success,
            )
          }

          if (success) {
            filePathManager.reloadCatalog()
            Log.i("DownloadService", "Repair download complete")
            _downloadEvents.emit(DownloadEvent.NewSupportAvailable(REPAIR_KIND))
          } else {
            _downloadEvents.emit(DownloadEvent.DownloadError(getString(R.string.download_failed_repair)))
          }
        } catch (e: Exception) {
          Log.e("DownloadService", "Repair download failed", e)
          updateRepairDownloadState {
            it.copy(isDownloading = false, error = e.message)
          }
          _downloadEvents.emit(DownloadEvent.DownloadError(getString(R.string.download_failed_repair)))
        } finally {
          repairDownloadJob = null
        }
      }

    repairDownloadJob = job
  }

  private fun cancelAdblockDownload() {
    adblockDownloadJob?.cancel()
    adblockDownloadJob = null

    updateAdblockDownloadState {
      it.copy(isDownloading = false, isCancelled = true, error = null)
    }

    Log.i("DownloadService", "Cancelled adblock download")
  }

  private fun cancelLanguageDownload(language: Language) {
    downloadJobs[language]?.cancel()
    downloadJobs.remove(language)

    updateDownloadState(language) {
      it.copy(isDownloading = false, isCancelled = true, error = null)
    }

    Log.i("DownloadService", "Cancelled download for ${language.displayName}")
  }

  private fun updateDownloadState(
    language: Language,
    update: (DownloadState) -> DownloadState,
  ) {
    synchronized(this) {
      val currentStates = _downloadStates.value.toMutableMap()
      val currentState = currentStates[language] ?: DownloadState()
      val newState = update(currentState)
      Log.d(
        "DownloadService",
        "updateDownloadState: ${language.code} thread=${Thread.currentThread().name} before=${currentState.downloaded} after=${newState.downloaded} isDownloading=${newState.isDownloading}",
      )
      currentStates[language] = newState
      _downloadStates.value = currentStates
    }
  }

  private fun incrementDownloadBytes(
    language: Language,
    incrementalBytes: Long,
  ) {
    synchronized(this) {
      val currentStates = _downloadStates.value.toMutableMap()
      val currentState = currentStates[language] ?: DownloadState()
      val newDownloaded = currentState.downloaded + incrementalBytes
      currentStates[language] =
        currentState.copy(
          downloaded = newDownloaded,
        )
      _downloadStates.value = currentStates
    }
  }

  private fun updateDictionaryDownloadState(
    language: Language,
    update: (DownloadState) -> DownloadState,
  ) {
    synchronized(this) {
      val currentStates = _dictionaryDownloadStates.value.toMutableMap()
      val currentState = currentStates[language] ?: DownloadState()
      val newState = update(currentState)
      Log.d(
        "DownloadService",
        "updateDictionaryDownloadState: ${language.code} thread=${Thread.currentThread().name} before=${currentState.downloaded} after=${newState.downloaded} isDownloading=${newState.isDownloading}",
      )
      currentStates[language] = newState
      _dictionaryDownloadStates.value = currentStates
    }
  }

  private fun incrementDictionaryDownloadBytes(
    language: Language,
    incrementalBytes: Long,
  ) {
    synchronized(this) {
      val currentStates = _dictionaryDownloadStates.value.toMutableMap()
      val currentState = currentStates[language] ?: DownloadState()
      val newDownloaded = currentState.downloaded + incrementalBytes
      currentStates[language] =
        currentState.copy(
          downloaded = newDownloaded,
        )
      _dictionaryDownloadStates.value = currentStates
    }
  }

  private fun updateTtsDownloadState(
    language: Language,
    update: (DownloadState) -> DownloadState,
  ) {
    synchronized(this) {
      val currentStates = _ttsDownloadStates.value.toMutableMap()
      val currentState = currentStates[language] ?: DownloadState()
      currentStates[language] = update(currentState)
      _ttsDownloadStates.value = currentStates
    }
  }

  private fun incrementTtsDownloadBytes(
    language: Language,
    incrementalBytes: Long,
  ) {
    synchronized(this) {
      val currentStates = _ttsDownloadStates.value.toMutableMap()
      val currentState = currentStates[language] ?: DownloadState()
      currentStates[language] =
        currentState.copy(
          downloaded = currentState.downloaded + incrementalBytes,
        )
      _ttsDownloadStates.value = currentStates
    }
  }

  private fun updateAdblockDownloadState(update: (DownloadState) -> DownloadState) {
    synchronized(this) {
      _adblockDownloadState.value = update(_adblockDownloadState.value)
    }
  }

  private fun incrementAdblockDownloadBytes(incrementalBytes: Long) {
    updateAdblockDownloadState {
      it.copy(downloaded = it.downloaded + incrementalBytes)
    }
  }

  private fun updateRepairDownloadState(update: (DownloadState) -> DownloadState) {
    synchronized(this) {
      _repairDownloadState.value = update(_repairDownloadState.value)
    }
  }

  private fun incrementRepairDownloadBytes(incrementalBytes: Long) {
    updateRepairDownloadState {
      it.copy(downloaded = it.downloaded + incrementalBytes)
    }
  }

  private suspend fun downloadPackFile(
    task: DownloadTask,
    targetLanguage: Language,
    incrementDictionary: Boolean = false,
    incrementTts: Boolean = false,
  ): Boolean =
    downloadPackFile(task) { incrementalProgress ->
      if (incrementDictionary) {
        incrementDictionaryDownloadBytes(targetLanguage, incrementalProgress)
      } else if (incrementTts) {
        incrementTtsDownloadBytes(targetLanguage, incrementalProgress)
      } else {
        incrementDownloadBytes(targetLanguage, incrementalProgress)
      }
    }

  private suspend fun downloadPackFile(
    task: DownloadTask,
    onProgress: (Long) -> Unit,
  ): Boolean {
    val outputFile = filePathManager.resolveInstallPath(task.installPath)
    val lock = fileDownloadLock(outputFile)
    return lock.withLock {
      if (task.archiveFormat != "zip" && outputFile.exists() && outputFile.length() > 0L) {
        onProgress(task.sizeBytes.toLong())
        Log.i("DownloadService", "Reusing downloaded ${task.packId}:${task.installPath}")
        true
      } else {
        downloadPackFileLocked(task, outputFile, onProgress)
      }
    }
  }

  private fun fileDownloadLock(outputFile: File): Mutex =
    synchronized(fileDownloadLocksGuard) {
      fileDownloadLocks.getOrPut(outputFile.absolutePath) { Mutex() }
    }

  private suspend fun downloadPackFileLocked(
    task: DownloadTask,
    outputFile: File,
    onProgress: (Long) -> Unit,
  ): Boolean {
    val url = task.url
    val extractTo = task.extractTo
    val installMarkerPath = task.installMarkerPath
    val installMarkerVersion = task.installMarkerVersion
    return try {
      val success =
        if (task.archiveFormat == "zip" && extractTo != null) {
          downloadAndExtractZip(
            url = url,
            archiveFile = outputFile,
            extractTo = filePathManager.resolveInstallPath(extractTo),
            deleteAfterExtract = task.deleteAfterExtract,
            installMarkerPath = installMarkerPath,
            onProgress = onProgress,
          ).also { extracted ->
            if (extracted && installMarkerPath != null && installMarkerVersion != null) {
              filePathManager.writeInstallMarker(installMarkerPath, installMarkerVersion)
            }
          }
        } else {
          download(
            url,
            outputFile,
            decompress = task.decompress,
            onProgress = onProgress,
          )
        }
      Log.i("DownloadService", "Downloaded ${task.packId}:${task.installPath} from $url = $success")
      success
    } catch (e: Exception) {
      Log.e("DownloadService", "Failed to download ${task.packId}:${task.installPath} from $url", e)
      false
    }
  }

  private suspend fun download(
    url: String,
    outputFile: File,
    decompress: Boolean = false,
    onProgress: (Long) -> Unit,
  ) = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection()
    val size = conn.contentLengthLong
    val tempFile = File(outputFile.parentFile, "${outputFile.name}.tmp")

    try {
      outputFile.parentFile?.mkdirs()
    } catch (e: Exception) {
      Log.e("DownloadService", "Failed to mkdirs", e)
      return@withContext false
    }
    try {
      conn.getInputStream().use { rawInputStream ->
        val trackingStream =
          TrackingInputStream(rawInputStream, size) { incrementalProgress ->
            onProgress(incrementalProgress)
          }

        tempFile.outputStream().use { output ->
          val processedStream = if (decompress) GZIPInputStream(trackingStream) else trackingStream
          processedStream.use { stream ->
            val buffer = ByteArray(16384)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
              output.write(buffer, 0, bytesRead)
            }
          }
        }
      }

      if (tempFile.renameTo(outputFile)) {
        true
      } else {
        Log.e(
          "DownloadService",
          "Failed to move temp file $tempFile to final location $outputFile",
        )
        tempFile.delete()
        false
      }
    } catch (e: Exception) {
      val operation = if (decompress) "downloading/decompressing" else "downloading"
      Log.e("DownloadService", "Error $operation file from $url to $outputFile: ${e.javaClass.simpleName}: ${e.message}", e)
      if (tempFile.exists()) {
        tempFile.delete()
      }
      if (outputFile.exists()) {
        outputFile.delete()
      }
      false
    }
  }

  private suspend fun downloadAndExtractZip(
    url: String,
    archiveFile: File,
    extractTo: File,
    deleteAfterExtract: Boolean,
    installMarkerPath: String? = null,
    onProgress: (Long) -> Unit,
  ) = withContext(Dispatchers.IO) {
    val downloaded =
      download(
        url = url,
        outputFile = archiveFile,
        decompress = false,
        onProgress = onProgress,
      )
    if (!downloaded) {
      return@withContext false
    }

    try {
      val installRootName =
        installMarkerPath
          ?.let(filePathManager::resolveInstallPath)
          ?.parentFile
          ?.name

      val managedPaths = mutableSetOf<File>()
      ZipFile(archiveFile).use { zipFile ->
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
          val entry = entries.nextElement()
          // Skip entries that would escape extractTo so they cannot influence
          // which existing directories get deleted below (Zip Slip, CWE-22).
          if (safeZipEntryTarget(extractTo, entry.name, installRootName) == null) continue
          val normalizedName = normalizeZipEntryName(entry.name, installRootName)
          val parts = normalizedName.split('/').filter { it.isNotBlank() }
          if (parts.size >= 2) {
            managedPaths += File(extractTo, "${parts[0]}/${parts[1]}")
          } else if (parts.size == 1) {
            managedPaths += File(extractTo, parts[0])
          }
        }
      }
      managedPaths
        .sortedByDescending { it.absolutePath.length }
        .forEach { path ->
          if (path.exists()) {
            path.deleteRecursively()
          }
        }

      ZipInputStream(archiveFile.inputStream().buffered()).use { zipInput ->
        var entry = zipInput.nextEntry
        while (entry != null) {
          val output = safeZipEntryTarget(extractTo, entry.name, installRootName)
          if (output == null) {
            // Zip Slip guard: an entry resolving outside extractTo (via `..`, an
            // absolute path, etc.) is dropped rather than written.
            Log.w("DownloadService", "Skipping unsafe zip entry: ${entry.name}")
          } else if (entry.isDirectory) {
            output.mkdirs()
          } else {
            output.parentFile?.mkdirs()
            output.outputStream().use { stream ->
              val buffer = ByteArray(16384)
              var bytesRead: Int
              while (zipInput.read(buffer).also { bytesRead = it } != -1) {
                stream.write(buffer, 0, bytesRead)
              }
            }
          }
          zipInput.closeEntry()
          entry = zipInput.nextEntry
        }
      }
      if (deleteAfterExtract && archiveFile.exists()) {
        archiveFile.delete()
      }
      true
    } catch (e: Exception) {
      Log.e("DownloadService", "Failed to extract zip $archiveFile", e)
      if (archiveFile.exists()) {
        archiveFile.delete()
      }
      false
    }
  }

  private fun fetchCatalog() {
    serviceScope.launch {
      try {
        val catalogFile = filePathManager.getCatalogFile()
        val url = settingsManager.settings.value.catalogIndexUrl

        catalogFile.parentFile?.mkdirs()
        val tempFile = File(catalogFile.parentFile, "${catalogFile.name}.tmp")

        val conn = URL(url).openConnection()
        conn.getInputStream().use { inputStream ->
          tempFile.outputStream().use { output ->
            val buffer = ByteArray(16384)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
              output.write(buffer, 0, bytesRead)
            }
          }
        }

        if (tempFile.renameTo(catalogFile)) {
          Log.i("DownloadService", "Downloaded catalog from $url to $catalogFile")
          filePathManager.reloadCatalog()
          _downloadEvents.emit(DownloadEvent.CatalogDownloaded)
        } else {
          Log.e("DownloadService", "Failed to move temp catalog file $tempFile to final location $catalogFile")
          tempFile.delete()
          _downloadEvents.emit(DownloadEvent.DownloadError(getString(R.string.download_catalog_save_failed)))
        }
      } catch (e: Exception) {
        Log.e("DownloadService", "Error downloading catalog", e)
        val detail = e.message ?: getString(R.string.download_unknown_error)
        _downloadEvents.emit(DownloadEvent.DownloadError(getString(R.string.download_catalog_failed, detail)))
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    serviceScope.cancel()
    cleanupTempFiles()
  }

  private fun cleanupTempFiles() {
    val binDir = filePathManager.getDataDir()
    if (binDir.exists()) {
      binDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { tempFile ->
        if (tempFile.delete()) {
          Log.d("DownloadService", "Cleaned up temp file: ${tempFile.name}")
        }
      }
    }
  }

  inner class DownloadBinder : Binder() {
    fun getService(): DownloadService = this@DownloadService
  }
}

data class DownloadState(
  val isDownloading: Boolean = false,
  val isCompleted: Boolean = false,
  val isCancelled: Boolean = false,
  val downloaded: Long = 0,
  val totalSize: Long = 1,
  val error: String? = null,
)

private const val ADBLOCK_KIND = "adblock"
private const val DOC_DETECT_KIND = "doc_detect"

// Not a catalog pack kind: the NewSupportAvailable event key for a finished
// repair download, so availability listeners refresh like any support install.
private const val REPAIR_KIND = "repair"

/**
 * Normalizes a zip entry name the way pack extraction expects: it strips a
 * leading `/` or `./` and, when the pack has a known install root
 * ([installRootName]), re-roots the entry under it so archives with or without a
 * top-level directory land in the same place.
 *
 * This is purely a naming transform — it does NOT make the result safe to join
 * onto the extraction directory (it does not neutralise `..` segments). Use
 * [safeZipEntryTarget] to obtain a path that is guaranteed to stay inside the
 * target directory.
 */
internal fun normalizeZipEntryName(
  entryName: String,
  installRootName: String?,
): String {
  val trimmed = entryName.trimStart('/').removePrefix("./")
  if (trimmed.isBlank()) return trimmed
  val rootName = installRootName ?: return trimmed
  return if (trimmed == rootName || trimmed.startsWith("$rootName/")) {
    trimmed
  } else {
    "$rootName/$trimmed"
  }
}

/**
 * Resolves a zip entry to the concrete file it would extract to under
 * [extractTo], or returns `null` when the entry must be skipped because it is
 * blank or because it would resolve outside [extractTo] via `..` segments, an
 * absolute path, or similar.
 *
 * This is the Zip Slip guard (CWE-22): [normalizeZipEntryName] only trims a
 * leading `/`/`./` and, when a root is known, prefixes it — which still lets a
 * name like `../../x` (or `root/../../x`) escape the target. Comparing the
 * canonicalised target against the canonicalised [extractTo] closes that hole so
 * a malicious or corrupt archive can never write (or trigger a delete) outside
 * the intended directory.
 */
internal fun safeZipEntryTarget(
  extractTo: File,
  entryName: String,
  installRootName: String?,
): File? {
  val normalized = normalizeZipEntryName(entryName, installRootName)
  if (normalized.isBlank()) return null
  val target = File(extractTo, normalized)
  val root = extractTo.canonicalFile
  val canonical = target.canonicalFile
  return if (canonical == root || canonical.path.startsWith(root.path + File.separator)) {
    target
  } else {
    null
  }
}
