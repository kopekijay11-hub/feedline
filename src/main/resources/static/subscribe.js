// Feedline Subscribe popover: Copy / Open in Feedly / Preview.
// Feedly handoff shape verified 2026-11-26:
//   https://feedly.com/i/subscription/feed/<url-encoded feed URL>
// No Inoreader web handoff could be verified, so none is
// offered; Copy serves Inoreader users (paste into its
// subscribe box).
(function () {
  var popover = null;

  function absolute(feedPath) {
    return window.location.origin + feedPath;
  }

  function close() {
    if (popover) { popover.classList.add('hidden'); popover.innerHTML = ''; }
  }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function preview(feedPath, box) {
    box.textContent = 'Loading preview…';
    fetch(feedPath, { headers: { 'Accept': 'application/rss+xml' } })
      .then(function (r) { return r.text(); })
      .then(function (xml) {
        var doc = new DOMParser().parseFromString(xml, 'text/xml');
        var items = doc.querySelectorAll('item');
        box.innerHTML = '';
        var n = Math.min(items.length, 5);
        if (n === 0) { box.textContent = 'No items in this feed yet.'; return; }
        var list = el('ul', 'preview-list');
        for (var i = 0; i < n; i++) {
          var t = items[i].querySelector('title');
          var d = items[i].querySelector('pubDate');
          var li = el('li', null, (t ? t.textContent : '(untitled)') +
            (d ? ' — ' + d.textContent : ''));
          list.appendChild(li);
        }
        box.appendChild(list);
      })
      .catch(function () { box.textContent = 'Preview failed to load.'; });
  }

  function open(row) {
    if (!popover) return;
    var url = absolute(row.getAttribute('data-feed'));
    var name = row.getAttribute('data-name');
    popover.innerHTML = '';
    popover.appendChild(el('p', 'popover-title', name));
    popover.appendChild(el('p', 'popover-url', url));
    var copy = el('button', 'btn btn-secondary', 'Copy link');
    copy.type = 'button';
    copy.addEventListener('click', function () {
      navigator.clipboard.writeText(url).then(function () {
        copy.textContent = 'Copied';
        setTimeout(function () { copy.textContent = 'Copy link'; }, 1500);
      });
    });
    popover.appendChild(copy);
    if (row.getAttribute('data-feed').indexOf('.opml') < 0) {
      var feedly = el('a', 'btn btn-secondary', 'Open in Feedly');
      feedly.href = 'https://feedly.com/i/subscription/feed/' +
        encodeURIComponent(url);
      feedly.target = '_blank';
      feedly.rel = 'noopener';
      popover.appendChild(feedly);
      var box = el('div', 'popover-preview');
      popover.appendChild(box);
      preview(row.getAttribute('data-feed'), box);
    } else {
      popover.appendChild(el('p', 'muted',
        'Paste this link into your reader’s OPML import.'));
    }
    var x = el('button', 'btn btn-ghost', 'Close');
    x.type = 'button';
    x.addEventListener('click', close);
    popover.appendChild(x);
    popover.classList.remove('hidden');
  }

  function wire() {
    popover = document.getElementById('subscribe-popover');
    var rows = document.querySelectorAll('.feed-row');
    rows.forEach(function (row) {
      row.addEventListener('click', function () { open(row); });
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') close();
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', wire);
  } else { wire(); }
})();
