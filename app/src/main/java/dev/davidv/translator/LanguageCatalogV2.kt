package dev.davidv.translator

import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashSet

data class CatalogSourcesV2(
  val languageIndexVersion: Int,
  val languageIndexUpdatedAt: Long,
  val dictionaryIndexVersion: Int,
  val dictionaryIndexUpdatedAt: Long,
)

data class LanguageMetaV2(
  val code: String,
  val name: String,
  val shortName: String,
  val script: String,
)

data class LanguageAssetsV2(
  val translate: List<String> = emptyList(),
  val ocr: Map<String, String> = emptyMap(),
  val dictionary: String? = null,
  val support: List<String> = emptyList(),
)

data class LanguageEntryV2(
  val meta: LanguageMetaV2,
  val assets: LanguageAssetsV2,
)

data class AssetFileV2(
  val name: String,
  val sizeBytes: Long,
  val installPath: String,
  val url: String,
  val sourcePath: String? = null,
)

data class AssetPackMetadataV2(
  val date: Long? = null,
  val type: String? = null,
  val wordCount: Long? = null,
)

data class AssetPackV2(
  val id: String,
  val feature: String,
  val language: String? = null,
  val from: String? = null,
  val to: String? = null,
  val engine: String? = null,
  val dictionaryCode: String? = null,
  val languages: List<String> = emptyList(),
  val kind: String? = null,
  val files: List<AssetFileV2>,
  val dependsOn: List<String> = emptyList(),
  val metadata: AssetPackMetadataV2? = null,
)

data class LanguageCatalogV2(
  val formatVersion: Int,
  val generatedAt: Long,
  val translationModelsBaseUrl: String,
  val tesseractModelsBaseUrl: String,
  val dictionaryBaseUrl: String,
  val dictionaryVersion: Int,
  val sources: CatalogSourcesV2,
  val languages: Map<String, LanguageEntryV2>,
  val packs: Map<String, AssetPackV2>,
) {
  fun toLanguageIndex(): LanguageIndex {
    val languageList = languages.keys.sorted().mapNotNull { code -> languages[code]?.toLanguage(code) }
    return LanguageIndex(
      languages = languageList,
      updatedAt = sources.languageIndexUpdatedAt,
      version = sources.languageIndexVersion,
      translationModelsBaseUrl = translationModelsBaseUrl,
      tesseractModelsBaseUrl = tesseractModelsBaseUrl,
      dictionaryBaseUrl = dictionaryBaseUrl,
      dictionaryVersion = dictionaryVersion,
    )
  }

  fun toDictionaryIndex(): DictionaryIndex {
    val dictionaries =
      packs.values
        .filter { it.feature == "dictionary" && it.dictionaryCode != null }
        .associate { pack ->
          pack.dictionaryCode!! to
            DictionaryInfo(
              date = pack.metadata?.date ?: 0L,
              filename = pack.files.first().name,
              size = pack.files.first().sizeBytes,
              type = pack.metadata?.type.orEmpty(),
              wordCount = pack.metadata?.wordCount ?: 0L,
            )
        }
    return DictionaryIndex(
      dictionaries = dictionaries,
      updatedAt = sources.dictionaryIndexUpdatedAt,
      version = sources.dictionaryIndexVersion,
    )
  }

  fun languageEntry(code: String): LanguageEntryV2? = languages[code]

  fun pack(packId: String): AssetPackV2? = packs[packId]

  fun translationPackId(
    from: String,
    to: String,
  ): String? =
    packs.values.firstOrNull {
      it.feature == "translation" &&
        it.from == from &&
        it.to == to
    }?.id

  fun isFeatureDirectlyDownloadable(
    languageCode: String,
    feature: String,
  ): Boolean =
    when {
      languageCode == "en" && feature == "translation" -> false
      else -> true
    }

  fun corePackIdsForLanguage(languageCode: String): Set<String> {
    val assets = languages[languageCode]?.assets ?: return emptySet()
    return buildSet {
      if (isFeatureDirectlyDownloadable(languageCode, "translation")) {
        addAll(assets.translate)
      }
      addAll(assets.ocr.values)
      addAll(assets.support)
    }
  }

  fun dictionaryPackIdForLanguage(languageCode: String): String? = languages[languageCode]?.assets?.dictionary

  fun dependencyClosure(rootPackIds: Iterable<String>): Set<String> {
    val resolved = LinkedHashSet<String>()

    fun visit(packId: String) {
      if (!resolved.add(packId)) return
      val pack = packs[packId] ?: return
      pack.dependsOn.forEach(::visit)
    }

    rootPackIds.forEach(::visit)
    return resolved
  }

  fun packDownloadUrl(
    pack: AssetPackV2,
    file: AssetFileV2,
    settings: AppSettings,
  ): String {
    val sourcePath = file.sourcePath ?: return file.url
    val base =
      when (pack.feature) {
        "translation" -> settings.translationModelsBaseUrl ?: translationModelsBaseUrl
        "ocr" -> settings.tesseractModelsBaseUrl ?: tesseractModelsBaseUrl
        "dictionary", "support" -> settings.dictionaryBaseUrl
        else -> return file.url
      }.trimEnd('/')
    return "$base/$sourcePath"
  }

  fun shouldDecompress(
    pack: AssetPackV2,
    file: AssetFileV2,
  ): Boolean = pack.feature == "translation" && ((file.sourcePath ?: file.url).endsWith(".gz"))

  private fun LanguageEntryV2.toLanguage(code: String): Language? {
    val tesseractPack = assets.ocr["tesseract"]?.let(packs::get)
    val tessFile = tesseractPack?.files?.firstOrNull()
    val tessName = tessFile?.name?.removeSuffix(".traineddata") ?: return null
    val dictionaryCode = assets.dictionary?.let(packs::get)?.dictionaryCode ?: code

    val supportFiles =
      assets.support.mapNotNull(packs::get).flatMap { pack ->
        pack.files.map { it.name }
      }

    return Language(
      code = meta.code,
      displayName = meta.name,
      shortDisplayName = meta.shortName,
      tessName = tessName,
      script = meta.script,
      dictionaryCode = dictionaryCode,
      tessdataSizeBytes = tessFile.sizeBytes,
      toEnglish = if (code == "en") null else translationDirection(from = code, to = "en"),
      fromEnglish = if (code == "en") null else translationDirection(from = "en", to = code),
      extraFiles = supportFiles,
    )
  }

  private fun translationDirection(
    from: String,
    to: String,
  ): LanguageDirection? {
    val pack =
      packs.values.firstOrNull {
        it.feature == "translation" &&
          it.from == from &&
          it.to == to
      } ?: return null

    val byName = pack.files.associateBy { it.name }
    val model = byName.values.firstOrNull { it.name.startsWith("model.") } ?: return null
    val lex = byName.values.firstOrNull { it.name.startsWith("lex.") } ?: return null
    val vocabFiles = byName.values.filter { it.name.contains("vocab") }.sortedBy { it.name }
    val srcVocab = vocabFiles.firstOrNull() ?: return null
    val tgtVocab = vocabFiles.getOrElse(1) { srcVocab }

    return LanguageDirection(
      model = model.toModelFile(),
      srcVocab = srcVocab.toModelFile(),
      tgtVocab = tgtVocab.toModelFile(),
      lex = lex.toModelFile(),
    )
  }
}

fun parseLanguageCatalogV2(json: String): LanguageCatalogV2 {
  val root = JSONObject(json)
  val sourcesObj = root.getJSONObject("sources")

  val languagesObj = root.getJSONObject("languages")
  val languages =
    buildMap {
      for (code in languagesObj.keys()) {
        val entry = languagesObj.getJSONObject(code)
        val metaObj = entry.getJSONObject("meta")
        val assetsObj = entry.optJSONObject("assets") ?: JSONObject()
        put(
          code,
          LanguageEntryV2(
            meta =
              LanguageMetaV2(
                code = metaObj.getString("code"),
                name = metaObj.getString("name"),
                shortName = metaObj.getString("shortName"),
                script = metaObj.getString("script"),
              ),
            assets =
              LanguageAssetsV2(
                translate = parseStringArray(assetsObj.optJSONArray("translate")),
                ocr = parseStringMap(assetsObj.optJSONObject("ocr")),
                dictionary = assetsObj.optString("dictionary").ifBlank { null },
                support = parseStringArray(assetsObj.optJSONArray("support")),
              ),
          ),
        )
      }
    }

  val packsObj = root.getJSONObject("packs")
  val packs =
    buildMap {
      for (id in packsObj.keys()) {
        val obj = packsObj.getJSONObject(id)
        put(
          id,
          AssetPackV2(
            id = id,
            feature = obj.getString("feature"),
            language = obj.optString("language").ifBlank { null },
            from = obj.optString("from").ifBlank { null },
            to = obj.optString("to").ifBlank { null },
            engine = obj.optString("engine").ifBlank { null },
            dictionaryCode = obj.optString("dictionaryCode").ifBlank { null },
            languages = parseStringArray(obj.optJSONArray("languages")),
            kind = obj.optString("kind").ifBlank { null },
            files = parseAssetFiles(obj.getJSONArray("files")),
            dependsOn = parseStringArray(obj.optJSONArray("dependsOn")),
            metadata =
              obj.optJSONObject("metadata")?.let { metadata ->
                AssetPackMetadataV2(
                  date = metadata.optLong("date").takeIf { metadata.has("date") },
                  type = metadata.optString("type").ifBlank { null },
                  wordCount = metadata.optLong("wordCount").takeIf { metadata.has("wordCount") },
                )
              },
          ),
        )
      }
    }

  return LanguageCatalogV2(
    formatVersion = root.getInt("formatVersion"),
    generatedAt = root.getLong("generatedAt"),
    translationModelsBaseUrl = root.getString("translationModelsBaseUrl"),
    tesseractModelsBaseUrl = root.getString("tesseractModelsBaseUrl"),
    dictionaryBaseUrl = root.getString("dictionaryBaseUrl"),
    dictionaryVersion = root.getInt("dictionaryVersion"),
    sources =
      CatalogSourcesV2(
        languageIndexVersion = sourcesObj.getInt("languageIndexVersion"),
        languageIndexUpdatedAt = sourcesObj.getLong("languageIndexUpdatedAt"),
        dictionaryIndexVersion = sourcesObj.getInt("dictionaryIndexVersion"),
        dictionaryIndexUpdatedAt = sourcesObj.getLong("dictionaryIndexUpdatedAt"),
      ),
    languages = languages,
    packs = packs,
  )
}

private fun parseStringArray(arr: JSONArray?): List<String> =
  buildList {
    if (arr == null) return@buildList
    for (i in 0 until arr.length()) {
      add(arr.getString(i))
    }
  }

private fun parseStringMap(obj: JSONObject?): Map<String, String> =
  buildMap {
    if (obj == null) return@buildMap
    for (key in obj.keys()) {
      put(key, obj.getString(key))
    }
  }

private fun parseAssetFiles(arr: JSONArray): List<AssetFileV2> =
  buildList {
    for (i in 0 until arr.length()) {
      val obj = arr.getJSONObject(i)
      add(
        AssetFileV2(
          name = obj.getString("name"),
          sizeBytes = obj.getLong("sizeBytes"),
          installPath = obj.getString("installPath"),
          url = obj.getString("url"),
          sourcePath = obj.optString("sourcePath").ifBlank { null },
        ),
      )
    }
  }

private fun AssetFileV2.toModelFile(): ModelFile =
  ModelFile(
    name = name,
    sizeBytes = sizeBytes,
    path = sourcePath ?: url,
  )
