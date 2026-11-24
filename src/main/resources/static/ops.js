(() => {
  'use strict';

  const CATEGORY_ORDER = ['All', 'World', 'Business & Markets', 'Canada', 'United States', 'Europe', 'Asia-Pacific', 'Technology', 'Official Releases', 'Company News', 'Other'];

  const state = {
    adminKey: '',
    autoRefresh: true,
    refreshTimer: null,
    itemsCursor: null,
    itemsCursorStack: [],
    itemsLastQuery: null,
    adminBusy: false,
    actuatorOk: null,
    lastSources: null,
    lastSourcesAggregate: null,
    categories: {},
    selectedSourceIds: new Set(),
    panel: {
      health: { lastUpdated: null, hasData: false },
      sources: { lastUpdated: null, hasData: false },
      items: { lastUpdated: null, hasData: false },
      clusters: { lastUpdated: null, hasData: false },
      admin: { lastUpdated: null, hasData: false },
    },
    expandedCluster: null,
    expandedItem: null,
    clusterMembersCache: new Map(),
  };

  const el = (id) => document.getElementById(id);
  const ui = {
    adminKey: el('admin-key'),
    saveKey: el('save-key'),
    clearKey: el('clear-key'),
    autoRefresh: el('auto-refresh'),
    refreshAll: el('refresh-all'),
    overallStatus: el('overall-status'),
    overallStatusText: el('overall-status-text'),
    overallStatusDetail: el('overall-status-detail'),
    actuatorStatus: el('actuator-status'),
    actuatorDetail: el('actuator-detail'),
    outboxPending: el('outbox-pending'),
    outboxPublished: el('outbox-published'),
    outboxPoison: el('outbox-poison'),
    healthUpdated: el('health-updated'),
    healthError: el('health-error'),
    healthState: el('health-state'),
    sourcesBody: el('sources-body'),
    sourcesSummary: el('sources-summary'),
    sourcesError: el('sources-error'),
    sourcesState: el('sources-state'),
    itemsForm: el('items-form'),
    itemsBody: el('items-body'),
    itemsSummary: el('items-summary'),
    itemsError: el('items-error'),
    itemsState: el('items-state'),
    clustersBody: el('clusters-body'),
    clustersState: el('clusters-state'),
    clustersSummary: el('clusters-summary'),
    clustersError: el('clusters-error'),
    itemsPageMeta: el('items-page-meta'),
    itemsPrev: el('items-prev'),
    itemsNext: el('items-next'),
    itemsReset: el('items-reset'),
    filterSource: el('filter-source'),
    categoryChips: el('category-chips'),
    filterSources: el('filter-sources'),
    filterQ: el('filter-q'),
    filterFrom: el('filter-from'),
    filterTo: el('filter-to'),
    filterSize: el('filter-size'),
    poisonBody: el('poison-body'),
    poisonTopic: el('poison-topic'),
    poisonLimit: el('poison-limit'),
    poisonRefresh: el('poison-refresh'),
    replayBatch: el('replay-batch'),
    cleanupPublished: el('cleanup-published'),
    adminError: el('admin-error'),
    adminSummary: el('admin-summary'),
    adminState: el('admin-state'),
  };

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function fmtTime(value) {
    if (!value) return '<span class="muted">—</span>';
    const d = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(d.getTime())) return `<span class="mono">${esc(value)}</span>`;
    return `<span class="mono" title="${esc(value instanceof Date ? value.toISOString() : value)}">${esc(d.toISOString().replace('T', ' ').replace('Z', ' UTC'))}</span>`;
  }

  function fmtNumber(value) {
    return value == null ? '—' : String(value);
  }

  function nowIso() {
    return new Date().toISOString();
  }

  function fmtClock(d) {
    if (!d) return '';
    const dt = d instanceof Date ? d : new Date(d);
    return dt.toISOString().replace('T', ' ').replace('Z', ' UTC');
  }

  function showError(target, err) {
    const msg = formatError(err);
    target.textContent = msg;
    target.classList.remove('hidden');
  }

  function clearError(target) {
    target.textContent = '';
    target.classList.add('hidden');
  }

  function banner(msg, isError) {
    // Use a transient top-of-page status line via overall detail? Keep simple console-safe: reuse overall-status detail temporarily.
    ui.overallStatusDetail.textContent = msg;
    ui.overallStatusDetail.style.color = isError ? '#ffd6d6' : '#d7ecff';
    clearTimeout(banner._timer);
    banner._timer = setTimeout(() => {
      ui.overallStatusDetail.style.color = '';
      updateOverallStatus();
    }, 4000);
  }

  function formatError(err) {
    if (!err) return 'Unknown error';
    if (typeof err === 'string') return err;
    const parts = [];
    if (err.status) parts.push(`HTTP ${err.status}`);
    if (err.title) parts.push(err.title);
    if (err.detail) parts.push(err.detail);
    if (err.message && !err.detail) parts.push(err.message);
    if (err.errors && Array.isArray(err.errors)) parts.push(err.errors.map(e => e.detail || e.message || JSON.stringify(e)).join('; '));
    if (parts.length === 0 && err.body) parts.push(String(err.body).slice(0, 500));
    return parts.join(' — ') || 'Request failed';
  }

  async function fetchJson(url, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set('Accept', 'application/json, application/problem+json');
    if (options.admin) {
      if (!state.adminKey) {
        throw { status: 401, title: 'Admin key required', detail: 'Enter an X-Admin-Token to use admin endpoints. The key is kept in browser memory only.' };
      }
      headers.set('X-Admin-Token', state.adminKey);
    }
    if (options.body && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    let res;
    try {
      res = await fetch(url, { ...options, headers, cache: 'no-store' });
    } catch (e) {
      throw { title: 'Network error', detail: e.message || String(e) };
    }
    const ct = res.headers.get('content-type') || '';
    let body = null;
    let text = '';
    if (ct.includes('json')) {
      try { body = await res.json(); } catch (_) { body = null; }
    } else {
      try { text = await res.text(); } catch (_) { text = ''; }
    }
    if (!res.ok) {
      const problem = (body && typeof body === 'object') ? body : {};
      throw {
        status: res.status,
        title: problem.title || res.statusText || 'Request failed',
        detail: problem.detail || problem.message || text || '',
        body: body || text,
        problem,
      };
    }
    return body;
  }

  function setDefaultWindow() {
    const now = new Date();
    const from = new Date(now.getTime() - 24 * 60 * 60 * 1000);
    ui.filterTo.value = now.toISOString();
    ui.filterFrom.value = from.toISOString();
    if (!ui.filterSize.value) ui.filterSize.value = '20';
  }

  function badge(status) {
    const s = String(status || 'UNKNOWN').toUpperCase();
    let cls = 'unknown';
    if (s === 'UP' || s === 'OK' || s === 'HEALTHY' || s === 'CURRENT') cls = 'up';
    else if (s === 'DEGRADED' || s === 'STALE') cls = 'degraded';
    else if (s === 'DOWN' || s === 'BACKOFF' || s === 'ERROR') cls = 'down';
    else if (s === 'DISABLED') cls = 'disabled';
    return `<span class="badge ${cls}">${esc(s)}</span>`;
  }

  function setPanelState(name, status, text) {
    const elState = ui[name + 'State'];
    if (!elState) return;
    elState.classList.remove('state-loading', 'state-ready', 'state-empty', 'state-error', 'state-stale');
    elState.classList.add('state-' + status);
    if (text != null) elState.textContent = text;
    else {
      const labels = { loading: 'Loading…', ready: 'Up to date', empty: 'Empty', error: 'Error', stale: 'Stale' };
      elState.textContent = labels[status] || status;
    }
    const panel = document.querySelector(`[data-panel="${name}"]`);
    if (panel) {
      panel.classList.remove('is-loading', 'is-ready', 'is-empty', 'is-error', 'is-stale');
      panel.classList.add('is-' + status);
      panel.setAttribute('data-state', status);
    }
  }

  function safeHttpUrl(value) {
    if (!value) return null;
    try {
      const u = new URL(String(value));
      if (u.protocol === 'http:' || u.protocol === 'https:') return u.href;
      return null;
    } catch (_) {
      return null;
    }
  }

  // Shared member rendering for panel row expansion and item badge expansion.
  // Feed-supplied strings pass through esc(); titles become links only when the
  // member link passes safeHttpUrl, otherwise escaped text.
  function clusterMembersHtml(members) {
    const list = Array.isArray(members) ? members : [];
    if (list.length === 0) return '<div class="empty">No members.</div>';
    return list.map(m => {
      const sourceName = esc(m == null ? '' : (m.sourceName || ''));
      const title = esc(m == null ? '' : (m.title || '—'));
      const link = m == null ? null : safeHttpUrl(m.link);
      const titleHtml = link
        ? `<a class="link mono" href="${esc(link)}" target="_blank" rel="noopener noreferrer">${title}</a>`
        : `<span class="mono">${title}</span>`;
      return `<div class="cluster-member"><span class="cluster-source">${sourceName}</span> ${titleHtml}</div>`;
    }).join('');
  }

  function adminClosedMessage(err) {
    if (err && err.status === 401) {
      if (!state.adminKey) {
        return 'Admin API is closed: the server has no FEED_ADMIN_API_KEY configured (or it is missing). This is by design. Set FEED_ADMIN_API_KEY on the server to enable admin actions.';
      }
      return 'Admin key rejected (401). Check that it matches the server FEED_ADMIN_API_KEY.';
    }
    if (err && err.status === 403) {
      return 'Admin access forbidden (403).';
    }
    return null;
  }

  function preserveScroll(wrap, fn) {
    if (!wrap) return fn();
    const top = wrap.scrollTop;
    const left = wrap.scrollLeft;
    const r = fn();
    requestAnimationFrame(() => { wrap.scrollTop = top; wrap.scrollLeft = left; });
    return r;
  }

  async function loadActuator() {
    try {
      const data = await fetchJson('/actuator/health');
      const status = String(data.status || 'UNKNOWN').toUpperCase();
      ui.actuatorStatus.innerHTML = badge(status);
      const components = data.components ? Object.keys(data.components).join(', ') : 'no details exposed';
      ui.actuatorDetail.textContent = `Components: ${components}`;
      clearError(ui.healthError);
      state.actuatorOk = true;
      return true;
    } catch (err) {
      ui.actuatorStatus.innerHTML = badge('DOWN');
      ui.actuatorDetail.textContent = 'GET /actuator/health failed';
      state.actuatorOk = false;
      throw err;
    }
  }

  async function loadOutboxStats() {
    try {
      const data = await fetchJson('/api/v1/admin/outbox/stats', { admin: true });
      ui.outboxPending.textContent = fmtNumber(data.pendingCount);
      ui.outboxPublished.textContent = fmtNumber(data.publishedCount);
      ui.outboxPoison.textContent = fmtNumber(data.poisonedCount);
      ui.adminSummary.textContent = `Admin token active — pending ${data.pendingCount}, poison ${data.poisonedCount}`;
      clearError(ui.healthError);
      return true;
     } catch (err) {
       const closed = adminClosedMessage(err);
      if (closed) {
        ui.adminSummary.textContent = closed;
      } else if (err.status === 401 || err.status === 403) {
        ui.adminSummary.textContent = 'Admin token required for outbox stats/controls';
      }
      if (state.adminKey && !(err.status === 401 || err.status === 403)) {
        showError(ui.healthError, err);
      }
      if (err.status === 401 || err.status === 403) return false;
      throw err;
    }
  }

  async function loadSources() {
    try {
      const data = await fetchJson('/api/v1/sources/health');
      const sources = Array.isArray(data.sources) ? data.sources : [];
      state.lastSources = sources;
      state.lastSourcesAggregate = data;
      const wrap = ui.sourcesBody.closest('.table-wrap');
      preserveScroll(wrap, () => {
        if (sources.length === 0) {
          ui.sourcesBody.innerHTML = '<tr><td colspan="7" class="empty">No sources registered.</td></tr>';
        } else {
          ui.sourcesBody.innerHTML = sources.map(s => {
            const diag = [];
            if (s.lastErrorClass) diag.push(`class=${s.lastErrorClass}`);
            if (s.lastError) diag.push(`error=${s.lastError}`);
            if (s.lastErrorAt) diag.push(`at=${s.lastErrorAt}`);
            const diagText = diag.length ? diag.join('\n') : '—';
            const backoff = [s.backoff ? 'active' : null, s.nextPollAt ? `next=${s.nextPollAt}` : null].filter(Boolean).join(', ');
            return `<tr data-source-id="${esc(s.id)}" style="cursor:pointer" title="Click to read this source">
              <td><div>${esc(s.name || ('Source ' + s.id))}</div><div class="detail mono">id=${esc(s.id)}</div></td>
              <td>${badge(s.state)}</td>
              <td>${fmtTime(s.lastSuccessAt)}<div class="detail">${s.secondsSinceLastSuccess == null ? '' : esc(s.secondsSinceLastSuccess + 's ago')}</div></td>
              <td class="mono">${esc(fmtNumber(s.consecutiveFailures))}</td>
              <td><div class="mono">${esc(backoff || 'none')}</div></td>
              <td><div class="mono">items=${esc(fmtNumber(s.skippedItemCount))}</div><div class="detail mono">occ=${esc(fmtNumber(s.skippedOccurrences))}</div></td>
              <td class="diagnostics">${esc(diagText)}</td>
            </tr>`;
          }).join('');
        }
      });
      const needing = sources.filter(s => s.state !== 'UP' && s.state !== 'DISABLED').length;
      const active = sources.filter(s => s.state !== 'DISABLED').length;
      ui.sourcesSummary.textContent = `${sources.length} sources — ${sources.length - needing} healthy — ${needing} need attention`;
      renderSourcePicker();
      renderChips();
      clearError(ui.sourcesError);
      const now = new Date();
      state.panel.sources.lastUpdated = now;
      state.panel.sources.hasData = true;
      if (sources.length === 0) {
        setPanelState('sources', 'empty', 'Empty — no sources');
      } else {
        setPanelState('sources', 'ready', `Last updated ${fmtClock(now)}`);
      }
      return true;
    } catch (err) {
      if (state.panel.sources.hasData) {
        setPanelState('sources', 'stale', `Last updated ${fmtClock(state.panel.sources.lastUpdated)} — refresh failed`);
        showError(ui.sourcesError, err);
      } else {
        ui.sourcesBody.innerHTML = '<tr><td colspan="7" class="empty">Failed to load sources.</td></tr>';
        setPanelState('sources', 'error', 'Error');
        showError(ui.sourcesError, err);
      }
      throw err;
    }
  }

  async function loadHealth(auto = false) {
    if (!auto) setPanelState('health', 'loading', 'Loading…');
    ui.healthUpdated.textContent = `Refreshing… ${nowIso().replace('T', ' ').replace('Z', ' UTC')}`;
    const results = await Promise.allSettled([loadActuator(), loadOutboxStats(), loadSources()]);
    const failed = results.some(r => r.status === 'rejected');
    updateOverallStatus();
    if (failed) {
      if (state.panel.health.hasData) {
        setPanelState('health', 'stale', `Last updated ${fmtClock(state.panel.health.lastUpdated)} — refresh failed`);
        ui.healthUpdated.textContent = state.panel.health.lastUpdated
          ? `Last updated ${fmtClock(state.panel.health.lastUpdated)} — refresh failed`
          : 'Refresh failed';
      } else {
        setPanelState('health', 'error', 'Error');
        ui.healthUpdated.textContent = 'Refresh failed';
      }
    } else {
      const now = new Date();
      state.panel.health.lastUpdated = now;
      state.panel.health.hasData = true;
      setPanelState('health', 'ready', `Last updated ${fmtClock(now)}`);
      ui.healthUpdated.textContent = `Last updated ${fmtClock(now)}`;
    }
    return !failed;
  }

  function updateOverallStatus() {
    const actuatorOk = state.actuatorOk;
    const sources = state.lastSources;
    const aggregate = state.lastSourcesAggregate;
    let text = 'Checking service health…';
    let detail = 'Loading sources and health checks…';
    let cls = 'loading';
    if (actuatorOk === false) {
      text = 'Service unreachable';
      detail = 'The health check failed. Is the backend running?';
      cls = 'down';
    } else if (!sources) {
      text = 'Checking service health…';
      detail = 'Loading sources and health checks…';
      cls = 'loading';
    } else {
      const total = sources.length;
      const needing = sources.filter(s => s.state !== 'UP' && s.state !== 'DISABLED').length;
      const active = sources.filter(s => s.state !== 'DISABLED').length;
      const heartbeatStale = !!(aggregate && aggregate.heartbeatStale);
      if (total === 0) {
        text = 'No sources registered';
        detail = 'Add feed sources to begin ingestion.';
        cls = 'empty';
      } else if (needing === 0 && !heartbeatStale) {
        text = 'All sources healthy';
        detail = `${total} source${total === 1 ? '' : 's'} polling normally.`;
        cls = 'up';
      } else if (needing > 0) {
        text = `${needing} of ${active} source${active === 1 ? '' : 's'} need attention`;
        detail = `${sources.length - needing} healthy, ${needing} need attention.`;
        cls = 'down';
      } else {
        text = 'Polling heartbeat is stale';
        detail = 'No recent poll cycle completed.';
        cls = 'degraded';
      }
    }
    ui.overallStatusText.textContent = text;
    ui.overallStatusDetail.textContent = detail;
    ui.overallStatus.setAttribute('data-state', cls);
    ui.overallStatus.classList.remove('state-up', 'state-down', 'state-degraded', 'state-loading', 'state-empty');
    ui.overallStatus.classList.add('state-' + cls);
  }

  function categoryOf(name) {
    return (state.categories && Object.prototype.hasOwnProperty.call(state.categories, name))
      ? state.categories[name] : 'Other';
  }

  function sourcesInCategory(cat) {
    const list = state.lastSources || [];
    return list.filter(s => categoryOf(s.name) === cat);
  }

  function categoryList() {
    const present = new Set((state.lastSources || []).map(s => categoryOf(s.name)));
    const cats = CATEGORY_ORDER.filter(c => c !== 'All' && present.has(c));
    if (present.has('Other') && cats.indexOf('Other') === -1) cats.push('Other');
    return cats;
  }

  function isCategorySelected(cat) {
    if (cat === 'All') return state.selectedSourceIds.size === 0;
    const ids = sourcesInCategory(cat).map(s => Number(s.id));
    return ids.length > 0 && ids.every(id => state.selectedSourceIds.has(id)) && state.selectedSourceIds.size === ids.length;
  }

  function renderChips() {
    if (!ui.categoryChips) return;
    ui.categoryChips.innerHTML = '';
    const allChip = document.createElement('button');
    allChip.type = 'button';
    allChip.className = 'chip' + (state.selectedSourceIds.size === 0 ? ' active' : '');
    allChip.textContent = 'All';
    allChip.dataset.category = 'All';
    allChip.addEventListener('click', () => selectCategory('All'));
    ui.categoryChips.appendChild(allChip);
    for (const cat of categoryList()) {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'chip' + (isCategorySelected(cat) ? ' active' : '');
      chip.textContent = cat;
      chip.dataset.category = cat;
      chip.addEventListener('click', () => selectCategory(cat));
      ui.categoryChips.appendChild(chip);
    }
  }

  function syncPicker() {
    if (!ui.filterSources) return;
    Array.from(ui.filterSources.options).forEach(opt => {
      opt.selected = state.selectedSourceIds.has(Number(opt.value));
    });
  }

  function renderSourcePicker() {
    if (!ui.filterSources) return;
    ui.filterSources.innerHTML = '';
    for (const cat of categoryList()) {
      const srcs = sourcesInCategory(cat);
      if (!srcs.length) continue;
      const og = document.createElement('optgroup');
      og.label = cat;
      for (const s of srcs) {
        const opt = document.createElement('option');
        opt.value = s.id;
        opt.textContent = s.name;
        opt.selected = state.selectedSourceIds.has(Number(s.id));
        og.appendChild(opt);
      }
      ui.filterSources.appendChild(og);
    }
  }

  function selectCategory(cat) {
    if (cat === 'All') {
      state.selectedSourceIds.clear();
    } else {
      state.selectedSourceIds = new Set(sourcesInCategory(cat).map(s => Number(s.id)));
    }
    syncPicker();
    renderChips();
    updateUrlFromState();
    state.itemsCursorStack = [];
    state.itemsCursor = null;
    loadItems(null, null);
  }

  function updateUrlFromState() {
    const params = new URLSearchParams(window.location.search);
    if (state.selectedSourceIds.size) params.set('sources', [...state.selectedSourceIds].join(','));
    else params.delete('sources');
    params.delete('source');
    const q = ui.filterQ.value.trim();
    if (q) params.set('q', q); else params.delete('q');
    const from = ui.filterFrom.value.trim();
    if (from) params.set('fetchedFrom', from); else params.delete('fetchedFrom');
    const to = ui.filterTo.value.trim();
    if (to) params.set('fetchedTo', to); else params.delete('fetchedTo');
    const size = ui.filterSize.value.trim();
    if (size) params.set('size', size); else params.delete('size');
    const newUrl = params.toString() ? ('?' + params.toString()) : window.location.pathname;
    history.replaceState(null, '', newUrl);
  }

  function restoreFromUrl() {
    const params = new URLSearchParams(window.location.search);
    if (params.has('q')) ui.filterQ.value = params.get('q');
    if (params.has('fetchedFrom')) ui.filterFrom.value = params.get('fetchedFrom');
    if (params.has('fetchedTo')) ui.filterTo.value = params.get('fetchedTo');
    if (params.has('size')) ui.filterSize.value = params.get('size');
    let ids = [];
    if (params.has('sources')) {
      ids = params.get('sources').split(',').map(x => Number(x.trim())).filter(n => !Number.isNaN(n));
    } else if (params.has('source')) {
      const n = Number(params.get('source'));
      if (!Number.isNaN(n)) ids = [n];
    }
    state.selectedSourceIds = new Set(ids);
    syncPicker();
    renderChips();
  }

  function loadCategories() {
    return fetch('/categories.json', { cache: 'no-store' })
      .then(res => res.json())
      .then(data => { state.categories = data || {}; })
      .catch(() => { state.categories = {}; });
  }

  function buildItemsQuery(cursor) {
    const params = new URLSearchParams();
    const q = ui.filterQ.value.trim();
    const from = ui.filterFrom.value.trim();
    const to = ui.filterTo.value.trim();
    const size = ui.filterSize.value.trim();
    if (state.selectedSourceIds.size) params.set('sources', [...state.selectedSourceIds].join(','));
    if (q) params.set('q', q);
    if (from) params.set('fetchedFrom', from);
    if (to) params.set('fetchedTo', to);
    if (size) params.set('size', size);
    if (cursor) params.set('cursor', cursor);
    return params;
  }

  async function loadItems(cursor, pushStack, auto = false) {
    if (!auto) setPanelState('items', 'loading', 'Loading…');
    try {
      const params = buildItemsQuery(cursor);
      const url = '/api/v1/items?' + params.toString();
      const data = await fetchJson(url);
      const items = Array.isArray(data.items) ? data.items : [];
      ui.itemsSummary.textContent = `${data.total} items total — showing ${items.length} — hasMore=${data.hasMore}`;
      const wrap = ui.itemsBody.closest('.table-wrap');
      preserveScroll(wrap, () => {
        ui.itemsBody.innerHTML = items.length ? items.map(item => {
          const safeUrl = safeHttpUrl(item.link);
          const link = item.link
            ? (safeUrl
                ? `<a class="link mono" href="${esc(safeUrl)}" target="_blank" rel="noopener noreferrer">open</a>`
                : `<span class="mono">${esc(item.link)} (not a link)</span>`)
            : '<span class="muted">—</span>';
          return `<tr>
            <td class="mono">${esc(item.id)}</td>
            <td class="mono">${esc(item.sourceId)}</td>
            <td><div>${esc(item.title || '—')}</div><div class="detail">${esc((item.summary || '').slice(0, 140))}${(item.summary || '').length > 140 ? '…' : ''}</div></td>
            <td>${fmtTime(item.publishedAt)}</td>
            <td>${fmtTime(item.fetchedAt)}</td>
            <td>${link}</td>
            <td class="cluster-cell">
              ${item.clusterId != null && item.clusterSize != null
                ? `<button class="cluster-item-badge btn btn-secondary" data-cluster-id="${esc(item.clusterId)}" data-cluster-size="${esc(item.clusterSize)}" type="button">Same story · ${esc(item.clusterSize)} sources</button>`
                : '<span class="muted">—</span>'}
            </td>
          </tr>`;
        }).join('') : '<tr><td colspan="7" class="empty">No items matched the current filters.</td></tr>';
      });
      if (pushStack) state.itemsCursorStack.push(pushStack);
      state.itemsCursor = data.nextCursor || null;
      state.itemsLastQuery = { cursor: cursor || null };
      ui.itemsNext.disabled = !data.hasMore || !data.nextCursor;
      ui.itemsPrev.disabled = state.itemsCursorStack.length === 0;
      ui.itemsPageMeta.textContent = `Page depth ${state.itemsCursorStack.length + 1} — cursor ${cursor ? 'set' : 'start'} — nextCursor ${data.nextCursor ? 'available' : 'none'}`;
      clearError(ui.itemsError);
      const now = new Date();
      state.panel.items.lastUpdated = now;
      state.panel.items.hasData = true;
      if (items.length === 0) {
        setPanelState('items', 'empty', 'Empty — no items');
      } else {
        setPanelState('items', 'ready', `Last updated ${fmtClock(now)}`);
      }
    } catch (err) {
      if (state.panel.items.hasData) {
        setPanelState('items', 'stale', `Last updated ${fmtClock(state.panel.items.lastUpdated)} — refresh failed`);
        showError(ui.itemsError, err);
      } else {
        ui.itemsBody.innerHTML = '<tr><td colspan="6" class="empty">Query failed.</td></tr>';
        setPanelState('items', 'error', 'Error');
        showError(ui.itemsError, err);
      }
    }
  }

  async function loadClusters(auto = false) {
    if (!auto) setPanelState('clusters', 'loading', 'Loading…');
    try {
      const data = await fetchJson('/api/v1/clusters?sinceHours=48');
      const clusters = Array.isArray(data) ? data : [];
      const wrap = ui.clustersBody.closest('.table-wrap');
      preserveScroll(wrap, () => {
        ui.clustersBody.innerHTML = clusters.length ? clusters.map(c => {
          const more = Math.max(0, (c.sourceNames || []).length - 4);
          const shown = (c.sourceNames || []).slice(0, 4);
          const sources = shown.map(n => esc(n)).join(', ') + (more > 0 ? ` +${more} more` : '');
          return `<tr data-cluster-id="${esc(c.id)}">
            <td><div>${esc(c.representativeTitle || '—')}</div></td>
            <td class="mono">${esc(fmtNumber(c.memberCount))} reports</td>
            <td>${sources}</td>
            <td>${fmtTime(c.lastSeenAt)}</td>
            <td><button class="cluster-toggle" data-cluster-id="${esc(c.id)}" type="button">Show members</button></td>
          </tr>`;
        }).join('') : '<tr><td colspan="5" class="empty">No clusters in the last 48 hours.</td></tr>';
      });
      ui.clustersBody.querySelectorAll('.cluster-toggle').forEach(btn => {
        btn.addEventListener('click', () => toggleCluster(btn.getAttribute('data-cluster-id')));
      });
      // re-expand a previously expanded cluster if it is still present
      if (state.expandedCluster != null && clusters.some(c => c.id != null && c.id.toString() === String(state.expandedCluster))) {
        const c = clusters.find(x => x.id != null && x.id.toString() === String(state.expandedCluster));
        toggleCluster(String(c.id));
      }
      clearError(ui.clustersError);
      const now = new Date();
      state.panel.clusters.lastUpdated = now;
      state.panel.clusters.hasData = true;
      if (clusters.length === 0) {
        setPanelState('clusters', 'empty', 'Empty — no clusters');
      } else {
        setPanelState('clusters', 'ready', `Last updated ${fmtClock(now)} — ${clusters.length} clusters`);
      }
      return true;
    } catch (err) {
      if (state.panel.clusters.hasData) {
        setPanelState('clusters', 'stale', `Last updated ${fmtClock(state.panel.clusters.lastUpdated)} — refresh failed`);
        showError(ui.clustersError, err);
      } else {
        ui.clustersBody.innerHTML = '<tr><td colspan="5" class="empty">Failed to load clusters.</td></tr>';
        setPanelState('clusters', 'error', 'Error');
        showError(ui.clustersError, err);
      }
      throw err;
    }
  }

  function collapseCluster() {
    const prev = ui.clustersBody.querySelector('tr[data-members-for]');
    if (prev) prev.remove();
    state.expandedCluster = null;
    const btn = ui.clustersBody.querySelector('.cluster-toggle');
    if (btn) btn.textContent = 'Show members';
  }

  async function toggleCluster(id) {
    if (!id) return;
    if (state.expandedCluster === id) {
      collapseCluster();
      return;
    }
    collapseCluster();
    state.expandedCluster = id;
    const row = ui.clustersBody.querySelector(`tr[data-cluster-id="${id}"]`);
    if (!row) return;
    const btn = row.querySelector('.cluster-toggle');
    let members = state.clusterMembersCache.get(id);
    if (members == null) {
      try {
        const data = await fetchJson(`/api/v1/clusters/${id}`);
        members = Array.isArray(data.members) ? data.members : [];
        state.clusterMembersCache.set(id, members);
      } catch (err) {
        showError(ui.clustersError, err);
        state.expandedCluster = null;
        if (btn) btn.textContent = 'Show members';
        return;
      }
    }
    row.insertAdjacentHTML('afterend', `<tr data-members-for="${esc(id)}"><td colspan="5"><div class="cluster-members">${clusterMembersHtml(members)}</div></td></tr>`);
    if (btn) btn.textContent = 'Hide members';
  }

  async function toggleItemCluster(clusterId, clusterSize, btn) {
    if (!clusterId) return;
    const tr = btn.closest('tr');
    if (!tr) return;
    if (state.expandedItem === clusterId) {
      const prev = ui.itemsBody.querySelector('tr[data-item-members]');
      if (prev) prev.remove();
      state.expandedItem = null;
      btn.textContent = `Same story · ${esc(clusterSize)} sources`;
      return;
    }
    const prev = ui.itemsBody.querySelector('tr[data-item-members]');
    if (prev) {
      prev.remove();
      const prevBtn = ui.itemsBody.querySelector('.cluster-item-badge[data-expanded="true"]');
      if (prevBtn) prevBtn.textContent = `Same story · ${esc(prevBtn.getAttribute('data-cluster-size'))} sources`;
      prevBtn?.setAttribute('data-expanded', 'false');
    }
    state.expandedItem = clusterId;
    btn.setAttribute('data-expanded', 'true');
    btn.textContent = 'Collapse';
    let members = state.clusterMembersCache.get(clusterId);
    if (members == null) {
      try {
        const data = await fetchJson(`/api/v1/clusters/${clusterId}`);
        members = Array.isArray(data.members) ? data.members : [];
        state.clusterMembersCache.set(clusterId, members);
      } catch (err) {
        showError(ui.itemsError, err);
        state.expandedItem = null;
        btn.setAttribute('data-expanded', 'false');
        btn.textContent = `Same story · ${esc(clusterSize)} sources`;
        return;
      }
    }
    tr.insertAdjacentHTML('afterend', `<tr data-item-members="${esc(clusterId)}"><td colspan="7"><div class="cluster-members">${clusterMembersHtml(members)}</div></td></tr>`);
  }

  async function loadPoison(auto = false) {
    clearError(ui.adminError);
    if (!auto) setPanelState('admin', 'loading', 'Loading…');
    state.adminBusy = true;
    try {
      const params = new URLSearchParams();
      const topic = ui.poisonTopic.value.trim();
      const size = ui.poisonLimit.value.trim() || '20';
      if (topic) params.set('topic', topic);
      params.set('size', Math.min(100, Math.max(1, Number(size) || 20)));
      params.set('page', '0');
      const data = await fetchJson('/api/v1/admin/outbox/poisoned?' + params.toString(), { admin: true });
      const items = Array.isArray(data.items) ? data.items : [];
      const wrap = ui.poisonBody.closest('.table-wrap');
      preserveScroll(wrap, () => {
        ui.poisonBody.innerHTML = items.length ? items.map(item => `
          <tr>
            <td class="mono">${esc(item.id)}</td>
            <td><div class="mono">${esc(item.topic || '')}</div><div class="detail mono">${esc(item.eventKey || '')}</div></td>
            <td><div class="mono">attempts=${esc(item.attempts)}</div><div class="detail mono">replayed=${esc(item.replayedCount)}</div></td>
            <td>${fmtTime(item.poisonedAt)}<div class="detail">${fmtTime(item.lastAttemptAt)}</div></td>
            <td class="diagnostics">${esc(item.lastError || '—')}</td>
            <td><button class="btn btn-secondary btn-replay-one" data-id="${esc(item.id)}" type="button">Replay</button></td>
          </tr>`).join('') : '<tr><td colspan="6" class="empty">No poisoned outbox entries.</td></tr>';
      });
      ui.poisonBody.querySelectorAll('.btn-replay-one').forEach(btn => {
        btn.addEventListener('click', () => replayOne(btn.getAttribute('data-id')));
      });
      ui.adminSummary.textContent = `Poison entries: ${data.totalElements} total (page ${data.page + 1}/${Math.max(1, data.totalPages)})`;
      clearError(ui.adminError);
      const now = new Date();
      state.panel.admin.lastUpdated = now;
      state.panel.admin.hasData = true;
      if (items.length === 0) {
        setPanelState('admin', 'empty', 'Empty — no poison entries');
      } else {
        setPanelState('admin', 'ready', `Last updated ${fmtClock(now)}`);
      }
    } catch (err) {
      const closed = adminClosedMessage(err);
      if (closed) {
        ui.adminSummary.textContent = closed;
      }
      if (state.panel.admin.hasData) {
        setPanelState('admin', 'stale', `Last updated ${fmtClock(state.panel.admin.lastUpdated)} — refresh failed`);
        showError(ui.adminError, err);
      } else {
        ui.poisonBody.innerHTML = '<tr><td colspan="6" class="empty">Unable to load poison entries.</td></tr>';
        setPanelState('admin', 'error', 'Error');
        showError(ui.adminError, err);
      }
    } finally {
      state.adminBusy = false;
    }
  }

  async function replayOne(id) {
    clearError(ui.adminError);
    if (!id) return;
    if (!window.confirm(`Replay poisoned outbox event ${id}?`)) return;
    state.adminBusy = true;
    try {
      await fetchJson(`/api/v1/admin/outbox/poisoned/${encodeURIComponent(id)}/replay`, { method: 'POST', admin: true });
      banner(`Replayed outbox event ${id}.`, false);
      await Promise.all([loadOutboxStats(), loadPoison()]);
    } catch (err) {
      const closed = adminClosedMessage(err);
      if (closed) ui.adminSummary.textContent = closed;
      showError(ui.adminError, err);
    } finally {
      state.adminBusy = false;
    }
  }

  async function replayBatch() {
    clearError(ui.adminError);
    const limitRaw = ui.poisonLimit.value.trim() || '50';
    const limit = Math.min(500, Math.max(1, Number(limitRaw) || 50));
    const topic = ui.poisonTopic.value.trim();
    if (!window.confirm(`Replay up to ${limit} poisoned outbox events${topic ? ` for topic ${topic}` : ''}?`)) return;
    state.adminBusy = true;
    try {
      const params = new URLSearchParams();
      params.set('limit', String(limit));
      if (topic) params.set('topic', topic);
      const data = await fetchJson('/api/v1/admin/outbox/poisoned/replay-batch?' + params.toString(), { method: 'POST', admin: true });
      banner(`Batch replay complete: replayed ${data.replayedCount}.`, false);
      await Promise.all([loadOutboxStats(), loadPoison()]);
    } catch (err) {
      const closed = adminClosedMessage(err);
      if (closed) ui.adminSummary.textContent = closed;
      showError(ui.adminError, err);
    } finally {
      state.adminBusy = false;
    }
  }

  async function cleanupPublished() {
    clearError(ui.adminError);
    if (!window.confirm('Trigger published outbox cleanup now? This deletes aged published rows according to server retention config.')) return;
    state.adminBusy = true;
    try {
      const data = await fetchJson('/api/v1/admin/outbox/cleanup', { method: 'POST', admin: true });
      banner(`Cleanup completed: deleted ${data.deleted} published rows.`, false);
      await loadOutboxStats();
    } catch (err) {
      const closed = adminClosedMessage(err);
      if (closed) ui.adminSummary.textContent = closed;
      showError(ui.adminError, err);
    } finally {
      state.adminBusy = false;
    }
  }

  function setAutoRefresh(enabled) {
    state.autoRefresh = enabled;
    ui.autoRefresh.checked = enabled;
    if (state.refreshTimer) {
      clearInterval(state.refreshTimer);
      state.refreshTimer = null;
    }
    if (enabled) {
      state.refreshTimer = setInterval(() => {
         const ae = document.activeElement;
         const adminFocused = !!ae && (ui.adminError.contains(ae) || ui.poisonBody.contains(ae) || ui.poisonTopic === ae || ui.poisonLimit === ae || ui.adminKey === ae || ui.replayBatch === ae || ui.cleanupPublished === ae || ui.poisonRefresh === ae);
         const tableFocused = !!ae && (ui.sourcesBody.contains(ae) || ui.itemsBody.contains(ae) || ui.poisonBody.contains(ae));
         if (adminFocused || tableFocused || state.adminBusy) return;
        loadHealth(true).catch(() => {});
        loadClusters(true).catch(() => {});
        if (state.adminKey && !adminFocused && !state.adminBusy) loadPoison(true).catch(() => {});
      }, 15000);
    }
  }

  ui.saveKey.addEventListener('click', () => {
    state.adminKey = ui.adminKey.value.trim();
    if (state.adminKey) {
      banner('Admin key stored in memory for this tab session.', false);
      loadOutboxStats().catch(() => {});
      loadPoison().catch(() => {});
    } else {
      banner('Admin key cleared.', false);
    }
  });

  ui.clearKey.addEventListener('click', () => {
    state.adminKey = '';
    ui.adminKey.value = '';
    ui.outboxPending.textContent = '—';
    ui.outboxPublished.textContent = '—';
    ui.outboxPoison.textContent = '—';
    ui.adminSummary.textContent = 'Admin token required for outbox stats/controls';
    ui.poisonBody.innerHTML = '<tr><td colspan="6" class="empty">Enter admin key and load poison entries.</td></tr>';
    clearError(ui.adminError);
    setPanelState('admin', 'empty', 'Empty');
    banner('Admin key cleared from memory.', false);
  });

  ui.autoRefresh.addEventListener('change', (e) => setAutoRefresh(e.target.checked));
  ui.refreshAll.addEventListener('click', () => {
    loadHealth(false).catch(() => {});
    loadClusters(false).catch(() => {});
    if (state.itemsLastQuery) loadItems(state.itemsLastQuery.cursor, null).catch(() => {});
    if (state.adminKey) loadPoison(false).catch(() => {});
  });

  if (ui.filterSources) {
    ui.filterSources.addEventListener('change', () => {
      state.selectedSourceIds = new Set(Array.from(ui.filterSources.selectedOptions).map(o => Number(o.value)));
      renderChips();
      updateUrlFromState();
      state.itemsCursorStack = [];
      state.itemsCursor = null;
      loadItems(null, null);
    });
  }

  ui.itemsForm.addEventListener('submit', (e) => {
    e.preventDefault();
    state.itemsCursorStack = [];
    state.itemsCursor = null;
    updateUrlFromState();
    loadItems(null, null);
  });

  ui.itemsReset.addEventListener('click', () => {
    state.selectedSourceIds.clear();
    ui.filterQ.value = '';
    setDefaultWindow();
    state.itemsCursorStack = [];
    state.itemsCursor = null;
    syncPicker();
    renderChips();
    updateUrlFromState();
    loadItems(null, null);
  });

  ui.itemsNext.addEventListener('click', () => {
    if (!state.itemsCursor) return;
    const current = state.itemsLastQuery ? state.itemsLastQuery.cursor : null;
    loadItems(state.itemsCursor, current);
  });

  ui.itemsPrev.addEventListener('click', () => {
    if (state.itemsCursorStack.length === 0) return;
    const prev = state.itemsCursorStack.pop();
    loadItems(prev, null);
  });

  ui.poisonRefresh.addEventListener('click', () => loadPoison());
  ui.replayBatch.addEventListener('click', () => replayBatch());
  ui.cleanupPublished.addEventListener('click', () => cleanupPublished());

  if (ui.sourcesBody) {
    ui.sourcesBody.addEventListener('click', (e) => {
      const tr = e.target && typeof e.target.closest === 'function' ? e.target.closest('tr[data-source-id]') : null;
      if (!tr) return;
      const id = Number(tr.getAttribute('data-source-id'));
      if (Number.isNaN(id)) return;
      state.selectedSourceIds = new Set([id]);
      syncPicker();
      renderChips();
      updateUrlFromState();
      state.itemsCursorStack = [];
      state.itemsCursor = null;
      loadItems(null, null);
    });
  }

  ui.itemsBody.addEventListener('click', (e) => {
    const btn = e.target && typeof e.target.closest === 'function' ? e.target.closest('button.cluster-item-badge') : null;
    if (!btn) return;
    toggleItemCluster(btn.getAttribute('data-cluster-id'), btn.getAttribute('data-cluster-size'), btn);
  });

  setDefaultWindow();
  setAutoRefresh(true);
  (async () => {
    await loadCategories();
    await loadHealth();
    restoreFromUrl();
    renderSourcePicker();
    renderChips();
    updateOverallStatus();
    loadItems(null, null).catch(() => {});
    loadClusters().catch(() => {});
  })();
})();
