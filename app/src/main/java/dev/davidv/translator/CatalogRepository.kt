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

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.bindings.CatalogHandle
import uniffi.bindings.MigrationJobRecord
import java.io.File

private const val BUNDLED_INDEX_ASSET = "index_v7.json"

private class CatalogIndex(
  val bundledJson: String,
  val diskJson: String?,
  val baseDir: String,
)

// The one session per process. Every install-state change goes through here so the published
// catalog always follows it; the session's warm engines live as long as the process does.
class CatalogRepository private constructor(
  private val context: Context,
  private val filePathManager: FilePathManager,
  private val handle: CatalogHandle,
) {
  // Serializes change+publish so a slower concurrent refresh can't publish rows older than
  // the ones already out.
  private val lock = Any()
  private val published = MutableStateFlow(LanguageCatalog.of(handle))
  val catalog: StateFlow<LanguageCatalog> = published.asStateFlow()

  companion object {
    fun open(
      context: Context,
      filePathManager: FilePathManager,
    ): CatalogRepository {
      val index = readIndex(context, filePathManager)
      val handle = CatalogHandle.open(index.bundledJson, index.diskJson, index.baseDir)
      return CatalogRepository(context, filePathManager, handle)
    }

    private fun readIndex(
      context: Context,
      filePathManager: FilePathManager,
    ): CatalogIndex {
      val baseDir = filePathManager.currentBaseDir()
      // Older catalog formats served under earlier filenames stay served for old
      // app versions, but their on-disk copies are stale once this build moves to
      // a newer one. Delete every superseded index_v*.json, keeping only the one
      // this build reads.
      val currentCatalogName = filePathManager.getCatalogFile().name
      baseDir
        .listFiles { file -> file.name.matches(Regex("index_v\\d+\\.json")) && file.name != currentCatalogName }
        ?.forEach { it.delete() }
      // The kokoro ONNX engine is gone (dropped from v5, unloadable under MNN). Its
      // 92 MB core blob is no longer in any pack, so reclaim it directly here.
      File(baseDir, "bin/kokoro/kokoro-v1.0.int8.onnx").delete()
      val bundledJson = context.assets.open(BUNDLED_INDEX_ASSET).bufferedReader().use { it.readText() }
      val diskJson =
        try {
          filePathManager.getCatalogFile().takeIf(File::exists)?.readText()
        } catch (e: Exception) {
          Log.w("CatalogRepository", "Error reading cached catalog index", e)
          null
        }
      return CatalogIndex(bundledJson, diskJson, baseDir.absolutePath)
    }
  }

  fun reloadIndex() =
    publishAfter {
      val index = readIndex(context, filePathManager)
      handle.replaceCatalog(index.bundledJson, index.diskJson, index.baseDir)
    }

  fun refresh() = publishAfter { handle.refresh() }

  fun applyDeletePlan(plan: DeletePlan) = publishAfter { handle.applyDeletePlan(plan) }

  fun commitInstall(
    staging: StagedInstall,
    deleteAfter: DeletePlan,
  ) = publishAfter {
    staging.moveIntoPlace()
    handle.applyDeletePlan(deleteAfter)
  }

  fun discardMigration(jobs: List<MigrationJobRecord>) = publishAfter { handle.discardMigration(jobs) }

  private inline fun publishAfter(change: () -> Unit) {
    synchronized(lock) {
      change()
      published.value = LanguageCatalog.of(handle)
    }
  }
}
