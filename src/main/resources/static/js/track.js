/* Public tracking: a small static map with the vehicle's approximate square and the destination. */
(function () {
  'use strict';
  const host = document.querySelector('[data-track-map]');
  if (!host) return;
  const v = [Number(host.dataset.vlat), Number(host.dataset.vlng)];
  const d = [Number(host.dataset.dlat), Number(host.dataset.dlng)];
  const sheet = SheetMap.create(host, { compact: true, interactive: false, bounds: [v, d] });
  L.circle(v, { radius: 1200, color: '#1b5c9e', weight: 2, fillOpacity: 0.15 }).bindTooltip('Your delivery vehicle is around here').addTo(sheet.map);
  L.marker(d, { icon: SheetMap.stopIcon('★', 'PENDING', true, 'DELIVERY') }).bindTooltip('Your delivery').addTo(sheet.map);
  sheet.map.fitBounds([v, d], { padding: [30, 30], maxZoom: 12 });
})();
