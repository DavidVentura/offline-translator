(function () {
  function ensureReadability() {
    if (typeof Readability === 'function') return true;
    try {
      (0, eval)(window.__translatorReadabilityScript || '');
      return typeof Readability === 'function';
    } catch (e) {
      console.warn('[reader-mode] failed to load Readability', e);
      return false;
    }
  }

  function escapeHtml(value) {
    return String(value || '').replace(/[&<>"']/g, function (ch) {
      return {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;'
      }[ch];
    });
  }

  function sanitizeArticleHtml(html) {
    var container = document.createElement('template');
    container.innerHTML = html || '';
    var blocked = container.content.querySelectorAll(
      'script, style, iframe, object, embed, form, input, button, textarea, select, link, meta'
    );
    for (var i = 0; i < blocked.length; i++) blocked[i].remove();

    var walker = document.createTreeWalker(container.content, NodeFilter.SHOW_ELEMENT);
    var node;
    while ((node = walker.nextNode())) {
      var attrs = Array.prototype.slice.call(node.attributes || []);
      for (var j = 0; j < attrs.length; j++) {
        var name = attrs[j].name;
        var lowerName = name.toLowerCase();
        var value = attrs[j].value || '';
        if (lowerName.indexOf('on') === 0 || lowerName === 'srcdoc') {
          node.removeAttribute(name);
          continue;
        }
        if (
          (lowerName === 'href' || lowerName === 'src' || lowerName === 'xlink:href') &&
          /^\s*javascript:/i.test(value)
        ) {
          node.removeAttribute(name);
        }
      }
    }
    return container.innerHTML;
  }

  function buildReaderDocument(article) {
    var title = escapeHtml(article.title || document.title || 'Reader mode');
    var byline = article.byline ? '<p class="reader-byline">' + escapeHtml(article.byline) + '</p>' : '';
    var siteName = article.siteName ? '<p class="reader-site">' + escapeHtml(article.siteName) + '</p>' : '';
    var content = sanitizeArticleHtml(article.content);
    var lang = escapeHtml(article.lang || document.documentElement.lang || '');
    var dir = escapeHtml(article.dir || document.dir || 'auto');
    return (
      '<!doctype html><html lang="' +
      lang +
      '" dir="' +
      dir +
      '">' +
      '<head><meta charset="utf-8">' +
      '<meta name="viewport" content="width=device-width, initial-scale=1">' +
      '<title>' +
      title +
      '</title>' +
      '<style>' +
      ':root{color-scheme:light dark}' +
      'body{margin:0;background:#f6f0e4;color:#211f1a;font:19px/1.68 Georgia,"Times New Roman",serif}' +
      'main{box-sizing:border-box;max-width:min(760px,calc(100vw - 32px));margin:0 auto;padding:72px 0 40px}' +
      '.reader-site{margin:0 0 10px;color:#746c5b;font:600 13px/1.2 sans-serif;letter-spacing:.08em;text-transform:uppercase}' +
      'h1{margin:0 0 10px;color:#171510;font:700 34px/1.14 Georgia,"Times New Roman",serif}' +
      '.reader-byline{margin:0 0 28px;color:#746c5b;font:15px/1.4 sans-serif}' +
      'p,li,blockquote{color:inherit}' +
      'a{color:#7b4a1f}' +
      'img,video,figure,pre,table{max-width:100%;height:auto}' +
      'img,video{border-radius:10px}' +
      'pre{overflow:auto}' +
      'figcaption{color:#746c5b;font:14px/1.4 sans-serif}' +
      '@media (prefers-color-scheme:dark){body{background:#191714;color:#eee5d5}h1{color:#fff7e8}.reader-site,.reader-byline,figcaption{color:#a99f8d}a{color:#e0a15c}}' +
      '</style></head><body><main>' +
      siteName +
      '<h1>' +
      title +
      '</h1>' +
      byline +
      content +
      '</main></body></html>'
    );
  }

  if (!ensureReadability()) return window.__translatorReaderModeProbe ? false : null;

  try {
    var clone = document.cloneNode(true);
    var article = new Readability(clone, {
      classesToPreserve: [
        'caption',
        'emoji',
        'hidden',
        'invisible',
        'sr-only',
        'visually-hidden',
        'visuallyhidden',
        'wp-caption',
        'wp-caption-text',
        'wp-smiley'
      ]
    }).parse();
    if (!article || !article.content || article.length < 500) {
      return window.__translatorReaderModeProbe ? false : null;
    }
    return window.__translatorReaderModeProbe ? true : buildReaderDocument(article);
  } catch (e) {
    console.warn('[reader-mode] failed', e);
    return window.__translatorReaderModeProbe ? false : null;
  }
})();
