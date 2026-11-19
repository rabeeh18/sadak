// Sign-in page: switch between "Track a parcel" and "Staff sign in" without a reload,
// and remember the last choice so staff don't have to pick their tab every morning.
(function () {
  'use strict';
  const nav = document.querySelector('[data-login-tabs]');
  if (!nav) return;
  const KEY = 'sadak.loginTab';
  const tabs = [...nav.querySelectorAll('[data-tab]')];

  function show(name, focus) {
    tabs.forEach(t => t.setAttribute('aria-selected', String(t.dataset.tab === name)));
    // Every pane, including email sign-in, which has no tab of its own in demo mode.
    document.querySelectorAll('.login-pane').forEach(p => { p.hidden = p.id !== 'pane-' + name; });
    if (focus) document.querySelector('#pane-' + name + ' input:not([type=hidden])')?.focus();
  }
  function remember(name) {
    try { localStorage.setItem(KEY, name); } catch (e) { /* storage blocked: fine */ }
  }

  tabs.forEach(t => t.addEventListener('click', e => {
    e.preventDefault();
    show(t.dataset.tab, true);
    remember(t.dataset.tab);
    history.replaceState(null, '', t.getAttribute('href'));
  }));

  // The server picks the tab when the URL or an error says which one; otherwise use the remembered one.
  if (!nav.hasAttribute('data-fixed')) {
    let saved = null;
    try { saved = localStorage.getItem(KEY); } catch (e) { /* ignore */ }
    if (tabs.some(t => t.dataset.tab === saved)) show(saved, false);
  } else {
    const selected = tabs.find(t => t.getAttribute('aria-selected') === 'true');
    if (selected) remember(selected.dataset.tab);
  }
})();
