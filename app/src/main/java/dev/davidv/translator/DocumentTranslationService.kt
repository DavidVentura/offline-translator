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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.system.measureTimeMillis

data class DocumentTranslationServiceState(
  val taskId: Long,
  val fileName: String,
  val fileSizeBytes: Long,
  val outputPath: String? = null,
  val errorMessage: String? = null,
  val progressLabel: String = "Preparing file",
  val progressCurrent: Int? = null,
  val progressTotal: Int? = null,
  val progressUnit: String? = null,
) {
  val isTranslating: Boolean
    get() = outputPath == null && errorMessage == null
}

class DocumentTranslationService : Service() {
  private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var translationJob: Job? = null
  private val cancelRequested = AtomicBoolean(false)

  companion object {
    private const val ACTION_START = "dev.davidv.translator.action.START_DOCUMENT_TRANSLATION"
    private const val ACTION_CANCEL = "dev.davidv.translator.action.CANCEL_DOCUMENT_TRANSLATION"
    private const val EXTRA_TASK_ID = "task_id"
    private const val EXTRA_INPUT_PATH = "input_path"
    private const val EXTRA_OUTPUT_PATH = "output_path"
    private const val EXTRA_FILE_NAME = "file_name"
    private const val EXTRA_FILE_SIZE_BYTES = "file_size_bytes"
    private const val EXTRA_FROM_CODE = "from_code"
    private const val EXTRA_TO_CODE = "to_code"
    private const val EXTRA_DELETE_AFTER_LOAD = "delete_after_load"

    private const val CHANNEL_ID = "document_translation"
    private const val NOTIFICATION_ID = 1002

    private val _documentTranslationState = MutableStateFlow<DocumentTranslationServiceState?>(null)
    val documentTranslationState: StateFlow<DocumentTranslationServiceState?> = _documentTranslationState.asStateFlow()

    fun startTranslation(
      context: Context,
      inputPath: String,
      outputPath: String,
      displayName: String,
      sizeBytes: Long,
      from: Language,
      to: Language,
      deleteAfterLoad: Boolean,
    ) {
      val taskId = System.currentTimeMillis()
      _documentTranslationState.value =
        DocumentTranslationServiceState(
          taskId = taskId,
          fileName = displayName,
          fileSizeBytes = sizeBytes,
        )

      val intent =
        Intent(context, DocumentTranslationService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_TASK_ID, taskId)
          putExtra(EXTRA_INPUT_PATH, inputPath)
          putExtra(EXTRA_OUTPUT_PATH, outputPath)
          putExtra(EXTRA_FILE_NAME, displayName)
          putExtra(EXTRA_FILE_SIZE_BYTES, sizeBytes)
          putExtra(EXTRA_FROM_CODE, from.code)
          putExtra(EXTRA_TO_CODE, to.code)
          putExtra(EXTRA_DELETE_AFTER_LOAD, deleteAfterLoad)
        }
      ContextCompat.startForegroundService(context, intent)
    }

    fun dismiss(context: Context) {
      _documentTranslationState.value = null
      NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun cancel(context: Context) {
      _documentTranslationState.value = null
      NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
      val intent =
        Intent(context, DocumentTranslationService::class.java).apply {
          action = ACTION_CANCEL
        }
      context.startService(intent)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    if (intent?.action == ACTION_CANCEL) {
      cancelDocumentTranslation()
      return START_NOT_STICKY
    }
    if (intent?.action != ACTION_START) return START_NOT_STICKY
    val request = intent.toTranslationRequest() ?: return START_NOT_STICKY

    ensureChannel()
    cancelRequested.set(false)
    startForegroundCompat(buildNotification(request.initialState()))

    translationJob?.cancel()
    translationJob =
      serviceScope.launch {
        translateDocument(request)
      }

    return START_NOT_STICKY
  }

  override fun onDestroy() {
    translationJob?.cancel()
    serviceScope.cancel()
    super.onDestroy()
  }

  private suspend fun translateDocument(request: DocumentTranslationRequest) {
    val app = application as TranslatorApplication
    val inputFile = File(request.inputPath)
    try {
      val catalog =
        app.filePathManager.loadCatalog()
          ?: throw IllegalStateException("Catalog unavailable")
      val from = catalog.languageByCode(request.fromCode) ?: throw IllegalStateException("Source language unavailable")
      val to = catalog.languageByCode(request.toCode) ?: throw IllegalStateException("Target language unavailable")
      val availableLanguages =
        catalog.languageRows
          .asSequence()
          .filter { it.availability.translatorFiles }
          .map { it.language }
          .toList()

      updateState(request.taskId) {
        it.copy(progressLabel = "Preparing file", progressCurrent = null, progressTotal = null, progressUnit = null)
      }

      var result: Result<String>? = null
      val elapsedMs =
        measureTimeMillis {
          result =
            app.translationCoordinator.translateDocumentPath(
              inputPath = request.inputPath,
              outputPath = request.outputPath,
              from = from,
              to = to,
              availableLanguages = availableLanguages,
              onProgress = { progress ->
                updateProgress(request.taskId, progress)
              },
              isCancelled = { cancelRequested.get() },
            )
        }
      Log.i(
        "DocumentTranslationService",
        "${if (cancelRequested.get()) "Cancelled" else "Translated"} ${request.fileName} from ${from.code} to ${to.code} in ${elapsedMs}ms",
      )

      if (cancelRequested.get()) return
      if (!coroutineContext.isActive) return

      requireNotNull(result)
        .onSuccess { outputPath ->
          updateState(request.taskId) {
            it.copy(
              outputPath = outputPath,
              errorMessage = null,
              progressLabel = "Translated file",
              progressCurrent = it.progressTotal,
            )
          }
        }.onFailure { error ->
          val message = error.message ?: "Document translation failed"
          updateState(request.taskId) {
            it.copy(errorMessage = message)
          }
        }
    } catch (e: Exception) {
      if (cancelRequested.get()) {
        Log.i("DocumentTranslationService", "Cancelled ${request.fileName}")
        return
      }
      Log.e("DocumentTranslationService", "Document translation failed", e)
      updateState(request.taskId) {
        it.copy(errorMessage = e.message ?: "Document translation failed")
      }
    } finally {
      if (request.deleteAfterLoad) {
        inputFile.delete()
      }
      documentTranslationState.value?.let { state ->
        if (state.taskId == request.taskId) {
          updateNotification(state)
        }
      }
      removeForegroundNotification()
      stopSelf()
    }
  }

  private fun cancelDocumentTranslation() {
    cancelRequested.set(true)
    _documentTranslationState.value = null
    removeForegroundNotification()
  }

  private fun updateProgress(
    taskId: Long,
    progress: DocumentTranslationProgress,
  ) {
    updateState(taskId) { current ->
      when (progress) {
        DocumentTranslationProgress.Preparing ->
          current.copy(
            progressLabel = "Preparing file",
            progressCurrent = null,
            progressTotal = null,
            progressUnit = null,
          )
        is DocumentTranslationProgress.Translating ->
          current.copy(
            progressLabel =
              when (progress.unit) {
                "page" -> "Translating page"
                else -> "Translating block"
              },
            progressCurrent = progress.current,
            progressTotal = progress.total,
            progressUnit = progress.unit,
          )
        DocumentTranslationProgress.Writing ->
          current.copy(
            progressLabel = "Saving translated file",
            progressCurrent = null,
            progressTotal = null,
            progressUnit = null,
          )
      }
    }
  }

  private fun updateState(
    taskId: Long,
    transform: (DocumentTranslationServiceState) -> DocumentTranslationServiceState,
  ) {
    val current = documentTranslationState.value ?: return
    if (current.taskId != taskId) return
    val next = transform(current)
    _documentTranslationState.value = next
    updateNotification(next)
  }

  private fun buildNotification(state: DocumentTranslationServiceState): Notification {
    val openIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openPendingIntent =
      PendingIntent.getActivity(
        this,
        0,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_translate_button)
        .setContentTitle(if (state.isTranslating) "Translating file" else "Translated file")
        .setContentText(notificationText(state))
        .setContentIntent(openPendingIntent)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setOngoing(state.isTranslating)
        .setAutoCancel(!state.isTranslating)
        .setOnlyAlertOnce(true)

    val current = state.progressCurrent
    val total = state.progressTotal
    if (state.isTranslating && current != null && total != null && total > 0) {
      notification.setProgress(total, current.coerceIn(0, total), false)
    }

    return notification.build()
  }

  private fun notificationText(state: DocumentTranslationServiceState): String {
    if (state.errorMessage != null) return state.errorMessage
    if (state.outputPath != null) return File(state.outputPath).name
    val current = state.progressCurrent
    val total = state.progressTotal
    if (current != null && total != null && total > 0) {
      val displayCurrent = if (current < total) current + 1 else current
      val unit =
        when (state.progressUnit) {
          "page" -> "Page"
          else -> "Block"
        }
      return "$unit $displayCurrent/$total"
    }
    return state.progressLabel
  }

  private fun updateNotification(state: DocumentTranslationServiceState) {
    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
  }

  private fun startForegroundCompat(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun removeForegroundNotification() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        "Document translation",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "Shows progress while translating documents"
        setShowBadge(false)
      }
    nm.createNotificationChannel(channel)
  }

  private fun Intent.toTranslationRequest(): DocumentTranslationRequest? {
    val taskId = getLongExtra(EXTRA_TASK_ID, -1L)
    val inputPath = getStringExtra(EXTRA_INPUT_PATH)
    val outputPath = getStringExtra(EXTRA_OUTPUT_PATH)
    val fileName = getStringExtra(EXTRA_FILE_NAME)
    val fromCode = getStringExtra(EXTRA_FROM_CODE)
    val toCode = getStringExtra(EXTRA_TO_CODE)
    if (taskId < 0 || inputPath == null || outputPath == null || fileName == null || fromCode == null || toCode == null) {
      return null
    }
    return DocumentTranslationRequest(
      taskId = taskId,
      inputPath = inputPath,
      outputPath = outputPath,
      fileName = fileName,
      fileSizeBytes = getLongExtra(EXTRA_FILE_SIZE_BYTES, -1L),
      fromCode = fromCode,
      toCode = toCode,
      deleteAfterLoad = getBooleanExtra(EXTRA_DELETE_AFTER_LOAD, false),
    )
  }

  private fun DocumentTranslationRequest.initialState(): DocumentTranslationServiceState =
    DocumentTranslationServiceState(
      taskId = taskId,
      fileName = fileName,
      fileSizeBytes = fileSizeBytes,
    )
}

private data class DocumentTranslationRequest(
  val taskId: Long,
  val inputPath: String,
  val outputPath: String,
  val fileName: String,
  val fileSizeBytes: Long,
  val fromCode: String,
  val toCode: String,
  val deleteAfterLoad: Boolean,
)
