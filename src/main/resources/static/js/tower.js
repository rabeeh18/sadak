/* Control tower: live map, trip index, incident feed and the selected-trip panel. */
(function () {
  'use strict';
  const { api, fmtTime, fmtDelta, esc, icon, spot, subscribe, toast, toMs } = window.Fleet;

  const root = document.querySelector('[data-tower]');
  const sheet = SheetMap.create(root.querySelector('[data-sheet]'));
  const map = sheet.map;

  const state = {
    trips: new Map(),        // tripId -> summary
    vehicles: new Map(),     // vehicleId -> vehicle json
    markers: new Map(),      // vehicleId -> marker
    routeLines: new Map(),   // tripId -> faint route polyline
    selected: null,          // tripId
    detail: null,            // selected trip detail json
    detailLayer: L.layerGroup().addTo(map),
    incidents: []
  };
  const routesLayer = L.layerGroup().addTo(map);

  // ------------------------------------------------------------------ initial load

  async function load() {
    const snap = await api('GET', '/api/tower/snapshot');
    snap.warehouses.forEach(w => {
      L.marker([w.lat, w.lng], { icon: SheetMap.warehouseIcon(w.code), keyboard: false, zIndexOffset: -100 })
        .bindTooltip(`<strong>${esc(w.name)}</strong><br>${esc(w.city)} · square ${w.grid}`, { className: 'sym-tip', direction: 'top', offset: [0, -6] })
        .addTo(map);
    });
    snap.vehicles.forEach(v => state.vehicles.set(v.id, v));
    applyTrips(snap.trips);
    applyPositions(snap.positions);
    applyKpis(snap.kpis);
    state.incidents = snap.incidents;
    renderIncidents();
    renderFleet();
    loadRoutes();
  }

  async function loadRoutes() {
    for (const tripId of state.trips.keys()) {
      if (state.routeLines.has(tripId)) continue;
      try {
        const d = await api('GET', `/api/tower/trips/${tripId}`);
        const active = d.routes.find(r => r.active);
        if (!active) continue;
        const line = L.polyline(SheetMap.decode(active.geometry), {
          pane: 'routes', color: '#1b5c9e', weight: 2, opacity: 0.4, dashArray: '6 6', interactive: false
        }).addTo(routesLayer);
        state.routeLines.set(tripId, line);
      } catch (e) { /* trip may have ended */ }
    }
  }

  // ------------------------------------------------------------------ live data

  function applyTrips(list) {
    const ids = new Set();
    list.forEach(t => { state.trips.set(t.tripId, t); ids.add(t.tripId); });
    for (const id of [...state.trips.keys()]) {
      if (!ids.has(id)) {
        state.trips.delete(id);
        const line = state.routeLines.get(id);
        if (line) { routesLayer.removeLayer(line); state.routeLines.delete(id); }
      }
    }
    renderTrips();
    restyleMarkers();
    if (state.selected && state.trips.has(state.selected)) renderDetailHeader();
  }

  function applyPositions(frame) {
    const byVehicle = new Map(frame.vehicles.map(p => [p.v, p]));
    byVehicle.forEach((p, vehicleId) => {
      let m = state.markers.get(vehicleId);
      const trip = p.trip ? state.trips.get(p.trip) : null;
      if (m && m._idle) {
        // A parked vehicle has started reporting: replace its idle symbol with a moving one.
        map.removeLayer(m);
        m = null;
      }
      if (!m) {
        m = L.marker([p.lat, p.lng], { keyboard: false, riseOnHover: true, icon: SheetMap.vehicleIcon('', 'LOW', 0, '') }).addTo(map);
        const marker = m;
        marker.on('click', () => { if (marker._tripId) select(marker._tripId); });
        state.markers.set(vehicleId, m);
      } else {
        SheetMap.glide(m, [p.lat, p.lng], 950);
      }
      m._tripId = p.trip || null;
      m._pos = p;
      setMarkerIcon(m, vehicleId, trip, p);
    });
    // Vehicles with no fix yet (idle at their warehouse).
    state.vehicles.forEach((v, id) => {
      if (state.markers.has(id) || v.status === 'ON_TRIP') return;
      const m = L.marker([v.lat, v.lng], { keyboard: false, icon: SheetMap.idleIcon(v.registration.slice(-4)) })
        .bindTooltip(`${esc(v.registration)} · ${esc(v.statusLabel)}`, { className: 'sym-tip', direction: 'top' }).addTo(map);
      m._idle = true;
      state.markers.set(id, m);
    });
  }

  function setMarkerIcon(m, vehicleId, trip, p) {
    if (m._idle) return;
    const band = trip ? trip.riskBand : 'LOW';
    const label = trip ? trip.code : (state.vehicles.get(vehicleId)?.registration || '');
    const stale = p && p.stale;
    const key = [band, label, Math.round((p?.h || 0) / 10), stale, state.selected === trip?.tripId, dimmed(trip)].join('|');
    if (m._key === key) return;
    m._key = key;
    const states = [stale ? 'stale' : '', state.selected && trip && state.selected === trip.tripId ? 'selected' : '', dimmed(trip) ? 'dim' : '']
      .filter(Boolean).join(' is-');
    m.setIcon(SheetMap.vehicleIcon(label, band, p?.h || 0, states));
    m.setZIndexOffset(trip && state.selected === trip.tripId ? 1000 : (band === 'CRITICAL' ? 500 : band === 'HIGH' ? 300 : 0));
    if (trip) {
      m.bindTooltip(`<strong>${esc(trip.code)}</strong> · ${esc(trip.registration)}<br>${esc(trip.driver)}<br>${esc(trip.condition)} · square ${esc(trip.grid)}`,
        { className: 'sym-tip', direction: 'top', offset: [0, -10] });
    }
  }

  function dimmed(trip) {
    return state.selected !== null && (!trip || trip.tripId !== state.selected);
  }

  function restyleMarkers() {
    state.markers.forEach((m, vehicleId) => {
      if (m._idle) {
        m.getElement()?.classList.toggle('is-dim', state.selected !== null);
        return;
      }
      const trip = m._tripId ? state.trips.get(m._tripId) : null;
      setMarkerIcon(m, vehicleId, trip, m._pos);
    });
    state.routeLines.forEach((line, tripId) => {
      line.setStyle({ opacity: state.selected === null ? 0.4 : (tripId === state.selected ? 0 : 0.12) });
    });
  }

  function applyKpis(k) {
    root.querySelectorAll('[data-kpi]').forEach(el => {
      const v = k[el.dataset.kpi];
      el.textContent = v === null || v === undefined ? '—' : v;
    });
    const onTime = root.querySelector('[data-kpi-note="onTime"]');
    if (onTime) onTime.textContent = k.onTimeToday === null ? 'No deliveries yet' : `${k.onTimeToday}% on time`;
    root.querySelector('[data-tone-for="atRisk"]').dataset.tone = k.atRisk > 0 ? 'warn' : '';
    root.querySelector('[data-tone-for="openIncidents"]').dataset.tone = k.criticalIncidents > 0 ? 'alert' : (k.openIncidents > 0 ? 'warn' : '');
  }

  // ------------------------------------------------------------------ side list

  const tabs = root.querySelectorAll('[data-tab]');
  tabs.forEach(tab => tab.addEventListener('click', () => {
    tabs.forEach(t => t.setAttribute('aria-selected', String(t === tab)));
    root.querySelectorAll('[data-pane]').forEach(p => { p.hidden = p.dataset.pane !== tab.dataset.tab; });
  }));

  function renderTrips() {
    const rows = [...state.trips.values()].sort((a, b) => b.riskScore - a.riskScore || a.code.localeCompare(b.code));
    root.querySelector('[data-trip-count]').textContent = rows.length;
    const body = root.querySelector('[data-trip-rows]');
    if (rows.length === 0) {
      body.innerHTML = `<tr><td colspan="5"><div class="empty"><strong>No trips on the road</strong>Planned trips depart at their start time. <a href="/planning">Plan a trip</a></div></td></tr>`;
      return;
    }
    body.innerHTML = rows.map(t => {
      const dev = t.lateMin ? `<span class="dev late">+${t.lateMin} min late</span>` : `<span class="dev">${esc(fmtDelta(t.deviationMin))}</span>`;
      const cond = esc(t.condition).replace(/ /g, '-');
      return `<tr data-trip="${t.tripId}" aria-selected="${state.selected === t.tripId}">
        <td>${spot(t.riskScore, t.riskBand)}</td>
        <td><span class="code">${esc(t.code)}</span> <span class="plate" style="font-size:10px;padding:0 4px">${esc(t.registration.slice(-7))}</span>
            <span class="route">${esc(t.route)}</span></td>
        <td class="r">${dev}<span class="sub">${t.stopsDone}/${t.stopsTotal} stops</span></td>
        <td class="cond"><span class="cond-tag cond-${cond}">${esc(t.condition)}</span>
            <div class="progress bar"><i style="width:${t.progress}%"></i></div></td>
        <td><span class="grid-ref">${esc(t.grid)}</span></td>
      </tr>`;
    }).join('');
  }
  root.querySelector('[data-trip-rows]').addEventListener('click', e => {
    const row = e.target.closest('tr[data-trip]');
    if (row) select(Number(row.dataset.trip));
  });

  function renderIncidents() {
    root.querySelector('[data-incident-tab-count]').textContent = state.incidents.length || '';
    const ul = root.querySelector('[data-incident-rows]');
    if (state.incidents.length === 0) {
      ul.innerHTML = '<li class="empty"><strong>No open incidents</strong>Monitoring checks every live trip every few seconds.</li>';
      return;
    }
    ul.innerHTML = state.incidents.map(i => `<li><a href="/incidents/${i.id}">
        <span class="sev"><span class="tag ${sevTag(i.severity)}">${esc(i.severity.toLowerCase())}</span></span>
        <span class="title">${esc(i.title)}</span>
        <span class="meta">${esc(i.code)} · ${esc(i.typeLabel)} · ${esc(i.statusLabel)}${i.owner ? ' · ' + esc(i.owner) : ''}</span>
      </a></li>`).join('');
  }

  function renderFleet() {
    const body = root.querySelector('[data-fleet-rows]');
    body.innerHTML = [...state.vehicles.values()].map(v => `<tr data-href="/fleet/vehicles/${v.id}">
      <td><span class="plate">${esc(v.registration)}</span><span class="sub">${esc(v.type)} · ${v.capacityKg} kg</span></td>
      <td>${esc(v.statusLabel)}</td><td>${esc(v.warehouse)}</td></tr>`).join('');
  }

  const sevTag = s => ({ CRITICAL: 'tag-solid-red', HIGH: 'tag-orange', MEDIUM: 'tag-ochre', LOW: 'tag-ink' }[s] || 'tag-ink');

  // ------------------------------------------------------------------ selection

  const listEl = root.querySelector('[data-side-list]');
  const detailEl = root.querySelector('[data-side-detail]');

  async function select(tripId) {
    state.selected = tripId;
    listEl.hidden = true;
    detailEl.hidden = false;
    detailEl.innerHTML = '<div class="empty">Loading trip…</div>';
    restyleMarkers();
    renderTrips();
    await refreshDetail(true);
  }

  function deselect() {
    state.selected = null;
    state.detail = null;
    state.detailLayer.clearLayers();
    detailEl.hidden = true;
    listEl.hidden = false;
    restyleMarkers();
    renderTrips();
  }

  async function refreshDetail(fit) {
    if (state.selected === null) return;
    const id = state.selected;
    let d;
    try {
      d = await api('GET', `/api/tower/trips/${id}`);
    } catch (e) {
      detailEl.innerHTML = `<div class="empty"><strong>Trip not available</strong>${esc(e.message)}</div>`;
      return;
    }
    if (state.selected !== id) return;
    state.detail = d;
    drawDetailOnMap(d, fit);
    renderDetail();
  }

  function drawDetailOnMap(d, fit) {
    const layer = state.detailLayer;
    layer.clearLayers();
    const bounds = [];
    d.routes.forEach(r => {
      const pts = SheetMap.decode(r.geometry);
      L.polyline(pts, {
        pane: 'routes', color: r.active ? '#1b5c9e' : '#7d7a69', weight: r.active ? 4 : 2,
        opacity: r.active ? 0.9 : 0.6, dashArray: r.active ? '10 7' : '4 6', interactive: false
      }).addTo(layer);
      if (r.active) pts.forEach(p => bounds.push(p));
    });
    if (d.trail.length > 1) {
      L.polyline(d.trail, { pane: 'routes', color: '#1e1b16', weight: 4, opacity: 0.9, interactive: false }).addTo(layer);
      d.trail.forEach(p => bounds.push(p));
    }
    const nextId = (d.stops.find(s => s.status === 'PENDING' || s.status === 'ARRIVED') || {}).id;
    d.stops.forEach(s => {
      L.marker([s.lat, s.lng], { icon: SheetMap.stopIcon(s.seq, s.status, s.id === nextId, s.kind), keyboard: false })
        .bindTooltip(`<strong>${s.seq}. ${esc(s.name)}</strong><br>ETA ${fmtTime(s.eta)} · due ${fmtTime(s.deadline)}`, { className: 'sym-tip', direction: 'top' })
        .addTo(layer);
      bounds.push([s.lat, s.lng]);
    });
    L.marker([d.warehouse.lat, d.warehouse.lng], { icon: SheetMap.warehouseIcon(d.warehouse.code), keyboard: false }).addTo(layer);
    bounds.push([d.warehouse.lat, d.warehouse.lng]);
    if (fit && bounds.length) map.flyToBounds(bounds, { padding: [40, 40], duration: 0.8, maxZoom: 12 });
  }

  function renderDetail() {
    const d = state.detail;
    const t = state.trips.get(d.id) || d.summary;
    const live = d.status === 'IN_PROGRESS';
    const nextIdx = d.stops.findIndex(s => s.status === 'PENDING' || s.status === 'ARRIVED');
    const scen = d.scenario;
    const reasons = t && t.reasons && t.reasons.length
      ? `<ul class="reasons">${t.reasons.map(r => `<li><span class="pts band-${t.riskBand}">+${r.points}</span><span><span class="what">${esc(r.label)}</span><span class="why">${esc(r.detail)}</span></span></li>`).join('')}</ul>`
      : '<p class="muted small" style="margin:0">No risk conditions. Every rule is checked every few seconds.</p>';

    detailEl.innerHTML = `
      <div class="detail-head" data-detail-head></div>
      <div class="detail-body">
        <div class="detail-facts" data-detail-facts></div>
        <section class="detail-section">
          <h3 class="caps">Why this score</h3>
          <div data-reasons>${reasons}</div>
        </section>
        ${live ? `<section class="detail-section">
          <h3 class="caps">Simulate a disruption</h3>
          <div class="sim-controls">
            <button class="btn" data-scenario="SLOW" aria-pressed="${scen === 'SLOW'}">${icon('snail')}Slow down</button>
            <button class="btn" data-scenario="EXTEND_STOP">${icon('timer')}Stop +20 min</button>
            <button class="btn" data-scenario="DETOUR" aria-pressed="${scen === 'DETOUR'}">${icon('signpost')}Detour</button>
            <button class="btn" data-scenario="SILENT" aria-pressed="${scen === 'SILENT'}">${icon('wifi-off')}Go silent</button>
            <button class="btn btn-danger" data-scenario="BREAKDOWN" aria-pressed="${scen === 'BREAKDOWN'}">${icon('wrench')}Breakdown</button>
            <button class="btn btn-quiet" data-scenario="NORMAL">${icon('refresh-cw')}Normal</button>
          </div>
          ${d.extraDwellMin ? `<p class="small muted" style="margin:8px 0 0">Next stop will take ${d.extraDwellMin} min longer.</p>` : ''}
        </section>` : ''}
        ${d.incidents.length ? `<section class="detail-section">
          <h3 class="caps">Open incidents</h3>
          <ul class="incident-feed">${d.incidents.map(i => `<li><a href="/incidents/${i.id}" style="padding:8px 0">
            <span class="sev"><span class="tag ${sevTag(i.severity)}">${esc(i.severity.toLowerCase())}</span></span>
            <span class="title">${esc(i.title)}</span><span class="meta">${esc(i.code)} · ${esc(i.statusLabel)}</span></a></li>`).join('')}</ul>
        </section>` : ''}
        <section class="detail-section">
          <h3 class="caps">Stops</h3>
          <ol class="timeline">${d.stops.map((s, i) => {
            const etaMs = toMs(s.eta), dueMs = toMs(s.deadline);
            const late = s.status !== 'COMPLETED' && etaMs && dueMs && etaMs > dueMs;
            const times = s.status === 'COMPLETED' || s.status === 'FAILED'
              ? `<span>arrived <b>${fmtTime(s.actualArrival)}</b></span><span>planned ${fmtTime(s.plannedArrival)}</span>`
              : `<span>ETA <b class="${late ? 'late' : ''}">${fmtTime(s.eta)}</b></span><span>planned ${fmtTime(s.plannedArrival)}</span>${s.deadline ? `<span class="${late ? 'late' : ''}">due ${fmtTime(s.deadline)}</span>` : ''}`;
            return `<li data-state="${s.status}" ${i === nextIdx ? 'data-next' : ''}><span class="node"></span>
              <div class="t-head"><span>${s.seq}. ${esc(s.name)}</span><span class="grid-ref">${esc(s.grid)}</span></div>
              <div class="stop-times">${times}<span>${s.shipments} parcel${s.shipments === 1 ? '' : 's'}</span>${s.status === 'FAILED' ? '<span class="late">failed</span>' : ''}${s.kind === 'TRANSFER' ? '<span>cargo transfer</span>' : ''}</div></li>`;
          }).join('')}</ol>
        </section>
      </div>
      <div class="panel-head" style="border-top:1px solid var(--rule);border-bottom:0">
        <button class="btn btn-quiet" data-back>${icon('arrow-left')}All trips</button>
        <a class="btn btn-primary" href="/trips/${d.id}">Open trip ${icon('arrow-up-right')}</a>
      </div>`;
    renderDetailHeader();
    detailEl.querySelector('[data-back]').addEventListener('click', deselect);
    detailEl.querySelectorAll('[data-scenario]').forEach(b => b.addEventListener('click', () => runScenario(b.dataset.scenario)));
  }

  function renderDetailHeader() {
    const d = state.detail;
    if (!d) return;
    const t = state.trips.get(d.id) || d.summary;
    const head = detailEl.querySelector('[data-detail-head]');
    const facts = detailEl.querySelector('[data-detail-facts]');
    if (!head) return;
    head.innerHTML = `
      <div>
        <h2>${esc(d.code)} <span class="plate">${esc(d.vehicle.registration)}</span></h2>
        <div class="route">${esc(d.warehouse.name)} · ${esc(d.driver.name)} · <a href="tel:${esc(d.driver.phone)}">${esc(d.driver.phone)}</a></div>
      </div>
      <div style="text-align:right">${t ? spot(t.riskScore, t.riskBand) : ''}<div class="small muted">${t ? esc(t.riskBand.toLowerCase()) + ' risk' : esc(d.status)}</div></div>`;
    if (t) {
      facts.innerHTML = `
        <div><span class="figure">${t.stopsDone}<small>/${t.stopsTotal}</small></span><span class="label">stops done</span></div>
        <div><span class="figure">${fmtTime(t.nextEta)}</span><span class="label">next: ${esc(t.nextStop || '—')}</span></div>
        <div><span class="figure ${t.lateMin ? 'band-CRITICAL' : ''}">${t.lateMin ? '+' + t.lateMin : esc(fmtDelta(t.deviationMin).replace(' min', ''))}</span><span class="label">${t.lateMin ? 'min past deadline' : 'min vs plan'} · pace ×${t.pace}</span></div>`;
      const reasons = detailEl.querySelector('[data-reasons]');
      if (reasons && t.reasons) {
        reasons.innerHTML = t.reasons.length
          ? `<ul class="reasons">${t.reasons.map(r => `<li><span class="pts band-${t.riskBand}">+${r.points}</span><span><span class="what">${esc(r.label)}</span><span class="why">${esc(r.detail)}</span></span></li>`).join('')}</ul>`
          : '<p class="muted small" style="margin:0">No risk conditions. Every rule is checked every few seconds.</p>';
      }
    } else {
      facts.innerHTML = `<div style="grid-column:1/-1" class="muted small">${esc(d.status === 'PLANNED' ? 'Planned to depart at ' + fmtTime(d.plannedStart) : 'This trip is no longer on the road.')}</div>`;
    }
  }

  async function runScenario(scenario) {
    if (scenario === 'BREAKDOWN' && !confirm('Report a breakdown for this vehicle? This opens a critical incident.')) return;
    try {
      await api('POST', `/api/sim/trips/${state.selected}/scenario`, { scenario, minutes: 20 });
      const labels = { SLOW: 'Vehicle slowed to 45% of planned speed', EXTEND_STOP: 'Next stop extended by 20 minutes',
        DETOUR: 'Vehicle will leave its route for the next few kilometres', SILENT: 'Vehicle stopped sending its location',
        BREAKDOWN: 'Breakdown reported', NORMAL: 'Vehicle back to normal' };
      toast(labels[scenario] || scenario, state.detail?.code);
      await refreshDetail(false);
    } catch (e) {
      toast('Could not apply', e.message, 'HIGH');
    }
  }

  // ------------------------------------------------------------------ wiring

  subscribe('/topic/fleet/positions', frame => {
    applyPositions(frame);
    const status = root.querySelector('[data-live-status]');
    if (status) status.textContent = `Live · ${frame.vehicles.length} vehicles · ${Math.round(frame.speed)}× ${frame.paused ? '(paused)' : ''}`;
  });
  let lastDetailRefresh = 0;
  subscribe('/topic/fleet/trips', payload => {
    applyTrips(payload.trips);
    if ([...state.trips.keys()].some(id => !state.routeLines.has(id))) loadRoutes();
    if (state.selected !== null && Date.now() - lastDetailRefresh > 8000) {
      lastDetailRefresh = Date.now();
      refreshDetail(false);
    }
  });
  subscribe('/topic/fleet/incidents', async () => {
    const snap = await api('GET', '/api/tower/snapshot');
    state.incidents = snap.incidents;
    renderIncidents();
    applyKpis(snap.kpis);
  });
  setInterval(() => api('GET', '/api/tower/kpis').then(applyKpis).catch(() => {}), 15000);
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && state.selected !== null) deselect(); });

  load().catch(e => toast('Could not load the control tower', e.message, 'CRITICAL'));
})();
