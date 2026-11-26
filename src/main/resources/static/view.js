// Feedline dashboard view switching (Reader / Ops).
// State: location.hash wins, then localStorage, then Reader.
(function () {
  var KEY = 'feedline.view';
  var VIEWS = ['reader', 'ops'];

  function valid(name) { return VIEWS.indexOf(name) >= 0; }

  function initialView() {
    var fromHash = (window.location.hash || '').replace('#', '');
    if (valid(fromHash)) return fromHash;
    try {
      var saved = window.localStorage.getItem(KEY);
      if (valid(saved)) return saved;
    } catch (e) { /* storage unavailable: fall through */ }
    return 'reader';
  }

  function setView(name, pushHash) {
    if (!valid(name)) name = 'reader';
    VIEWS.forEach(function (v) {
      var pane = document.getElementById('view-' + v);
      if (pane) pane.classList.toggle('hidden', v !== name);
      var btn = document.getElementById('view-' + v + '-btn');
      if (btn) {
        btn.classList.toggle('active', v === name);
        btn.setAttribute('aria-selected', v === name ? 'true' : 'false');
      }
    });
    try { window.localStorage.setItem(KEY, name); } catch (e) {}
    if (pushHash && window.location.hash !== '#' + name) {
      history.replaceState(null, '', '#' + name);
    }
  }

  function wire() {
    VIEWS.forEach(function (v) {
      var btn = document.getElementById('view-' + v + '-btn');
      if (btn) btn.addEventListener('click', function () { setView(v, true); });
    });
    window.addEventListener('hashchange', function () { setView(initialView(), false); });
    setView(initialView(), false);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', wire);
  } else {
    wire();
  }
})();
