/* The survey-sheet map: a Leaflet map framed by a double neatline, with the lettered
   quarter-degree grid drawn on the map and its letters, numbers and graticule printed in the
   collar. Grid squares match GridReference.java, so a reference on a table row points at the
   same square on the map. Exposes window.SheetMap. */
(function () {
  'use strict';

  const GRID = { west: 73.75, north: 32.55, cell: 0.25, cols: 13, rows: 12 };
  const PUNJAB_BOUNDS = [[29.55, 73.85], [32.5, 76.95]];

  function gridRef(lat, lng) {
    const col = Math.floor((lng - GRID.west) / GRID.cell);
    const row = Math.floor((GRID.north - lat) / GRID.cell);
    if (col < 0 || col >= GRID.cols || row < 0 || row >= GRID.rows) return '—';
    return String.fromCharCode(65 + col) + (row + 1);
  }

  function dm(value, pos, neg) {
    const abs = Math.abs(value);
    const d = Math.floor(abs);
    const m = Math.round((abs - d) * 60);
    return `${d}°${String(m).padStart(2, '0')}′${value >= 0 ? pos : neg}`;
  }

  /** Decodes a precision-5 encoded polyline into [lat, lng] pairs. */
  function decode(str) {
    let index = 0, lat = 0, lng = 0;
    const out = [];
    while (index < str.length) {
      let b, shift = 0, result = 0;
      do { b = str.charCodeAt(index++) - 63; result |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20);
      lat += (result & 1) ? ~(result >> 1) : (result >> 1);
      shift = 0; result = 0;
      do { b = str.charCodeAt(index++) - 63; result |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20);
      lng += (result & 1) ? ~(result >> 1) : (result >> 1);
      out.push([lat / 1e5, lng / 1e5]);
    }
    return out;
  }

  /**
   * Builds the sheet inside `host` and returns { map, el }.
   * Options: { bounds, interactive (default true), grid (default true), compact }
   */
  function create(host, options) {
    const opts = Object.assign({ interactive: true, grid: true, compact: false }, options || {});
    host.classList.add('sheet');
    if (opts.compact) host.classList.add('sheet-compact');
    host.innerHTML = `
      <div class="sheet-collar sheet-top" aria-hidden="true"></div>
      <div class="sheet-collar sheet-left" aria-hidden="true"></div>
      <div class="sheet-map"></div>
      <div class="sheet-collar sheet-right" aria-hidden="true"></div>
      <div class="sheet-collar sheet-bottom" aria-hidden="true"></div>`;
    const mapEl = host.querySelector('.sheet-map');
    const map = L.map(mapEl, {
      zoomControl: opts.interactive,
      attributionControl: true,
      scrollWheelZoom: opts.interactive,
      dragging: opts.interactive,
      doubleClickZoom: opts.interactive,
      boxZoom: false,
      keyboard: opts.interactive,
      zoomSnap: 0.25,
      preferCanvas: false
    });
    map.attributionControl.setPrefix(false);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 18,
      attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    }).addTo(map);
    L.control.scale({ metric: true, imperial: false, position: 'bottomleft' }).addTo(map);

    map.createPane('grid');
    map.getPane('grid').style.zIndex = 350;
    map.getPane('grid').style.pointerEvents = 'none';
    map.createPane('routes');
    map.getPane('routes').style.zIndex = 420;

    if (opts.grid) {
      const lines = [];
      for (let c = 0; c <= GRID.cols; c++) {
        const lng = GRID.west + c * GRID.cell;
        lines.push([[GRID.north - GRID.rows * GRID.cell, lng], [GRID.north, lng]]);
      }
      for (let r = 0; r <= GRID.rows; r++) {
        const lat = GRID.north - r * GRID.cell;
        lines.push([[lat, GRID.west], [lat, GRID.west + GRID.cols * GRID.cell]]);
      }
      L.polyline(lines, { pane: 'grid', color: '#8e5a2b', weight: 1, opacity: 0.45, interactive: false }).addTo(map);
    }

    map.fitBounds(opts.bounds || PUNJAB_BOUNDS, { padding: [8, 8] });

    const top = host.querySelector('.sheet-top');
    const left = host.querySelector('.sheet-left');
    const right = host.querySelector('.sheet-right');
    const bottom = host.querySelector('.sheet-bottom');
    function drawCollar() {
      const size = map.getSize();
      let topHtml = '', leftHtml = '', rightHtml = '', bottomHtml = '';
      for (let c = 0; c < GRID.cols; c++) {
        const lngMid = GRID.west + (c + 0.5) * GRID.cell;
        const x = map.latLngToContainerPoint([map.getCenter().lat, lngMid]).x;
        if (x > 8 && x < size.x - 8) {
          topHtml += `<span class="sheet-letter" style="left:${x}px">${String.fromCharCode(65 + c)}</span>`;
        }
        const lngEdge = GRID.west + c * GRID.cell;
        const xe = map.latLngToContainerPoint([map.getCenter().lat, lngEdge]).x;
        if (xe > 30 && xe < size.x - 30 && Math.abs(lngEdge * 2 - Math.round(lngEdge * 2)) < 1e-6) {
          bottomHtml += `<span class="sheet-tick" style="left:${xe}px">${dm(lngEdge, 'E', 'W')}</span>`;
        }
      }
      for (let r = 0; r < GRID.rows; r++) {
        const latMid = GRID.north - (r + 0.5) * GRID.cell;
        const y = map.latLngToContainerPoint([latMid, map.getCenter().lng]).y;
        if (y > 8 && y < size.y - 8) {
          leftHtml += `<span class="sheet-number" style="top:${y}px">${r + 1}</span>`;
        }
        const latEdge = GRID.north - r * GRID.cell;
        const ye = map.latLngToContainerPoint([latEdge, map.getCenter().lng]).y;
        if (ye > 20 && ye < size.y - 20 && Math.abs(latEdge * 2 - Math.round(latEdge * 2)) < 1e-6) {
          rightHtml += `<span class="sheet-tick sheet-tick-v" style="top:${ye}px">${dm(latEdge, 'N', 'S')}</span>`;
        }
      }
      top.innerHTML = topHtml;
      left.innerHTML = leftHtml;
      right.innerHTML = rightHtml;
      bottom.innerHTML = bottomHtml;
    }
    map.on('move zoom resize', drawCollar);
    map.whenReady(drawCollar);
    // The container often has no final size yet when the map is created; fit once it does.
    let fitted = false;
    new ResizeObserver(() => {
      map.invalidateSize({ pan: false });
      if (!fitted && mapEl.clientHeight > 50) {
        fitted = true;
        map.fitBounds(opts.bounds || PUNJAB_BOUNDS, { padding: [8, 8] });
      }
      drawCollar();
    }).observe(mapEl);
    return { map, el: host };
  }

  // ------------------------------------------------------------------ symbols

  function warehouseIcon(code) {
    return L.divIcon({
      className: 'sym-warehouse',
      html: `<span class="sym-sq"></span><span class="sym-label">${code}</span>`,
      iconSize: [12, 12], iconAnchor: [6, 6]
    });
  }

  function vehicleIcon(label, band, heading, state) {
    return L.divIcon({
      className: `sym-vehicle band-${band || 'LOW'}${state ? ' is-' + state : ''}`,
      html: `<span class="sym-ring"></span><svg class="sym-arrow" viewBox="0 0 20 20" style="transform:rotate(${heading || 0}deg)"><path d="M10 1.5 17 17.5 10 13.6 3 17.5z"/></svg><span class="sym-label">${label}</span>`,
      iconSize: [22, 22], iconAnchor: [11, 11]
    });
  }

  function idleIcon(label) {
    return L.divIcon({
      className: 'sym-idle',
      html: `<svg viewBox="0 0 12 12"><circle cx="6" cy="6" r="4"/></svg><span class="sym-label">${label}</span>`,
      iconSize: [12, 12], iconAnchor: [6, 6]
    });
  }

  function stopIcon(seq, status, isNext, kind) {
    const cls = ['sym-stop', 'st-' + status.toLowerCase(), isNext ? 'is-next' : '', kind === 'TRANSFER' ? 'is-transfer' : ''].join(' ');
    return L.divIcon({ className: cls, html: `<span>${seq}</span>`, iconSize: [20, 20], iconAnchor: [10, 10] });
  }

  /** Smoothly moves a marker to a new position over `ms`. */
  function glide(marker, to, ms) {
    cancelAnimationFrame(marker._glide);
    // Start from the previous target, not wherever an interrupted animation stopped: background
    // tabs pause requestAnimationFrame, and markers must never be left behind.
    if (marker._target) marker.setLatLng(marker._target);
    marker._target = to;
    const from = marker.getLatLng();
    if (document.hidden || !from || (from.lat === to[0] && from.lng === to[1])) { marker.setLatLng(to); return; }
    const t0 = performance.now();
    const step = now => {
      const k = Math.min(1, (now - t0) / ms);
      marker.setLatLng([from.lat + (to[0] - from.lat) * k, from.lng + (to[1] - from.lng) * k]);
      if (k < 1) marker._glide = requestAnimationFrame(step);
    };
    marker._glide = requestAnimationFrame(step);
  }

  window.SheetMap = { create, decode, gridRef, warehouseIcon, vehicleIcon, idleIcon, stopIcon, glide, PUNJAB_BOUNDS };
})();
