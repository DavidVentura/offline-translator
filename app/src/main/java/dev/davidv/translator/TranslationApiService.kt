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
import uniffi.bindings.CatalogHandle
import uniffi.bindings.HttpServerException
import uniffi.bindings.HttpServerHandle
import uniffi.bindings.HttpServerHost
import uniffi.bindings.startHttpServer
import uniffi.translator.BindAddress
import uniffi.translator.HttpServerConfig
import uniffi.translator.OcrSettings
import uniffi.translator.ServeSettings
import java.io.File

/**
 * Foreground service hosting the native LibreTranslate-compatible HTTP server. It
 * must be foreground because the whole point is to answer requests from other apps
 * while this app is backgrounded, which would otherwise let the process (and its
 * listening socket) be suspended or killed.
 */
class TranslationApiService : Service() {
  private var server: HttpServerHandle? = null
  private var currentConfig: HttpServerConfig? = null

  companion object {
    private const val TAG = "TranslationApiService"
    private const val ACTION_START = "dev.davidv.translator.action.START_TRANSLATION_API"
    private const val ACTION_STOP = "dev.davidv.translator.action.STOP_TRANSLATION_API"
    private const val ACTION_DISABLE = "dev.davidv.translator.action.DISABLE_TRANSLATION_API"
    private const val EXTRA_PORT = "port"
    private const val EXTRA_BIND_MODE = "bind_mode"
    private const val CHANNEL_ID = "translation_api"
    private const val NOTIFICATION_ID = 1003

    fun start(
      context: Context,
      port: Int,
      bindMode: HttpServerBindMode,
    ) {
      val intent =
        Intent(context, TranslationApiService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_PORT, port)
          putExtra(EXTRA_BIND_MODE, bindMode.name)
        }
      ContextCompat.startForegroundService(context, intent)
    }

    fun restore(
      context: Context,
      settings: AppSettings,
    ) {
      if (!settings.httpServerEnabled) return
      start(context, settings.httpServerPort, settings.httpServerBindMode)
    }

    fun stop(context: Context) {
      val intent =
        Intent(context, TranslationApiService::class.java).apply {
          action = ACTION_STOP
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
    if (intent?.action == ACTION_STOP) {
      shutdown()
      return START_NOT_STICKY
    }
    if (intent?.action == ACTION_DISABLE) {
      // The notification's "Disable" flips the setting off (keeping the settings
      // switch and the service in sync), which cascades into stop() via the
      // app's settings effects; only stop directly if it was already off.
      val settingsManager = (application as TranslatorApplication).settingsManager
      if (settingsManager.settings.value.httpServerEnabled) {
        settingsManager.update { it.copy(httpServerEnabled = false) }
      } else {
        shutdown()
      }
      return START_NOT_STICKY
    }
    if (intent?.action != ACTION_START) return START_NOT_STICKY

    val port = intent.getIntExtra(EXTRA_PORT, 5000)
    val bindMode =
      intent.getStringExtra(EXTRA_BIND_MODE)?.let { runCatching { HttpServerBindMode.valueOf(it) }.getOrNull() }
        ?: HttpServerBindMode.LOCALHOST

    ensureChannel()
    startForegroundCompat(runningNotification(hostFor(bindMode), port))

    val app = application as TranslatorApplication
    val config = serverConfig(port, bindMode)
    if (server != null && config == currentConfig) {
      return START_REDELIVER_INTENT
    }

    stopServer()
    val host = hostFor(bindMode)
    try {
      server =
        startHttpServer(
          config,
          object : HttpServerHost {
            override fun catalog(): CatalogHandle? = app.catalogRepository.catalog.value.planarHandle()

            override fun settings(): ServeSettings = serveSettings(app.settingsManager.settings.value)
          },
        )
      currentConfig = config
      Log.i(TAG, "Translation API server listening on $host:$port")
    } catch (e: HttpServerException) {
      Log.e(TAG, "Failed to bind translation API server on $host:$port", e)
      server = null
      NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, errorNotification(port))
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_DETACH)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(false)
      }
      stopSelf()
    }
    return START_REDELIVER_INTENT
  }

  override fun onDestroy() {
    stopServer()
    super.onDestroy()
  }

  private fun shutdown() {
    stopServer()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    stopSelf()
  }

  private fun stopServer() {
    server?.stop()
    server = null
    currentConfig = null
  }

  private fun serverConfig(
    port: Int,
    bindMode: HttpServerBindMode,
  ): HttpServerConfig =
    HttpServerConfig(
      bind = bindMode.toNative(),
      port = port.toUShort(),
      outputDir = File(cacheDir, "translate_file").absolutePath,
    )

  private fun serveSettings(settings: AppSettings): ServeSettings =
    ServeSettings(
      ocr =
        OcrSettings(
          maxImageSize = settings.maxImageSize.toUInt(),
          minConfidence = settings.minConfidence.toUInt(),
          backgroundMode = settings.backgroundMode,
        ),
      translatePdfImages = settings.translatePdfImages,
    )

  private fun hostFor(bindMode: HttpServerBindMode): String =
    when (bindMode) {
      HttpServerBindMode.LOCALHOST -> "127.0.0.1"
      HttpServerBindMode.ALL_INTERFACES -> "0.0.0.0"
    }

  private fun HttpServerBindMode.toNative(): BindAddress =
    when (this) {
      HttpServerBindMode.LOCALHOST -> BindAddress.LOCALHOST
      HttpServerBindMode.ALL_INTERFACES -> BindAddress.ALL_INTERFACES
    }

  private fun startForegroundCompat(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun runningNotification(
    host: String,
    port: Int,
  ): Notification {
    val openIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openPendingIntent =
      PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val disableIntent =
      Intent(this, TranslationApiService::class.java).apply { action = ACTION_DISABLE }
    val disablePendingIntent =
      PendingIntent.getService(this, 1, disableIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_translate_button)
      .setContentTitle(getString(R.string.http_server_notif_running))
      .setContentText(getString(R.string.http_server_notif_text, host, port))
      .setContentIntent(openPendingIntent)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .addAction(0, getString(R.string.http_server_notif_disable), disablePendingIntent)
      .build()
  }

  private fun errorNotification(port: Int): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_translate_button)
      .setContentTitle(getString(R.string.http_server_notif_running))
      .setContentText(getString(R.string.http_server_notif_error, port))
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_ERROR)
      .setAutoCancel(true)
      .build()

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        getString(R.string.http_server_notif_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = getString(R.string.http_server_notif_channel_desc)
        setShowBadge(false)
      }
    nm.createNotificationChannel(channel)
  }
}
