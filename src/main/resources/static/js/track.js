/* Public tracking: the map (the road still to go, the truck's rough area, the destination) and the help options (one panel open at a time). */
(function () {
  'use strict';

  // ------------------------------------------------------------------ map
  const host = document.querySelector('[data-track-map]');
  if (host && window.SheetMap && window.L) {
    const v = [Number(host.dataset.vlat), Number(host.dataset.vlng)];
    const d = [Number(host.dataset.dlat), Number(host.dataset.dlng)];
    const sheet = SheetMap.create(host, { compact: true, interactive: true, bounds: [v, d] });
    const map = sheet.map;
    const extent = L.latLngBounds([v, d]);
    // The truck is "somewhere in this circle". The road ahead starts on the circle's edge, so the
    // line never points at its exact position (the server also leaves a gap around it).
    const RADIUS = 1800;
    if (host.dataset.todo) {
      const todo = clipToCircle(SheetMap.decode(host.dataset.todo), L.latLng(v), RADIUS, map);
      const line = L.polyline(todo, { color: '#1b5c9e', weight: 4, dashArray: '9 7', pane: 'routes' }).addTo(map);
      todo.forEach(p => extent.extend(p));
      if (host.dataset.left) {
        line.bindTooltip(host.dataset.left, { permanent: true, direction: 'center', className: 'trk-left' });
      }
    }
    L.circle(v, { radius: RADIUS, color: '#1b5c9e', weight: 2, fillOpacity: 0.15 })
      .bindTooltip('Your delivery truck is somewhere in here').addTo(map);
    L.marker(d, { icon: SheetMap.stopIcon('★', 'PENDING', true, 'DELIVERY') }).bindTooltip('Your delivery').addTo(map);
    const fit = () => map.fitBounds(extent, { padding: [28, 28], maxZoom: 13 });
    fit();
    setTimeout(() => { map.invalidateSize(); fit(); }, 150);
  }

  /** Drops the part of the route inside the circle, so the line starts exactly on its edge. */
  function clipToCircle(points, centre, radius, map) {
    const inside = p => map.distance(L.latLng(p), centre) < radius;
    let i = 0;
    while (i < points.length && inside(points[i])) i++;
    if (i === 0 || i === points.length) return i === 0 ? points : [];
    const a = points[i - 1];
    const b = points[i];
    let lo = 0;
    let hi = 1;
    for (let k = 0; k < 20; k++) {
      const mid = (lo + hi) / 2;
      if (inside([a[0] + (b[0] - a[0]) * mid, a[1] + (b[1] - a[1]) * mid])) lo = mid; else hi = mid;
    }
    return [[a[0] + (b[0] - a[0]) * hi, a[1] + (b[1] - a[1]) * hi]].concat(points.slice(i));
  }

  // ------------------------------------------------------------------ help options
  const help = document.querySelector('[data-help]');
  if (help) {
    const tabs = [...help.querySelectorAll('[data-help-tab]')];
    const panels = [...help.querySelectorAll('[data-help-panel]')];
    function open(name, focus) {
      tabs.forEach(t => {
        const on = t.dataset.helpTab === name;
        t.classList.toggle('on', on);
        t.setAttribute('aria-selected', String(on));
      });
      panels.forEach(p => { p.hidden = p.dataset.helpPanel !== name; });
      if (focus) {
        const panel = panels.find(p => p.dataset.helpPanel === name);
        panel?.querySelector('textarea, input:not([type=hidden]), a.btn')?.focus();
      }
    }
    tabs.forEach(t => t.addEventListener('click', () => {
      const already = t.classList.contains('on');
      if (already) {
        t.classList.remove('on');
        panels.forEach(p => { p.hidden = true; });
      } else {
        open(t.dataset.helpTab, true);
      }
    }));
    if (help.dataset.open) {
      open(help.dataset.open, false);
      help.scrollIntoView({ block: 'start' });
    }

    // Character count for the note.
    help.querySelectorAll('[data-count]').forEach(area => {
      const out = document.querySelector(area.dataset.count);
      const max = Number(area.getAttribute('maxlength'));
      const update = () => { out.textContent = area.value.length + ' / ' + max; };
      area.addEventListener('input', update);
      update();
    });
  }
})();
