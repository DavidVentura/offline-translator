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
import dev.davidv.bergamot.LangDetect
import dev.davidv.translator.FilePathManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LanguageDetector {
  private val TAG = this.javaClass.name.substringAfterLast('.')

  private val langDetect = LangDetect()

  suspend fun detectLanguage(
    text: String,
    fromLang: Language?,
  ): Language? =
    withContext(Dispatchers.IO) {
      if (text.isBlank()) {
        return@withContext null
      }

      val detected = langDetect.detectLanguage(text, fromLang?.code)
      if (detected.isReliable) {
        Language.entries.firstOrNull { it.code == detected.language }
      } else {
        null
      }
    }

  suspend fun detectLanguageRobust(
    text: String,
    hint: Language?,
    filePathManager: FilePathManager,
  ): Language? =
    withContext(Dispatchers.IO) {
      Log.d(TAG, "detectLanguageRobust: ${hint ?: "null"} | $text")
      val initialDetection = detectLanguage(text, hint)
      if (initialDetection != null) {
        return@withContext initialDetection
      }

      val downloadedLanguages = getDownloadedLanguages(filePathManager.getDataDir())
      for (lang in downloadedLanguages) {
        if (lang == hint) continue // Already tried
        Log.d(TAG, "trying ${lang.code}")
        val detected = langDetect.detectLanguage(text, lang.code)
        if (detected.isReliable) {
          val detectedLang = Language.entries.firstOrNull { it.code == detected.language }
          if (detectedLang == lang) {
            return@withContext lang
          }
        }
      }

      Log.w(TAG, "no reliable detection")
      return@withContext null
    }
}
