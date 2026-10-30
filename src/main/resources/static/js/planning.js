/* Planning: live load against the chosen vehicle's capacity, and a preview of the stops. */
(function () {
  'use strict';
  const form = document.querySelector('[data-planner]');
  if (!form) return;
  const boxes = [...form.querySelectorAll('input[name="shipment"]')];
  const vehicle = form.querySelector('[data-vehicle]');
  const loadEl = form.querySelector('[data-load]');
  const bar = form.querySelector('[data-load-bar]');
  const hint = form.querySelector('[data-load-hint]');
  const submit = form.querySelector('[data-submit]');
  const countEl = form.querySelector('[data-stop-count]');
  const mapHost = form.querySelector('[data-plan-map]');

  const sheet = SheetMap.create(mapHost, { compact: true });
  const wh = [Number(mapHost.dataset.whLat), Number(mapHost.dataset.whLng)];
  L.marker(wh, { icon: SheetMap.warehouseIcon(mapHost.dataset.whCode) }).addTo(sheet.map);
  const stops = L.layerGroup().addTo(sheet.map);

  function update() {
    const chosen = boxes.filter(b => b.checked);
    const load = chosen.reduce((s, b) => s + Number(b.dataset.weight), 0);
    const cap = Number(vehicle.selectedOptions[0]?.dataset.capacity || 0);
    const pct = cap ? Math.min(100, Math.round(100 * load / cap)) : 0;
    loadEl.textContent = `${load} kg of ${cap} kg`;
    bar.style.width = pct + '%';
    bar.style.background = load > cap ? 'var(--road)' : (pct > 85 ? 'var(--orange)' : 'var(--ink)');
    hint.textContent = chosen.length === 0 ? 'Select shipments to see the load.'
      : load > cap ? `Over capacity by ${load - cap} kg — choose a bigger vehicle or fewer shipments.`
      : `${chosen.length} shipment${chosen.length === 1 ? '' : 's'}, ${cap - load} kg spare.`;
    hint.style.color = load > cap ? 'var(--road)' : '';
    submit.disabled = chosen.length === 0 || load > cap || !vehicle.value;

    stops.clearLayers();
    const seen = new Map();
    chosen.forEach(b => seen.set(`${Number(b.dataset.lat).toFixed(4)},${Number(b.dataset.lng).toFixed(4)}`, b));
    const pts = [wh];
    [...seen.values()].forEach((b, i) => {
      const p = [Number(b.dataset.lat), Number(b.dataset.lng)];
      pts.push(p);
      L.marker(p, { icon: SheetMap.stopIcon('•', 'PENDING', false, 'DELIVERY') })
        .bindTooltip(Fleet.esc(b.dataset.name), { className: 'sym-tip', direction: 'top' }).addTo(stops);
    });
    countEl.textContent = `${seen.size} stop${seen.size === 1 ? '' : 's'}`;
    if (pts.length > 1) sheet.map.fitBounds(pts, { padding: [20, 20], maxZoom: 11 });
  }

  boxes.forEach(b => b.addEventListener('change', update));
  vehicle.addEventListener('change', update);
  form.querySelector('[data-select-all]').addEventListener('change', e => {
    boxes.forEach(b => { b.checked = e.target.checked; });
    update();
  });
  update();
})();
