package dev.davidv.translator

import android.content.Context
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class FilePathManager(
  val context: Context,
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

  fun currentBaseDir(): File = baseDir

  fun getTranslatedDocumentsDir(): File =
    File(context.cacheDir, "translated_documents").also { dir ->
      if (!dir.exists()) {
        dir.mkdirs()
      }
    }

  fun getDataDir(): File = File(baseDir, "bin")

  fun getDictionariesDir(): File = File(baseDir, "dictionaries")

  fun getAdblockDir(): File = File(baseDir, "adblock")

  fun getDictionaryFile(language: Language): File = File(getDictionariesDir(), "${language.dictionaryCode}.dict")

  fun getCatalogFile(): File = File(baseDir, "index_v7.json")

  fun getMucabFile(): File = File(getDataDir(), "mucab.bin")
}
