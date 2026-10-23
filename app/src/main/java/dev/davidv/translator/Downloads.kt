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

enum class SupportKind(
  val wire: String,
) {
  ADBLOCK("adblock"),
}

sealed interface DownloadId {
  data class Core(
    val language: Language,
  ) : DownloadId

  data class Dictionary(
    val language: Language,
  ) : DownloadId

  data class Tts(
    val language: Language,
    val packId: String,
  ) : DownloadId

  data class OcrUpgrade(
    val engine: String,
  ) : DownloadId

  data object TranslationUpgrade : DownloadId

  data object TtsUpgrade : DownloadId

  data class Support(
    val kind: SupportKind,
  ) : DownloadId

  data object Repair : DownloadId

  data object Catalog : DownloadId
}

sealed interface DownloadRequest {
  val id: DownloadId

  data class Core(
    val language: Language,
  ) : DownloadRequest {
    override val id: DownloadId get() = DownloadId.Core(language)
  }

  data class Dictionary(
    val language: Language,
  ) : DownloadRequest {
    override val id: DownloadId get() = DownloadId.Dictionary(language)
  }

  data class Tts(
    val language: Language,
    val packId: String,
  ) : DownloadRequest {
    override val id: DownloadId get() = DownloadId.Tts(language, packId)
  }

  data class OcrUpgrade(
    val languages: List<Language>,
    val engine: String,
  ) : DownloadRequest {
    override val id: DownloadId get() = DownloadId.OcrUpgrade(engine)
  }

  data class TranslationUpgrade(
    val languages: List<Language>,
  ) : DownloadRequest {
    override val id: DownloadId get() = DownloadId.TranslationUpgrade
  }

  data class TtsUpgrade(
    val languages: List<Language>,
  ) : DownloadRequest {
    override val id: DownloadId get() = DownloadId.TtsUpgrade
  }

  data class Support(
    val kind: SupportKind,
  ) : DownloadRequest {
    override val id: DownloadId get() = DownloadId.Support(kind)
  }

  data object Repair : DownloadRequest {
    override val id: DownloadId get() = DownloadId.Repair
  }

  data object Catalog : DownloadRequest {
    override val id: DownloadId get() = DownloadId.Catalog
  }
}

// A finished download has no status: the catalog is what says it is installed.
sealed interface DownloadStatus {
  data object Queued : DownloadStatus

  data class Running(
    val done: Long,
    val total: Long,
  ) : DownloadStatus {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
  }

  data class Failed(
    val reason: String,
  ) : DownloadStatus
}

val DownloadStatus?.inFlightProgress: Float?
  get() =
    when (this) {
      DownloadStatus.Queued -> 0f
      is DownloadStatus.Running -> fraction
      is DownloadStatus.Failed, null -> null
    }
