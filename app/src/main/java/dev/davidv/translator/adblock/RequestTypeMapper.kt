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

import android.webkit.WebResourceRequest
import uniffi.bindings.AdblockRequestType

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif")
private val FONT_EXTS = setOf("woff", "woff2", "ttf", "otf", "eot")
private val MEDIA_EXTS = setOf("mp4", "webm", "mp3", "ogg", "wav", "m4a", "m4v", "mov", "flac")

fun mapRequestType(request: WebResourceRequest): AdblockRequestType {
  if (request.isForMainFrame) return AdblockRequestType.DOCUMENT

  val headers = caseInsensitive(request.requestHeaders)
  val accept = headers["accept"]?.lowercase() ?: ""
  val secFetchDest = headers["sec-fetch-dest"]?.lowercase()
  val xRequestedWith = headers["x-requested-with"]

  if (xRequestedWith == "XMLHttpRequest") return AdblockRequestType.XHR

  when (secFetchDest) {
    "document" -> return AdblockRequestType.DOCUMENT
    "iframe", "frame" -> return AdblockRequestType.SUBDOCUMENT
    "script" -> return AdblockRequestType.SCRIPT
    "style" -> return AdblockRequestType.STYLESHEET
    "image" -> return AdblockRequestType.IMAGE
    "font" -> return AdblockRequestType.FONT
    "video", "audio", "track" -> return AdblockRequestType.MEDIA
    "object", "embed" -> return AdblockRequestType.OBJECT
    "manifest" -> return AdblockRequestType.OTHER
    "empty" -> return AdblockRequestType.FETCH
    "report" -> return AdblockRequestType.PING
  }

  if (accept.contains("text/css")) return AdblockRequestType.STYLESHEET
  if (accept.contains("javascript")) return AdblockRequestType.SCRIPT
  if (accept.contains("image/")) return AdblockRequestType.IMAGE
  if (accept.contains("font/")) return AdblockRequestType.FONT

  val ext = extensionOf(request.url.toString())
  return when {
    ext == "css" -> AdblockRequestType.STYLESHEET
    ext == "js" || ext == "mjs" -> AdblockRequestType.SCRIPT
    ext in IMAGE_EXTS -> AdblockRequestType.IMAGE
    ext in FONT_EXTS -> AdblockRequestType.FONT
    ext in MEDIA_EXTS -> AdblockRequestType.MEDIA
    else -> AdblockRequestType.OTHER
  }
}

private fun caseInsensitive(headers: Map<String, String>?): Map<String, String> {
  if (headers.isNullOrEmpty()) return emptyMap()
  val out = HashMap<String, String>(headers.size)
  for ((k, v) in headers) out[k.lowercase()] = v
  return out
}

fun refererOf(request: WebResourceRequest): String? = caseInsensitive(request.requestHeaders)["referer"]

private fun extensionOf(url: String): String? {
  val q = url.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) url.length else it }
  val path = url.substring(0, q)
  val slash = path.lastIndexOf('/')
  val name = if (slash >= 0) path.substring(slash + 1) else path
  val dot = name.lastIndexOf('.')
  if (dot < 0 || dot == name.length - 1) return null
  return name.substring(dot + 1).lowercase()
}
