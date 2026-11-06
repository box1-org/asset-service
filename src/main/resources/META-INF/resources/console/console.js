'use strict';
// The asset telemetry console. A client only: every limit, the gate, the confirmation and the actor are decided by the
// service. Values come from /api/v1/control; anything the service has not confirmed recently is shown as "unknown".
(() => {
  const API = '/api/v1/control';
  const STALE_MS = 15000;
  const $ = (id) => document.getElementById(id);
  let skewMs = 0;           // server time minus browser time, from the last confirmed state
  let lastConfirmed = 0;    // browser time of the last confirmed state
  let state = null;
  let limits = null;
  let armed = null;

  async function api(path, method = 'GET', body) {
    const headers = { 'Accept': 'application/json', 'X-Requested-With': 'JavaScript' };
    if (method !== 'GET') {
      headers['Content-Type'] = 'application/json';
      headers['X-Box1-Console'] = '1';
    }
    const response = await fetch(API + path, { method, headers, credentials: 'same-origin', cache: 'no-store',
      body: method === 'GET' ? undefined : JSON.stringify(body ?? {}) });
    if (response.status === 401 || response.status === 499) {
      throw new ApiError(response.status, 'SESSION', 'Your session has ended. Reload the page to sign in again.');
    }
    const text = await response.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (ignored) { data = null; }
    if (!response.ok) {
      throw new ApiError(response.status, data?.code ?? 'HTTP_' + response.status, data?.detail ?? 'The service refused the request.');
    }
    return data;
  }

  class ApiError extends Error {
    constructor(status, code, message) { super(message); this.status = status; this.code = code; }
  }

  function say(message) {
    const box = $('message');
    box.textContent = message;
    clearTimeout(say.timer);
    say.timer = setTimeout(() => { box.textContent = ''; }, 8000);
  }

  function set(id, value, unknown = value === null || value === undefined) {
    const el = $(id);
    el.textContent = unknown ? 'unknown' : String(value);
    el.classList.toggle('unknown', unknown);
  }

  const serverNow = () => Date.now() + skewMs;
  const seconds = (ms) => (ms <= 0 ? '0 s' : ms >= 60000 ? `${Math.floor(ms / 60000)} min ${Math.floor((ms % 60000) / 1000)} s` : `${Math.ceil(ms / 1000)} s`);
  const until = (iso) => (iso ? seconds(Date.parse(iso) - serverNow()) : '—');
  const time = (iso) => (iso ? new Date(iso).toISOString().slice(11, 19) + 'Z' : '—');

  function markUnknown(reason) {
    $('banner').dataset.state = 'unknown';
    set('confirmed', `UNKNOWN — ${reason}`);
    ['r-admitted', 'r-published', 'r-rejected', 'r-alerts', 'r-stale', 'r-lag', 'r-queue', 'r-producer'].forEach((id) => set(id, null));
    set('gate', null);
    const body = $('open-runs');
    body.replaceChildren(row([cell('unknown: the service has not confirmed run state', 'unknown', 9)]));
  }

  async function pollState() {
    try {
      const data = await api('/state');
      state = data;
      skewMs = Date.parse(data.confirmedAt) - Date.now();
      lastConfirmed = Date.now();
      renderState();
    } catch (error) {
      markUnknown(error.code === 'SESSION' ? 'session ended' : 'no response');
      if (error.code === 'SESSION') { say(error.message); }
    }
  }

  function renderState() {
    $('banner').dataset.state = 'known';
    set('actor', state.actor);
    set('namespace', state.namespace);
    set('environment', state.environment);
    set('gate', state.chaos.open ? 'OPEN' : `CLOSED (${state.chaos.code})`);
    $('gate-detail').textContent = state.chaos.message;
    set('confirmed', `confirmed ${time(state.confirmedAt)}`);
    const r = state.readouts;
    set('r-admitted', r.admittedPerSecond);
    set('r-published', r.publishedPerSecond);
    set('r-rejected', r.rejectedPerSecond);
    set('r-alerts', r.activeAlerts);
    set('r-stale', r.staleAssets);
    set('r-lag', r.consumerLag === null ? null : `${r.consumerLag} records`);
    set('r-queue', `${r.producerInFlight} / ${r.producerCapacity}`);
    set('r-producer', r.producerPaused ? `PAUSED until ${time(r.producerPausedUntil)}` : 'sending');
    // Mirrors the server's gate for clarity only; the service refuses chaos regardless of what the page shows.
    const chaos = document.querySelector('input[name="kind"][value="CHAOS"]');
    chaos.disabled = !state.chaos.open;
    $('chaos-off').hidden = state.chaos.open;
    if (chaos.disabled && chaos.checked) {
      document.querySelector('input[name="kind"][value="LOAD"]').checked = true;
      updateComputed();
    }
    renderOpenRuns(state.openRuns);
  }

  function cell(text, cls, span) {
    const td = document.createElement('td');
    td.textContent = text ?? '—';
    if (cls) { td.className = cls; }
    if (span) { td.colSpan = span; }
    return td;
  }

  function row(cells) {
    const tr = document.createElement('tr');
    cells.forEach((c) => tr.append(c));
    return tr;
  }

  function button(label, name, handler) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = label;
    b.setAttribute('aria-label', name);
    b.addEventListener('click', handler);
    return b;
  }

  function renderOpenRuns(runs) {
    const body = $('open-runs');
    if (!runs.length) {
      body.replaceChildren(row([cell('No armed or running runs.', null, 9)]));
      return;
    }
    body.replaceChildren(...runs.map((run) => {
      const unconfirmed = run.state === 'RUNNING' && Date.parse(run.heartbeatAt) < serverNow() - 15000;
      const actions = document.createElement('td');
      actions.append(button('Stop', `Stop run ${run.runId}`, () => stopRun(run.runId)));
      return row([
        cell(run.runId, 'mono'), cell(run.kind, 'nowrap'), cell(unconfirmed ? 'RUNNING? (no heartbeat)' : run.state),
        cell(run.seed, 'mono'), cell(run.state === 'RUNNING' ? until(run.deadline) : `arm ${until(run.armedUntil)}`),
        cell(run.state === 'RUNNING' ? until(run.ttlAt) : '—'), cell(`${run.emitted} / ${run.rejected}`),
        cell(run.currentInjection ?? (run.executingHere ? 'none' : 'not visible from this pod')), actions]);
    }));
  }

  async function refreshHistory() {
    try {
      const [runs, audit] = await Promise.all([api('/runs'), api('/audit?limit=20')]);
      $('recent-title-count').textContent = `${runs.length} recent`;
      $('audit-title-count').textContent = `${audit.length} latest entries`;
      $('recent-runs').replaceChildren(...runs.filter((r) => r.state !== 'ARMED' && r.state !== 'RUNNING').map((run) => {
        const actions = document.createElement('td');
        actions.append(button('Replay seed', `Replay seed ${run.seed} from run ${run.runId}`, () => replay(run)));
        return row([cell(run.runId, 'mono'), cell(run.kind, 'nowrap'), cell(`${run.state}${run.stopReason ? ': ' + run.stopReason : ''}`),
          cell(run.seed, 'mono'), cell(run.sequenceDigest, 'mono wrap'), actions]);
      }));
      $('audit').replaceChildren(...audit.map((a) => row([cell(time(a.at), 'mono'), cell(a.actor, 'nowrap'), cell(a.action, 'nowrap'),
        cell(a.runId, 'mono'), cell(a.detail, 'mono wrap')])));
    } catch (error) {
      $('audit').replaceChildren(row([cell('unknown: audit could not be read', 'unknown', 5)]));
    }
  }

  function request() {
    const form = $('run-form');
    const num = (name) => Number.parseInt(form.elements[name].value, 10);
    const seed = form.elements.seed.value.trim();
    return {
      kind: form.elements.kind.value, sites: num('sites'), assetsPerSite: num('assetsPerSite'), ratePerAsset: num('ratePerAsset'),
      durationSeconds: num('durationSeconds'), seed: seed || null,
      metrics: [...document.querySelectorAll('#metrics input:checked')].map((i) => i.value),
    };
  }

  function updateComputed() {
    const r = request();
    const rate = r.sites * r.assetsPerSite * r.ratePerAsset * r.metrics.length;
    $('computed').textContent = Number.isFinite(rate)
      ? `${rate} readings/s for ${r.durationSeconds} s before chaos (the service checks this against its cap of ${limits?.maxRunRatePerSecond ?? '?'})`
      : 'Enter whole numbers.';
  }

  async function loadLimits() {
    try {
      limits = await api('/limits');
      const box = $('metrics');
      box.querySelectorAll('label').forEach((l) => l.remove());
      limits.metrics.forEach((metric) => {
        const label = document.createElement('label');
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.value = metric;
        input.checked = true;
        input.addEventListener('change', updateComputed);
        label.append(input, ' ', metric);
        box.append(label);
      });
      $('limits').textContent = `Service limits: ${limits.maxSites} sites, ${limits.maxAssetsPerSite} assets per site, `
        + `${limits.maxRatePerAsset}/s per asset, ${limits.maxRunRatePerSecond} readings/s per run, ${limits.maxDurationSeconds} s, `
        + `${limits.maxConcurrentRuns} concurrent run(s), TTL = duration + ${limits.ttlGraceSeconds} s.`;
      updateComputed();
    } catch (error) {
      $('limits').textContent = 'Limits unknown: the service did not answer.';
    }
  }

  async function arm(event) {
    event.preventDefault();
    try {
      const r = request();
      armed = await api('/runs', 'POST', r);
      armed.kind = r.kind;
      $('confirm-empty').hidden = true;
      $('confirm-armed').hidden = false;
      $('armed-id').textContent = armed.runId;
      $('armed-kind').textContent = armed.kind;
      $('armed-seed').textContent = armed.seed;
      $('armed-rate').textContent = `${armed.baseRate} readings/s`;
      $('confirm-ns-name').textContent = armed.namespace;
      $('confirm-ns').value = '';
      $('start').disabled = true;
      $('confirm-ns').focus();
      say(`Armed ${armed.runId}. Type the namespace to start it.`);
    } catch (error) {
      say(`Not armed: ${error.message}`);
    }
  }

  async function start() {
    if (!armed) { return; }
    try {
      await api(`/runs/${armed.runId}/start`, 'POST', { confirmToken: armed.confirmToken, confirmNamespace: $('confirm-ns').value });
      say(`Started ${armed.runId}.`);
      clearArmed();
      pollState();
    } catch (error) {
      say(`Not started: ${error.message}`);
    }
  }

  function clearArmed() {
    armed = null;
    $('confirm-armed').hidden = true;
    $('confirm-empty').hidden = false;
  }

  async function stopRun(runId) {
    try {
      await api(`/runs/${runId}/stop`, 'POST');
      say(`Stop requested for ${runId}.`);
      if (armed?.runId === runId) { clearArmed(); }
    } catch (error) {
      say(`Stop failed: ${error.message}. Use Stop all runs.`);
    }
    pollState();
    refreshHistory();
  }

  async function stopAll() {
    $('stop-dialog').close();
    try {
      const result = await api('/stop-all', 'POST');
      say(`Stopped ${result.stopped.length} run(s); producer resumed.`);
      clearArmed();
    } catch (error) {
      say(`Stop all failed: ${error.message}`);
    }
    pollState();
    refreshHistory();
  }

  function replay(run) {
    const config = JSON.parse(run.config);
    const form = $('run-form');
    form.elements.kind.value = config.kind;
    form.elements.sites.value = config.sites;
    form.elements.assetsPerSite.value = config.assetsPerSite;
    form.elements.ratePerAsset.value = config.ratePerAsset;
    form.elements.durationSeconds.value = config.durationSeconds;
    form.elements.seed.value = run.seed;
    document.querySelectorAll('#metrics input').forEach((i) => { i.checked = config.metrics.includes(i.value); });
    updateComputed();
    $('arm').focus();
    say(`Form filled from ${run.runId} with seed ${run.seed}. Arm and confirm to replay it.`);
  }

  async function preview() {
    const r = request();
    if (!r.seed) { say('Enter a seed to preview; arming without one picks a new seed.'); $('seed').focus(); return; }
    try {
      const result = await api('/preview?limit=20', 'POST', r);
      $('preview-panel').hidden = false;
      $('preview-digest').textContent = `Seed ${result.seed}; digest of the first 200 emissions: ${result.digest}`;
      $('preview-lines').textContent = result.lines.join('\n');
    } catch (error) {
      say(`No preview: ${error.message}`);
    }
  }

  function tick() {
    if (lastConfirmed && Date.now() - lastConfirmed > STALE_MS) {
      markUnknown(`not confirmed for ${Math.round((Date.now() - lastConfirmed) / 1000)} s`);
    } else if (state) {
      renderOpenRuns(state.openRuns);
    }
    if (armed) {
      const left = Date.parse(armed.armedUntil) - serverNow();
      $('armed-countdown').textContent = left > 0 ? seconds(left) : 'expired — arm again';
      $('start').disabled = left <= 0 || $('confirm-ns').value !== armed.namespace;
    }
  }

  document.addEventListener('DOMContentLoaded', () => {
    $('run-form').addEventListener('submit', arm);
    $('run-form').addEventListener('input', updateComputed);
    $('preview').addEventListener('click', preview);
    $('start').addEventListener('click', start);
    $('disarm').addEventListener('click', () => armed && stopRun(armed.runId));
    $('confirm-ns').addEventListener('input', tick);
    $('stop-all').addEventListener('click', () => $('stop-dialog').showModal());
    $('stop-confirm').addEventListener('click', stopAll);
    $('stop-cancel').addEventListener('click', () => $('stop-dialog').close());
    loadLimits();
    pollState();
    refreshHistory();
    setInterval(pollState, 2000);
    setInterval(refreshHistory, 5000);
    setInterval(tick, 1000);
  });
})();
