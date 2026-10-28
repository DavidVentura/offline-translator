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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Delivering BOOT_COMPLETED starts the process, and the app's settings effects restore the
// tap-to-translate notification at startup. The translation server needs this receiver: handling
// the broadcast is the one time a background process may start a foreground service.
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    val app = context.applicationContext as TranslatorApplication
    TranslationApiService.restore(context, app.settingsManager.settings.value)
  }
}
