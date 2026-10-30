/* Shared behaviour for signed-in pages: API calls, the simulation clock, live connection,
   notifications and toasts. Exposes window.Fleet. */
(function () {
  'use strict';

  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content || '';
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
  const TZ = 'Asia/Kolkata';
  const timeFmt = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: TZ });
  const dateFmt = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: TZ });

  async function api(method, url, body) {
    const res = await fetch(url, {
      method,
      headers: Object.assign({ 'Accept': 'application/json' },
        body !== undefined ? { 'Content-Type': 'application/json' } : {},
        method !== 'GET' ? { [csrfHeader]: csrfToken } : {}),
      body: body !== undefined ? JSON.stringify(body) : undefined,
      credentials: 'same-origin'
    });
    if (res.status === 401 || res.redirected && res.url.includes('/login')) {
      location.href = '/login';
      throw new Error('Signed out');
    }
    const data = res.headers.get('content-type')?.includes('json') ? await res.json() : null;
    if (!res.ok) {
      throw new Error(data?.error || ('Request failed (' + res.status + ')'));
    }
    return data;
  }

  /** Accepts epoch millis or ISO strings (Jackson writes Instants as ISO). */
  function toMs(v) {
    if (v === null || v === undefined || v === '') return null;
    if (typeof v === 'number') return v;
    const n = Date.parse(v);
    return Number.isNaN(n) ? null : n;
  }
  const fmtTime = v => { const ms = toMs(v); return ms === null ? '—' : timeFmt.format(ms); };
  const fmtDelta = m => m === null || m === undefined ? '—' : (m === 0 ? 'on plan' : (m > 0 ? '+' : '−') + Math.abs(m) + ' min');
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const icon = (name, cls) => `<svg class="icon ${cls || ''}" aria-hidden="true"><use href="/img/icons.svg#i-${name}"></use></svg>`;
  const spot = (score, band) => `<span class="spot band-${band}" title="Risk ${score} (${band.toLowerCase()})"><svg viewBox="0 0 10 10" aria-hidden="true"><path d="M5 .8 9.4 9.2H.6z" fill="currentColor"/></svg>${score}</span>`;

  // ------------------------------------------------------------------ simulation clock

  const clockEl = document.querySelector('[data-simclock]');
  const clock = {
    base: Date.now(), baseSim: Date.now(), speed: 1, paused: false,
    now() { return this.paused ? this.baseSim : this.baseSim + (Date.now() - this.base) * this.speed; },
    sync(simMs, speed, paused) {
      this.baseSim = simMs; this.base = Date.now();
      if (speed !== undefined) this.speed = speed;
      if (paused !== undefined) this.paused = paused;
      renderClockControls();
    }
  };
  function renderClockControls() {
    if (!clockEl) return;
    const sel = clockEl.querySelector('[data-clock-speed]');
    if (sel && document.activeElement !== sel) sel.value = String(Math.round(clock.speed));
    clockEl.toggleAttribute('data-paused', clock.paused);
    clockEl.querySelector('[data-icon-pause]').hidden = clock.paused;
    clockEl.querySelector('[data-icon-play]').hidden = !clock.paused;
    clockEl.querySelector('[data-clock-toggle]').setAttribute('aria-label', clock.paused ? 'Resume simulation' : 'Pause simulation');
  }
  if (clockEl) {
    clock.sync(Number(clockEl.dataset.now), Number(clockEl.dataset.speed), clockEl.hasAttribute('data-paused'));
    const timeEl = clockEl.querySelector('[data-clock-time]');
    const dateEl = clockEl.querySelector('[data-clock-date]');
    const tick = () => {
      const n = clock.now();
      timeEl.textContent = timeFmt.format(n);
      dateEl.textContent = dateFmt.format(n);
    };
    tick();
    setInterval(tick, 500);
    clockEl.querySelector('[data-clock-speed]').addEventListener('change', async e => {
      const r = await api('POST', '/api/sim/speed', { speed: Number(e.target.value) });
      clock.sync(r.now, r.speed, r.paused);
    });
    clockEl.querySelector('[data-clock-toggle]').addEventListener('click', async () => {
      const r = await api('POST', clock.paused ? '/api/sim/resume' : '/api/sim/pause');
      clock.sync(r.now, r.speed, r.paused);
    });
  }

  // ------------------------------------------------------------------ toasts

  const toastStack = document.querySelector('[data-toasts]');
  function toast(title, body, severity, link) {
    if (!toastStack) return;
    const el = document.createElement(link ? 'a' : 'div');
    el.className = 'toast';
    if (link) { el.href = link; el.style.display = 'block'; el.style.color = 'inherit'; el.style.textDecoration = 'none'; }
    el.dataset.severity = severity || '';
    el.innerHTML = `<strong>${esc(title)}</strong>${body ? `<div class="small muted">${esc(body)}</div>` : ''}`;
    toastStack.appendChild(el);
    setTimeout(() => el.remove(), 8000);
    while (toastStack.children.length > 4) toastStack.firstChild.remove();
  }

  // ------------------------------------------------------------------ notifications

  const notesBtn = document.querySelector('[data-notes-toggle]');
  const notesPanel = document.querySelector('[data-notes-panel]');
  const notesCount = document.querySelector('[data-notes-count]');
  async function refreshNoteCount() {
    if (!notesCount) return;
    try {
      const r = await api('GET', '/api/notifications/unread');
      notesCount.textContent = r.count > 0 ? (r.count > 99 ? '99+' : r.count) : '';
    } catch (e) { /* ignore */ }
  }
  async function openNotes() {
    const list = notesPanel.querySelector('[data-notes-list]');
    const items = await api('GET', '/api/notifications');
    list.innerHTML = items.length === 0
      ? '<div class="empty"><strong>Nothing new</strong>Alerts for high-risk trips and incidents appear here.</div>'
      : items.map(n => `<a class="note-item" href="${esc(n.link || '#')}" ${n.read ? '' : 'data-unread'}>
          <div class="t">${esc(n.title)}</div><div class="b">${esc(n.body || '')} · ${esc(n.ago)}</div></a>`).join('');
  }
  if (notesBtn) {
    notesBtn.addEventListener('click', async () => {
      const open = notesPanel.hidden;
      notesPanel.hidden = !open;
      notesBtn.setAttribute('aria-expanded', String(open));
      if (open) await openNotes();
    });
    document.addEventListener('click', e => {
      if (!notesPanel.hidden && !notesPanel.contains(e.target) && !notesBtn.contains(e.target)) {
        notesPanel.hidden = true; notesBtn.setAttribute('aria-expanded', 'false');
      }
    });
    notesPanel.querySelector('[data-notes-read]').addEventListener('click', async () => {
      await api('POST', '/api/notifications/read');
      await openNotes();
      refreshNoteCount();
    });
    refreshNoteCount();
  }

  // ------------------------------------------------------------------ live connection

  const handlers = {};
  let stomp = null;
  function subscribe(topic, fn) {
    (handlers[topic] = handlers[topic] || []).push(fn);
    if (stomp && stomp.connected) stomp.subscribe(topic, m => fn(JSON.parse(m.body)));
  }
  function connect() {
    if (!window.StompJs || !clockEl) return;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    stomp = new StompJs.Client({
      brokerURL: `${proto}://${location.host}/ws`,
      connectHeaders: { [csrfHeader]: csrfToken },
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000
    });
    stomp.onConnect = () => {
      document.documentElement.dataset.live = 'on';
      Object.entries(handlers).forEach(([topic, fns]) => stomp.subscribe(topic, m => {
        const data = JSON.parse(m.body);
        fns.forEach(fn => fn(data));
      }));
    };
    stomp.onWebSocketClose = () => { document.documentElement.dataset.live = 'off'; };
    stomp.activate();
  }

  subscribe('/user/queue/notifications', n => {
    toast(n.title, n.body, n.severity, n.link);
    refreshNoteCount();
  });
  subscribe('/topic/fleet/incidents', () => {
    api('GET', '/api/tower/kpis').then(k => {
      document.querySelectorAll('[data-incident-count]').forEach(el => { el.textContent = k.openIncidents > 0 ? k.openIncidents : ''; });
    }).catch(() => {});
  });
  subscribe('/topic/fleet/positions', frame => clock.sync(frame.t, frame.speed, frame.paused));

  // Rows that link somewhere.
  document.addEventListener('click', e => {
    const row = e.target.closest('tr[data-href]');
    if (row && !e.target.closest('a, button, input, select, label')) location.href = row.dataset.href;
  });
  // Selects that apply immediately (no inline handlers: the CSP forbids them).
  document.addEventListener('change', e => {
    if (e.target.matches('[data-autosubmit]')) e.target.form.submit();
  });
  // Confirm before destructive form posts.
  document.addEventListener('submit', e => {
    const msg = e.target.dataset.confirm;
    if (msg && !confirm(msg)) e.preventDefault();
  });

  window.Fleet = { api, toMs, fmtTime, fmtDelta, esc, icon, spot, toast, subscribe, clock, csrfToken, csrfHeader };
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', connect);
  } else {
    connect();
  }
})();
