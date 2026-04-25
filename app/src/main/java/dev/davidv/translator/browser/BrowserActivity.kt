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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.davidv.translator.Language
import dev.davidv.translator.R
import dev.davidv.translator.TranslationService
import dev.davidv.translator.TranslatorApplication
import dev.davidv.translator.adblock.AdblockManager
import dev.davidv.translator.adblock.mapRequestType
import dev.davidv.translator.adblock.refererOf
import dev.davidv.translator.ui.components.LanguageSelectionRow
import dev.davidv.translator.ui.theme.TranslatorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

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
            translationService = app.translationService,
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
  translationService: TranslationService,
  adblockManager: AdblockManager,
  onFinish: () -> Unit,
) {
  val context = LocalContext.current
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
  var topBarScrollAccumulator by remember { mutableStateOf(0) }
  var readerModeEnabled by remember { mutableStateOf(false) }
  var readerModeAvailable by remember { mutableStateOf(false) }
  var loadingReaderMode by remember { mutableStateOf(false) }
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
      adblockManager = adblockManager,
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
      onPageStarted = {
        if (!loadingReaderMode) {
          readerModeEnabled = false
          readerModeAvailable = false
        }
      },
      onPageFinished = { webView ->
        loadingReaderMode = false
        if (!readerModeEnabled) {
          val js = buildReaderModeJs(readabilityScript, readerModeScript, probeOnly = true)
          webView.evaluateJavascript(js) { result ->
            readerModeAvailable = result == "true"
          }
        }
      },
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
          drawable =
            Pair(
              if (readerModeEnabled) "Exit reader mode" else "Reader mode",
              R.drawable.chrome_reader_mode,
            ),
          onSettings =
            if (readerModeEnabled || readerModeAvailable) {
              {
                val webView = webViewRef[0]
                if (webView != null) {
                  if (readerModeEnabled) {
                    if (webView.canGoBack()) {
                      webView.goBack()
                    } else {
                      webView.reload()
                    }
                  } else {
                    val js = buildReaderModeJs(readabilityScript, readerModeScript, probeOnly = false)
                    webView.evaluateJavascript(js) { result ->
                      val readerHtml = decodeJavascriptString(result)
                      if (readerHtml == null) {
                        readerModeAvailable = false
                        Toast.makeText(context, "Reader mode unavailable for this page", Toast.LENGTH_SHORT).show()
                      } else {
                        loadingReaderMode = true
                        readerModeEnabled = true
                        val baseUrl = webView.url
                        webView.loadDataWithBaseURL(baseUrl, readerHtml, "text/html", "UTF-8", baseUrl)
                      }
                    }
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserWebView(
  url: String,
  sourceLang: Language,
  targetLang: Language,
  contentScript: String,
  translationService: TranslationService,
  adblockManager: AdblockManager,
  onScrollDelta: (y: Int, dy: Int) -> Unit,
  onWebViewReady: (WebView) -> Unit,
  onPageStarted: () -> Unit,
  onPageFinished: (WebView) -> Unit,
) {
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val bridgeRef = remember { arrayOfNulls<TranslatorJsBridge>(1) }
  val currentPageUrlRef = remember { java.util.concurrent.atomic.AtomicReference<String?>(null) }

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
      webView.addJavascriptInterface(AdblockJsBridge(adblockManager), "__adblockBridge")

      webView.webViewClient =
        object : WebViewClient() {
          override fun onPageStarted(
            view: WebView?,
            url: String?,
            favicon: Bitmap?,
          ) {
            Log.i("AdblockManager", "onPageStarted view=$view url=$url")
            onPageStarted()
            currentPageUrlRef.set(url)
            if (view != null && url != null) {
              applyCosmeticFiltersAsync(scope, view, adblockManager, url)
            }
          }

          override fun onPageFinished(
            view: WebView?,
            url: String?,
          ) {
            view?.evaluateJavascript(contentScript, null)
            if (view != null) onPageFinished(view)
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

private fun buildReaderModeJs(
  readabilityScript: String,
  readerModeScript: String,
  probeOnly: Boolean,
): String {
  val readabilityLiteral = JSONObject.quote(readabilityScript)
  val readerModeLiteral = JSONObject.quote(readerModeScript)
  return "window.__translatorReadabilityScript = $readabilityLiteral;\n" +
    "window.__translatorReaderModeProbe = $probeOnly;\n" +
    "(0, eval)($readerModeLiteral);"
}

private fun decodeJavascriptString(value: String): String? {
  if (value == "null") return null
  return runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
}

private fun applyCosmeticFiltersAsync(
  scope: CoroutineScope,
  webView: WebView,
  adblockManager: AdblockManager,
  url: String,
) {
  Log.i("AdblockManager", "onPageStarted url=$url scheduling lookup")
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
      )
    if (js.isEmpty()) {
      Log.i("AdblockManager", "url=$url empty cosmetic, skipping")
      return@launch
    }
    webView.evaluateJavascript(js, null)
  }
}

private fun buildCosmeticJs(
  hideSelectors: List<String>,
  styleRules: List<String>,
  proceduralFilters: List<String>,
  exceptions: List<String>,
  generichide: Boolean,
  injectedScript: String,
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
  val css = cssParts.joinToString("\n")
  val hideSelectorsLiteral =
    org.json.JSONArray().apply { hideSelectors.forEach { put(it) } }.toString()
  val cssLiteral = JSONObject.quote(css)
  val proceduralLiteral =
    org.json.JSONArray().apply { proceduralFilters.forEach { put(it) } }.toString()
  val exceptionsLiteral =
    org.json.JSONArray().apply { exceptions.forEach { put(it) } }.toString()
  val scriptLiteral = JSONObject.quote(injectedScript)
  val generichideLiteral = if (generichide) "true" else "false"
  return """
    (function(){
      var baseCss = $cssLiteral;
      var hideSelectors = $hideSelectorsLiteral;
      var js = $scriptLiteral;
      var procedurals = $proceduralLiteral;
      var exceptions = $exceptionsLiteral;
      var generichide = $generichideLiteral;

      var jsInjected = false;
      var validatedHideCss = null;

      function isValidSelector(selector) {
        try {
          document.querySelector(selector);
          return true;
        } catch (e) {
          return false;
        }
      }

      function buildCssText() {
        if (validatedHideCss === null) {
          var valid = [];
          var invalid = 0;
          for (var i = 0; i < hideSelectors.length; i++) {
            if (isValidSelector(hideSelectors[i])) valid.push(hideSelectors[i]);
            else invalid++;
          }
          validatedHideCss = valid.length
            ? valid.join(',\n') + ' { display: none !important; min-height: 0 !important; min-width: 0 !important; height: 0 !important; }\n'
            : '';
          if (invalid) console.log('[adblock] skipped invalid cosmetic selectors: ' + invalid);
        }
        return validatedHideCss + baseCss;
      }

      function applyCss() {
        var root = document.head || document.documentElement || document.body;
        if (!root) return false;
        try {
          var css = buildCssText();
          if (css) {
            var existing = document.querySelector('style[data-adblock="1"]');
            if (!existing || !existing.isConnected || existing.parentNode !== root) {
              if (existing && !existing.isConnected) existing.remove();
              var s = document.createElement('style');
              s.setAttribute('data-adblock','1');
              s.textContent = css;
              root.appendChild(s);
            } else if (existing.textContent !== css) {
              existing.textContent = css;
            }
          }
          if (js && !jsInjected) {
            var sc = document.createElement('script');
            sc.setAttribute('data-adblock','1');
            sc.textContent = js;
            root.appendChild(sc);
            sc.remove();
            jsInjected = true;
          }
          return true;
        } catch (e) {
          console.warn('[adblock] cosmetic apply failed', e);
          return true;
        }
      }

      function guardCss() {
        try {
          var target = document.documentElement;
          if (!target) return;
          var mo = new MutationObserver(function(){
            var existing = document.querySelector('style[data-adblock="1"]');
            if (!existing || !existing.isConnected) applyCss();
          });
          mo.observe(target, { childList: true, subtree: true });
        } catch (e) {}
      }

      // ---- procedural filter pipeline (uBO-style) ----
      function opTask(op) {
        var arg = op.arg;
        switch (op.type) {
          case 'CssSelector':
            return function(nodes) {
              if (nodes === null) {
                try { return Array.prototype.slice.call(document.querySelectorAll(arg)); }
                catch (e) { return []; }
              }
              var out = [];
              for (var i = 0; i < nodes.length; i++) {
                try { out.push.apply(out, nodes[i].querySelectorAll(arg)); } catch (e) {}
              }
              return out;
            };
          case 'HasText':
            var re;
            if (arg && arg.length > 1 && arg.charAt(0) === '/' &&
                arg.lastIndexOf('/') > 0) {
              var slash = arg.lastIndexOf('/');
              try { re = new RegExp(arg.slice(1, slash), arg.slice(slash + 1)); } catch (e) {}
            }
            var literal = arg;
            return function(nodes) {
              return nodes.filter(function(n){
                var t = (n.textContent || '');
                return re ? re.test(t) : t.indexOf(literal) >= 0;
              });
            };
          case 'MatchesCss':
          case 'MatchesCssBefore':
          case 'MatchesCssAfter':
            // arg shape: "prop: value" possibly more complex
            var idx = arg.indexOf(':');
            if (idx < 0) return function(nodes){ return []; };
            var prop = arg.slice(0, idx).trim();
            var val = arg.slice(idx + 1).trim();
            var pseudo = op.type === 'MatchesCssBefore' ? '::before'
                       : op.type === 'MatchesCssAfter' ? '::after' : null;
            return function(nodes) {
              return nodes.filter(function(n){
                try {
                  var cs = window.getComputedStyle(n, pseudo);
                  return cs && cs.getPropertyValue(prop).trim() === val;
                } catch (e) { return false; }
              });
            };
          case 'Upward':
            // arg: integer N, or selector
            var n = parseInt(arg, 10);
            if (!isNaN(n) && /^-?\d+$/.test(String(arg))) {
              return function(nodes) {
                var out = [];
                for (var i = 0; i < nodes.length; i++) {
                  var p = nodes[i];
                  for (var k = 0; k < n && p; k++) p = p.parentElement;
                  if (p) out.push(p);
                }
                return out;
              };
            }
            return function(nodes) {
              var out = [];
              for (var i = 0; i < nodes.length; i++) {
                try {
                  var anc = nodes[i].parentElement && nodes[i].parentElement.closest(arg);
                  if (anc) out.push(anc);
                } catch (e) {}
              }
              return out;
            };
          default:
            return null; // unsupported -> drop entire filter
        }
      }

      function compileFilter(filter) {
        var tasks = [];
        for (var i = 0; i < filter.selector.length; i++) {
          var t = opTask(filter.selector[i]);
          if (!t) return null;
          tasks.push(t);
        }
        return tasks;
      }

      function runFilter(filter) {
        var tasks = compileFilter(filter);
        if (!tasks) return;
        var nodes = null;
        for (var i = 0; i < tasks.length; i++) nodes = tasks[i](nodes);
        if (!nodes || nodes.length === 0) return;
        var action = filter.action;
        if (!action) {
          for (var j = 0; j < nodes.length; j++) nodes[j].setAttribute('data-adblock-hide', '1');
          return;
        }
        switch (action.type) {
          case 'Style':
            for (var j2 = 0; j2 < nodes.length; j2++) {
              try { nodes[j2].style.cssText += ';' + action.arg; } catch (e) {}
            }
            return;
          case 'Remove':
            for (var j3 = 0; j3 < nodes.length; j3++) {
              try { nodes[j3].remove(); } catch (e) {}
            }
            return;
          case 'RemoveAttr':
            for (var j4 = 0; j4 < nodes.length; j4++) {
              try { nodes[j4].removeAttribute(action.arg); } catch (e) {}
            }
            return;
          case 'RemoveClass':
            for (var j5 = 0; j5 < nodes.length; j5++) {
              try { nodes[j5].classList.remove(action.arg); } catch (e) {}
            }
            return;
        }
      }

      function runProcedurals() {
        if (!procedurals || procedurals.length === 0) return;
        var hits = 0;
        for (var i = 0; i < procedurals.length; i++) {
          var f;
          try { f = JSON.parse(procedurals[i]); } catch (e) { continue; }
          try { runFilter(f); hits++; } catch (e) {}
        }
      }


      function ready(fn) {
        if (document.readyState === 'loading') {
          document.addEventListener('DOMContentLoaded', fn, { once: true });
        } else {
          fn();
        }
      }

      function applyGeneric() {
        if (generichide) return;
        if (!window.__adblockBridge) return;
        var classes = new Set();
        var ids = new Set();
        var els = document.getElementsByTagName('*');
        for (var i = 0; i < els.length; i++) {
          var el = els[i];
          if (el.id) ids.add(el.id);
          var cls = el.getAttribute && el.getAttribute('class');
          if (cls) {
            var parts = cls.split(/\s+/);
            for (var j = 0; j < parts.length; j++) {
              if (parts[j]) classes.add(parts[j]);
            }
          }
        }
        var classArr = JSON.stringify(Array.from(classes));
        var idArr = JSON.stringify(Array.from(ids));
        var exArr = JSON.stringify(exceptions || []);
        var resultJson;
        try {
          resultJson = window.__adblockBridge.lookupGenericSelectors(classArr, idArr, exArr);
        } catch (e) {
          console.warn('[adblock] generic lookup failed', e);
          return;
        }
        var selectors;
        try { selectors = JSON.parse(resultJson); } catch (e) { return; }
        if (!selectors || selectors.length === 0) return;
        var style = document.createElement('style');
        style.setAttribute('data-adblock-generic', '1');
        style.textContent =
          selectors.join(',\n') +
          ' { display: none !important; min-height: 0 !important; min-width: 0 !important; height: 0 !important; }';
        (document.head || document.documentElement).appendChild(style);
        console.log('[adblock] generic selectors applied: ' + selectors.length);
      }

      if (!applyCss()) {
        var tries = 0;
        var iv = setInterval(function(){
          tries++;
          if (applyCss() || tries > 50) clearInterval(iv);
        }, 20);
      }
      ready(function(){
        applyCss(); // re-assert in case the framework rebuilt <head>
        guardCss();
        runProcedurals();
        applyGeneric();
      });
    })();
    """.trimIndent()
}
