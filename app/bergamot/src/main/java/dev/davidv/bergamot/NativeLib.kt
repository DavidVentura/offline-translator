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

package dev.davidv.bergamot

import android.util.Log

class NativeLib {
  init {
    Log.d("NativeLib", "Initializing bergamot library")
    initializeService()
  }

  external fun loadModelIntoCache(
    cfg: String,
    key: String,
  )

  external fun translateMultiple(
    inputs: Array<String>,
    key: String,
  ): Array<String>

  external fun translateMultipleWithAlignment(
    inputs: Array<String>,
    key: String,
  ): Array<TranslationWithAlignment>

  external fun pivotMultiple(
    firstKey: String,
    secondKey: String,
    inputs: Array<String>,
  ): Array<String>

  external fun pivotMultipleWithAlignment(
    firstKey: String,
    secondKey: String,
    inputs: Array<String>,
  ): Array<TranslationWithAlignment>

  private external fun initializeService()

  external fun cleanup()

  companion object {
    // Used to load the 'bergamot' library on application startup.
    init {
      Log.d("NativeLib", "Loading bergamot library")
      System.loadLibrary("bergamot-sys")
    }
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

data class DetectionResult(
  val language: String,
  val isReliable: Boolean,
  val confidence: Int,
)

class LangDetect {
  external fun detectLanguage(
    text: String,
    langCode: String?,
  ): DetectionResult
}
