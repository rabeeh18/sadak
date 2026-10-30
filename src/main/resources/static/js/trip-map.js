/* Trip detail map: every route version, the travelled trail, stops, and the vehicle when live. */
(function () {
  'use strict';
  const host = document.querySelector('[data-trip-map]');
  if (!host) return;
  const { api, fmtTime, esc } = window.Fleet;
  const sheet = SheetMap.create(host, { compact: true });
  const map = sheet.map;
  const layer = L.layerGroup().addTo(map);
  let vehicle = null;

  async function draw(fit) {
    const d = await api('GET', `/api/tower/trips/${host.dataset.tripId}`);
    layer.clearLayers();
    const bounds = [[d.warehouse.lat, d.warehouse.lng]];
    d.routes.forEach(r => {
      const pts = SheetMap.decode(r.geometry);
      L.polyline(pts, { pane: 'routes', color: r.active ? '#1b5c9e' : '#7d7a69', weight: r.active ? 4 : 2,
        opacity: r.active ? 0.9 : 0.6, dashArray: r.active ? '10 7' : '4 6' })
        .bindTooltip(`Route v${r.version}${r.active ? ' (active)' : ''}: ${esc(r.reason)}`, { className: 'sym-tip', sticky: true })
        .addTo(layer);
      pts.forEach(p => bounds.push(p));
    });
    if (d.trail.length > 1) {
      L.polyline(d.trail, { pane: 'routes', color: '#1e1b16', weight: 4, opacity: 0.9 }).addTo(layer);
    }
    const nextId = (d.stops.find(s => s.status === 'PENDING' || s.status === 'ARRIVED') || {}).id;
    d.stops.forEach(s => {
      L.marker([s.lat, s.lng], { icon: SheetMap.stopIcon(s.seq, s.status, s.id === nextId, s.kind) })
        .bindTooltip(`<strong>${s.seq}. ${esc(s.name)}</strong><br>planned ${fmtTime(s.plannedArrival)} · ${s.actualArrival ? 'arrived ' + fmtTime(s.actualArrival) : 'ETA ' + fmtTime(s.eta)}`, { className: 'sym-tip', direction: 'top' })
        .addTo(layer);
      bounds.push([s.lat, s.lng]);
    });
    L.marker([d.warehouse.lat, d.warehouse.lng], { icon: SheetMap.warehouseIcon(d.warehouse.code) }).addTo(layer);
    if (d.status === 'IN_PROGRESS' && d.summary && d.summary.lat) {
      vehicle = L.marker([d.summary.lat, d.summary.lng], {
        icon: SheetMap.vehicleIcon(d.code, d.summary.riskBand, 0, 'selected'), zIndexOffset: 1000
      }).addTo(layer);
      vehicle._vid = d.vehicle.id;
    }
    if (fit) map.fitBounds(bounds, { padding: [24, 24], maxZoom: 12 });
  }

  draw(true).catch(() => {});
  window.Fleet.subscribe('/topic/fleet/positions', frame => {
    if (!vehicle) return;
    const p = frame.vehicles.find(v => v.v === vehicle._vid);
    if (p) SheetMap.glide(vehicle, [p.lat, p.lng], 950);
  });
})();
