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

import android.app.ForegroundServiceStartNotAllowedException
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "downloads"
private const val NOTIFICATION_ID = 1004

// Android drops notification updates posted faster than a few per second.
private const val NOTIFICATION_UPDATE_INTERVAL_MS = 500L

data class ForegroundDownloads(
  val count: Int,
  val done: Long,
  val total: Long,
)

// The catalog index fetch is small and only runs while the Manage screen is open, so it never
// needs to outlive the UI.
fun foregroundDownloads(state: Map<DownloadId, DownloadStatus>): ForegroundDownloads? {
  val inFlight = state.filter { (id, status) -> id != DownloadId.Catalog && status !is DownloadStatus.Failed }
  if (inFlight.isEmpty()) return null
  val running = inFlight.values.filterIsInstance<DownloadStatus.Running>()
  return ForegroundDownloads(inFlight.size, running.sumOf { it.done }, running.sumOf { it.total })
}

// Keeps the process in the foreground while DownloadManager has work; holds no download state.
class DownloadService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var progressJob: Job? = null
  private var lastStartId = 0

  companion object {
    fun start(context: Context) {
      try {
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
      } catch (e: IllegalStateException) {
        failIfStartNotAllowed(context, e)
      }
    }

    private fun failIfStartNotAllowed(
      context: Context,
      error: IllegalStateException,
    ) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || error !is ForegroundServiceStartNotAllowedException) throw error
      Log.w("DownloadService", "Foreground download service start denied", error)
      (context.applicationContext as TranslatorApplication).downloadManager.failForegroundDownloads(
        context.getString(R.string.download_stopped_service_denied),
      )
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  @OptIn(FlowPreview::class)
  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    val downloads = (application as TranslatorApplication).downloadManager
    lastStartId = startId
    ensureChannel()
    try {
      startForegroundCompat(buildNotification(foregroundDownloads(downloads.state.value)))
    } catch (e: IllegalStateException) {
      failIfStartNotAllowed(this, e)
      stopSelf()
      return START_NOT_STICKY
    }
    if (progressJob == null) {
      progressJob =
        scope.launch {
          downloads.state
            .sample(NOTIFICATION_UPDATE_INTERVAL_MS)
            .map(::foregroundDownloads)
            .distinctUntilChanged()
            .collect { progress ->
              if (progress == null) {
                stopForegroundCompat()
                stopSelf(lastStartId)
              } else {
                NotificationManagerCompat.from(this@DownloadService).notify(NOTIFICATION_ID, buildNotification(progress))
              }
            }
        }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  override fun onTimeout(
    startId: Int,
    fgsType: Int,
  ) {
    progressJob?.cancel()
    progressJob = null
    (application as TranslatorApplication).downloadManager.failForegroundDownloads(getString(R.string.download_stopped_service_timeout))
    stopForegroundCompat()
    stopSelf()
  }

  private fun buildNotification(progress: ForegroundDownloads?): Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val builder =
      NotificationCompat
        .Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_translate_button)
        .setContentTitle(getString(R.string.download_notif_title))
        .setContentIntent(openIntent)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
    if (progress == null || progress.total <= 0) {
      return builder.setProgress(0, 0, true).build()
    }
    val permille = (progress.done * 1000 / progress.total).toInt().coerceIn(0, 1000)
    return builder
      .setContentText(getString(R.string.doc_notif_progress_percent, permille / 10))
      .setProgress(1000, permille, false)
      .build()
  }

  private fun startForegroundCompat(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun stopForegroundCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        getString(R.string.download_notif_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = getString(R.string.download_notif_channel_desc)
        setShowBadge(false)
      }
    manager.createNotificationChannel(channel)
  }
}
