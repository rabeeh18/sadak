/* Click-to-place a point on the sheet map; fills latitude/longitude inputs. */
(function () {
  'use strict';
  const host = document.querySelector('[data-pick-map]');
  if (!host) return;
  const lat = document.querySelector('[data-lat]');
  const lng = document.querySelector('[data-lng]');
  const label = document.querySelector('[data-pick-grid]');
  const sheet = SheetMap.create(host);
  let marker = null;

  function place(p) {
    if (!marker) {
      marker = L.marker(p, { icon: SheetMap.stopIcon('•', 'PENDING', true, 'DELIVERY'), draggable: true }).addTo(sheet.map);
      marker.on('dragend', () => place(marker.getLatLng()));
    } else {
      marker.setLatLng(p);
    }
    const ll = L.latLng(p);
    lat.value = ll.lat.toFixed(5);
    lng.value = ll.lng.toFixed(5);
    if (label) label.textContent = `Square ${SheetMap.gridRef(ll.lat, ll.lng)} · ${ll.lat.toFixed(4)}, ${ll.lng.toFixed(4)}`;
  }

  sheet.map.on('click', e => place(e.latlng));
  [lat, lng].forEach(input => input.addEventListener('change', () => {
    if (lat.value && lng.value) {
      place([Number(lat.value), Number(lng.value)]);
      sheet.map.panTo([Number(lat.value), Number(lng.value)]);
    }
  }));
  if (lat.value && lng.value) place([Number(lat.value), Number(lng.value)]);
})();
