package dev.davidv.translator

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

data class TtsVoiceFiles(
  val engine: String,
  val model: File,
  val aux: File,
  val languageCode: String,
  val speakerId: Int? = null,
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
  }

  fun getTtsVoiceFiles(language: Language): TtsVoiceFiles? {
    val catalog = loadCatalog() ?: return null
    val resolver = PackResolver(catalog, this)
    val voicePackId = catalog.installedTtsPackIdForLanguage(language.code, resolver::isInstalled) ?: return null
    val voicePack = catalog.pack(voicePackId) ?: return null
    val engine = voicePack.engine ?: "piper"
    val packFiles =
      catalog
        .dependencyClosure(setOf(voicePackId))
        .mapNotNull(catalog::pack)
        .flatMap { it.files }
    val modelAsset = packFiles.firstOrNull { it.name.endsWith(".onnx") && !it.name.endsWith(".onnx.json") } ?: return null
    val modelFile = resolveInstallPath(modelAsset.installPath)
    if (!modelFile.exists()) return null

    return when (engine) {
      "kokoro" -> {
        val voicesAsset = packFiles.firstOrNull { it.name.endsWith(".bin") } ?: return null
        val auxFile = resolveInstallPath(voicesAsset.installPath)
        if (!auxFile.exists()) return null
        TtsVoiceFiles(
          engine = engine,
          model = modelFile,
          aux = auxFile,
          languageCode = language.code,
          speakerId = voicePack.defaultSpeakerId,
        )
      }
      else -> {
        val configAsset = packFiles.firstOrNull { it.name.endsWith(".onnx.json") } ?: return null
        val auxFile = resolveInstallPath(configAsset.installPath)
        if (!auxFile.exists()) return null
        TtsVoiceFiles(
          engine = engine,
          model = modelFile,
          aux = auxFile,
          languageCode = language.code,
          speakerId = voicePack.defaultSpeakerId,
        )
      }
    }
  }

  fun getTtsSupportDataRoot(): File? {
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
        if (assetFile.archiveFormat == "zip") {
          assetFile.installMarkerPath?.let { markerPath ->
            val extractionRoot = resolveInstallPath(markerPath).parentFile
            if (extractionRoot?.exists() == true && extractionRoot.deleteRecursively()) {
              Log.i("FilePathManager", "Deleted extracted archive dir ${extractionRoot.absolutePath} from $packId")
            }
          }
        }
        val file = resolveInstallPath(assetFile.installPath)
        if (file.exists() && file.delete()) {
          Log.i("FilePathManager", "Deleted ${assetFile.installPath} from $packId")
        }
      }
    }
  }

  fun loadCatalog(): LanguageCatalog? {
    val diskFile = getCatalogFile()
    var diskCatalog: LanguageCatalog? = null

    if (diskFile.exists()) {
      try {
        diskCatalog = parseAndValidateCatalog(diskFile.readText())
      } catch (e: Exception) {
        Log.w("FilePathManager", "Deleting invalid cached catalog: ${diskFile.absolutePath}", e)
        if (!diskFile.delete()) {
          Log.w("FilePathManager", "Failed to delete invalid cached catalog: ${diskFile.absolutePath}")
        }
      }
    }

    return try {
      val jsonString = context.assets.open("index.json").bufferedReader().readText()
      val bundledCatalog = parseAndValidateCatalog(jsonString)
      if (diskCatalog != null && diskCatalog.generatedAt >= bundledCatalog.generatedAt) {
        diskCatalog
      } else {
        bundledCatalog
      }
    } catch (e: Exception) {
      Log.e("FilePathManager", "Error parsing bundled catalog index", e)
      diskCatalog
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
