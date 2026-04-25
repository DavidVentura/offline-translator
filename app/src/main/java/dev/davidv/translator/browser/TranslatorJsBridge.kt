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
  @Volatile var sourceLanguage: Language,
  @Volatile var targetLanguage: Language,
) {
  @JavascriptInterface
  fun translateHtmlFragments(
    requestId: Int,
    fragmentsJson: String,
    nonce: String,
  ) {
    scope.launch {
      val fragments = decodeStringArray(fragmentsJson)
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
    nonce: String,
  ) {
    scope.launch {
      val texts = decodeStringArray(textsJson)
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

  private fun decodeStringArray(json: String): List<String> {
    val arr = JSONArray(json)
    return List(arr.length()) { arr.getString(it) }
  }

  private fun encodeStringArray(values: List<String>): String {
    val arr = JSONArray()
    values.forEach { arr.put(it) }
    return arr.toString()
  }
}
