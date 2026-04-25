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
import org.json.JSONArray

class AdblockJsBridge(
  private val adblockManager: AdblockManager,
  private val bridgeToken: String,
) {
  companion object {
    private const val MAX_VALUES = 5000
    private const val MAX_JSON_CHARS = 200_000
  }

  @JavascriptInterface
  fun lookupGenericSelectors(
    classesJson: String,
    idsJson: String,
    exceptionsJson: String,
    token: String,
  ): String {
    if (token != bridgeToken) return "[]"
    val classes = parseStringArray(classesJson) ?: return "[]"
    val ids = parseStringArray(idsJson) ?: return "[]"
    val exceptions = parseStringArray(exceptionsJson) ?: return "[]"
    val selectors = adblockManager.hiddenClassIdSelectors(classes, ids, exceptions)
    val arr = JSONArray()
    selectors.forEach { arr.put(it) }
    return arr.toString()
  }

  private fun parseStringArray(json: String): List<String>? {
    if (json.length > MAX_JSON_CHARS) return null
    val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
    if (arr.length() > MAX_VALUES) return null
    return List(arr.length()) { i -> arr.optString(i, "") }
  }
}
