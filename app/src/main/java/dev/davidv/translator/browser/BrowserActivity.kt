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
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.davidv.translator.Language
import dev.davidv.translator.R
import dev.davidv.translator.TranslationCoordinator
import dev.davidv.translator.TranslationService
import dev.davidv.translator.TranslatorApplication
import dev.davidv.translator.adblock.AdblockManager
import dev.davidv.translator.adblock.mapRequestType
import dev.davidv.translator.adblock.refererOf
import dev.davidv.translator.isWebUrl
import dev.davidv.translator.ui.components.LanguageSelectionRow
import dev.davidv.translator.ui.theme.TranslatorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

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
    val readabilityScript = assets.open("Readability.js").bufferedReader().use { it.readText() }
    val readerModeScript = assets.open("reader-mode.js").bufferedReader().use { it.readText() }
    val adblockCosmeticScript =
      assets.open("adblock-cosmetic.js").bufferedReader().use { it.readText() }

    setContent {
      TranslatorTheme {
        Surface(
          color = MaterialTheme.colorScheme.background,
          modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
          BrowserScreen(
            viewModel = viewModel,
            contentScript = contentScript,
            readabilityScript = readabilityScript,
            readerModeScript = readerModeScript,
            adblockCosmeticScript = adblockCosmeticScript,
            translationService = app.translationService,
            translationCoordinator = app.translationCoordinator,
            adblockManager = app.adblockManager,
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
      if (!shared.isNullOrBlank()) {
        if (isWebUrl(shared)) return shared
        Log.d(TAG, "ACTION_SEND had no URL; using full text as query target: $shared")
        return shared
      }
    }
    intent.data?.let { return it.toString() }
    return null
  }
}

private const val TOPBAR_SCROLL_THRESHOLD_PX = 8
private const val TOPBAR_HIDE_SCROLL_DISTANCE_PX = 56
private const val TOPBAR_SHOW_SCROLL_DISTANCE_PX = 72
private const val TOPBAR_TOP_SHOW_PX = 50

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserScreen(
  viewModel: BrowserViewModel,
  contentScript: String,
  readabilityScript: String,
  readerModeScript: String,
  adblockCosmeticScript: String,
  translationService: TranslationService,
  translationCoordinator: TranslationCoordinator,
  adblockManager: AdblockManager,
  onFinish: () -> Unit,
) {
  val context = LocalContext.current
  val from by viewModel.from.collectAsStateWithLifecycle()
  val to by viewModel.to.collectAsStateWithLifecycle()
  val languageState by viewModel.languageState.collectAsStateWithLifecycle()
  val languageMetadata by viewModel.languageMetadata.collectAsStateWithLifecycle()
  val url by viewModel.url.collectAsStateWithLifecycle()
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val darkTheme = isSystemInDarkTheme()

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
  var topBarScrollAccumulator by remember { mutableStateOf(0) }
  val readerController = remember { ReaderController(readabilityScript, readerModeScript) }
  val readerPageState by readerController.state.collectAsStateWithLifecycle()
  val readerActive = readerPageState.reader is ReaderState.Active
  val readerVisible = readerPageState.reader != ReaderState.Idle
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
      adblockCosmeticScript = adblockCosmeticScript,
      translationService = translationService,
      translationCoordinator = translationCoordinator,
      adblockManager = adblockManager,
      clearDataOnClose = settings.clearWebTranslatorDataOnClose,
      darkTheme = darkTheme,
      onScrollDelta = { y, dy ->
        if (y < TOPBAR_TOP_SHOW_PX) {
          topBarVisible = true
          topBarScrollAccumulator = 0
        } else if (kotlin.math.abs(dy) > TOPBAR_SCROLL_THRESHOLD_PX) {
          val continuedSameDirection =
            (topBarScrollAccumulator >= 0 && dy > 0) ||
              (topBarScrollAccumulator <= 0 && dy < 0)
          topBarScrollAccumulator =
            if (continuedSameDirection) {
              topBarScrollAccumulator + dy
            } else {
              dy
            }

          when {
            topBarVisible && topBarScrollAccumulator >= TOPBAR_HIDE_SCROLL_DISTANCE_PX -> {
              topBarVisible = false
              topBarScrollAccumulator = 0
            }
            !topBarVisible && topBarScrollAccumulator <= -TOPBAR_SHOW_SCROLL_DISTANCE_PX -> {
              topBarVisible = true
              topBarScrollAccumulator = 0
            }
          }
        }
      },
      onWebViewReady = { webViewRef[0] = it },
      readerController = readerController,
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
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
          LanguageSelectionRow(
            from = from!!,
            to = to!!,
            canSwap = viewModel.canSwapLanguages(from!!, to!!),
            languageState = languageState,
            languageMetadata = languageMetadata,
            onMessage = viewModel::onMessage,
            drawable =
              Pair(
                if (readerActive) "Exit reader mode" else "Reader mode",
                R.drawable.chrome_reader_mode,
              ),
            onSettings =
              if (readerVisible) {
                {
                  val webView = webViewRef[0]
                  if (webView != null) {
                    readerController.toggle(webView) {
                      Toast.makeText(context, "Reader mode unavailable for this page", Toast.LENGTH_SHORT).show()
                    }
                  }
                }
              } else {
                null
              },
          )
        }
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
  adblockCosmeticScript: String,
  translationService: TranslationService,
  translationCoordinator: TranslationCoordinator,
  adblockManager: AdblockManager,
  clearDataOnClose: Boolean,
  darkTheme: Boolean,
  onScrollDelta: (y: Int, dy: Int) -> Unit,
  onWebViewReady: (WebView) -> Unit,
  readerController: ReaderController,
) {
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val bridgeRef = remember { arrayOfNulls<TranslatorJsBridge>(1) }
  val webViewRef = remember { arrayOfNulls<WebView>(1) }
  val currentPageUrlRef = remember { java.util.concurrent.atomic.AtomicReference<String?>(null) }
  val currentPageSerialRef = remember { java.util.concurrent.atomic.AtomicInteger(0) }
  val adblockReady by adblockManager.ready.collectAsStateWithLifecycle()
  val bridgeToken = remember { UUID.randomUUID().toString() }
  val translatorBridgeName = remember { "__translatorBridge_${bridgeToken.filter { it != '-' }}" }
  val adblockBridgeName = remember { "__adblockBridge_${bridgeToken.filter { it != '-' }}" }
  val clearDataOnCloseState = rememberUpdatedState(clearDataOnClose)
  val touchCoords = remember { intArrayOf(0, 0) }
  var imageMenuOffset by remember { mutableStateOf<IntOffset?>(null) }

  DisposableEffect(Unit) {
    onDispose {
      if (clearDataOnCloseState.value) clearWebTranslatorData(webViewRef[0])
    }
  }

  LaunchedEffect(adblockReady) {
    if (!adblockReady) return@LaunchedEffect
    val webView = webViewRef[0] ?: return@LaunchedEffect
    val pageSerial = currentPageSerialRef.get()
    if (pageSerial == 0) return@LaunchedEffect
    val currentUrl = webView.url ?: currentPageUrlRef.get() ?: return@LaunchedEffect
    applyCosmeticFiltersAsync(
      scope,
      webView,
      adblockManager,
      currentUrl,
      pageSerial,
      currentPageSerialRef,
      adblockBridgeName,
      bridgeToken,
      adblockCosmeticScript,
    )
  }

  Box(modifier = Modifier.fillMaxSize()) {
    key(darkTheme) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          val webViewContext =
            ContextThemeWrapper(
              ctx,
              if (darkTheme) {
                R.style.Theme_Translator_WebView_Dark
              } else {
                R.style.Theme_Translator_WebView_Light
              },
            )
          val webView =
            WebView(webViewContext).apply {
              layoutParams =
                ViewGroup.LayoutParams(
                  ViewGroup.LayoutParams.MATCH_PARENT,
                  ViewGroup.LayoutParams.MATCH_PARENT,
                )
              settings.javaScriptEnabled = true
              settings.domStorageEnabled = true
            }

          webView.webChromeClient =
            object : WebChromeClient() {
              override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i(
                  "BrowserConsole",
                  "[${message.messageLevel()}] ${message.message()} (${message.sourceId()}:${message.lineNumber()})",
                )
                return true
              }
            }

          webView.setOnScrollChangeListener { _, _, y, _, oldY ->
            onScrollDelta(y, y - oldY)
          }

          webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
              touchCoords[0] = event.x.toInt()
              touchCoords[1] = event.y.toInt()
            }
            false
          }

          webView.setOnLongClickListener {
            val r = webView.hitTestResult
            val isImage =
              r.type == WebView.HitTestResult.IMAGE_TYPE ||
                r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            if (isImage) {
              imageMenuOffset = IntOffset(touchCoords[0], touchCoords[1])
              true
            } else {
              false
            }
          }

          val bridge =
            TranslatorJsBridge(
              scope = scope,
              webView = webView,
              translationService = translationService,
              translationCoordinator = translationCoordinator,
              bridgeToken = bridgeToken,
              sourceLanguage = sourceLang,
              targetLanguage = targetLang,
            )
          bridgeRef[0] = bridge
          webViewRef[0] = webView
          webView.addJavascriptInterface(bridge, translatorBridgeName)
          webView.addJavascriptInterface(AdblockJsBridge(adblockManager, bridgeToken), adblockBridgeName)

          webView.webViewClient =
            object : WebViewClient() {
              override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?,
              ) {
                Log.i("AdblockManager", "onPageStarted view=$view url=$url")
                readerController.onPageStarted()
                currentPageUrlRef.set(url)
                val pageSerial = currentPageSerialRef.incrementAndGet()
                // Inject the translator content script as early as possible.
                // The script self-defers its DOM scan until DOMContentLoaded
                view?.evaluateJavascript(
                  buildTranslatorContentJs(contentScript, translatorBridgeName, bridgeToken),
                  null,
                )
                if (view != null && url != null) {
                  applyCosmeticFiltersAsync(
                    scope,
                    view,
                    adblockManager,
                    url,
                    pageSerial,
                    currentPageSerialRef,
                    adblockBridgeName,
                    bridgeToken,
                    adblockCosmeticScript,
                  )
                }
              }

              override fun onPageFinished(
                view: WebView?,
                url: String?,
              ) {
                val pageSerial = currentPageSerialRef.get()
                if (view != null && url != null) {
                  applyCosmeticFiltersAsync(
                    scope,
                    view,
                    adblockManager,
                    url,
                    pageSerial,
                    currentPageSerialRef,
                    adblockBridgeName,
                    bridgeToken,
                    adblockCosmeticScript,
                  )
                }
                if (view != null) readerController.probeOnPageFinished(view)
              }

              override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
              ): WebResourceResponse? {
                if (request == null) return null
                val topUrl = currentPageUrlRef.get() ?: return null
                val sourceUrl =
                  if (request.isForMainFrame) topUrl else refererOf(request) ?: topUrl
                val verdict =
                  adblockManager.checkRequest(
                    url = request.url.toString(),
                    sourceUrl = sourceUrl,
                    requestType = mapRequestType(request),
                  ) ?: return null
                if (!verdict.matched || verdict.exception) return null
                return WebResourceResponse("text/plain", "utf-8", ByteArray(0).inputStream())
              }

              override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
              ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                val description = if (Build.VERSION.SDK_INT >= 23) {
                  error?.description?.toString().orEmpty()
                } else {
                  ""
                }
                Log.w("BrowserActivity", "WebView failed to load ${request.url}: $description")
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
          if (langsChanged) {
            webView.evaluateJavascript(
              "window.__translator && window.__translator.reset && window.__translator.reset();",
            ) { result ->
              if (result != "true") webView.reload()
            }
          }
        },
      )
    }

    imageMenuOffset?.let { offset ->
      Popup(
        offset = offset,
        onDismissRequest = { imageMenuOffset = null },
      ) {
        Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(8.dp),
          tonalElevation = 4.dp,
          shadowElevation = 8.dp,
        ) {
          Text(
            text = "Translate image",
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
              Modifier
                .clickable {
                  webViewRef[0]?.evaluateJavascript(
                    "window.__translator && window.__translator.translateTouchedImage && window.__translator.translateTouchedImage();",
                    null,
                  )
                  imageMenuOffset = null
                }.padding(horizontal = 16.dp, vertical = 12.dp),
          )
        }
      }
    }
  }
}

private fun clearWebTranslatorData(webView: WebView?) {
  webView?.clearCache(true)
  CookieManager.getInstance().apply {
    removeAllCookies(null)
    flush()
  }
  WebStorage.getInstance().deleteAllData()
}

private fun buildTranslatorContentJs(
  contentScript: String,
  bridgeName: String,
  bridgeToken: String,
): String =
  "(function(){\n" +
    "var __translatorBridgeName = ${JSONObject.quote(bridgeName)};\n" +
    "var __translatorBridgeToken = ${JSONObject.quote(bridgeToken)};\n" +
    contentScript +
    "\n})();"

private fun applyCosmeticFiltersAsync(
  scope: CoroutineScope,
  webView: WebView,
  adblockManager: AdblockManager,
  url: String,
  pageSerial: Int,
  currentPageSerialRef: java.util.concurrent.atomic.AtomicInteger,
  adblockBridgeName: String,
  bridgeToken: String,
  adblockCosmeticScript: String,
) {
  Log.i("AdblockManager", "url=$url scheduling cosmetic lookup")
  scope.launch {
    val tFetch = System.currentTimeMillis()
    val cosmetic = withContext(Dispatchers.IO) { adblockManager.cosmeticResources(url) }
    val fetchMs = System.currentTimeMillis() - tFetch
    if (cosmetic == null) {
      Log.i("AdblockManager", "url=$url cosmetic=null (engine not loaded?) fetch=${fetchMs}ms")
      return@launch
    }
    val sdaPresent = cosmetic.hideSelectors.any { it == ".sdaContainer" }
    Log.i(
      "AdblockManager",
      "url=$url hide=${cosmetic.hideSelectors.size} style=${cosmetic.styleRules.size} " +
        "procedural=${cosmetic.proceduralFilters.size} script=${cosmetic.injectedScript.length}B " +
        "exc=${cosmetic.exceptions.size} generichide=${cosmetic.generichide} " +
        "hasSdaContainer=$sdaPresent fetch=${fetchMs}ms",
    )
    val js =
      buildCosmeticJs(
        cosmetic.hideSelectors,
        cosmetic.styleRules,
        cosmetic.proceduralFilters,
        cosmetic.exceptions,
        cosmetic.generichide,
        cosmetic.injectedScript,
        adblockBridgeName,
        bridgeToken,
        adblockCosmeticScript,
      )
    if (js.isEmpty()) {
      Log.i("AdblockManager", "url=$url empty cosmetic, skipping")
      return@launch
    }
    withContext(Dispatchers.Main) {
      if (currentPageSerialRef.get() != pageSerial) {
        Log.i("AdblockManager", "url=$url stale cosmetic result, skipping")
        return@withContext
      }
      webView.evaluateJavascript(js, null)
    }
  }
}

private fun buildCosmeticJs(
  hideSelectors: List<String>,
  styleRules: List<String>,
  proceduralFilters: List<String>,
  exceptions: List<String>,
  generichide: Boolean,
  injectedScript: String,
  adblockBridgeName: String,
  bridgeToken: String,
  adblockCosmeticScript: String,
): String {
  if (hideSelectors.isEmpty() && styleRules.isEmpty() &&
    proceduralFilters.isEmpty() && injectedScript.isBlank() && generichide
  ) {
    return ""
  }
  val cssParts = mutableListOf<String>()
  cssParts.add(
    "[data-adblock-hide] { display: none !important; min-height: 0 !important; min-width: 0 !important; height: 0 !important; }",
  )
  if (styleRules.isNotEmpty()) {
    cssParts.addAll(styleRules)
  }
  val config =
    JSONObject()
      .put("baseCss", cssParts.joinToString("\n"))
      .put("hideSelectors", org.json.JSONArray().apply { hideSelectors.forEach { put(it) } })
      .put(
        "proceduralFilters",
        org.json.JSONArray().apply { proceduralFilters.forEach { put(it) } },
      )
      .put("exceptions", org.json.JSONArray().apply { exceptions.forEach { put(it) } })
      .put("generichide", generichide)
      .put("injectedScript", injectedScript)
      .put("adblockBridgeName", adblockBridgeName)
      .put("bridgeToken", bridgeToken)
      .toString()
  return "window.__translatorAdblockConfig = $config;\n$adblockCosmeticScript"
}
