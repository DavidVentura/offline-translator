package dev.davidv.translator

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class PiperVoiceFiles(
  val model: File,
  val config: File,
)

class FilePathManager(
  private val context: Context,
  private val settingsFlow: StateFlow<AppSettings>,
) {
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

  fun getDataDir(): File = File(baseDir, "bin")

  fun getTesseractDataDir(): File = File(baseDir, "tesseract/tessdata")

  fun getTesseractDir(): File = File(baseDir, "tesseract")

  fun getDictionariesDir(): File = File(baseDir, "dictionaries")

  fun resolveInstallPath(relativePath: String): File = File(baseDir, relativePath)

  fun getDictionaryFile(language: Language): File = File(getDictionariesDir(), "${language.dictionaryCode}.dict")

  fun getCatalogFile(): File = File(baseDir, "index.json")

  fun getMucabFile(): File = File(getDataDir(), "mucab.bin")

  fun getPiperVoiceFiles(language: Language): PiperVoiceFiles? {
    val configName =
      language.extraFiles.firstOrNull { it.endsWith(".onnx.json") }
        ?: "${language.code}.onnx.json"
    val modelName =
      language.extraFiles.firstOrNull { it.endsWith(".onnx") && !it.endsWith(".onnx.json") }
        ?: configName.removeSuffix(".json")

    val modelFile = File(getDataDir(), modelName)
    val configFile = File(getDataDir(), configName)

    return if (modelFile.exists() && configFile.exists()) {
      PiperVoiceFiles(model = modelFile, config = configFile)
    } else {
      null
    }
  }

  fun getPiperEspeakDataRoot(): File? {
    val dataDir = getDataDir()
    val espeakDataDir = File(dataDir, "espeak-ng-data")
    return dataDir.takeIf { espeakDataDir.exists() }
  }

  fun deleteLanguageFiles(
    language: Language,
    deleteDictionary: Boolean = true,
  ) {
    val dataPath = getDataDir()

    language.toEnglish?.allFiles()?.forEach { modelFile ->
      val file = File(dataPath, modelFile.name)
      if (file.exists() && file.delete()) {
        Log.i("FilePathManager", "Deleted: ${modelFile.name}")
      }
    }

    language.fromEnglish?.allFiles()?.forEach { modelFile ->
      val file = File(dataPath, modelFile.name)
      if (file.exists() && file.delete()) {
        Log.i("FilePathManager", "Deleted: ${modelFile.name}")
      }
    }

    language.extraFiles.forEach { fileName ->
      val file = File(dataPath, fileName)
      if (file.exists() && file.delete()) {
        Log.i("FilePathManager", "Deleted extra file: $fileName")
      }
    }

    // Delete tessdata file
    val tessDataPath = getTesseractDataDir()
    val tessFile = File(tessDataPath, language.tessFilename)
    if (tessFile.exists() && tessFile.delete()) {
      Log.i("FilePathManager", "Deleted: ${tessFile.name}")
    }

    // Delete dictionary file
    if (deleteDictionary) {
      val dictionaryFile = getDictionaryFile(language)
      if (dictionaryFile.exists() && dictionaryFile.delete()) {
        Log.i("FilePathManager", "Deleted: ${dictionaryFile.name}")
      }
    }
  }

  fun deletePackFiles(
    catalog: LanguageCatalog,
    packIds: Set<String>,
  ) {
    packIds.forEach { packId ->
      val pack = catalog.pack(packId) ?: return@forEach
      pack.files.forEach { assetFile ->
        val file = resolveInstallPath(assetFile.installPath)
        if (file.exists() && file.delete()) {
          Log.i("FilePathManager", "Deleted ${assetFile.installPath} from $packId")
        }
      }
    }
  }

  fun loadCatalog(): LanguageCatalog? {
    val diskFile = getCatalogFile()

    if (diskFile.exists()) {
      try {
        return parseAndValidateCatalog(diskFile.readText())
      } catch (e: Exception) {
        Log.w("FilePathManager", "Deleting invalid cached catalog: ${diskFile.absolutePath}", e)
        if (!diskFile.delete()) {
          Log.w("FilePathManager", "Failed to delete invalid cached catalog: ${diskFile.absolutePath}")
        }
      }
    }

    return try {
      val jsonString = context.assets.open("index.json").bufferedReader().readText()
      parseAndValidateCatalog(jsonString)
    } catch (e: Exception) {
      Log.e("FilePathManager", "Error parsing bundled catalog index", e)
      null
    }
  }

  private fun parseAndValidateCatalog(jsonString: String): LanguageCatalog {
    val catalog = parseLanguageCatalog(jsonString)
    require(catalog.formatVersion == 2) {
      "Unsupported catalog formatVersion=${catalog.formatVersion}"
    }
    return catalog
  }
}
