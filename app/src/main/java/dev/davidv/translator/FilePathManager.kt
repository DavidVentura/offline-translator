package dev.davidv.translator

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

class FilePathManager(
  private val context: Context,
  private val settingsFlow: StateFlow<AppSettings>,
) {
  private val catalogLock = Any()

  // Kotlin treats LanguageCatalog as an immutable snapshot.
  // FilePathManager owns the current cached snapshot reference and swaps it on reload.
  private var cachedCatalog: LanguageCatalog? = null
  private var cachedCatalogBaseDir: String? = null

  private val baseDir: File
    get() =
      if (settingsFlow.value.useExternalStorage) {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
          val ext = context.getExternalFilesDir(null)
          ext ?: context.filesDir
        } else {
          val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
          File(documentsDir, "dev.davidv.translator").also { dir ->
            if (!dir.exists()) {
              dir.mkdirs()
            }
          }
        }
      } else {
        context.filesDir
      }

  fun currentBaseDir(): File = baseDir

  fun getDataDir(): File = File(baseDir, "bin")

  fun getTesseractDataDir(): File = File(baseDir, "tesseract/tessdata")

  fun getTesseractDir(): File = File(baseDir, "tesseract")

  fun getDictionariesDir(): File = File(baseDir, "dictionaries")

  fun getAdblockDir(): File = File(baseDir, "adblock")

  fun resolveInstallPath(relativePath: String): File = File(baseDir, relativePath)

  fun getDictionaryFile(language: Language): File = File(getDictionariesDir(), "${language.dictionaryCode}.dict")

  fun getCatalogFile(): File = File(baseDir, "index.json")

  fun getMucabFile(): File = File(getDataDir(), "mucab.bin")

  fun hasInstallMarker(
    relativePath: String,
    expectedVersion: Int,
  ): Boolean {
    val markerFile = resolveInstallPath(relativePath)
    if (!markerFile.exists()) return false
    return try {
      val root = JSONObject(markerFile.readText())
      root.optInt("version", -1) == expectedVersion
    } catch (_: Exception) {
      false
    }
  }

  fun writeInstallMarker(
    relativePath: String,
    version: Int,
  ) {
    val markerFile = resolveInstallPath(relativePath)
    markerFile.parentFile?.mkdirs()
    markerFile.writeText(
      JSONObject()
        .put("version", version)
        .toString(),
    )
    invalidateCatalog()
  }

  fun applyDeletePlan(plan: DeletePlan) {
    plan.directoryPaths.forEach { relativePath ->
      val directory = resolveInstallPath(relativePath)
      if (directory.exists() && directory.deleteRecursively()) {
        Log.i("FilePathManager", "Deleted directory $relativePath")
      }
    }

    plan.filePaths.forEach { relativePath ->
      val file = resolveInstallPath(relativePath)
      if (file.exists() && file.delete()) {
        Log.i("FilePathManager", "Deleted file $relativePath")
      }
    }

    invalidateCatalog()
  }

  fun loadCatalog(): LanguageCatalog? {
    val baseDirPath = currentBaseDir().absolutePath
    synchronized(catalogLock) {
      cachedCatalog?.takeIf { cachedCatalogBaseDir == baseDirPath }?.let { return it }
      val catalog = openCatalog(baseDirPath)
      replaceCachedCatalogLocked(catalog, baseDirPath)
      return catalog
    }
  }

  fun reloadCatalog(): LanguageCatalog? =
    synchronized(catalogLock) {
      val baseDirPath = currentBaseDir().absolutePath
      val catalog = openCatalog(baseDirPath)
      replaceCachedCatalogLocked(catalog, baseDirPath)
      catalog
    }

  fun invalidateCatalog() {
    synchronized(catalogLock) {
      replaceCachedCatalogLocked(null, null)
    }
  }

  private fun openCatalog(baseDirPath: String): LanguageCatalog? {
    val bundledJson =
      try {
        context.assets.open("index.json").bufferedReader().readText()
      } catch (e: Exception) {
        Log.e("FilePathManager", "Error reading bundled catalog index", e)
        null
      }

    val diskJson =
      try {
        getCatalogFile().takeIf(File::exists)?.readText()
      } catch (e: Exception) {
        Log.w("FilePathManager", "Error reading cached catalog index", e)
        null
      }

    val catalog =
      try {
        when {
          bundledJson != null -> LanguageCatalog.open(bundledJson, diskJson, baseDirPath)
          diskJson != null -> LanguageCatalog.open(diskJson, null, baseDirPath)
          else -> null
        }
      } catch (e: Exception) {
        Log.e("FilePathManager", "Error loading catalog index", e)
        null
      }

    if (catalog == null) {
      Log.e("FilePathManager", "No valid catalog found")
    }
    return catalog
  }

  private fun replaceCachedCatalogLocked(
    newCatalog: LanguageCatalog?,
    baseDirPath: String?,
  ) {
    if (cachedCatalog === newCatalog && cachedCatalogBaseDir == baseDirPath) return
    cachedCatalog = newCatalog
    cachedCatalogBaseDir = baseDirPath
  }
}
