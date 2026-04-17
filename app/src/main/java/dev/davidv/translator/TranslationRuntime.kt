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

import uniffi.bindings.detectLanguageRecord
import uniffi.bindings.detectLanguageRobustCodeRecord

internal typealias DetectionResult = uniffi.translator.DetectionResult

internal class NativeLanguageRuntime {
  fun detectLanguage(
    text: String,
    langCode: String?,
  ): DetectionResult? = detectLanguageRecord(text, langCode)

  fun detectLanguageRobustCode(
    text: String,
    hintCode: String?,
    availableLanguageCodes: Array<String>,
  ): String? = detectLanguageRobustCodeRecord(text, hintCode, availableLanguageCodes.toList())
}
