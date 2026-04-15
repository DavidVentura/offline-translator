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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LanguageDetector(
  private val languageByCode: (String) -> Language?,
) {
  private val tag = this.javaClass.name.substringAfterLast('.')
  private val nativeLanguageRuntime = NativeLanguageRuntime()

  private fun languageForCode(code: String?): Language? = code?.let(languageByCode)

  suspend fun detectLanguage(
    text: String,
    fromLang: Language?,
  ): Language? =
    withContext(Dispatchers.IO) {
      if (text.isBlank()) {
        return@withContext null
      }

      val detected = nativeLanguageRuntime.detectLanguage(text, fromLang?.code) ?: return@withContext null
      if (detected.isReliable) {
        languageForCode(detected.language)
      } else {
        null
      }
    }

  suspend fun detectLanguageRobust(
    text: String,
    hint: Language?,
    availableLanguages: List<Language>,
  ): Language? =
    withContext(Dispatchers.IO) {
      Log.d(tag, "detectLanguageRobust: ${hint ?: "null"} | $text")
      languageForCode(
        nativeLanguageRuntime.detectLanguageRobustCode(
          text,
          hint?.code,
          availableLanguages.map { it.code }.toTypedArray(),
        ),
      )
    }
}
