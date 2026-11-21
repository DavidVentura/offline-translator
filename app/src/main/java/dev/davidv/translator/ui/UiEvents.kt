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

package dev.davidv.translator.ui

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class UiEvent {
  data class ShowToast(val message: String) : UiEvent()

  data class ShareImage(val bitmap: Bitmap) : UiEvent()

  data class ShareAudio(val file: File) : UiEvent()
}

// One-shot UI events (Toast, errors, etc.). Emitting suspends until the screen has taken the
// event, so state transitions hand it to the scope instead of waiting on the collector.
class UiEvents(
  private val scope: CoroutineScope,
) {
  private val events = MutableSharedFlow<UiEvent>()
  val flow: SharedFlow<UiEvent> = events.asSharedFlow()

  fun send(event: UiEvent) {
    scope.launch { events.emit(event) }
  }
}
