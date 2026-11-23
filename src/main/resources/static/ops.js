(() => {
  'use strict';

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
    panel: {
      health: { lastUpdated: null, hasData: false },
      sources: { lastUpdated: null, hasData: false },
      items: { lastUpdated: null, hasData: false },
      admin: { lastUpdated: null, hasData: false },
    },
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
    itemsPageMeta: el('items-page-meta'),
    itemsPrev: el('items-prev'),
    itemsNext: el('items-next'),
    itemsReset: el('items-reset'),
    filterSource: el('filter-source'),
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
            return `<tr>
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

  function buildItemsQuery(cursor) {
    const params = new URLSearchParams();
    const source = ui.filterSource.value.trim();
    const q = ui.filterQ.value.trim();
    const from = ui.filterFrom.value.trim();
    const to = ui.filterTo.value.trim();
    const size = ui.filterSize.value.trim();
    if (source) params.set('source', source);
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
          </tr>`;
        }).join('') : '<tr><td colspan="6" class="empty">No items matched the current filters.</td></tr>';
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
    if (state.itemsLastQuery) loadItems(state.itemsLastQuery.cursor, null).catch(() => {});
    if (state.adminKey) loadPoison(false).catch(() => {});
  });

  ui.itemsForm.addEventListener('submit', (e) => {
    e.preventDefault();
    state.itemsCursorStack = [];
    state.itemsCursor = null;
    loadItems(null, null);
  });

  ui.itemsReset.addEventListener('click', () => {
    ui.filterSource.value = '';
    ui.filterQ.value = '';
    setDefaultWindow();
    state.itemsCursorStack = [];
    state.itemsCursor = null;
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

  setDefaultWindow();
  setAutoRefresh(true);
  loadHealth().catch(() => {});
  loadItems(null, null).catch(() => {});
  updateOverallStatus();
})();
