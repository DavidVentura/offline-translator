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

package dev.davidv.translator.browser

import android.webkit.JavascriptInterface
import dev.davidv.translator.adblock.AdblockManager

class AdblockJsBridge(
  private val adblockManager: AdblockManager,
  private val bridgeToken: String,
) {
  companion object {
    private const val MAX_VALUES = 5000
    private const val MAX_PAYLOAD_CHARS = 200_000
    private const val MAX_ITEM_CHARS = 4_000
  }

  @JavascriptInterface
  fun lookupGenericSelectors(
    classesPayload: String,
    idsPayload: String,
    exceptionsPayload: String,
    token: String,
  ): String {
    if (token != bridgeToken) return ""
    val classes = decodeWire(classesPayload) ?: return ""
    val ids = decodeWire(idsPayload) ?: return ""
    val exceptions = decodeWire(exceptionsPayload) ?: return ""
    val selectors = adblockManager.hiddenClassIdSelectors(classes, ids, exceptions)
    return encodeWire(selectors)
  }

  // Wire format: length-prefixed records `<len>:<text><len>:<text>...`
  // (UTF-16 code-unit counts; matches JS String.length).
  private fun decodeWire(payload: String): List<String>? {
    if (payload.isEmpty()) return emptyList()
    if (payload.length > MAX_PAYLOAD_CHARS) return null
    val items = ArrayList<String>()
    var i = 0
    val n = payload.length
    while (i < n) {
      val colon = payload.indexOf(':', i)
      if (colon < 0) return null
      val len =
        try {
          payload.substring(i, colon).toInt()
        } catch (_: NumberFormatException) {
          return null
        }
      if (len < 0 || len > MAX_ITEM_CHARS) return null
      val start = colon + 1
      val end = start + len
      if (end > n) return null
      items.add(payload.substring(start, end))
      if (items.size > MAX_VALUES) return null
      i = end
    }
    return items
  }

  private fun encodeWire(values: List<String>): String {
    val sb = StringBuilder()
    for (v in values) sb.append(v.length).append(':').append(v)
    return sb.toString()
  }
}
