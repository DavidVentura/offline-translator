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
) {
  @JavascriptInterface
  fun lookupGenericSelectors(
    classesJson: String,
    idsJson: String,
    exceptionsJson: String,
  ): String {
    val classes = parseStringArray(classesJson)
    val ids = parseStringArray(idsJson)
    val exceptions = parseStringArray(exceptionsJson)
    val selectors = adblockManager.hiddenClassIdSelectors(classes, ids, exceptions)
    val arr = JSONArray()
    selectors.forEach { arr.put(it) }
    return arr.toString()
  }

  private fun parseStringArray(json: String): List<String> {
    val arr = JSONArray(json)
    return List(arr.length()) { arr.getString(it) }
  }
}
