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

object TransliterationService {
  private val binding = TransliterateBinding()

  private fun shouldTransliterate(
    language: Language,
    targetScript: String = "Latn",
  ): Boolean = language.script != targetScript

  fun transliterate(
    text: String,
    language: Language,
    targetScript: String = "Latn",
    mucabBinding: MucabBinding? = null,
    japaneseSpaced: Boolean,
  ): String? {
    if (!shouldTransliterate(language, targetScript)) {
      return null
    }

    return try {
      if (language == Language.JAPANESE) {
        transliterateJapanese(text, mucabBinding, japaneseSpaced)
      } else {
        binding.transliterate(text, language.script)
      }
    } catch (e: Exception) {
      Log.w("TransliterationService", "Failed to transliterate text for $language", e)
      null
    }
  }

  private fun transliterateJapanese(
    text: String,
    mucabBinding: MucabBinding?,
    spaced: Boolean,
  ): String? {
    var toTransliterate = text
    if (mucabBinding != null && mucabBinding.isOpen()) {
      val res = mucabBinding.transliterateJP(text, spaced)
      if (res != null) {
        toTransliterate = res
      }
    }
    return binding.transliterate(toTransliterate, "Jpan")
  }
}
