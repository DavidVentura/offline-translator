(function () {
  var config = window.__translatorAdblockConfig;
  if (!config) return;

  if (window.__translatorAdblockRuntime) {
    window.__translatorAdblockRuntime.configure(config);
    return;
  }

  var state = {
    config: config,
    jsInjected: false,
    validatedHideCss: null,
    pendingRun: false,
    observer: null,
  };

  var RUN_DELAY_MS = 120;

  // Wire format: length-prefixed records `<len>:<text><len>:<text>...`
  // (UTF-16 code-unit counts; matches Kotlin String.length on the bridge
  // side). Robust to any content, no escape work.
  function encodeWire(items) {
    var s = '';
    for (var i = 0; i < items.length; i++) {
      var t = items[i];
      s += t.length + ':' + t;
    }
    return s;
  }

  function decodeWire(s) {
    var items = [];
    var i = 0;
    while (i < s.length) {
      var colon = s.indexOf(':', i);
      if (colon < 0) return null;
      var len = parseInt(s.slice(i, colon), 10);
      if (!isFinite(len) || len < 0) return null;
      var start = colon + 1;
      var end = start + len;
      if (end > s.length) return null;
      items.push(s.slice(start, end));
      i = end;
    }
    return items;
  }

  function current() {
    return state.config || {};
  }

  function isValidSelector(selector) {
    try {
      document.querySelector(selector);
      return true;
    } catch (e) {
      return false;
    }
  }

  function buildCssText() {
    var cfg = current();
    if (state.validatedHideCss === null) {
      var valid = [];
      var invalid = 0;
      var selectors = cfg.hideSelectors || [];
      for (var i = 0; i < selectors.length; i++) {
        if (isValidSelector(selectors[i])) valid.push(selectors[i]);
        else invalid++;
      }
      state.validatedHideCss = valid.length
        ? valid.join(',\n') + ' { display: none !important; min-height: 0 !important; min-width: 0 !important; height: 0 !important; }\n'
        : '';
      if (invalid) console.log('[adblock] skipped invalid cosmetic selectors: ' + invalid);
    }
    return state.validatedHideCss + (cfg.baseCss || '');
  }

  function rootNode() {
    return document.head || document.documentElement || document.body;
  }

  function applyCss() {
    var root = rootNode();
    if (!root) return false;
    try {
      var css = buildCssText();
      if (css) {
        var existing = document.querySelector('style[data-adblock="1"]');
        if (!existing || !existing.isConnected || existing.parentNode !== root) {
          if (existing && !existing.isConnected) existing.remove();
          var s = document.createElement('style');
          s.setAttribute('data-adblock', '1');
          s.textContent = css;
          root.appendChild(s);
        } else if (existing.textContent !== css) {
          existing.textContent = css;
        }
      }
      injectScriptlet(root);
      return true;
    } catch (e) {
      console.warn('[adblock] cosmetic apply failed', e);
      return true;
    }
  }

  function injectScriptlet(root) {
    var js = current().injectedScript || '';
    if (!js || state.jsInjected) return;
    var sc = document.createElement('script');
    sc.setAttribute('data-adblock', '1');
    sc.textContent = js;
    root.appendChild(sc);
    sc.remove();
    state.jsInjected = true;
  }

  function opTask(op) {
    var arg = op.arg;
    switch (op.type) {
      case 'CssSelector':
        return function (nodes) {
          if (nodes === null) {
            try {
              return Array.prototype.slice.call(document.querySelectorAll(arg));
            } catch (e) {
              return [];
            }
          }
          var out = [];
          for (var i = 0; i < nodes.length; i++) {
            try {
              out.push.apply(out, nodes[i].querySelectorAll(arg));
            } catch (e) {}
          }
          return out;
        };
      case 'HasText':
        var re;
        if (arg && arg.length > 1 && arg.charAt(0) === '/' && arg.lastIndexOf('/') > 0) {
          var slash = arg.lastIndexOf('/');
          try {
            re = new RegExp(arg.slice(1, slash), arg.slice(slash + 1));
          } catch (e) {}
        }
        var literal = arg;
        return function (nodes) {
          return nodes.filter(function (n) {
            var t = n.textContent || '';
            return re ? re.test(t) : t.indexOf(literal) >= 0;
          });
        };
      case 'MatchesCss':
      case 'MatchesCssBefore':
      case 'MatchesCssAfter':
        var idx = arg.indexOf(':');
        if (idx < 0) return function () { return []; };
        var prop = arg.slice(0, idx).trim();
        var val = arg.slice(idx + 1).trim();
        var pseudo = op.type === 'MatchesCssBefore' ? '::before'
          : op.type === 'MatchesCssAfter' ? '::after' : null;
        return function (nodes) {
          return nodes.filter(function (n) {
            try {
              var cs = window.getComputedStyle(n, pseudo);
              return cs && cs.getPropertyValue(prop).trim() === val;
            } catch (e) {
              return false;
            }
          });
        };
      case 'Upward':
        var n = parseInt(arg, 10);
        if (!isNaN(n) && /^-?\d+$/.test(String(arg))) {
          return function (nodes) {
            var out = [];
            for (var i = 0; i < nodes.length; i++) {
              var p = nodes[i];
              for (var k = 0; k < n && p; k++) p = p.parentElement;
              if (p) out.push(p);
            }
            return out;
          };
        }
        return function (nodes) {
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
        return null;
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
          try {
            nodes[j2].style.cssText += ';' + action.arg;
          } catch (e) {}
        }
        return;
      case 'Remove':
        for (var j3 = 0; j3 < nodes.length; j3++) {
          try {
            nodes[j3].remove();
          } catch (e) {}
        }
        return;
      case 'RemoveAttr':
        for (var j4 = 0; j4 < nodes.length; j4++) {
          try {
            nodes[j4].removeAttribute(action.arg);
          } catch (e) {}
        }
        return;
      case 'RemoveClass':
        for (var j5 = 0; j5 < nodes.length; j5++) {
          try {
            nodes[j5].classList.remove(action.arg);
          } catch (e) {}
        }
        return;
    }
  }

  function runProcedurals() {
    var procedurals = current().proceduralFilters || [];
    if (procedurals.length === 0) return;
    for (var i = 0; i < procedurals.length; i++) {
      var f;
      try {
        f = JSON.parse(procedurals[i]);
      } catch (e) {
        continue;
      }
      try {
        runFilter(f);
      } catch (e) {}
    }
  }

  function applyGeneric() {
    var cfg = current();
    if (cfg.generichide) return;
    var adblockBridge = window[cfg.adblockBridgeName];
    if (!adblockBridge) return;
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

    var resultPayload;
    try {
      resultPayload = adblockBridge.lookupGenericSelectors(
        encodeWire(Array.from(classes)),
        encodeWire(Array.from(ids)),
        encodeWire(cfg.exceptions || []),
        cfg.bridgeToken
      );
    } catch (e) {
      console.warn('[adblock] generic lookup failed', e);
      return;
    }

    var selectors = decodeWire(resultPayload || '');
    if (!selectors || selectors.length === 0) return;

    var style = document.querySelector('style[data-adblock-generic="1"]');
    if (!style) {
      style = document.createElement('style');
      style.setAttribute('data-adblock-generic', '1');
      (rootNode() || document.documentElement).appendChild(style);
    }
    style.textContent =
      selectors.join(',\n') +
      ' { display: none !important; min-height: 0 !important; min-width: 0 !important; height: 0 !important; }';
  }

  function runAll() {
    state.pendingRun = false;
    applyCss();
    runProcedurals();
    applyGeneric();
  }

  function scheduleRun(delay) {
    if (state.pendingRun) return;
    state.pendingRun = true;
    setTimeout(runAll, delay == null ? RUN_DELAY_MS : delay);
  }

  function setupObserver() {
    if (state.observer || !document.documentElement) return;
    state.observer = new MutationObserver(function () {
      scheduleRun();
    });
    state.observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      characterData: true,
      attributes: true,
      attributeFilter: ['class', 'id', 'style'],
    });
  }

  function ready(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn, { once: true });
    } else {
      fn();
    }
  }

  window.__translatorAdblockRuntime = {
    configure: function (nextConfig) {
      state.config = nextConfig || {};
      state.validatedHideCss = null;
      applyCss();
      setupObserver();
      scheduleRun(0);
    },
  };

  if (!applyCss()) {
    var tries = 0;
    var iv = setInterval(function () {
      tries++;
      if (applyCss() || tries > 50) clearInterval(iv);
    }, 20);
  }

  ready(function () {
    applyCss();
    setupObserver();
    scheduleRun(0);
  });
})();
