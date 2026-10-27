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

import android.content.Context
import android.util.Log
import dev.davidv.translator.adblock.AdblockManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DownloadManager"

// Caller holds the manager's lock. Detach runs before cancellation so their cleanup
// cannot remove the failure state or a subsequently retried download.
internal fun failForegroundDownloadRuns(
  runs: MutableMap<DownloadId, Job>,
  published: MutableStateFlow<Map<DownloadId, DownloadStatus>>,
  reason: String,
): Boolean {
  val ids = runs.keys.filter { it != DownloadId.Catalog }
  if (ids.isEmpty()) return false
  val jobs = ids.mapNotNull { runs.remove(it) }
  published.value += ids.associateWith { DownloadStatus.Failed(reason) }
  jobs.forEach { it.cancel() }
  return true
}

class DownloadManager(
  private val context: Context,
  private val scope: CoroutineScope,
  private val catalogRepository: CatalogRepository,
  private val filePathManager: FilePathManager,
  private val settingsManager: SettingsManager,
  private val adblockManager: AdblockManager,
) {
  private val installer = PackInstaller(catalogRepository)

  // Guards runs and every state write. A run may only touch its id while it is still the Job
  // registered there, so a cancelled or superseded run can neither publish nor remove a newer one.
  private val lock = Any()
  private val runs = mutableMapOf<DownloadId, Job>()
  private val published = MutableStateFlow<Map<DownloadId, DownloadStatus>>(emptyMap())
  val state: StateFlow<Map<DownloadId, DownloadStatus>> = published.asStateFlow()

  private val failures = MutableSharedFlow<String>()
  val errors: SharedFlow<String> = failures.asSharedFlow()

  // Voices of one language share engine files, so their packs install one at a time; the
  // waiting ones stay Queued.
  private val ttsLanguageLocks = ConcurrentHashMap<String, Mutex>()

  fun enqueue(request: DownloadRequest) {
    synchronized(lock) {
      if (request.id in runs) return
      val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { execute(request) }
      runs[request.id] = job
      published.value += request.id to DownloadStatus.Queued
      job.start()
    }
  }

  fun cancel(id: DownloadId) {
    synchronized(lock) {
      runs.remove(id)?.cancel()
      published.value -= id
    }
    Log.i(TAG, "Cancelled $id")
  }

  // The catalog is owned by the Manage screen, not the dataSync foreground service.
  fun failForegroundDownloads(reason: String) {
    val failed = synchronized(lock) { failForegroundDownloadRuns(runs, published, reason) }
    if (failed) scope.launch { failures.emit(reason) }
  }

  private suspend fun execute(request: DownloadRequest) {
    val run = currentCoroutineContext().job
    try {
      when (request) {
        is DownloadRequest.Tts ->
          ttsLanguageLocks.getOrPut(request.language.code) { Mutex() }.withLock { install(request, run) }
        DownloadRequest.Catalog -> fetchCatalog(run)
        else -> install(request, run)
      }
      Log.i(TAG, "Completed ${request.id}")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Failed ${request.id}", e)
      fail(request.id, run, failureMessage(request, e))
    } finally {
      synchronized(lock) {
        if (runs[request.id] === run) {
          runs.remove(request.id)
          published.value -= request.id
        }
      }
    }
  }

  private suspend fun install(
    request: DownloadRequest,
    run: Job,
  ) {
    val plan = planFor(request, catalogRepository.catalog.value) ?: error("Catalog has nothing to download for $request")
    publish(request.id, run, DownloadStatus.Running(0, plan.totalSize.toLong()))
    installer.installPlan(plan) { bytes -> addProgress(request.id, run, bytes) }
    when (request) {
      is DownloadRequest.Support ->
        when (request.kind) {
          SupportKind.ADBLOCK -> adblockManager.reload()
        }
      else -> Unit
    }
  }

  private fun planFor(
    request: DownloadRequest,
    catalog: LanguageCatalog,
  ): DownloadPlan? =
    when (request) {
      is DownloadRequest.Core -> catalog.planDownload(request.language.code, Feature.CORE)
      is DownloadRequest.Dictionary -> catalog.planDownload(request.language.code, Feature.DICTIONARY)
      is DownloadRequest.Tts -> catalog.planDownload(request.language.code, Feature.TTS, request.packId)
      is DownloadRequest.OcrUpgrade -> catalog.planOcrEngineUpgrades(request.languages.map { it.code }, request.engine)
      is DownloadRequest.TranslationUpgrade -> catalog.planTranslationUpgrades(request.languages.map { it.code })
      is DownloadRequest.TtsUpgrade -> catalog.planTtsUpgrades(request.languages.map { it.code })
      is DownloadRequest.Support -> catalog.planSupportDownloadByKind(request.kind.wire)
      DownloadRequest.Repair -> catalog.planRepair()
      DownloadRequest.Catalog -> error("The catalog index is fetched, not planned")
    }

  private suspend fun fetchCatalog(run: Job) {
    publish(DownloadId.Catalog, run, DownloadStatus.Running(0, 0))
    val url = settingsManager.settings.value.catalogIndexUrl
    installer.downloadFile(url, filePathManager.getCatalogFile())
    Log.i(TAG, "Downloaded catalog from $url")
    catalogRepository.reloadIndex()
  }

  private fun failureMessage(
    request: DownloadRequest,
    error: Exception,
  ): String =
    when (request) {
      is DownloadRequest.Core -> context.getString(R.string.download_failed_core, request.language.displayName)
      is DownloadRequest.Dictionary -> context.getString(R.string.download_failed_dictionary, request.language.displayName)
      is DownloadRequest.Tts -> context.getString(R.string.download_failed_tts, request.language.displayName)
      is DownloadRequest.OcrUpgrade -> context.getString(R.string.download_failed_ocr_batch)
      is DownloadRequest.TranslationUpgrade -> context.getString(R.string.download_failed_translation_batch)
      is DownloadRequest.TtsUpgrade -> context.getString(R.string.download_failed_tts_batch)
      is DownloadRequest.Support ->
        when (request.kind) {
          SupportKind.ADBLOCK -> context.getString(R.string.download_failed_adblock)
        }
      DownloadRequest.Repair -> context.getString(R.string.download_failed_repair)
      DownloadRequest.Catalog ->
        context.getString(R.string.download_catalog_failed, error.message ?: context.getString(R.string.download_unknown_error))
    }

  private fun publish(
    id: DownloadId,
    run: Job,
    status: DownloadStatus,
  ) {
    synchronized(lock) {
      if (runs[id] !== run) return
      published.value += id to status
    }
  }

  private fun addProgress(
    id: DownloadId,
    run: Job,
    bytes: Long,
  ) {
    synchronized(lock) {
      if (runs[id] !== run) return
      val running = published.value.getValue(id) as DownloadStatus.Running
      published.value += id to running.copy(done = running.done + bytes)
    }
  }

  private suspend fun fail(
    id: DownloadId,
    run: Job,
    message: String,
  ) {
    synchronized(lock) {
      if (runs[id] !== run) return
      runs.remove(id)
      published.value += id to DownloadStatus.Failed(message)
    }
    failures.emit(message)
  }
}
