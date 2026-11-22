(() => {
  'use strict';

  const state = {
    adminKey: '',
    autoRefresh: true,
    refreshTimer: null,
    itemsCursor: null,
    itemsCursorStack: [],
    itemsLastQuery: null,
  };

  const el = (id) => document.getElementById(id);
  const ui = {
    adminKey: el('admin-key'),
    saveKey: el('save-key'),
    clearKey: el('clear-key'),
    autoRefresh: el('auto-refresh'),
    refreshAll: el('refresh-all'),
    globalBanner: el('global-banner'),
    actuatorStatus: el('actuator-status'),
    actuatorDetail: el('actuator-detail'),
    outboxPending: el('outbox-pending'),
    outboxPublished: el('outbox-published'),
    outboxPoison: el('outbox-poison'),
    healthUpdated: el('health-updated'),
    healthError: el('health-error'),
    sourcesBody: el('sources-body'),
    sourcesSummary: el('sources-summary'),
    sourcesError: el('sources-error'),
    itemsForm: el('items-form'),
    itemsBody: el('items-body'),
    itemsSummary: el('items-summary'),
    itemsError: el('items-error'),
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
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return `<span class="mono">${esc(value)}</span>`;
    return `<span class="mono" title="${esc(value)}">${esc(d.toISOString().replace('T', ' ').replace('Z', ' UTC'))}</span>`;
  }

  function fmtNumber(value) {
    return value == null ? '—' : String(value);
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
    ui.globalBanner.textContent = msg;
    ui.globalBanner.classList.remove('hidden');
    ui.globalBanner.style.borderColor = isError ? 'rgba(255,107,107,0.35)' : 'rgba(89,179,255,0.3)';
    ui.globalBanner.style.background = isError ? 'rgba(255,107,107,0.12)' : 'rgba(89,179,255,0.12)';
    ui.globalBanner.style.color = isError ? '#ffd6d6' : '#d7ecff';
    clearTimeout(banner._timer);
    banner._timer = setTimeout(() => ui.globalBanner.classList.add('hidden'), 5000);
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
    if (s === 'UP' || s === 'OK' || s === 'HEALTHY') cls = 'up';
    else if (s === 'DEGRADED') cls = 'degraded';
    else if (s === 'DOWN' || s === 'BACKOFF' || s === 'ERROR') cls = 'down';
    else if (s === 'DISABLED') cls = 'disabled';
    return `<span class="badge ${cls}">${esc(s)}</span>`;
  }

  async function loadActuator() {
    try {
      const data = await fetchJson('/actuator/health');
      const status = String(data.status || 'UNKNOWN').toUpperCase();
      ui.actuatorStatus.innerHTML = badge(status);
      const components = data.components ? Object.keys(data.components).join(', ') : 'no details exposed';
      ui.actuatorDetail.textContent = `Components: ${components}`;
      clearError(ui.healthError);
    } catch (err) {
      ui.actuatorStatus.innerHTML = badge('DOWN');
      ui.actuatorDetail.textContent = 'GET /actuator/health failed';
      showError(ui.healthError, err);
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
    } catch (err) {
      ui.outboxPending.textContent = '—';
      ui.outboxPublished.textContent = '—';
      ui.outboxPoison.textContent = '—';
      if (err.status === 401 || err.status === 403) {
        ui.adminSummary.textContent = 'Admin token required for outbox stats/controls';
      }
      if (state.adminKey) showError(ui.healthError, err);
    }
  }

  async function loadHealth() {
    ui.healthUpdated.textContent = `Refreshing… ${new Date().toISOString()}`;
    await Promise.all([loadActuator(), loadOutboxStats(), loadSources()]);
    ui.healthUpdated.textContent = `Updated ${new Date().toISOString().replace('T', ' ').replace('Z', ' UTC')}`;
  }

  async function loadSources() {
    try {
      const data = await fetchJson('/api/v1/sources/health');
      const sources = Array.isArray(data.sources) ? data.sources : [];
      ui.sourcesSummary.textContent = `${sources.length} sources — aggregate ${data.status || 'UNKNOWN'} / sources ${data.sourcesStatus || 'UNKNOWN'} / heartbeat stale ${data.heartbeatStale}`;
      if (sources.length === 0) {
        ui.sourcesBody.innerHTML = '<tr><td colspan="7" class="empty">No sources registered.</td></tr>';
        clearError(ui.sourcesError);
        return;
      }
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
      clearError(ui.sourcesError);
    } catch (err) {
      ui.sourcesBody.innerHTML = '<tr><td colspan="7" class="empty">Failed to load sources.</td></tr>';
      showError(ui.sourcesError, err);
    }
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

  async function loadItems(cursor, pushStack) {
    try {
      const params = buildItemsQuery(cursor);
      const url = '/api/v1/items?' + params.toString();
      const data = await fetchJson(url);
      const items = Array.isArray(data.items) ? data.items : [];
      ui.itemsSummary.textContent = `${data.total} items total — showing ${items.length} — hasMore=${data.hasMore}`;
      ui.itemsBody.innerHTML = items.length ? items.map(item => {
        const link = item.link ? `<a class="link mono" href="${esc(item.link)}" target="_blank" rel="noopener noreferrer">open</a>` : '<span class="muted">—</span>';
        return `<tr>
          <td class="mono">${esc(item.id)}</td>
          <td class="mono">${esc(item.sourceId)}</td>
          <td><div>${esc(item.title || '—')}</div><div class="detail">${esc((item.summary || '').slice(0, 140))}${(item.summary || '').length > 140 ? '…' : ''}</div></td>
          <td>${fmtTime(item.publishedAt)}</td>
          <td>${fmtTime(item.fetchedAt)}</td>
          <td>${link}</td>
        </tr>`;
      }).join('') : '<tr><td colspan="6" class="empty">No items matched the current filters.</td></tr>';
      if (pushStack) state.itemsCursorStack.push(pushStack);
      state.itemsCursor = data.nextCursor || null;
      state.itemsLastQuery = { cursor: cursor || null };
      ui.itemsNext.disabled = !data.hasMore || !data.nextCursor;
      ui.itemsPrev.disabled = state.itemsCursorStack.length === 0;
      ui.itemsPageMeta.textContent = `Page depth ${state.itemsCursorStack.length + 1} — cursor ${cursor ? 'set' : 'start'} — nextCursor ${data.nextCursor ? 'available' : 'none'}`;
      clearError(ui.itemsError);
    } catch (err) {
      ui.itemsBody.innerHTML = '<tr><td colspan="6" class="empty">Query failed.</td></tr>';
      ui.itemsNext.disabled = true;
      showError(ui.itemsError, err);
    }
  }

  async function loadPoison() {
    clearError(ui.adminError);
    try {
      const params = new URLSearchParams();
      const topic = ui.poisonTopic.value.trim();
      const size = ui.poisonLimit.value.trim() || '20';
      if (topic) params.set('topic', topic);
      params.set('size', Math.min(100, Math.max(1, Number(size) || 20)));
      params.set('page', '0');
      const data = await fetchJson('/api/v1/admin/outbox/poisoned?' + params.toString(), { admin: true });
      const items = Array.isArray(data.items) ? data.items : [];
      ui.adminSummary.textContent = `Poison entries: ${data.totalElements} total (page ${data.page + 1}/${Math.max(1, data.totalPages)})`;
      ui.poisonBody.innerHTML = items.length ? items.map(item => `
        <tr>
          <td class="mono">${esc(item.id)}</td>
          <td><div class="mono">${esc(item.topic || '')}</div><div class="detail mono">${esc(item.eventKey || '')}</div></td>
          <td><div class="mono">attempts=${esc(item.attempts)}</div><div class="detail mono">replayed=${esc(item.replayedCount)}</div></td>
          <td>${fmtTime(item.poisonedAt)}<div class="detail">${fmtTime(item.lastAttemptAt)}</div></td>
          <td class="diagnostics">${esc(item.lastError || '—')}</td>
          <td><button class="btn btn-secondary btn-replay-one" data-id="${esc(item.id)}" type="button">Replay</button></td>
        </tr>`).join('') : '<tr><td colspan="6" class="empty">No poisoned outbox entries.</td></tr>';
      ui.poisonBody.querySelectorAll('.btn-replay-one').forEach(btn => {
        btn.addEventListener('click', () => replayOne(btn.getAttribute('data-id')));
      });
    } catch (err) {
      ui.poisonBody.innerHTML = '<tr><td colspan="6" class="empty">Unable to load poison entries.</td></tr>';
      showError(ui.adminError, err);
    }
  }

  async function replayOne(id) {
    clearError(ui.adminError);
    if (!id) return;
    if (!window.confirm(`Replay poisoned outbox event ${id}?`)) return;
    try {
      await fetchJson(`/api/v1/admin/outbox/poisoned/${encodeURIComponent(id)}/replay`, { method: 'POST', admin: true });
      banner(`Replayed outbox event ${id}.`, false);
      await Promise.all([loadOutboxStats(), loadPoison()]);
    } catch (err) {
      showError(ui.adminError, err);
    }
  }

  async function replayBatch() {
    clearError(ui.adminError);
    const limitRaw = ui.poisonLimit.value.trim() || '50';
    const limit = Math.min(500, Math.max(1, Number(limitRaw) || 50));
    const topic = ui.poisonTopic.value.trim();
    if (!window.confirm(`Replay up to ${limit} poisoned outbox events${topic ? ` for topic ${topic}` : ''}?`)) return;
    try {
      const params = new URLSearchParams();
      params.set('limit', String(limit));
      if (topic) params.set('topic', topic);
      const data = await fetchJson('/api/v1/admin/outbox/poisoned/replay-batch?' + params.toString(), { method: 'POST', admin: true });
      banner(`Batch replay complete: replayed ${data.replayedCount}.`, false);
      await Promise.all([loadOutboxStats(), loadPoison()]);
    } catch (err) {
      showError(ui.adminError, err);
    }
  }

  async function cleanupPublished() {
    clearError(ui.adminError);
    if (!window.confirm('Trigger published outbox cleanup now? This deletes aged published rows according to server retention config.')) return;
    try {
      const data = await fetchJson('/api/v1/admin/outbox/cleanup', { method: 'POST', admin: true });
      banner(`Cleanup completed: deleted ${data.deleted} published rows.`, false);
      await loadOutboxStats();
    } catch (err) {
      showError(ui.adminError, err);
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
        loadHealth().catch(() => {});
        if (state.adminKey) loadPoison().catch(() => {});
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
    banner('Admin key cleared from memory.', false);
  });

  ui.autoRefresh.addEventListener('change', (e) => setAutoRefresh(e.target.checked));
  ui.refreshAll.addEventListener('click', () => {
    loadHealth().catch(() => {});
    if (state.itemsLastQuery) loadItems(state.itemsLastQuery.cursor, null).catch(() => {});
    if (state.adminKey) loadPoison().catch(() => {});
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
})();
