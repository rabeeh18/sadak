/* Driver app: optional phone GPS sharing, and a gentle refresh so ETAs and new stops appear. */
(function () {
  'use strict';
  const csrf = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';

  document.addEventListener('submit', e => {
    const msg = e.target.dataset.confirm;
    if (msg && !confirm(msg)) e.preventDefault();
  });

  const share = document.querySelector('[data-share-location]');
  let watch = null;
  if (share && 'geolocation' in navigator) {
    share.addEventListener('change', () => {
      if (share.checked) {
        watch = navigator.geolocation.watchPosition(pos => {
          fetch('/api/driver/location', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', [csrfHeader]: csrf },
            body: JSON.stringify({ lat: pos.coords.latitude, lng: pos.coords.longitude,
              speedKph: pos.coords.speed == null ? null : pos.coords.speed * 3.6 })
          }).catch(() => {});
        }, () => { share.checked = false; }, { enableHighAccuracy: true, maximumAge: 10000 });
      } else if (watch !== null) {
        navigator.geolocation.clearWatch(watch);
      }
    });
  } else if (share) {
    share.disabled = true;
  }

  // Refresh every 30 s unless the driver is typing or has a section open.
  setInterval(() => {
    const busy = document.activeElement && ['INPUT', 'SELECT', 'TEXTAREA'].includes(document.activeElement.tagName);
    const open = document.querySelector('details[open]');
    if (!busy && !open && !(share && share.checked)) location.reload();
  }, 30000);
})();
