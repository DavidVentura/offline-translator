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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.zip.GZIPInputStream
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
  private var cachedCatalog: LanguageCatalog? = null

  private fun getCatalog(): LanguageCatalog? {
    cachedCatalog?.let { return it }
    val catalog = filePathManager.loadCatalog()
    cachedCatalog = catalog
    return catalog
  }

  private val _downloadStates = MutableStateFlow<Map<Language, DownloadState>>(emptyMap())
  val downloadStates: StateFlow<Map<Language, DownloadState>> = _downloadStates

  private val _dictionaryDownloadStates = MutableStateFlow<Map<Language, DownloadState>>(emptyMap())
  val dictionaryDownloadStates: StateFlow<Map<Language, DownloadState>> = _dictionaryDownloadStates

  private val _downloadEvents = MutableSharedFlow<DownloadEvent>()
  val downloadEvents: SharedFlow<DownloadEvent> = _downloadEvents.asSharedFlow()

  private val downloadJobs = mutableMapOf<Language, Job>()
  private val dictionaryDownloadJobs = mutableMapOf<Language, Job>()

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

    fun fetchCatalog(context: Context) {
      val intent =
        Intent(context, DownloadService::class.java).apply {
          action = "FETCH_CATALOG"
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

      "FETCH_CATALOG" -> {
        fetchCatalog()
      }
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent): IBinder = binder

  private fun startLanguageDownload(language: Language) {
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
          val resolver = PackResolver(catalog, filePathManager)
          val rootPackIds = catalog.corePackIdsForLanguage(language.code)
          val missingFiles = resolver.missingFiles(rootPackIds)
          val downloadTasks = mutableListOf<suspend () -> Boolean>()
          val toDownload = missingFiles.sumOf { it.file.sizeBytes }

          missingFiles.forEach { missing ->
            downloadTasks.add {
              downloadPackFile(catalog, missing.pack, missing.file, language)
            }
          }

          var success = true
          if (downloadTasks.isNotEmpty()) {
            updateDownloadState(language) {
              it.copy(
                isDownloading = true,
                downloaded = 1,
                totalSize = toDownload,
              )
            }
            Log.i("DownloadService", "Starting ${downloadTasks.count()} download jobs")
            val downloadJobs = downloadTasks.map { task -> async { task() } }
            success = downloadJobs.awaitAll().all { it }
          }
          updateDownloadState(language) {
            DownloadState(
              isDownloading = false,
              isCompleted = success,
            )
          }
          if (success) {
            Log.i("DownloadService", "Download complete: ${language.displayName}")
            _downloadEvents.emit(DownloadEvent.NewTranslationAvailable(language))
          } else {
            _downloadEvents.emit(DownloadEvent.DownloadError("${language.displayName} download failed"))
          }
        } catch (e: Exception) {
          Log.e("DownloadService", "Download failed for ${language.displayName}", e)
          updateDownloadState(language) {
            it.copy(isDownloading = false, error = e.message)
          }
          _downloadEvents.emit(DownloadEvent.DownloadError("${language.displayName} download failed"))
        } finally {
          downloadJobs.remove(language)
        }
      }

    downloadJobs[language] = job
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
        val resolver = PackResolver(catalog, filePathManager)
        val dictPackId = catalog.dictionaryPackIdForLanguage(language.code) ?: return@launch
        val missingFiles = resolver.missingFiles(setOf(dictPackId))
        val downloadTasks = mutableListOf<suspend () -> Boolean>()
        val toDownload = if (missingFiles.isNotEmpty()) missingFiles.sumOf { it.file.sizeBytes } else dictionarySize

        missingFiles.forEach { missing ->
          downloadTasks.add {
            downloadPackFile(catalog, missing.pack, missing.file, language, incrementDictionary = true)
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
          Log.i("DownloadService", "Dictionary download complete: ${language.displayName}")
          _downloadEvents.emit(DownloadEvent.NewDictionaryAvailable(language))
        } else {
          _downloadEvents.emit(DownloadEvent.DownloadError("${language.displayName} dictionary download failed"))
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

  private suspend fun downloadPackFile(
    catalog: LanguageCatalog,
    pack: AssetPackV2,
    file: AssetFileV2,
    targetLanguage: Language,
    incrementDictionary: Boolean = false,
  ): Boolean {
    val outputFile = filePathManager.resolveInstallPath(file.installPath)
    val url = catalog.packDownloadUrl(pack, file, settingsManager.settings.value)
    return try {
      val success =
        download(
          url,
          outputFile,
          decompress = catalog.shouldDecompress(pack, file),
        ) { incrementalProgress ->
          if (incrementDictionary) {
            incrementDictionaryDownloadBytes(targetLanguage, incrementalProgress)
          } else {
            incrementDownloadBytes(targetLanguage, incrementalProgress)
          }
        }
      Log.i("DownloadService", "Downloaded ${pack.id}:${file.installPath} from $url = $success")
      success
    } catch (e: Exception) {
      Log.e("DownloadService", "Failed to download ${pack.id}:${file.installPath} from $url", e)
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

  private fun fetchCatalog() {
    serviceScope.launch {
      try {
        val catalogFile = filePathManager.getCatalogFile()
        val url = "${Constants.DEFAULT_CATALOG_INDEX_BASE_URL}/${Constants.CATALOG_INDEX_VERSION}/index.json"

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
          cachedCatalog = filePathManager.loadCatalog()
          cachedCatalog?.let { catalog ->
            _downloadEvents.emit(DownloadEvent.CatalogDownloaded(catalog))
          }
        } else {
          Log.e("DownloadService", "Failed to move temp catalog file $tempFile to final location $catalogFile")
          tempFile.delete()
          _downloadEvents.emit(DownloadEvent.DownloadError("Failed to save catalog"))
        }
      } catch (e: Exception) {
        Log.e("DownloadService", "Error downloading catalog", e)
        val errorMessage = "Failed to download catalog: ${e.message ?: "Unknown error"}"
        _downloadEvents.emit(DownloadEvent.DownloadError(errorMessage))
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

    val tessDir = filePathManager.getTesseractDataDir()
    if (tessDir.exists()) {
      tessDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { tempFile ->
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
