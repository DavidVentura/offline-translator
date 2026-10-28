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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import uniffi.bindings.PackInstallation
import uniffi.translator.DownloadEncoding
import uniffi.translator.StageTarget
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.math.max

private const val TAG = "PackInstaller"
private const val COPY_BUFFER_BYTES = 16 * 1024
private const val MIN_PROGRESS_STEP_BYTES = 128L * 1024

// Parallel downloads create shared staging parents at the same moment, and File.mkdirs on Android 6
// gives up when another thread wins that race, leaving the leaf uncreated.
private fun ensureDirectory(dir: File) {
  if (dir.isDirectory) return
  dir.parentFile?.let(::ensureDirectory)
  if (!dir.mkdir() && !dir.isDirectory) throw IOException("Failed to create directory $dir")
}

// The catalog session owns staging and commit; this only fetches bytes into the targets it hands out.
class PackInstaller(
  private val catalogRepository: CatalogRepository,
) {
  // Plans can share files (every TTS voice depends on the espeak pack). Each file is fetched by
  // the first in-flight run that needs it; later runs wait for that run to finish and then find
  // it installed. Waits only ever point at earlier runs, so they can't form a cycle.
  private val fileOwners = mutableMapOf<String, Job>()

  init {
    catalogRepository.discardAbandonedInstalls()
  }

  suspend fun installPlan(
    plan: DownloadPlan,
    onProgress: (Long) -> Unit,
  ) {
    val installation = withContext(Dispatchers.IO) { catalogRepository.beginInstall(plan) }
    val done = Job()
    val earlierOwners =
      synchronized(fileOwners) {
        plan.tasks.associateWith { task ->
          val owner = fileOwners[task.installPath]
          if (owner == null) fileOwners[task.installPath] = done
          owner
        }
      }
    try {
      coroutineScope {
        earlierOwners
          .map { (task, owner) ->
            async {
              // join rather than await: the owner failing or being cancelled must not end this run;
              // install then fetches the file itself.
              owner?.join()
              install(task, installation, onProgress)
            }
          }.awaitAll()
      }
      withContext(Dispatchers.IO) { catalogRepository.commitInstall(installation) }
    } finally {
      synchronized(fileOwners) { fileOwners.values.removeAll { it === done } }
      done.complete()
      installation.close()
    }
  }

  // Reports deltas through onProgress, adding up to exactly task.sizeBytes once the task lands.
  private suspend fun install(
    task: DownloadTask,
    installation: PackInstallation,
    onProgress: (Long) -> Unit,
  ) {
    val expected = task.sizeBytes.toLong()
    if (catalogRepository.catalog.value.downloadTaskInstalled(task)) {
      Log.i(TAG, "Reusing installed ${task.packId}:${task.installPath}")
      onProgress(expected)
      return
    }
    val reported =
      when (val target = withContext(Dispatchers.IO) { installation.stageTarget(task) }) {
        is StageTarget.File -> download(task.url, File(target.path), target.encoding, expected, onProgress)
        is StageTarget.Zip -> {
          val archive = File(target.archivePath)
          val reported = download(task.url, archive, DownloadEncoding.IDENTITY, expected, onProgress)
          unzip(archive, File(target.unpackDir))
          reported
        }
      }
    withContext(Dispatchers.IO) { installation.markStaged(task) }
    onProgress(max(0L, expected - reported))
    Log.i(TAG, "Staged ${task.packId}:${task.installPath} from ${task.url}")
  }

  suspend fun downloadFile(
    url: String,
    target: File,
  ) {
    download(url, target, DownloadEncoding.IDENTITY, expectedBytes = 0L) {}
  }

  // Returns the bytes reported through onProgress, capped at expectedBytes when it is known.
  private suspend fun download(
    url: String,
    target: File,
    encoding: DownloadEncoding,
    expectedBytes: Long,
    onProgress: (Long) -> Unit,
  ): Long =
    withContext(Dispatchers.IO) {
      target.parentFile?.let(::ensureDirectory)
      val tempFile = File(target.parentFile, "${target.name}.tmp")
      try {
        val connection = URL(url).openConnection()
        val progress = ProgressReporter(expectedBytes, onProgress)
        connection.getInputStream().use { raw ->
          val counted = CountingInputStream(raw, progress::advance)
          val decoded = if (encoding == DownloadEncoding.GZIP) GZIPInputStream(counted) else counted
          decoded.use { input ->
            tempFile.outputStream().use { output ->
              val buffer = ByteArray(COPY_BUFFER_BYTES)
              while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
              }
            }
          }
        }
        progress.flush()
        if (!tempFile.renameTo(target)) {
          throw IOException("Failed to move $tempFile to $target")
        }
        progress.reported
      } finally {
        tempFile.delete()
      }
    }

  // ZipInputStream takes entry names as given, so a name like ../x would land outside unpackDir.
  private suspend fun unzip(
    archive: File,
    unpackDir: File,
  ) = withContext(Dispatchers.IO) {
    val root = unpackDir.canonicalFile
    ZipInputStream(archive.inputStream().buffered()).use { input ->
      var entry = input.nextEntry
      while (entry != null) {
        ensureActive()
        val output = File(root, entry.name).canonicalFile
        if (!output.path.startsWith(root.path + File.separator)) {
          throw IOException("Zip entry ${entry.name} escapes $root")
        }
        if (entry.isDirectory) {
          ensureDirectory(output)
        } else {
          output.parentFile?.let(::ensureDirectory)
          output.outputStream().use { input.copyTo(it, COPY_BUFFER_BYTES) }
        }
        entry = input.nextEntry
      }
    }
  }
}

private class ProgressReporter(
  private val expectedBytes: Long,
  private val onProgress: (Long) -> Unit,
) {
  private val step = max(MIN_PROGRESS_STEP_BYTES, expectedBytes / 20)
  private var read = 0L
  var reported = 0L
    private set

  fun advance(bytes: Int) {
    read += bytes
    if (read - reported > step) flush()
  }

  fun flush() {
    val target = if (expectedBytes > 0) minOf(read, expectedBytes) else read
    if (target <= reported) return
    onProgress(target - reported)
    reported = target
  }
}

private class CountingInputStream(
  private val inputStream: InputStream,
  private val onRead: (Int) -> Unit,
) : InputStream() {
  override fun read(): Int {
    val byte = inputStream.read()
    if (byte != -1) onRead(1)
    return byte
  }

  override fun read(
    b: ByteArray,
    off: Int,
    len: Int,
  ): Int {
    val bytesRead = inputStream.read(b, off, len)
    if (bytesRead > 0) onRead(bytesRead)
    return bytesRead
  }

  override fun close() = inputStream.close()
}
