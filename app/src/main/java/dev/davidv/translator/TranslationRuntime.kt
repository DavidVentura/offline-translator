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

import android.util.Log

private object NativeBindingsLoader {
  init {
    Log.d("TranslationRuntime", "Loading bindings library")
    System.loadLibrary("bindings")
  }
}

data class TokenAlignment(
  val srcBegin: Int,
  val srcEnd: Int,
  val tgtBegin: Int,
  val tgtEnd: Int,
)

data class TranslationWithAlignment(
  val source: String,
  val target: String,
  val alignments: Array<TokenAlignment>,
)

internal data class DetectionResult(
  val language: String,
  val isReliable: Boolean,
  val confidence: Int,
)

internal class NativeLanguageRuntime {
  init {
    NativeBindingsLoader
  }

  external fun detectLanguage(
    text: String,
    langCode: String?,
  ): DetectionResult?

  external fun detectLanguageRobustCode(
    text: String,
    hintCode: String?,
    availableLanguageCodes: Array<String>,
  ): String?
}
