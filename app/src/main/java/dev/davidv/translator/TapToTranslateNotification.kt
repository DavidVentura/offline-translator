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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object TapToTranslateNotification {
  const val CHANNEL_ID = "tap_to_translate"
  const val NOTIFICATION_ID = 1001
  const val ACTION_OPEN_POPUP = "dev.davidv.translator.OPEN_POPUP"

  fun show(context: Context) {
    ensureChannel(context)

    val tapIntent =
      Intent(context, ProcessTextActivity::class.java).apply {
        action = ACTION_OPEN_POPUP
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val tapPendingIntent =
      PendingIntent.getActivity(
        context,
        0,
        tapIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val dismissIntent = Intent(context, TapToTranslateDismissReceiver::class.java)
    val dismissPendingIntent =
      PendingIntent.getBroadcast(
        context,
        0,
        dismissIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_translate_button)
        .setContentTitle(context.getString(R.string.tap_to_translate_title))
        .setContentText(context.getString(R.string.tap_to_translate_body))
        .setContentIntent(tapPendingIntent)
        .setDeleteIntent(dismissPendingIntent)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
  }

  fun hide(context: Context) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        context.getString(R.string.tap_to_translate_channel),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = context.getString(R.string.tap_to_translate_channel_description)
        setShowBadge(false)
      }
    nm.createNotificationChannel(channel)
  }
}

class TapToTranslateDismissReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    val app = context.applicationContext as TranslatorApplication
    app.settingsManager.update { it.copy(tapToTranslateEnabled = false) }
  }
}
