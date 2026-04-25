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

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import dev.davidv.translator.Language
import dev.davidv.translator.TranslationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class TranslatorJsBridge(
  private val scope: CoroutineScope,
  private val webView: WebView,
  private val translationService: TranslationService,
  private val bridgeToken: String,
  @Volatile var sourceLanguage: Language,
  @Volatile var targetLanguage: Language,
) {
  companion object {
    private const val MAX_BATCH_ITEMS = 50
    private const val MAX_TOTAL_CHARS = 100_000
    private const val MAX_ITEM_CHARS = 20_000
  }

  @JavascriptInterface
  fun translateHtmlFragments(
    requestId: Int,
    fragmentsJson: String,
    token: String,
    nonce: String,
  ) {
    if (token != bridgeToken) return
    scope.launch {
      val fragments = decodeStringArray(fragmentsJson)
      if (fragments == null) {
        resolveOnJs(requestId, emptyList(), nonce)
        return@launch
      }
      val translated =
        try {
          translationService.translateHtmlFragments(sourceLanguage, targetLanguage, fragments)
        } catch (t: Throwable) {
          Log.w("TranslatorJsBridge", "translateHtmlFragments failed", t)
          fragments
        }
      resolveOnJs(requestId, translated, nonce)
    }
  }

  @JavascriptInterface
  fun translateTexts(
    requestId: Int,
    textsJson: String,
    token: String,
    nonce: String,
  ) {
    if (token != bridgeToken) return
    scope.launch {
      val texts = decodeStringArray(textsJson)
      if (texts == null) {
        resolveOnJs(requestId, emptyList(), nonce)
        return@launch
      }
      val translated =
        try {
          val result =
            translationService.translateMixedTexts(
              texts,
              sourceLanguage,
              targetLanguage,
              listOf(sourceLanguage, targetLanguage),
            )
          mapMixedTextsToInputOrder(texts, result)
        } catch (t: Throwable) {
          Log.w("TranslatorJsBridge", "translateTexts failed", t)
          texts
        }
      resolveOnJs(requestId, translated, nonce)
    }
  }

  private fun mapMixedTextsToInputOrder(
    inputs: List<String>,
    result: dev.davidv.translator.BatchTextTranslationOutput,
  ): List<String> =
    when (result) {
      is dev.davidv.translator.BatchTextTranslationOutput.Translated ->
        inputs.map { result.results[it] ?: it }
      is dev.davidv.translator.BatchTextTranslationOutput.NothingToTranslate -> inputs
    }

  private suspend fun resolveOnJs(
    requestId: Int,
    translated: List<String>,
    nonce: String,
  ) {
    val payload = encodeStringArray(translated)
    val jsNonce = JSONObject.quote(nonce)
    withContext(Dispatchers.Main) {
      webView.evaluateJavascript(
        "window.__translator && window.__translator.resolve($requestId, $payload, $jsNonce);",
        null,
      )
    }
  }

  private fun decodeStringArray(json: String): List<String>? {
    if (json.length > MAX_TOTAL_CHARS + 4096) return null
    val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
    if (arr.length() > MAX_BATCH_ITEMS) return null
    val values = ArrayList<String>(arr.length())
    var totalChars = 0
    for (i in 0 until arr.length()) {
      if (arr.isNull(i)) return null
      val value = arr.getString(i)
      if (value.length > MAX_ITEM_CHARS) return null
      totalChars += value.length
      if (totalChars > MAX_TOTAL_CHARS) return null
      values += value
    }
    return values
  }

  private fun encodeStringArray(values: List<String>): String {
    val arr = JSONArray()
    values.forEach { arr.put(it) }
    return arr.toString()
  }
}
