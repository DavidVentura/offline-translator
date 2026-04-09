package dev.davidv.translator

data class PackInstallStatus(
  val packId: String,
  val installed: Boolean,
  val missingFiles: List<AssetFileV2>,
  val missingDependencyIds: Set<String>,
)

data class MissingPackFile(
  val pack: AssetPackV2,
  val file: AssetFileV2,
)

class PackResolver(
  private val catalog: LanguageCatalog,
  private val filePathManager: FilePathManager,
) {
  private val statusCache = mutableMapOf<String, PackInstallStatus>()

  fun status(packId: String): PackInstallStatus? {
    statusCache[packId]?.let { return it }
    val pack = catalog.pack(packId) ?: return null
    val missingFiles =
      pack.files.filter { file ->
        !filePathManager.resolveInstallPath(file.installPath).exists()
      }
    val missingDependencies =
      pack.dependsOn.filter { depId ->
        status(depId)?.installed != true
      }.toSet()
    return PackInstallStatus(
      packId = packId,
      installed = missingFiles.isEmpty() && missingDependencies.isEmpty(),
      missingFiles = missingFiles,
      missingDependencyIds = missingDependencies,
    ).also { statusCache[packId] = it }
  }

  fun isInstalled(packId: String): Boolean = status(packId)?.installed == true

  fun missingFiles(packIds: Iterable<String>): List<MissingPackFile> {
    val missing = mutableListOf<MissingPackFile>()
    val seenInstallPaths = mutableSetOf<String>()

    for (packId in catalog.dependencyClosure(packIds)) {
      val pack = catalog.pack(packId) ?: continue
      val status = status(packId) ?: continue
      for (file in status.missingFiles) {
        if (seenInstallPaths.add(file.installPath)) {
          missing += MissingPackFile(pack = pack, file = file)
        }
      }
    }

    return missing
  }
}
