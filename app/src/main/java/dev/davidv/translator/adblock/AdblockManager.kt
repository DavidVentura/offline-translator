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

package dev.davidv.translator.adblock

import android.util.Log
import dev.davidv.translator.FilePathManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.bindings.AdblockCosmetic
import uniffi.bindings.AdblockEngine
import uniffi.bindings.AdblockRequestType
import uniffi.bindings.AdblockVerdict
import java.io.File

class AdblockManager(
  private val filePathManager: FilePathManager,
) {
  companion object {
    private const val TAG = "AdblockManager"
    private const val CACHE_FILE = "engine.bin"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile
  private var engine: AdblockEngine? = null

  private val _ready = MutableStateFlow(false)
  val ready: StateFlow<Boolean> = _ready.asStateFlow()

  init {
    scope.launch { tryLoad() }
  }

  fun reload() {
    scope.launch {
      clearEngineCache()
      tryLoad()
    }
  }

  private fun clearEngineCache() {
    val cacheFile = File(filePathManager.getAdblockDir(), CACHE_FILE)
    if (cacheFile.exists()) cacheFile.delete()
  }

  private suspend fun tryLoad() =
    withContext(Dispatchers.IO) {
      val dir = filePathManager.getAdblockDir()
      val cacheFile = File(dir, CACHE_FILE)
      val ruleFiles = listRuleFiles(dir)
      Log.i(
        TAG,
        "adblock dir=${dir.absolutePath} engine.exists=${cacheFile.exists()} rules=${ruleFiles.map { it.name }}",
      )

      val loaded =
        loadFromCache(cacheFile)
          ?: loadFromRules(ruleFiles, cacheFile)

      if (loaded != null) {
        engine = loaded
        _ready.value = true
        Log.i(TAG, "adblock engine loaded")
      } else {
        engine = null
        _ready.value = false
        Log.i(TAG, "no adblock rules in ${dir.absolutePath}; pass-through")
      }
    }

  private fun listRuleFiles(dir: File): List<File> =
    dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedBy { it.name } ?: emptyList()

  private fun loadFromCache(cacheFile: File): AdblockEngine? {
    if (!cacheFile.exists()) return null
    return try {
      val t0 = System.currentTimeMillis()
      val bytes = cacheFile.readBytes()
      val readMs = System.currentTimeMillis() - t0
      val t1 = System.currentTimeMillis()
      val eng = AdblockEngine.deserialize(bytes)
      val deserMs = System.currentTimeMillis() - t1
      Log.i(TAG, "cache load: ${bytes.size} bytes read ${readMs}ms, deserialize ${deserMs}ms")
      eng
    } catch (t: Throwable) {
      Log.w(TAG, "cache load failed; will re-parse rules", t)
      cacheFile.delete()
      null
    }
  }

  private fun loadFromRules(
    ruleFiles: List<File>,
    cacheFile: File,
  ): AdblockEngine? {
    if (ruleFiles.isEmpty()) return null
    return try {
      val tRead = System.currentTimeMillis()
      val text =
        buildString {
          for (file in ruleFiles) {
            append(file.readText())
            if (!endsWith('\n')) append('\n')
          }
        }
      val readMs = System.currentTimeMillis() - tRead
      val ruleCount = text.count { it == '\n' }
      val tParse = System.currentTimeMillis()
      val eng = AdblockEngine.fromRules(text)
      val parseMs = System.currentTimeMillis() - tParse
      val tSer = System.currentTimeMillis()
      val serialized =
        runCatching {
          val bytes = eng.serialize()
          cacheFile.parentFile?.mkdirs()
          cacheFile.writeBytes(bytes)
          bytes.size
        }.onFailure { Log.w(TAG, "engine cache write failed", it) }
          .getOrDefault(-1)
      val serMs = System.currentTimeMillis() - tSer
      Log.i(
        TAG,
        "rule parse: ${ruleFiles.size} files, ~$ruleCount lines, read ${readMs}ms, " +
          "compile ${parseMs}ms, serialize+write ${serMs}ms (cache=${serialized}B)",
      )
      eng
    } catch (t: Throwable) {
      Log.w(TAG, "rule parse failed", t)
      null
    }
  }

  fun checkRequest(
    url: String,
    sourceUrl: String,
    requestType: AdblockRequestType,
  ): AdblockVerdict? = engine?.checkRequest(url, sourceUrl, requestType)

  fun cosmeticResources(url: String): AdblockCosmetic? = engine?.cosmeticResources(url)

  fun hiddenClassIdSelectors(
    classes: List<String>,
    ids: List<String>,
    exceptions: List<String>,
  ): List<String> = engine?.hiddenClassIdSelectors(classes, ids, exceptions) ?: emptyList()
}
