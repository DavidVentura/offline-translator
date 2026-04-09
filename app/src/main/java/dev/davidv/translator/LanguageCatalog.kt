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

data class LanguageTtsRegionV2(
  val displayName: String,
  val voices: List<String> = emptyList(),
)

data class LanguageTtsV2(
  val defaultRegion: String? = null,
  val regions: Map<String, LanguageTtsRegionV2> = emptyMap(),
)

data class LanguageEntryV2(
  val meta: LanguageMetaV2,
  val assets: LanguageAssetsV2,
  val tts: LanguageTtsV2? = null,
)

data class AssetFileV2(
  val name: String,
  val sizeBytes: Long,
  val installPath: String,
  val url: String,
  val sourcePath: String? = null,
  val archiveFormat: String? = null,
  val extractTo: String? = null,
  val deleteAfterExtract: Boolean = false,
  val installMarkerPath: String? = null,
  val installMarkerVersion: Int? = null,
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
  val locale: String? = null,
  val region: String? = null,
  val voice: String? = null,
  val quality: String? = null,
  val numSpeakers: Int? = null,
  val defaultSpeakerId: Int? = null,
  val dictionaryCode: String? = null,
  val languages: List<String> = emptyList(),
  val aliases: List<String> = emptyList(),
  val kind: String? = null,
  val files: List<AssetFileV2>,
  val dependsOn: List<String> = emptyList(),
  val metadata: AssetPackMetadataV2? = null,
)

data class LanguageCatalog(
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
  val languageList: List<Language> by lazy {
    languages.keys.sorted().mapNotNull { code -> languages[code]?.toLanguage(code) }
  }

  private val languagesByCode: Map<String, Language> by lazy { languageList.associateBy { it.code } }

  val english: Language by lazy { languagesByCode.getValue("en") }

  fun languageEntry(code: String): LanguageEntryV2? = languages[code]

  fun pack(packId: String): AssetPackV2? = packs[packId]

  fun languageByCode(code: String): Language? = languagesByCode[code]

  fun dictionaryInfoFor(language: Language): DictionaryInfo? = dictionaryInfo(language.dictionaryCode)

  fun dictionaryInfo(dictionaryCode: String): DictionaryInfo? =
    packs.values.firstOrNull { it.feature == "dictionary" && it.dictionaryCode == dictionaryCode }?.let { pack ->
      DictionaryInfo(
        date = pack.metadata?.date ?: 0L,
        filename = pack.files.first().name,
        size = pack.files.first().sizeBytes,
        type = pack.metadata?.type.orEmpty(),
        wordCount = pack.metadata?.wordCount ?: 0L,
      )
    }

  fun tts(languageCode: String): LanguageTtsV2? = languages[languageCode]?.tts

  fun ttsPackIdsForLanguage(languageCode: String): List<String> =
    languages[languageCode]
      ?.tts
      ?.let { tts ->
        buildList {
          tts.regions.values.forEach { region ->
            addAll(region.voices)
          }
        }.distinct()
      } ?: emptyList()

  fun defaultTtsPackIdForLanguage(languageCode: String): String? {
    val tts = languages[languageCode]?.tts ?: return null
    val region = tts.defaultRegion?.let(tts.regions::get) ?: tts.regions.values.firstOrNull()
    return region?.voices?.firstOrNull()
  }

  fun installedTtsPackIdForLanguage(
    languageCode: String,
    isInstalled: (String) -> Boolean,
  ): String? = ttsPackIdsForLanguage(languageCode).firstOrNull(isInstalled)

  fun ttsSizeBytesForLanguage(languageCode: String): Long =
    defaultTtsPackIdForLanguage(languageCode)
      ?.let(packs::get)
      ?.files
      ?.sumOf { it.sizeBytes }
      ?: 0L

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

fun parseLanguageCatalog(json: String): LanguageCatalog {
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
            tts = entry.optJSONObject("tts")?.let(::parseLanguageTts),
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
            locale = obj.optString("locale").ifBlank { null },
            region = obj.optString("region").ifBlank { null },
            voice = obj.optString("voice").ifBlank { null },
            quality = obj.optString("quality").ifBlank { null },
            numSpeakers = obj.optInt("numSpeakers").takeIf { obj.has("numSpeakers") && !obj.isNull("numSpeakers") },
            defaultSpeakerId =
              obj.optInt("defaultSpeakerId").takeIf {
                obj.has("defaultSpeakerId") && !obj.isNull("defaultSpeakerId")
              },
            dictionaryCode = obj.optString("dictionaryCode").ifBlank { null },
            languages = parseStringArray(obj.optJSONArray("languages")),
            aliases = parseStringArray(obj.optJSONArray("aliases")),
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

  return LanguageCatalog(
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

private fun parseLanguageTts(obj: JSONObject): LanguageTtsV2 =
  LanguageTtsV2(
    defaultRegion = obj.optString("defaultRegion").ifBlank { null },
    regions =
      buildMap {
        val regionsObj = obj.optJSONObject("regions") ?: return@buildMap
        for (regionCode in regionsObj.keys()) {
          val regionObj = regionsObj.getJSONObject(regionCode)
          put(
            regionCode,
            LanguageTtsRegionV2(
              displayName = regionObj.optString("displayName").ifBlank { regionCode },
              voices = parseStringArray(regionObj.optJSONArray("voices")),
            ),
          )
        }
      },
  )

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
          archiveFormat = obj.optString("archiveFormat").ifBlank { null },
          extractTo = obj.optString("extractTo").ifBlank { null },
          deleteAfterExtract = obj.optBoolean("deleteAfterExtract", false),
          installMarkerPath = obj.optString("installMarkerPath").ifBlank { null },
          installMarkerVersion =
            obj.optInt("installMarkerVersion").takeIf {
              obj.has("installMarkerVersion") && !obj.isNull("installMarkerVersion")
            },
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
