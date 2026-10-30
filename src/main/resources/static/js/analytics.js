/* Analytics charts in the sheet's palette. Clicking a day opens that day's trips. */
(function () {
  'use strict';
  const data = JSON.parse(document.getElementById('chart-data').textContent);
  const css = getComputedStyle(document.documentElement);
  const c = name => css.getPropertyValue(name).trim();
  const INK = c('--ink'), INK3 = c('--ink-3'), RULE = c('--rule-faint'), BLUE = c('--survey-blue'),
    GREEN = c('--forest'), ROAD = c('--road'), ORANGE = c('--orange'), OCHRE = '#c9a227', BROWN = c('--contour');

  Chart.defaults.font.family = getComputedStyle(document.body).fontFamily;
  Chart.defaults.font.size = 12;
  Chart.defaults.color = INK3;
  Chart.defaults.borderColor = RULE;
  Chart.defaults.plugins.legend.labels.boxWidth = 12;
  Chart.defaults.plugins.tooltip.backgroundColor = '#fbfbf6';
  Chart.defaults.plugins.tooltip.titleColor = INK;
  Chart.defaults.plugins.tooltip.bodyColor = INK;
  Chart.defaults.plugins.tooltip.borderColor = INK;
  Chart.defaults.plugins.tooltip.borderWidth = 1;
  Chart.defaults.plugins.tooltip.cornerRadius = 0;
  Chart.defaults.maintainAspectRatio = false;

  const days = data.daily.map(d => d.day);
  const label = d => new Date(d + 'T00:00:00').toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
  const params = new URLSearchParams(location.search);

  new Chart(document.querySelector('[data-chart="daily"]'), {
    type: 'bar',
    data: {
      labels: days.map(label),
      datasets: [
        { label: 'On time', data: data.daily.map(d => d.on_time), backgroundColor: GREEN, stack: 's' },
        { label: 'Late or failed', data: data.daily.map(d => d.late_or_failed), backgroundColor: ROAD, stack: 's' }
      ]
    },
    options: {
      scales: { x: { stacked: true, grid: { display: false } }, y: { stacked: true, beginAtZero: true, title: { display: true, text: 'shipments' } } },
      onClick: (e, items) => {
        if (!items.length) return;
        const day = days[items[0].index];
        const q = new URLSearchParams({ from: day, to: day });
        if (params.get('warehouse')) q.set('warehouse', params.get('warehouse'));
        if (params.get('vehicle')) q.set('vehicle', params.get('vehicle'));
        if (params.get('driver')) q.set('driver', params.get('driver'));
        location.href = '/trips?' + q;
      },
      onHover: (e, items) => { e.native.target.style.cursor = items.length ? 'pointer' : 'default'; }
    }
  });

  new Chart(document.querySelector('[data-chart="ontime"]'), {
    data: {
      labels: days.map(label),
      datasets: [
        { type: 'line', label: 'On-time rate %', yAxisID: 'y', borderColor: INK, backgroundColor: INK, borderWidth: 2, pointRadius: 0, tension: .25,
          data: data.daily.map(d => (d.on_time + d.late_or_failed) ? Math.round(1000 * d.on_time / (d.on_time + d.late_or_failed)) / 10 : null) },
        { type: 'bar', label: 'Avg delay of late (min)', yAxisID: 'y2', backgroundColor: 'rgba(185,82,15,.35)', borderColor: ORANGE, borderWidth: 1,
          data: data.daily.map(d => Number(d.avg_late)) }
      ]
    },
    options: {
      scales: {
        x: { grid: { display: false } },
        y: { min: 50, max: 100, title: { display: true, text: 'on time %' } },
        y2: { position: 'right', beginAtZero: true, grid: { display: false }, title: { display: true, text: 'minutes' } }
      }
    }
  });

  const reasons = (data.failures || []).filter(r => r.reason);
  new Chart(document.querySelector('[data-chart="failures"]'), {
    type: 'bar',
    data: { labels: reasons.map(r => r.reason), datasets: [{ label: 'Failed deliveries', data: reasons.map(r => r.n), backgroundColor: BROWN }] },
    options: { indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true, ticks: { precision: 0 } }, y: { grid: { display: false } } } }
  });

  const types = [...new Set(data.incidents.map(i => i.type))];
  const palette = { SLA_RISK: ORANGE, BREAKDOWN: ROAD, FAILED_DELIVERY: BROWN, ROUTE_DEVIATION: BLUE, LOCATION_STALE: OCHRE, LONG_STOP: INK3, RESOURCE_UNAVAILABLE: INK };
  new Chart(document.querySelector('[data-chart="incidents"]'), {
    type: 'bar',
    data: {
      labels: days.map(label),
      datasets: types.map(t => ({
        label: t.replace(/_/g, ' ').toLowerCase().replace(/^./, s => s.toUpperCase()),
        backgroundColor: palette[t] || INK3, stack: 'i',
        data: days.map(d => (data.incidents.find(i => i.day === d && i.type === t) || {}).n || 0)
      }))
    },
    options: { scales: { x: { stacked: true, grid: { display: false } }, y: { stacked: true, beginAtZero: true, ticks: { precision: 0 } } } }
  });
})();
