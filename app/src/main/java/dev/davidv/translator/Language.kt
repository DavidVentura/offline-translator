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

data class ModelFile(
  val name: String,
  val sizeBytes: Long,
  val path: String,
)

data class LanguageDirection(
  val model: ModelFile,
  val srcVocab: ModelFile,
  val tgtVocab: ModelFile,
  val lex: ModelFile,
) {
  fun allFiles(): List<ModelFile> = listOf(model, srcVocab, tgtVocab, lex).distinctBy { it.name }

  fun totalSize(): Long = allFiles().sumOf { it.sizeBytes }
}

data class Language(
  val code: String,
  val displayName: String,
  val shortDisplayName: String,
  val tessName: String,
  val script: String,
  val dictionaryCode: String,
  val tessdataSizeBytes: Long,
  val toEnglish: LanguageDirection?,
  val fromEnglish: LanguageDirection?,
  val extraFiles: List<String>,
) {
  val tessFilename: String get() = "$tessName.traineddata"
  val sizeBytes: Long get() = (toEnglish?.totalSize() ?: 0) + (fromEnglish?.totalSize() ?: 0) + tessdataSizeBytes
  val isEnglish: Boolean get() = code == "en"

  override fun equals(other: Any?): Boolean = other is Language && code == other.code

  override fun hashCode(): Int = code.hashCode()

  override fun toString(): String = "Language($code)"
}
