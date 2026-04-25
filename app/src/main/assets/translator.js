(function () {
  if (window.__translator) return;

  const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
  const bridgeName = typeof __translatorBridgeName === 'string' ? __translatorBridgeName : null;
  const bridgeToken = typeof __translatorBridgeToken === 'string' ? __translatorBridgeToken : null;
  const bridge = bridgeName ? window[bridgeName] : null;
  if (!bridge || !bridgeToken) return;
  const pending = new Map();
  let nextId = 1;

  // Wire format: length-prefixed records. `<len>:<text><len>:<text>...`
  // where `len` is the UTF-16 code-unit count of the text (matches JS
  // String.length and Kotlin String.length). Robust to any content,
  // cheaper than JSON because no per-char escaping.
  function encodeWire(items) {
    let s = '';
    for (let i = 0; i < items.length; i++) {
      const t = items[i];
      s += t.length + ':' + t;
    }
    return s;
  }

  function decodeWire(s) {
    const items = [];
    let i = 0;
    while (i < s.length) {
      const colon = s.indexOf(':', i);
      if (colon < 0) return null;
      const len = parseInt(s.slice(i, colon), 10);
      if (!Number.isFinite(len) || len < 0) return null;
      const start = colon + 1;
      const end = start + len;
      if (end > s.length) return null;
      items.push(s.slice(start, end));
      i = end;
    }
    return items;
  }

  function send(method, payload) {
    return new Promise((resolve, reject) => {
      const id = nextId++;
      pending.set(id, { resolve, reject });
      try {
        bridge[method](id, encodeWire(payload), bridgeToken, nonce);
      } catch (e) {
        pending.delete(id);
        reject(e);
      }
    });
  }

  const BLOCK_TAGS = new Set([
    'P', 'LI', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6',
    'TD', 'TH', 'FIGCAPTION', 'DT', 'DD', 'BLOCKQUOTE',
    'SUMMARY', 'CAPTION', 'LEGEND',
  ]);
  const STRUCTURAL_TAGS = new Set([
    'DIV', 'SECTION', 'ARTICLE', 'ASIDE', 'HEADER', 'FOOTER',
    'NAV', 'MAIN', 'FIGURE', 'DETAILS', 'FORM', 'ADDRESS',
  ]);
  const SKIP_TAGS = new Set([
    'SCRIPT', 'STYLE', 'CODE', 'PRE', 'KBD', 'SAMP', 'VAR',
    'OBJECT', 'EMBED', 'NOSCRIPT', 'TEMPLATE', 'IFRAME',
    'TEXTAREA', 'SVG', 'MATH', 'CANVAS',
  ]);

  const BATCH_MAX = 50;
  const DRAIN_INTERVAL_MS = 25;
  const INTERSECTION_MARGIN = '500px 0px';

  const GENERIC_ATTRS = [
    'title', 'placeholder', 'alt',
    'aria-label', 'aria-description', 'aria-placeholder',
    'aria-roledescription', 'aria-valuetext', 'aria-braillelabel',
  ];
  const ATTR_SELECTOR = GENERIC_ATTRS.map(a => `[${a}]`).join(',');

  const OBSERVER_OPTS = {
    childList: true,
    subtree: true,
    characterData: true,
  };

  function isBlockLike(el) {
    if (el.getAttribute && el.getAttribute('role') === 'heading') return true;
    return BLOCK_TAGS.has(el.tagName) || STRUCTURAL_TAGS.has(el.tagName);
  }

  function isSkippedBySelf(el) {
    if (SKIP_TAGS.has(el.tagName)) return true;
    if (el.getAttribute && el.getAttribute('translate') === 'no') return true;
    if (el.classList && el.classList.contains('notranslate')) return true;
    if (el.hasAttribute && el.hasAttribute('contenteditable')) return true;
    return false;
  }

  function isSkippedByAncestor(el) {
    for (let n = el; n; n = n.parentElement) {
      if (n.nodeType !== 1) continue;
      if (isSkippedBySelf(n)) return true;
    }
    return false;
  }

  function hasTranslatedAncestor(el) {
    for (let n = el; n; n = n.parentElement) {
      if (n.nodeType !== 1) continue;
      if (n.hasAttribute && n.hasAttribute('data-xlated')) return true;
    }
    return false;
  }

  function hasMeaningfulText(el) {
    const t = el.textContent;
    return !!t && t.trim().length > 0;
  }

  function collectUnits(root, units) {
    const kids = root.children;
    for (let i = 0; i < kids.length; i++) {
      walk(kids[i], units);
    }
  }

  // Single bottom-up pass: returns whether this subtree contains any
  // block-like element so the caller can decide if it should itself be a
  // leaf unit. Pushes leaf units into `target` as it goes. Inside skipped
  // subtrees `target` is null — we still recurse to report block-like-ness
  // (preserving the original semantic where skipped descendants prevent an
  // ancestor from becoming a leaf), but we don't collect anything.
  function walk(el, units) {
    if (el.hasAttribute('data-xlated')) return true;
    const skipped = isSkippedBySelf(el);
    const target = skipped ? null : units;
    if (!skipped && el.shadowRoot) collectUnits(el.shadowRoot, units);
    let childHasBlockLike = false;
    const kids = el.children;
    for (let i = 0; i < kids.length; i++) {
      if (walk(kids[i], target)) childHasBlockLike = true;
    }
    const myselfBlockLike = isBlockLike(el);
    if (target && myselfBlockLike && !childHasBlockLike && hasMeaningfulText(el)) {
      target.push(el);
    }
    return myselfBlockLike || childHasBlockLike;
  }

  const queued = new Set();

  const io = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (e.isIntersecting) enqueue(e.target);
      }
    },
    { rootMargin: INTERSECTION_MARGIN },
  );

  function enqueue(el) {
    if (queued.has(el)) return;
    if (!el.isConnected) return;
    if (el.hasAttribute('data-xlated')) {
      io.unobserve(el);
      return;
    }
    queued.add(el);
    scheduleDrain();
  }

  function observeUnits(units) {
    for (const u of units) io.observe(u);
  }

  let drainScheduled = false;
  let lastDrain = 0;

  function scheduleDrain() {
    if (drainScheduled) return;
    drainScheduled = true;
    requestAnimationFrame(maybeDrain);
  }

  function maybeDrain() {
    drainScheduled = false;
    if (queued.size === 0 && attrQueued.size === 0) return;
    const now = performance.now();
    const wait = lastDrain + DRAIN_INTERVAL_MS - now;
    if (wait > 0) {
      setTimeout(scheduleDrain, wait);
      return;
    }
    lastDrain = now;
    if (queued.size > 0) {
      const batch = Array.from(queued);
      queued.clear();
      for (const el of batch) io.unobserve(el);
      processUnits(batch);
    }
    if (attrQueued.size > 0) {
      drainAttrQueue();
    }
  }

  let observer = null;

  function pauseObserver() {
    if (observer) observer.disconnect();
  }

  function resumeObserver() {
    if (observer) observer.observe(document.body, OBSERVER_OPTS);
  }

  function writeBack(units, translated, snapshots) {
    pauseObserver();
    try {
      for (let i = 0; i < units.length; i++) {
        const el = units[i];
        const out = translated[i];
        if (out == null) continue;
        if (!el.isConnected) continue;
        if (el.textContent !== snapshots[i]) continue;
        el.innerHTML = out;
        el.setAttribute('data-xlated', '1');
      }
    } finally {
      resumeObserver();
    }
  }

  const attrQueued = new Map();

  const attrIo = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (e.isIntersecting) enqueueAttr(e.target);
      }
    },
    { rootMargin: INTERSECTION_MARGIN },
  );

  function translatableAttrsOf(el) {
    const result = [];
    for (const a of GENERIC_ATTRS) {
      if (el.hasAttribute(a)) result.push(a);
    }
    if (el.tagName === 'INPUT') {
      const t = (el.getAttribute('type') || '').toLowerCase();
      if ((t === 'button' || t === 'reset') && el.hasAttribute('value')) {
        result.push('value');
      }
    } else if (el.tagName === 'OPTION' || el.tagName === 'OPTGROUP' || el.tagName === 'TRACK') {
      if (el.hasAttribute('label')) result.push('label');
    }
    return result;
  }

  function enqueueAttr(el) {
    if (attrQueued.has(el)) return;
    if (!el.isConnected) return;
    if (el.hasAttribute('data-xlated-attr')) {
      attrIo.unobserve(el);
      return;
    }
    const attrs = translatableAttrsOf(el);
    if (attrs.length === 0) {
      attrIo.unobserve(el);
      return;
    }
    const snapshot = new Map();
    for (const a of attrs) snapshot.set(a, el.getAttribute(a));
    attrQueued.set(el, snapshot);
    scheduleDrain();
  }

  function observeAttrCandidates(root) {
    const scope =
      root.nodeType === 1 && root.matches && root.matches(ATTR_SELECTOR)
        ? [root]
        : [];
    if (root.querySelectorAll) {
      for (const el of root.querySelectorAll(ATTR_SELECTOR)) scope.push(el);
      for (const el of root.querySelectorAll('input[type=button],input[type=reset],option,optgroup,track')) {
        if (translatableAttrsOf(el).length > 0) scope.push(el);
      }
    }
    for (const el of scope) {
      if (isSkippedByAncestor(el)) continue;
      if (hasTranslatedAncestor(el)) continue;
      attrIo.observe(el);
    }
  }

  function translateMetaAttributes() {
    if (!document.head) return;
    const metas = Array.from(
      document.head.querySelectorAll('meta[name="description"],meta[name="keywords"]'),
    ).filter(m => m.hasAttribute('content') && !m.hasAttribute('data-xlated-attr'));
    if (metas.length === 0) return;
    const texts = metas.map(m => m.getAttribute('content'));
    send('translateTexts', texts)
      .then((translated) => {
        for (let i = 0; i < metas.length; i++) {
          const out = translated[i];
          if (out == null) continue;
          const m = metas[i];
          if (!m.isConnected) continue;
          if (m.getAttribute('content') !== texts[i]) continue;
          m.setAttribute('content', out);
          m.setAttribute('data-xlated-attr', '1');
        }
      })
      .catch((e) => console.warn('[translator] meta batch failed', e));
  }

  async function drainAttrQueue() {
    if (attrQueued.size === 0) return;
    const entries = Array.from(attrQueued.entries());
    attrQueued.clear();
    for (const [el] of entries) attrIo.unobserve(el);

    const records = [];
    for (const [el, snapshot] of entries) {
      if (!el.isConnected) continue;
      for (const [attr, text] of snapshot) {
        if (el.getAttribute(attr) !== text) continue;
        if (!text || text.trim().length === 0) continue;
        records.push({ el, attr, text });
      }
    }
    if (records.length === 0) return;

    for (let i = 0; i < records.length; i += BATCH_MAX) {
      const batch = records.slice(i, i + BATCH_MAX);
      const texts = batch.map(r => r.text);
      let translated;
      try {
        translated = await send('translateTexts', texts);
      } catch (e) {
        console.warn('[translator] attr batch failed', e);
        continue;
      }
      pauseObserver();
      try {
        for (let j = 0; j < batch.length; j++) {
          const r = batch[j];
          const out = translated[j];
          if (out == null) continue;
          if (!r.el.isConnected) continue;
          if (r.el.getAttribute(r.attr) !== r.text) continue;
          r.el.setAttribute(r.attr, out);
          r.el.setAttribute('data-xlated-attr', '1');
        }
      } finally {
        resumeObserver();
      }
    }
  }

  async function processUnits(units) {
    for (let i = 0; i < units.length; i += BATCH_MAX) {
      const batchUnits = units.slice(i, i + BATCH_MAX);
      const batchFragments = batchUnits.map(u => u.innerHTML);
      const batchSnapshots = batchUnits.map(u => u.textContent);
      let translated;
      try {
        translated = await send('translateHtmlFragments', batchFragments);
      } catch (e) {
        console.warn('[translator] batch failed', e);
        continue;
      }
      writeBack(batchUnits, translated, batchSnapshots);
    }
  }

  function handleMutations(muts) {
    const touched = new Set();
    for (const m of muts) {
      if (m.type === 'childList') {
        for (const node of m.addedNodes) {
          if (node.nodeType === 1) touched.add(node);
        }
      } else if (m.type === 'characterData') {
        const p = m.target.parentElement;
        if (p) touched.add(p);
      }
    }
    if (touched.size === 0) return;
    const newUnits = [];
    for (const node of touched) {
      if (!node.isConnected) continue;
      if (isSkippedByAncestor(node)) continue;
      if (hasTranslatedAncestor(node)) continue;
      walk(node, newUnits);
      observeAttrCandidates(node);
    }
    observeUnits(newUnits);
  }

  function scan() {
    if (isSkippedByAncestor(document.documentElement)) return;
    const units = [];
    collectUnits(document.body, units);
    observeUnits(units);
    observeAttrCandidates(document.body);
    translateMetaAttributes();

    if (!observer) {
      observer = new MutationObserver(handleMutations);
      observer.observe(document.body, OBSERVER_OPTS);
    }
  }

  window.__translator = {
    resolve(id, results, callerNonce) {
      if (callerNonce !== nonce) return;
      const entry = pending.get(id);
      if (!entry) return;
      pending.delete(id);
      const parsed =
        typeof results === 'string' ? (decodeWire(results) || []) : results;
      entry.resolve(parsed);
    },
  };

  function hookSpaNavigation() {
    const origPush = history.pushState;
    const origReplace = history.replaceState;
    const emit = () => window.dispatchEvent(new Event('__translator:urlchange'));
    history.pushState = function () {
      origPush.apply(this, arguments);
      emit();
    };
    history.replaceState = function () {
      origReplace.apply(this, arguments);
      emit();
    };
    window.addEventListener('popstate', emit);
    window.addEventListener('__translator:urlchange', () => scan());
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
      scan();
      hookSpaNavigation();
    }, { once: true });
  } else {
    scan();
    hookSpaNavigation();
  }

  console.log('[translator] content script ready');
})();
