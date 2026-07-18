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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import uniffi.translator_core.LanguageCode
import uniffi.translator_core.OcrSourceSelection
import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Exposes the on-device translation engine over the subset of the LibreTranslate
 * HTTP API that maps onto it: POST /translate, POST /detect, GET /languages.
 * Any app that already speaks LibreTranslate can point at this server unchanged.
 *
 * NanoHTTPD serves each request on its own worker thread, so `runBlocking` around
 * the suspending engine calls just parks that worker; the engine's own mutex +
 * worker pool serialises the actual translation.
 */
class LibreTranslateHttpServer(
  hostname: String,
  port: Int,
  private val app: TranslatorApplication,
) : NanoHTTPD(hostname, port) {
  init {
    // Bind with SO_REUSEADDR so a port/interface change restarts cleanly without
    // tripping over the previous socket lingering in TIME_WAIT.
    setServerSocketFactory {
      ServerSocket().apply { reuseAddress = true }
    }
  }

  override fun serve(session: IHTTPSession): Response {
    // DNS-rebinding guard: a page on any origin can fetch() this loopback server,
    // and one that rebinds its own hostname to 127.0.0.1 could then read the
    // responses (ACAO is `*`). Reject requests whose Host header is a registered
    // domain name; loopback names and raw IP literals — what real LibreTranslate
    // clients use — are still accepted. Not cors()-wrapped, so a blocked browser
    // caller cannot read the body either.
    if (!isAllowedHost(session.headers["host"])) {
      return error(Response.Status.FORBIDDEN, "host not allowed")
    }
    if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.OK, MIME_JSON, "{}"))
    return try {
      val response =
        when {
          session.method == Method.POST && session.uri == "/translate" -> handleTranslate(session)
          session.method == Method.POST && session.uri == "/translate_file" -> handleTranslateFile(session)
          session.method == Method.POST && session.uri == "/detect" -> handleDetect(session)
          session.method == Method.GET && session.uri == "/languages" -> handleLanguages()
          session.method == Method.GET && session.uri.startsWith("/download/") -> handleDownload(session.uri.removePrefix("/download/"))
          else -> error(Response.Status.NOT_FOUND, "Not found")
        }
      cors(response)
    } catch (e: Exception) {
      Log.e(TAG, "serve failed", e)
      cors(error(Response.Status.INTERNAL_ERROR, e.message ?: "internal error"))
    }
  }

  private fun handleTranslate(session: IHTTPSession): Response {
    val params = readParams(session)
    val target = params.string("target") ?: return error(Response.Status.BAD_REQUEST, "'target' is required")
    val source = params.string("source") ?: "auto"
    val format = params.string("format") ?: "text"
    val (texts, isArray) = params.q() ?: return error(Response.Status.BAD_REQUEST, "'q' is required")

    val catalog = app.filePathManager.loadCatalog() ?: return error(Response.Status.INTERNAL_ERROR, "catalog unavailable")
    val to = catalog.languageByCode(target) ?: return error(Response.Status.BAD_REQUEST, "target language '$target' not available")
    val available = catalog.languageRows.filter { it.availability.translatorFiles }.map { it.language }

    var detected: Language? = null
    val from: Language =
      if (source == "auto") {
        runBlocking { app.translationCoordinator.detectLanguageRobust(texts.joinToString("\n"), null, available) }
          ?.also { detected = it }
          ?: return error(Response.Status.BAD_REQUEST, "could not detect source language")
      } else {
        catalog.languageByCode(source) ?: return error(Response.Status.BAD_REQUEST, "source language '$source' not available")
      }

    if (from != to && !catalog.canTranslate(from, to)) {
      return error(Response.Status.BAD_REQUEST, "translation ${from.code} -> ${to.code} not available")
    }

    val translated = ArrayList<String>(texts.size)
    for (text in texts) {
      if (from == to) {
        translated.add(text)
        continue
      }
      if (format == "html") {
        translated.add(runBlocking { app.translationService.translateHtmlFragments(from, to, listOf(text)) }.firstOrNull() ?: text)
        continue
      }
      when (val result = runBlocking { app.translationCoordinator.translateText(from, to, text) }) {
        is TranslationResult.Success -> translated.add(result.result.translated)
        is TranslationResult.Error -> return error(Response.Status.INTERNAL_ERROR, result.message)
      }
    }

    val body =
      JSONObject().apply {
        put("translatedText", if (isArray) JSONArray(translated) else translated.first())
        detected?.let { put("detectedLanguage", JSONObject().put("confidence", DETECTED_CONFIDENCE).put("language", it.code)) }
      }
    return json(Response.Status.OK, body)
  }

  private fun handleDetect(session: IHTTPSession): Response {
    val params = readParams(session)
    val (texts, _) = params.q() ?: return error(Response.Status.BAD_REQUEST, "'q' is required")
    val catalog = app.filePathManager.loadCatalog() ?: return error(Response.Status.INTERNAL_ERROR, "catalog unavailable")
    val available = catalog.languageRows.filter { it.availability.translatorFiles }.map { it.language }

    val detected = runBlocking { app.translationCoordinator.detectLanguageRobust(texts.joinToString("\n"), null, available) }
    val body = JSONArray()
    detected?.let { body.put(JSONObject().put("confidence", DETECTED_CONFIDENCE).put("language", it.code)) }
    return json(Response.Status.OK, body)
  }

  private fun handleLanguages(): Response {
    val catalog = app.filePathManager.loadCatalog() ?: return error(Response.Status.INTERNAL_ERROR, "catalog unavailable")
    val languages = catalog.languageRows.filter { it.availability.translatorFiles }.map { it.language }
    val body = JSONArray()
    languages.forEach { lang ->
      val targets = languages.filter { it != lang && catalog.canTranslate(lang, it) }.map { it.code }
      body.put(
        JSONObject()
          .put("code", lang.code)
          .put("name", lang.displayName)
          .put("targets", JSONArray(targets)),
      )
    }
    return json(Response.Status.OK, body)
  }

  // POST /translate_file: multipart upload of a document (pdf/odt/epub/txt) or
  // image (png/jpg/jpeg). Translates synchronously inside this worker (matching
  // LibreTranslate's blocking contract) and returns a one-shot download URL.
  private fun handleTranslateFile(session: IHTTPSession): Response {
    val files = HashMap<String, String>()
    session.parseBody(files)
    val tempPath = files["file"] ?: return error(Response.Status.BAD_REQUEST, "'file' is required")
    val filename = session.parameters["file"]?.firstOrNull() ?: "upload"
    val target = session.parameters["target"]?.firstOrNull() ?: return error(Response.Status.BAD_REQUEST, "'target' is required")
    val source = session.parameters["source"]?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: "auto"

    val catalog = app.filePathManager.loadCatalog() ?: return error(Response.Status.INTERNAL_ERROR, "catalog unavailable")
    val to = catalog.languageByCode(target) ?: return error(Response.Status.BAD_REQUEST, "target language '$target' not available")

    val ext = filename.substringAfterLast('.', "").lowercase()
    val base = filename.substringBeforeLast('.', filename)
    return when (ext) {
      "png", "jpg", "jpeg" -> translateImageFile(catalog, File(tempPath), source, to, base, session)
      "pdf", "odt", "epub", "txt" -> translateDocumentFile(catalog, tempPath, ext, source, to, base, session)
      else -> error(Response.Status.BAD_REQUEST, "unsupported file type '.$ext'")
    }
  }

  private fun translateImageFile(
    catalog: LanguageCatalog,
    input: File,
    source: String,
    to: Language,
    base: String,
    session: IHTTPSession,
  ): Response {
    val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return error(Response.Status.BAD_REQUEST, "could not decode image")
    val settings = app.settingsManager.settings.value
    val sourceSelection =
      if (source == "auto") {
        OcrSourceSelection.Auto
      } else {
        val from = catalog.languageByCode(source) ?: return error(Response.Status.BAD_REQUEST, "source language '$source' not available")
        OcrSourceSelection.Specific(LanguageCode(from.code))
      }

    val outBitmap =
      try {
        val plan =
          catalog.translateImagePlan(
            bitmap,
            settings.maxImageSize,
            sourceSelection,
            to,
            settings.minConfidence,
            null,
            settings.backgroundMode,
            null,
          )
        val rendered = catalog.renderTranslatedOverlay(plan, to, MIN_OVERLAY_FONT_SIZE_PX)
        rgbaToBitmap(rendered.rgbaBytes, plan.width.toInt(), plan.height.toInt())
      } catch (e: uniffi.bindings.CatalogException.MissingAsset) {
        Log.d(TAG, "image translate_file missing asset: ${e.message}")
        return error(Response.Status.BAD_REQUEST, "image translation $source -> ${to.code} not available")
      } catch (e: uniffi.bindings.CatalogException) {
        Log.d(TAG, "image translate_file failed: ${e.message}")
        return error(Response.Status.BAD_REQUEST, e.message?.removePrefix("reason=") ?: "image translation failed")
      } ?: return error(Response.Status.INTERNAL_ERROR, "could not render translated image")

    val id = FileTranslationStore.newId()
    val outFile = FileTranslationStore.outputFile(app, id, "png")
    outFile.outputStream().use { outBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    FileTranslationStore.register(id, outFile, "$base-${to.code}.png", "image/png")
    return fileUrlResponse(session, id)
  }

  private fun translateDocumentFile(
    catalog: LanguageCatalog,
    inputPath: String,
    ext: String,
    source: String,
    to: Language,
    base: String,
    session: IHTTPSession,
  ): Response {
    if (source == "auto") {
      return error(Response.Status.BAD_REQUEST, "'source' is required for documents (auto-detect is only supported for images)")
    }
    val from = catalog.languageByCode(source) ?: return error(Response.Status.BAD_REQUEST, "source language '$source' not available")
    if (from != to && !catalog.canTranslate(from, to)) {
      return error(Response.Status.BAD_REQUEST, "translation ${from.code} -> ${to.code} not available")
    }

    val available = catalog.languageRows.filter { it.availability.translatorFiles }.map { it.language }
    val id = FileTranslationStore.newId()
    val outFile = FileTranslationStore.outputFile(app, id, ext)
    // The document pipeline infers the format from the input path's extension, but
    // NanoHTTPD's multipart temp file has none — stage a copy with the real ext.
    val stagedInput = File(FileTranslationStore.dir(app), "in-${FileTranslationStore.newId()}.$ext")
    File(inputPath).copyTo(stagedInput, overwrite = true)
    val result =
      try {
        runBlocking {
          app.translationService.translateDocumentPath(
            inputPath = stagedInput.absolutePath,
            outputPath = outFile.absolutePath,
            from = from,
            to = to,
            availableLanguages = available,
            translatePdfImages = app.settingsManager.settings.value.translatePdfImages,
            txtLayout = TxtLayoutChoice.Preserve,
          )
        }
      } finally {
        stagedInput.delete()
      }
    return result.fold(
      onSuccess = {
        FileTranslationStore.register(id, outFile, "$base-${to.code}.$ext", mimeForExtension(ext))
        fileUrlResponse(session, id)
      },
      onFailure = { e ->
        Log.e(TAG, "document translate_file failed", e)
        error(Response.Status.INTERNAL_ERROR, e.message ?: "document translation failed")
      },
    )
  }

  private fun handleDownload(id: String): Response {
    val entry = FileTranslationStore.get(id) ?: return error(Response.Status.NOT_FOUND, "file not found")
    if (!entry.file.exists()) return error(Response.Status.NOT_FOUND, "file expired")
    val response = newFixedLengthResponse(Response.Status.OK, entry.mime, FileInputStream(entry.file), entry.file.length())
    response.addHeader("Content-Disposition", "attachment; filename=\"${entry.downloadName}\"")
    return response
  }

  private fun fileUrlResponse(
    session: IHTTPSession,
    id: String,
  ): Response {
    val host = session.headers["host"] ?: "127.0.0.1:$listeningPort"
    val body = JSONObject().put("translatedFileUrl", "http://$host/download/$id")
    return json(Response.Status.OK, body)
  }

  private fun rgbaToBitmap(
    bytes: ByteArray,
    width: Int,
    height: Int,
  ): Bitmap? =
    try {
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
      }
    } catch (e: Exception) {
      Log.e(TAG, "rgbaToBitmap failed", e)
      null
    }

  private fun mimeForExtension(ext: String): String =
    when (ext) {
      "pdf" -> "application/pdf"
      "odt" -> "application/vnd.oasis.opendocument.text"
      "epub" -> "application/epub+zip"
      "txt" -> "text/plain"
      else -> "application/octet-stream"
    }

  private fun readParams(session: IHTTPSession): RequestParams {
    val files = HashMap<String, String>()
    session.parseBody(files)
    val postData = files["postData"]
    val jsonBody = postData?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    return RequestParams(jsonBody, session.parameters)
  }

  private class RequestParams(
    private val json: JSONObject?,
    private val form: Map<String, List<String>>,
  ) {
    fun string(name: String): String? {
      val fromJson = json?.let { if (it.has(name)) it.optString(name) else null }?.takeIf { it.isNotEmpty() }
      return fromJson ?: form[name]?.firstOrNull()?.takeIf { it.isNotEmpty() }
    }

    // LibreTranslate's `q` is either a single string or an array of strings; the
    // response mirrors that shape. Returns null when `q` is absent.
    fun q(): Pair<List<String>, Boolean>? {
      val fromJson = json?.opt("q")
      when (fromJson) {
        is JSONArray -> return (0 until fromJson.length()).map { fromJson.getString(it) } to true
        is String -> return listOf(fromJson) to false
      }
      val fromForm = form["q"] ?: return null
      if (fromForm.isEmpty()) return null
      return if (fromForm.size > 1) fromForm to true else listOf(fromForm.first()) to false
    }
  }

  private fun json(
    status: Response.Status,
    body: JSONObject,
  ): Response = newFixedLengthResponse(status, MIME_JSON, serialize(body.toString()))

  private fun json(
    status: Response.Status,
    body: JSONArray,
  ): Response = newFixedLengthResponse(status, MIME_JSON, serialize(body.toString()))

  private fun error(
    status: Response.Status,
    message: String,
  ): Response = json(status, JSONObject().put("error", message))

  // org.json escapes every forward slash as `\/`. It's valid JSON but real
  // LibreTranslate emits bare slashes and it looks wrong; undo it. Safe because
  // `\/` inside a JSON string is identical to `/`, and a literal backslash before
  // a slash is serialised as `\\/`, which this leaves intact.
  private fun serialize(json: String): String = json.replace("\\/", "/")

  private fun cors(response: Response): Response =
    response.apply {
      addHeader("Access-Control-Allow-Origin", "*")
      addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
      addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    }

  companion object {
    private const val TAG = "LibreTranslateServer"
    private const val MIME_JSON = "application/json"
    private const val MIN_OVERLAY_FONT_SIZE_PX = 8.0f

    // The native robust detector returns a single best language code without a
    // score, so we report full confidence to satisfy the LibreTranslate schema.
    private const val DETECTED_CONFIDENCE = 100.0

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val IPV6 = Regex("""^[0-9a-fA-F:]+$""")

    /**
     * Whether a request's `Host` header is safe to serve. Accepts a missing
     * Host, `localhost`, and raw IPv4/IPv6 literals; rejects registered domain
     * names. This is the DNS-rebinding defense (see [serve]): rebinding relies on
     * a hostname that re-resolves to a loopback/LAN address, so refusing
     * hostnames — while still allowing the IPs and `localhost` that real clients
     * target — closes the hole without breaking normal use.
     */
    internal fun isAllowedHost(hostHeader: String?): Boolean {
      if (hostHeader.isNullOrBlank()) return true
      val host = hostNameOnly(hostHeader).lowercase()
      if (host.isEmpty()) return false
      if (host == "localhost") return true
      return IPV4.matches(host) || (host.contains(':') && IPV6.matches(host))
    }

    // Extracts the host from a `Host` header value, dropping any `:port`.
    // IPv6 literals are bracketed per RFC 3986 (`[::1]:5000`); a bare IPv6
    // address (multiple colons, no brackets) carries no port.
    private fun hostNameOnly(hostHeader: String): String {
      val h = hostHeader.trim()
      if (h.startsWith("[")) {
        val end = h.indexOf(']')
        return if (end >= 0) h.substring(1, end).trim() else h.substring(1).trim()
      }
      if (h.count { it == ':' } > 1) return h
      return h.substringBefore(':')
    }
  }
}

/**
 * Holds translated file outputs produced by /translate_file until a client
 * fetches them via /download/<id>. Files live in cacheDir and outputs older than
 * [TTL_MS] are swept on each new upload, so nothing accumulates unbounded.
 */
private object FileTranslationStore {
  data class Entry(
    val file: File,
    val downloadName: String,
    val mime: String,
  )

  private const val TTL_MS = 60 * 60 * 1000L
  private val entries = ConcurrentHashMap<String, Entry>()

  fun dir(context: android.content.Context): File = File(context.cacheDir, "translate_file").apply { mkdirs() }

  fun newId(): String = UUID.randomUUID().toString()

  fun outputFile(
    context: android.content.Context,
    id: String,
    ext: String,
  ): File {
    gc(context)
    return File(dir(context), "$id.$ext")
  }

  fun register(
    id: String,
    file: File,
    downloadName: String,
    mime: String,
  ) {
    entries[id] = Entry(file, downloadName, mime)
  }

  fun get(id: String): Entry? = entries[id]

  private fun gc(context: android.content.Context) {
    val cutoff = System.currentTimeMillis() - TTL_MS
    dir(context).listFiles()?.forEach { file ->
      if (file.lastModified() < cutoff) {
        file.delete()
        entries.entries.removeAll { it.value.file == file }
      }
    }
  }
}
