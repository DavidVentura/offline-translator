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

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.davidv.translator.Language
import dev.davidv.translator.TranslationService
import dev.davidv.translator.TranslatorApplication
import dev.davidv.translator.ui.components.LanguageSelectionRow
import dev.davidv.translator.ui.theme.TranslatorTheme

class BrowserActivity : ComponentActivity() {
  companion object {
    const val EXTRA_URL = "url"
    const val EXTRA_SOURCE_LANG = "source_lang"
    const val EXTRA_TARGET_LANG = "target_lang"
    private const val DEFAULT_HOME = "about:blank"
    private const val TAG = "BrowserActivity"
  }

  private lateinit var viewModel: BrowserViewModel

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val app = application as TranslatorApplication

    val initialUrl = extractUrl(intent) ?: DEFAULT_HOME
    val sourceCode = intent.getStringExtra(EXTRA_SOURCE_LANG)
    val targetCode = intent.getStringExtra(EXTRA_TARGET_LANG)

    viewModel =
      ViewModelProvider(
        this,
        BrowserViewModelFactory(
          settingsManager = app.settingsManager,
          filePathManager = app.filePathManager,
          languageMetadataManager = app.languageMetadataManager,
          translationService = app.translationService,
          initialUrl = initialUrl,
          initialSourceCode = sourceCode,
          initialTargetCode = targetCode,
        ),
      )[BrowserViewModel::class.java]

    val contentScript = assets.open("translator.js").bufferedReader().use { it.readText() }

    setContent {
      TranslatorTheme {
        Surface(
          color = MaterialTheme.colorScheme.background,
          modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
          BrowserScreen(
            viewModel = viewModel,
            contentScript = contentScript,
            translationService = app.translationService,
            onFinish = { finish() },
          )
        }
      }
    }
  }

  private fun extractUrl(intent: Intent?): String? {
    if (intent == null) return null
    val fromExtra = intent.getStringExtra(EXTRA_URL)
    if (!fromExtra.isNullOrBlank()) return fromExtra
    if (intent.action == Intent.ACTION_SEND) {
      val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
      if (!shared.isNullOrBlank()) return firstUrlInText(shared)
    }
    intent.data?.let { return it.toString() }
    return null
  }

  private fun firstUrlInText(text: String): String {
    for (token in text.split(Regex("\\s+"))) {
      if (token.startsWith("http://") || token.startsWith("https://")) return token
    }
    Log.d(TAG, "ACTION_SEND had no URL; using full text as query target: $text")
    return text
  }
}

private const val TOPBAR_SCROLL_THRESHOLD_PX = 8
private const val TOPBAR_TOP_SHOW_PX = 50

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserScreen(
  viewModel: BrowserViewModel,
  contentScript: String,
  translationService: TranslationService,
  onFinish: () -> Unit,
) {
  val from by viewModel.from.collectAsStateWithLifecycle()
  val to by viewModel.to.collectAsStateWithLifecycle()
  val languageState by viewModel.languageState.collectAsStateWithLifecycle()
  val languageMetadata by viewModel.languageMetadata.collectAsStateWithLifecycle()
  val url by viewModel.url.collectAsStateWithLifecycle()

  if (to == null) return

  if (from == null) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
      Text(
        text = "No source language available. Install one from the main screen first, or set a default source language in settings.",
        color = MaterialTheme.colorScheme.onBackground,
      )
    }
    return
  }

  var topBarVisible by remember { mutableStateOf(true) }
  val webViewRef = remember { arrayOfNulls<WebView>(1) }

  BackHandler {
    val wv = webViewRef[0]
    if (wv != null && wv.canGoBack()) {
      wv.goBack()
    } else {
      onFinish()
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    BrowserWebView(
      url = url,
      sourceLang = from!!,
      targetLang = to!!,
      contentScript = contentScript,
      translationService = translationService,
      onScrollDelta = { y, dy ->
        topBarVisible =
          when {
            y < TOPBAR_TOP_SHOW_PX -> true
            dy > TOPBAR_SCROLL_THRESHOLD_PX -> false
            dy < -TOPBAR_SCROLL_THRESHOLD_PX -> true
            else -> topBarVisible
          }
      },
      onWebViewReady = { webViewRef[0] = it },
    )

    AnimatedVisibility(
      visible = topBarVisible,
      enter = slideInVertically { -it },
      exit = slideOutVertically { -it },
      modifier = Modifier.align(Alignment.TopCenter),
    ) {
      Surface(
        color = MaterialTheme.colorScheme.background,
      ) {
        LanguageSelectionRow(
          from = from!!,
          to = to!!,
          canSwap = true,
          languageState = languageState,
          languageMetadata = languageMetadata,
          onMessage = viewModel::onMessage,
          onSettings = null,
          drawable = Pair("", 0),
        )
      }
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserWebView(
  url: String,
  sourceLang: Language,
  targetLang: Language,
  contentScript: String,
  translationService: TranslationService,
  onScrollDelta: (y: Int, dy: Int) -> Unit,
  onWebViewReady: (WebView) -> Unit,
) {
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val bridgeRef = remember { arrayOfNulls<TranslatorJsBridge>(1) }

  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { ctx ->
      val webView =
        WebView(ctx).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
        }

      webView.setOnScrollChangeListener { _, _, y, _, oldY ->
        onScrollDelta(y, y - oldY)
      }

      val bridge =
        TranslatorJsBridge(
          scope = scope,
          webView = webView,
          translationService = translationService,
          sourceLanguage = sourceLang,
          targetLanguage = targetLang,
        )
      bridgeRef[0] = bridge
      webView.addJavascriptInterface(bridge, "__translatorBridge")

      webView.webViewClient =
        object : WebViewClient() {
          override fun onPageStarted(
            view: WebView?,
            url: String?,
            favicon: Bitmap?,
          ) {
            view?.evaluateJavascript(contentScript, null)
          }
        }

      onWebViewReady(webView)
      webView.loadUrl(url)
      webView
    },
    update = { webView ->
      val b = bridgeRef[0] ?: return@AndroidView
      val langsChanged = b.sourceLanguage != sourceLang || b.targetLanguage != targetLang
      b.sourceLanguage = sourceLang
      b.targetLanguage = targetLang
      if (langsChanged) webView.reload()
    },
  )
}
