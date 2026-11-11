'use strict';
/* Fog Carbon Lab - frontend for the iFogSim carbon-aware offloading simulator. */

const $ = (sel, el = document) => el.querySelector(sel);
const $$ = (sel, el = document) => [...el.querySelectorAll(sel)];
const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const state = {
  info: null, runs: null, config: null, focus: null, charts: {},
  replay: { hour: 0, playing: false, timer: null, policy: null, selected: null },
  sensors: null, sensorFilter: null, sensorSelected: null, experiments: null,
};

/* ------------------------------------------------------------------ theme & colours */
function isDark() {
  const t = document.documentElement.dataset.theme;
  if (t) return t === 'dark';
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}
function css(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

// categorical palette (light, dark) - colour follows the policy, never its rank
const POLICY_COLORS = {
  'rl-carbon-aware': ['#2a78d6', '#3987e5'],
  'latency-greedy': ['#eb6834', '#d95926'],
  'carbon-greedy': ['#1baf7a', '#199e70'],
  'random': ['#eda100', '#c98500'],
  'nearest-fog': ['#e87ba4', '#d55181'],
  'cloud-only': ['#008300', '#008300'],
  'rl-latency-only': ['#6250d6', '#9085e9'],
  'local-only': ['#e34948', '#e66767'],
};
const policyColor = n => (POLICY_COLORS[n] || ['#898781', '#898781'])[isDark() ? 1 : 0];
const TIERS = [
  { key: 'share_local_pct', name: 'IoT device', c: ['#6250d6', '#9085e9'] },
  { key: 'share_fog_pct', name: 'Fog nodes', c: ['#eb6834', '#d95926'] },
  { key: 'share_cloud_pct', name: 'Cloud', c: ['#2a78d6', '#3987e5'] },
];
const tierColor = i => TIERS[i].c[isDark() ? 1 : 0];
const SERIES = () => isDark()
  ? ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300']
  : ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300'];
const SOLAR = '#eda100';
const CRITICAL = '#d03b3b';

function applyChartDefaults() {
  Chart.defaults.font.family = 'system-ui, -apple-system, "Segoe UI", sans-serif';
  Chart.defaults.font.size = 12;
  Chart.defaults.color = css('--ink-2');
  Chart.defaults.borderColor = css('--grid');
  Chart.defaults.plugins.legend.labels.boxWidth = 10;
  Chart.defaults.plugins.legend.labels.boxHeight = 10;
  Chart.defaults.plugins.tooltip.backgroundColor = isDark() ? '#2c2c2a' : '#0b0b0b';
  Chart.defaults.elements.line.borderWidth = 2;
  Chart.defaults.elements.point.radius = 0;
  Chart.defaults.elements.point.hoverRadius = 5;
  Chart.defaults.elements.bar.borderRadius = 4;
}

function setTheme(t) {
  document.documentElement.dataset.theme = t;
  try { localStorage.setItem('fog-theme', t); } catch (e) { /* storage unavailable */ }
  applyChartDefaults();
  renderAll();
}

/* ------------------------------------------------------------------ formatting */
const fmt = (v, d = 1) => v == null || isNaN(v) ? '–' : Number(v).toLocaleString(undefined, { minimumFractionDigits: d, maximumFractionDigits: d });
const fmt0 = v => fmt(v, 0);
function hhmm(h) {
  const m = Math.round(h * 60);
  return `${String(Math.floor(m / 60) % 24).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}
function hhmmss(sec) {
  const s = Math.floor(sec);
  return `${String(Math.floor(s / 3600) % 24).padStart(2, '0')}:${String(Math.floor(s / 60) % 60).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

/* ------------------------------------------------------------------ API */
async function api(path, body) {
  const res = await fetch(path, body ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {});
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || res.statusText);
  return data;
}
async function pollJob(id, onProgress) {
  for (;;) {
    const j = await api(`/api/jobs/${id}`);
    onProgress(j);
    if (j.status === 'done') return j.result;
    if (j.status === 'error') throw new Error(j.error);
    await new Promise(r => setTimeout(r, 400));
  }
}

/* ------------------------------------------------------------------ charts */
function makeChart(id, config) {
  if (state.charts[id]) state.charts[id].destroy();
  const el = document.getElementById(id);
  if (!el) return null;
  state.charts[id] = new Chart(el, config);
  return state.charts[id];
}
function baseOptions(extra = {}) {
  return {
    responsive: true, maintainAspectRatio: false, animation: { duration: 250 },
    interaction: { mode: 'index', intersect: false },
    plugins: { legend: { position: 'bottom', align: 'start' } },
    scales: {
      x: { grid: { display: false }, border: { color: css('--axis') }, ticks: { color: css('--muted') } },
      y: { border: { display: false }, ticks: { color: css('--muted') }, grid: { color: css('--grid') } },
    },
    ...extra,
  };
}
// shaded bands (e.g. crash windows) drawn behind a chart
const bandsPlugin = {
  id: 'bands',
  beforeDatasetsDraw(chart, _args, opts) {
    if (!opts || !opts.bands) return;
    const { ctx, chartArea: a, scales: { x } } = chart;
    ctx.save();
    for (const b of opts.bands) {
      const x0 = Math.max(a.left, x.getPixelForValue(b.from)), x1 = Math.min(a.right, x.getPixelForValue(b.to));
      if (x1 <= x0) continue;
      ctx.fillStyle = b.color;
      ctx.fillRect(x0, a.top, x1 - x0, a.bottom - a.top);
    }
    ctx.restore();
  },
};

/* ================================================================== SIMULATE */
function renderPolicyList() {
  const trained = state.info && state.info.trained;
  const list = $('#policy-list');
  const prev = new Set($$('input', list).filter(i => i.checked).map(i => i.value));
  const defaults = new Set(['rl-carbon-aware', 'latency-greedy', 'carbon-greedy', 'nearest-fog', 'cloud-only']);
  const order = ['rl-carbon-aware', 'rl-latency-only', 'carbon-greedy', 'latency-greedy', 'nearest-fog', 'cloud-only', 'random', 'local-only'];
  const policies = [...state.info.policies].sort((a, b) => order.indexOf(a.name) - order.indexOf(b.name));
  list.innerHTML = policies.map(p => {
    const disabled = p.needsTraining && !trained;
    const checked = prev.size ? prev.has(p.name) : defaults.has(p.name);
    return `<label class="check${disabled ? ' disabled' : ''}" title="${esc(p.description)}">
      <input type="checkbox" value="${p.name}" ${checked && !disabled ? 'checked' : ''} ${disabled ? 'disabled' : ''}>
      <span class="swatch" style="background:${policyColor(p.name)}"></span>
      <span><b>${p.name}</b><small>${esc(p.description)}${disabled ? ' - train the agents first' : ''}</small></span></label>`;
  }).join('');
}
function setPolicies(names) {
  $$('#policy-list input').forEach(i => { i.checked = !i.disabled && names.includes(i.value); });
}

async function runSimulation() {
  const policies = $$('#policy-list input').filter(i => i.checked).map(i => i.value);
  const status = $('#run-status'), bar = $('#run-progress'), btn = $('#run-btn');
  status.classList.remove('error');
  if (!policies.length) { status.textContent = 'Select at least one policy.'; status.classList.add('error'); return; }
  const body = {
    policies,
    faultMode: $('input[name="fault"]:checked').value,
    hours: +$('#in-hours').value,
    load: +$('#in-load').value,
    crashesPerNodePerDay: +$('#in-crash').value,
    seed: +$('#in-seed').value || 1000,
  };
  btn.disabled = true;
  try {
    const { jobId } = await api('/api/run', body);
    const result = await pollJob(jobId, j => { status.textContent = j.message; bar.style.width = `${Math.round(j.progress * 100)}%`; });
    showRunResult(result);
    const total = state.runs.reduce((s, r) => s + r.summary.tasks_generated, 0);
    status.textContent = `Done: ${fmt0(total)} tasks simulated across ${state.runs.length} run(s).`;
    bar.style.width = '100%';
  } catch (e) {
    status.textContent = e.message; status.classList.add('error');
  } finally {
    btn.disabled = false;
  }
}

function showRunResult(result) {
  state.runs = result.runs;
  state.config = result.config;
  state.focus = state.runs.some(r => r.policy === 'rl-carbon-aware') ? 'rl-carbon-aware' : state.runs[0].policy;
  state.replay.policy = state.focus;
  state.replay.hour = Math.min(12, result.config.hours - 1);
  renderSimResults();
  renderCampus();
}

async function trainAgents() {
  const status = $('#train-status'), bar = $('#train-progress'), btn = $('#train-btn');
  status.classList.remove('error');
  btn.disabled = true; $('#run-btn').disabled = true;
  try {
    const { jobId } = await api('/api/train', { days: +$('#in-days').value });
    const result = await pollJob(jobId, j => { status.textContent = j.message; bar.style.width = `${Math.round(j.progress * 100)}%`; });
    const last = result.curve.filter(r => r.agent === 'rl-carbon-aware').pop();
    status.textContent = `Saved. Carbon-aware agent, final training day: ${fmt(last.deadline_met_pct)}% deadlines met, ${fmt(last.carbon_mg_per_task, 3)} mg CO2e/task.`;
    await loadInfo();
  } catch (e) {
    status.textContent = e.message; status.classList.add('error');
  } finally {
    btn.disabled = false; $('#run-btn').disabled = false;
  }
}

function focusRun() { return state.runs && (state.runs.find(r => r.policy === state.focus) || state.runs[0]); }

function renderSimResults() {
  const box = $('#sim-results');
  if (!state.runs) return;
  const run = focusRun();
  const s = run.summary;
  const cfg = state.config;
  const multi = state.runs.length > 1;
  const typeRows = run.byType.map(t => `<tr>
      <td>${t.type}</td><td class="num">${fmt0(t.deadline_ms)} ms</td><td class="num">${fmt0(t.tasks)}</td>
      <td class="num">${fmt(t.deadline_met_pct)}%</td><td class="num">${fmt0(t.avg_latency_ms)} ms</td>
      <td class="num">${fmt(t.carbon_mg_per_task, 3)}</td>
      <td class="num">${fmt0(t.share_local_pct)} / ${fmt0(t.share_fog_pct)} / ${fmt0(t.share_cloud_pct)}</td></tr>`).join('');
  const crashRows = run.crashes.length ? run.crashes.map(c => `<tr>
      <td>${c.fog}</td>
      <td class="num">${c.predictable ? hhmm(c.degrade_start_h) : '<span class="hint">none (sudden)</span>'}</td>
      <td class="num">${hhmm(c.fail_h)}</td><td class="num">${hhmm(c.recover_h)}</td>
      <td>${c.predicted ? '<span class="tag good">&#10003; predicted</span>' : '<span class="tag bad">&#10007; not predicted</span>'}</td>
      <td class="num">${c.predicted ? fmt0(c.lead_time_s) + ' s' : '–'}</td></tr>`).join('')
    : '<tr><td colspan="6" class="hint">No crashes in this run.</td></tr>';

  box.innerHTML = `
  <div class="card">
    <div class="card-head">
      <h2>Results</h2>
      <p>${cfg.hours} h simulated · workload ${cfg.load}x · ${cfg.crashesPerNodePerDay} crashes/node/day · ${String(cfg.faultMode).toLowerCase().replace('_', ' ')} fault handling · seed ${cfg.seed}</p>
      <div class="spacer" style="flex:1"></div>
      <label class="hint" for="focus-select">Details for</label>
      <select id="focus-select" style="width:auto">${state.runs.map(r => `<option ${r.policy === state.focus ? 'selected' : ''}>${r.policy}</option>`).join('')}</select>
    </div>
    <div class="kpis">
      ${kpi('Deadlines met', fmt(s.deadline_met_pct) + '%', `${fmt0(s.tasks_completed)} of ${fmt0(s.tasks_generated)} tasks`)}
      ${kpi('Mean latency', fmt0(s.avg_latency_ms) + ' ms', `p95 ${fmt0(s.p95_latency_ms)} ms`)}
      ${kpi('Carbon per task', fmt(s.carbon_mg_per_task, 3) + ' mg', `${fmt0(s.task_carbon_g)} g CO2e attributed`)}
      ${kpi('Energy per task', fmt(s.energy_J_per_task, 2) + ' J', `${fmt0(s.task_energy_kJ)} kJ attributed`)}
      ${kpi('Placement', `${fmt0(s.share_local_pct)}/${fmt0(s.share_fog_pct)}/${fmt0(s.share_cloud_pct)}`, 'device / fog / cloud %')}
      ${kpi('Crashes predicted', `${fmt0(s.crashes_predicted)} of ${fmt0(s.crashes)}`, `${fmt0(s.false_alarms)} false alarms · lead ${fmt0(s.mean_lead_time_s)} s`)}
      ${kpi('Tasks hit by crashes', fmt0(s.tasks_hit_by_crash), `${fmt0(s.tasks_dropped)} lost · ${fmt0(s.tasks_resubmitted)} resubmitted`)}
    </div>
  </div>
  ${multi ? `<div class="card">
    <div class="card-head"><h2>Policy comparison</h2><p>Hover a bar for exact values</p></div>
    <div class="grid-4">
      <div><div class="chart-title">Deadlines met (%)</div><div class="chart-sub">higher is better</div><div class="chart-box small"><canvas id="cmp-met"></canvas></div></div>
      <div><div class="chart-title">Mean latency (ms)</div><div class="chart-sub">lower is better</div><div class="chart-box small"><canvas id="cmp-lat"></canvas></div></div>
      <div><div class="chart-title">Carbon per task (mg CO2e)</div><div class="chart-sub">lower is better</div><div class="chart-box small"><canvas id="cmp-co2"></canvas></div></div>
      <div><div class="chart-title">Energy per task (J)</div><div class="chart-sub">lower is better</div><div class="chart-box small"><canvas id="cmp-en"></canvas></div></div>
    </div></div>` : ''}
  <div class="card">
    <div class="card-head"><h2>Over the simulated day</h2><p>Carbon intensity and solar change by the hour, and so does the best place to run work</p></div>
    <div class="grid-2">
      <div><div class="chart-title">Where ${esc(run.policy)} runs tasks</div><div class="chart-sub">share of tasks per hour</div><div class="chart-box"><canvas id="day-place"></canvas></div></div>
      <div><div class="chart-title">Carbon per task by hour (mg CO2e)</div><div class="chart-sub">all simulated policies</div><div class="chart-box"><canvas id="day-co2"></canvas></div></div>
      <div><div class="chart-title">Grid carbon intensity (g CO2e / kWh)</div><div class="chart-sub">simulated campus grid vs cloud region</div><div class="chart-box small"><canvas id="day-grid"></canvas></div></div>
      <div><div class="chart-title">Solar output and IoT workload (% of peak)</div><div class="chart-sub">drivers of the trade-off</div><div class="chart-box small"><canvas id="day-solar"></canvas></div></div>
    </div>
  </div>
  <div class="card">
    <div class="card-head"><h2>By task type</h2><p>${esc(run.policy)}</p></div>
    <div class="table-wrap"><table>
      <thead><tr><th>Task</th><th class="num">Deadline</th><th class="num">Tasks</th><th class="num">Met</th><th class="num">Mean latency</th><th class="num">mg CO2e/task</th><th class="num">Device / fog / cloud %</th></tr></thead>
      <tbody>${typeRows}</tbody></table></div>
  </div>
  <div class="card">
    <div class="card-head"><h2>Fog-node health and failure prediction</h2><p>Crash-risk score from heartbeat telemetry; red bands = node down; dashed = alarm threshold</p></div>
    <div class="chart-box tall"><canvas id="risk-chart"></canvas></div>
    <div class="table-wrap" style="margin-top:12px"><table>
      <thead><tr><th>Fog node</th><th class="num">Degradation starts</th><th class="num">Crash</th><th class="num">Back up</th><th>Prediction</th><th class="num">Warning lead time</th></tr></thead>
      <tbody>${crashRows}</tbody></table></div>
  </div>`;

  $('#focus-select').addEventListener('change', e => { state.focus = e.target.value; renderSimResults(); });
  drawSimCharts(run);
}
function kpi(label, value, sub) {
  return `<div class="kpi"><div class="k-label">${label}</div><div class="k-value">${value}</div><div class="k-sub">${sub}</div></div>`;
}

function drawSimCharts(run) {
  const runs = state.runs;
  const labels = run.hourly.map(h => hhmm(h.hour));
  if (runs.length > 1) {
    const bar = (id, key, cap, digits) => makeChart(id, {
      type: 'bar',
      data: {
        labels: runs.map(r => r.policy),
        datasets: [{
          data: runs.map(r => cap ? Math.min(r.summary[key], cap) : r.summary[key]),
          backgroundColor: runs.map(r => policyColor(r.policy)), barThickness: 14,
        }],
      },
      options: baseOptions({
        indexAxis: 'y',
        interaction: { mode: 'nearest', axis: 'y', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: { callbacks: { label: c => { const v = runs[c.dataIndex].summary[key]; return cap && v > cap ? `${fmt(v, digits)} (unstable queue)` : fmt(v, digits); } } },
        },
        scales: {
          x: { grid: { color: css('--grid') }, border: { display: false }, ticks: { color: css('--muted') }, max: cap || undefined },
          y: { grid: { display: false }, ticks: { color: css('--ink-2') } },
        },
      }),
    });
    bar('cmp-met', 'deadline_met_pct', 0, 1);
    bar('cmp-lat', 'avg_latency_ms', 2000, 0);
    bar('cmp-co2', 'carbon_mg_per_task', 0, 3);
    bar('cmp-en', 'energy_J_per_task', 0, 2);
  }

  makeChart('day-place', {
    type: 'line',
    data: {
      labels,
      datasets: TIERS.map((t, i) => ({
        label: t.name, data: run.hourly.map(h => h[t.key]), fill: i === 0 ? 'origin' : '-1',
        borderColor: tierColor(i), backgroundColor: tierColor(i) + 'cc', borderWidth: 1.5, tension: 0.25,
      })),
    },
    options: baseOptions({
      scales: {
        x: { grid: { display: false }, ticks: { color: css('--muted'), maxTicksLimit: 12 } },
        y: { stacked: true, min: 0, max: 100, ticks: { color: css('--muted'), callback: v => v + '%' }, grid: { color: css('--grid') }, border: { display: false } },
      },
      plugins: { legend: { position: 'bottom', align: 'start' }, tooltip: { callbacks: { label: c => `${c.dataset.label}: ${fmt(c.raw)}%` } } },
    }),
  });

  makeChart('day-co2', {
    type: 'line',
    data: {
      labels,
      datasets: runs.filter(r => r.policy !== 'local-only').map(r => ({
        label: r.policy, data: r.hourly.map(h => h.carbon_mg_per_task), borderColor: policyColor(r.policy),
        backgroundColor: policyColor(r.policy), tension: 0.25, borderWidth: r.policy === state.focus ? 3 : 2,
      })),
    },
    options: baseOptions({ plugins: { legend: { position: 'bottom', align: 'start' }, tooltip: { callbacks: { label: c => `${c.dataset.label}: ${fmt(c.raw, 3)} mg` } } } }),
  });

  const s = SERIES();
  makeChart('day-grid', {
    type: 'line',
    data: {
      labels,
      datasets: [
        { label: 'Campus grid', data: run.hourly.map(h => h.campus_grid_g_per_kWh), borderColor: s[1], backgroundColor: s[1], tension: 0.25 },
        { label: 'Cloud-region grid', data: run.hourly.map(h => h.cloud_grid_g_per_kWh), borderColor: s[2], backgroundColor: s[2], tension: 0.25 },
      ],
    },
    options: baseOptions({ plugins: { legend: { position: 'bottom', align: 'start' }, tooltip: { callbacks: { label: c => `${c.dataset.label}: ${fmt0(c.raw)} g/kWh` } } } }),
  });
  makeChart('day-solar', {
    type: 'line',
    data: {
      labels,
      datasets: [
        { label: 'Rooftop PV output', data: run.hourly.map(h => h.solar_irradiance * 100), borderColor: SOLAR, backgroundColor: SOLAR, tension: 0.25 },
        { label: 'Campus activity', data: run.hourly.map(h => h.campus_activity * 100), borderColor: s[0], backgroundColor: s[0], tension: 0.25 },
      ],
    },
    options: baseOptions({
      scales: { x: { grid: { display: false }, ticks: { color: css('--muted') } }, y: { min: 0, max: 100, ticks: { color: css('--muted'), callback: v => v + '%' }, grid: { color: css('--grid') }, border: { display: false } } },
      plugins: { legend: { position: 'bottom', align: 'start' }, tooltip: { callbacks: { label: c => `${c.dataset.label}: ${fmt0(c.raw)}%` } } },
    }),
  });

  // crash-risk chart with crash windows
  const fogColors = SERIES();
  const maxH = state.config.hours;
  const datasets = run.risk.map((f, i) => ({
    label: f.fog, data: f.t.map((t, k) => ({ x: t, y: f.risk[k] })),
    borderColor: fogColors[i], backgroundColor: fogColors[i], borderWidth: 1.5, tension: 0, spanGaps: false,
  }));
  datasets.push({ label: 'Alarm threshold', data: [{ x: 0, y: 0.8 }, { x: maxH, y: 0.8 }], borderColor: css('--muted'), borderDash: [6, 4], borderWidth: 1, pointRadius: 0 });
  makeChart('risk-chart', {
    type: 'line',
    data: { datasets },
    plugins: [bandsPlugin],
    options: baseOptions({
      parsing: false,
      interaction: { mode: 'nearest', axis: 'x', intersect: false },
      scales: {
        x: { type: 'linear', min: 0, max: maxH, grid: { display: false }, ticks: { color: css('--muted'), stepSize: Math.max(1, Math.round(maxH / 12)), callback: v => hhmm(v) } },
        y: { min: 0, max: 1.05, ticks: { color: css('--muted') }, grid: { color: css('--grid') }, border: { display: false }, title: { display: true, text: 'crash risk', color: css('--muted') } },
      },
      plugins: {
        legend: { position: 'bottom', align: 'start' },
        tooltip: { callbacks: { title: items => hhmm(items[0].parsed.x), label: c => `${c.dataset.label}: ${fmt(c.parsed.y, 2)}` } },
        bands: { bands: run.crashes.map(c => ({ from: c.fail_h, to: c.recover_h, color: 'rgba(208,59,59,0.16)' })) },
      },
    }),
  });
}

/* ================================================================== CAMPUS REPLAY */
const FOG_X = [160, 420, 680, 940], FOG_Y = 300, CLOUD = { x: 550, y: 70 }, AP_Y = 440, DEV_Y = 540;
const GLYPH = { video: 'CAM', ecg: 'ECG', traffic: 'TRF', air: 'AIR', energy: 'MTR' };

function rampColor(t) {
  const lo = css('--seq-lo'), hi = css('--seq-hi');
  const p = c => [1, 3, 5].map(i => parseInt(c.slice(i, i + 2), 16));
  const a = p(lo), b = p(hi), u = Math.max(0, Math.min(1, t));
  return `rgb(${a.map((v, i) => Math.round(v + (b[i] - v) * u)).join(',')})`;
}
const replayRun = () => state.runs && (state.runs.find(r => r.policy === state.replay.policy) || state.runs[0]);

function renderCampus() {
  if (!state.runs) return;
  $('#campus-empty').hidden = true;
  $('#campus-content').hidden = false;
  const sel = $('#replay-policy');
  sel.innerHTML = state.runs.map(r => `<option ${r.policy === state.replay.policy ? 'selected' : ''}>${r.policy}</option>`).join('');
  const slider = $('#hour-slider');
  slider.max = state.config.hours - 1;
  slider.value = state.replay.hour;
  drawCampusHour();
}

function drawCampusHour() {
  const run = replayRun();
  if (!run) return;
  const h = state.replay.hour;
  const tl = run.timeline;
  const info = state.info;
  const hourRow = run.hourly[h];
  const byName = Object.fromEntries(tl.nodes.map(n => [n.name, n]));
  $('#clock').textContent = `${hhmm(h % 24)} – ${hhmm((h % 24) + 1)}`;
  $('#hour-title').textContent = `Day ${Math.floor(h / 24) + 1}, ${hhmm(h % 24)} – ${hhmm((h % 24) + 1)}`;

  // flow width scale across the whole run
  let maxFlow = 1;
  tl.zones.forEach(z => z.flows.forEach(f => f.slice(1).forEach(v => { maxFlow = Math.max(maxFlow, v); })));
  const width = c => c > 0 ? 1.5 + 12 * Math.sqrt(c / maxFlow) : 0;
  const fogC = tierColor(1), cloudC = tierColor(2), localC = tierColor(0);
  const svg = [];

  // static links
  FOG_X.forEach(x => svg.push(`<line class="link" x1="${x}" y1="${FOG_Y - 46}" x2="${CLOUD.x}" y2="${CLOUD.y + 34}"/>`));
  svg.push(`<line class="link" x1="${FOG_X[0]}" y1="${FOG_Y}" x2="${FOG_X[3]}" y2="${FOG_Y}" stroke-dasharray="4 4"/>`);
  svg.push(`<text class="sub" x="${(FOG_X[1] + FOG_X[2]) / 2}" y="${FOG_Y + 18}" text-anchor="middle">campus LAN 1 Gbps</text>`);
  svg.push(`<text class="sub" x="${CLOUD.x + 150}" y="${CLOUD.y + 80}">WAN 40 Mbps, 90 ms</text>`);

  // flows per zone
  tl.zones.forEach((z, zi) => {
    const f = z.flows[h] || [0, 0, 0, 0, 0, 0];
    const x = FOG_X[zi];
    // devices -> home fog via the zone AP
    if (f[1 + zi] > 0) svg.push(`<line class="flow" x1="${x}" y1="${AP_Y}" x2="${x}" y2="${FOG_Y + 46}" stroke="${fogC}" stroke-width="${width(f[1 + zi])}"><title>${fmt0(f[1 + zi])} tasks/h to ${info.fogs[zi].name}</title></line>`);
    // zone -> other fog nodes (LAN)
    for (let o = 0; o < 4; o++) {
      if (o === zi || !f[1 + o]) continue;
      const x2 = FOG_X[o], lift = 60 + Math.abs(o - zi) * 28 + (o > zi ? 0 : 14);
      svg.push(`<path class="flow" d="M${x} ${FOG_Y - 30} Q${(x + x2) / 2} ${FOG_Y - 30 - lift} ${x2} ${FOG_Y - 30}" stroke="${fogC}" stroke-opacity="0.75" stroke-width="${width(f[1 + o])}"><title>${fmt0(f[1 + o])} tasks/h from ${z.zone} to ${info.fogs[o].name}</title></path>`);
    }
    // zone -> cloud (through the campus edge router)
    if (f[5] > 0) svg.push(`<line class="flow" x1="${x + 10}" y1="${FOG_Y - 46}" x2="${CLOUD.x + (zi - 1.5) * 40}" y2="${CLOUD.y + 34}" stroke="${cloudC}" stroke-width="${width(f[5])}"><title>${fmt0(f[5])} tasks/h from ${z.zone} to the cloud</title></line>`);
    // AP + devices
    svg.push(`<line class="link" x1="${x}" y1="${AP_Y}" x2="${x}" y2="${FOG_Y + 46}"/>`);
    svg.push(`<circle cx="${x}" cy="${AP_Y}" r="6" fill="${css('--axis')}"><title>Wi-Fi access point (50 Mbps)</title></circle>`);
    const devs = info.devices.filter(d => d.zone === zi);
    const du = z.deviceUtilPct[h] || 0;
    devs.forEach((d, i) => {
      const dx = x - 100 + i * 40;
      svg.push(`<line class="link" x1="${dx}" y1="${DEV_Y - 16}" x2="${x}" y2="${AP_Y}" stroke-width="1"/>`);
      svg.push(`<g><title>${d.name}\n${d.label}\n1 core x ${d.mipsPerCore} MIPS (iFogSim FogDevice, level 2)</title>
        <rect x="${dx - 17}" y="${DEV_Y - 16}" width="34" height="30" rx="6" fill="${rampColor(du / 100)}" stroke="${css('--axis')}"/>
        <text x="${dx}" y="${DEV_Y + 4}" text-anchor="middle" style="font-size:10.5px;font-weight:600;fill:${du > 55 ? '#fff' : css('--ink')}">${GLYPH[d.taskType]}</text></g>`);
    });
    svg.push(`<text class="sub" x="${x}" y="${DEV_Y + 40}" text-anchor="middle">${f[0] ? `<tspan style="fill:${localC};font-weight:600">${fmt0(f[0])} tasks/h run locally</tspan>` : 'no local execution'}</text>`);
    svg.push(`<text x="${x}" y="${DEV_Y + 62}" text-anchor="middle" style="font-weight:600">${z.zone}</text>`);
  });

  // fog nodes
  info.fogs.forEach((spec, zi) => {
    const n = byName[spec.name];
    const u = n.utilPct[h] || 0, down = n.downMin[h] || 0;
    const x = FOG_X[zi], selected = state.replay.selected === spec.name;
    const irr = hourRow ? hourRow.solar_irradiance : 0;
    svg.push(`<g class="node${selected ? ' selected' : ''}" data-node="${spec.name}">
      <title>${spec.name}\n${spec.label}\n${spec.cores} cores x ${spec.mipsPerCore} MIPS · ${spec.idleW}-${spec.maxW} W · PUE ${spec.pue}\nutilisation ${fmt(u)}% · ${fmt0(n.executed[h])} tasks this hour</title>
      <circle cx="${x}" cy="${FOG_Y}" r="46" fill="${rampColor(u / 70)}"/>
      <circle class="ring" cx="${x}" cy="${FOG_Y}" r="49"/>
      <text x="${x}" y="${FOG_Y - 4}" text-anchor="middle" style="font-weight:700;font-size:16px;fill:${u > 38 ? '#fff' : css('--ink')}">${fmt0(u)}%</text>
      <text x="${x}" y="${FOG_Y + 14}" text-anchor="middle" style="font-size:11px;fill:${u > 38 ? '#fff' : css('--ink-2')}">CPU</text>
      ${spec.solarKWp > 0 ? `<circle cx="${x + 40}" cy="${FOG_Y - 40}" r="11" fill="${SOLAR}" fill-opacity="${0.12 + 0.88 * irr}" stroke="${SOLAR}"><title>${spec.solarKWp} kWp rooftop PV at ${fmt0(irr * 100)}% of rated output</title></circle>` : ''}
      ${down > 0 ? `<path class="down-x" d="M${x - 30} ${FOG_Y - 30}L${x + 30} ${FOG_Y + 30}M${x + 30} ${FOG_Y - 30}L${x - 30} ${FOG_Y + 30}"/>
        <text x="${x}" y="${FOG_Y + 66}" text-anchor="middle" style="fill:${CRITICAL};font-weight:700">DOWN ${fmt0(down)} min</text>` : ''}
    </g>`);
  });
  // node names last, on a background pill, so flow arcs never cover them
  info.fogs.forEach((spec, zi) => {
    const w = spec.name.length * 7.4 + 16;
    svg.push(`<g pointer-events="none"><rect class="name-bg" x="${FOG_X[zi] - w / 2}" y="${FOG_Y - 84}" width="${w}" height="22" rx="11"/>
      <text x="${FOG_X[zi]}" y="${FOG_Y - 68}" text-anchor="middle" style="font-weight:600;font-size:12.5px">${spec.name}</text></g>`);
  });

  // cloud
  const cn = byName[info.cloud.name];
  const cu = cn.utilPct[h] || 0;
  const cloudSel = state.replay.selected === info.cloud.name;
  svg.push(`<g class="node${cloudSel ? ' selected' : ''}" data-node="${info.cloud.name}">
    <title>${info.cloud.label}\n${info.cloud.cores} cores x ${info.cloud.mipsPerCore} MIPS · PUE ${info.cloud.pue}\n${fmt0(cn.executed[h])} tasks this hour</title>
    <rect x="${CLOUD.x - 120}" y="${CLOUD.y - 36}" width="240" height="70" rx="35" fill="${rampColor(Math.min(1, cu / 15 + 0.05))}"/>
    <rect class="ring" x="${CLOUD.x - 123}" y="${CLOUD.y - 39}" width="246" height="76" rx="38"/>
    <text x="${CLOUD.x}" y="${CLOUD.y - 4}" text-anchor="middle" style="font-weight:700;fill:${cu > 6 ? '#fff' : css('--ink')}">Cloud region</text>
    <text x="${CLOUD.x}" y="${CLOUD.y + 16}" text-anchor="middle" style="font-size:12px;fill:${cu > 6 ? '#fff' : css('--ink-2')}">${fmt0(cn.executed[h])} tasks this hour</text>
  </g>`);

  const el = $('#campus-svg');
  el.innerHTML = svg.join('');
  $$('.node', el).forEach(g => g.addEventListener('click', () => { state.replay.selected = g.dataset.node; drawCampusHour(); }));

  // side panel
  const r = hourRow || {};
  $('#hour-stats').innerHTML = [
    ['Tasks created', fmt0(r.tasks)],
    ['Deadlines met', fmt(r.deadline_met_pct) + '%'],
    ['Mean latency', fmt0(r.avg_latency_ms) + ' ms'],
    ['Carbon per task', fmt(r.carbon_mg_per_task, 3) + ' mg'],
    ['Device / fog / cloud', `${fmt0(r.share_local_pct)} / ${fmt0(r.share_fog_pct)} / ${fmt0(r.share_cloud_pct)} %`],
    ['Campus grid', fmt0(r.campus_grid_g_per_kWh) + ' g/kWh'],
    ['Cloud-region grid', fmt0(r.cloud_grid_g_per_kWh) + ' g/kWh'],
    ['Solar output', fmt0((r.solar_irradiance || 0) * 100) + '% of rated'],
    ['Campus activity', fmt0((r.campus_activity || 0) * 100) + '%'],
  ].map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join('');

  const allSpecs = [...info.fogs, info.cloud];
  $('#node-rows').innerHTML = allSpecs.map(spec => {
    const n = byName[spec.name];
    const u = n.utilPct[h] || 0, down = n.downMin[h] || 0;
    return `<div class="node-row${state.replay.selected === spec.name ? ' selected' : ''}" data-node="${spec.name}">
      <span>${spec.name}${down > 0 ? ` <span class="down-badge">down ${fmt0(down)} min</span>` : ''}</span>
      <div class="bar" title="CPU utilisation ${fmt(u)}%"><div style="width:${Math.min(100, u)}%"></div></div>
      <span class="num" style="text-align:right">${fmt0(u)}%</span></div>`;
  }).join('');
  $$('#node-rows .node-row').forEach(row => row.addEventListener('click', () => { state.replay.selected = row.dataset.node; drawCampusHour(); }));

  const selSpec = allSpecs.find(sp => sp.name === state.replay.selected);
  if (selSpec) {
    const n = byName[selSpec.name];
    $('#node-detail').innerHTML = `<h3>${selSpec.name}</h3><p class="hint" style="margin:2px 0 8px">${esc(selSpec.label)}</p>
      <dl class="stat-list">
        <dt>iFogSim class</dt><dd class="mono" style="font-weight:400">FogDevice (level ${selSpec.tier === 'CLOUD' ? 0 : 1})</dd>
        <dt>Cores x MIPS</dt><dd>${selSpec.cores} x ${fmt0(selSpec.mipsPerCore)}</dd>
        <dt>Power (idle - max)</dt><dd>${selSpec.idleW} - ${selSpec.maxW} W</dd>
        <dt>PUE</dt><dd>${selSpec.pue}</dd>
        <dt>Rooftop PV</dt><dd>${selSpec.solarKWp ? selSpec.solarKWp + ' kWp' : 'none'}</dd>
        <dt>Tasks this hour</dt><dd>${fmt0(n.executed[h])}</dd>
        <dt>CO2e this hour</dt><dd>${fmt(n.carbonG[h], 1)} g</dd>
        <dt>Downtime this hour</dt><dd>${fmt0(n.downMin[h])} min</dd>
      </dl>`;
  }
}

function togglePlay() {
  state.replay.playing = !state.replay.playing;
  $('#play-btn').textContent = state.replay.playing ? 'Pause' : 'Play';
  clearInterval(state.replay.timer);
  if (state.replay.playing) {
    state.replay.timer = setInterval(() => {
      state.replay.hour = (state.replay.hour + 1) % state.config.hours;
      $('#hour-slider').value = state.replay.hour;
      drawCampusHour();
    }, 1000);
  }
}

/* ================================================================== EXPERIMENTS */
async function loadExperiments() {
  if (state.experiments) return;
  try {
    state.experiments = await api('/api/experiments');
  } catch (e) {
    state.experiments = { error: e.message };
  }
  renderExperiments();
}
function renderExperiments() {
  const box = $('#exp-content');
  const ex = state.experiments;
  if (!ex) { box.innerHTML = '<div class="empty">Loading saved results...</div>'; return; }
  if (ex.error || !ex.summary_policies || !ex.summary_policies.length) {
    box.innerHTML = `<div class="empty"><h2>No saved study yet</h2><p>Run <code>.\\run.ps1 all</code> once to produce the full 8-policy, multi-day study in <code>results/</code>; it appears here.</p></div>`;
    return;
  }
  const sp = ex.summary_policies;
  const rows = sp.map(r => `<tr class="${r.policy === 'rl-carbon-aware' ? 'focus' : ''}">
    <td><span class="swatch-inline" style="background:${policyColor(r.policy)}"></span>${r.policy}</td>
    <td class="num">${fmt(r.deadline_met_pct)}%</td><td class="num">${r.avg_latency_ms > 5000 ? 'unstable' : fmt0(r.avg_latency_ms) + ' ms'}</td>
    <td class="num">${r.p95_latency_ms > 5000 ? 'unstable' : fmt0(r.p95_latency_ms) + ' ms'}</td>
    <td class="num">${fmt(r.carbon_mg_per_task, 3)}</td><td class="num">${fmt(r.energy_J_per_task, 2)}</td>
    <td class="num">${fmt0(r.share_local_pct)} / ${fmt0(r.share_fog_pct)} / ${fmt0(r.share_cloud_pct)}</td></tr>`).join('');
  const runs = sp[0].runs;
  const fs = ex.faults_summary || [];
  const faultRows = fs.map(r => `<tr><td>${esc(r.policy_mode)}</td><td class="num">${fmt(r.hit_tasks_per_crash)}</td>
    <td class="num">${fmt(r.tasks_dropped)}</td><td class="num">${fmt(r.crashes_predicted)} / ${fmt(r.crashes)}</td>
    <td class="num">${fmt(r.false_alarms)}</td><td class="num">${fmt0(r.mean_lead_time_s)} s</td></tr>`).join('');
  box.innerHTML = `
    <div class="card">
      <div class="card-head"><h2>Saved study: policy comparison</h2><p>Mean of ${runs} unseen evaluation day(s), ≈850,000 tasks per day, proactive fault handling (from results/summary_policies.csv)</p></div>
      <div class="table-wrap"><table>
        <thead><tr><th>Policy</th><th class="num">Deadlines met</th><th class="num">Mean latency</th><th class="num">p95 latency</th><th class="num">mg CO2e / task</th><th class="num">J / task</th><th class="num">Device / fog / cloud %</th></tr></thead>
        <tbody>${rows}</tbody></table></div>
    </div>
    <div class="card"><div class="grid-2">
      <div><div class="chart-title">Carbon per task (mg CO2e)</div><div class="chart-sub">lower is better</div><div class="chart-box"><canvas id="ex-co2"></canvas></div></div>
      <div><div class="chart-title">Deadlines met (%)</div><div class="chart-sub">higher is better</div><div class="chart-box"><canvas id="ex-met"></canvas></div></div>
    </div></div>
    <div class="card">
      <div class="card-head"><h2>Saved study: fault tolerance</h2><p>2 crashes per fog node per day (from results/faults_summary.csv)</p></div>
      <div class="grid-2">
        <div><div class="chart-title">Tasks caught on a crashing node, per crash</div><div class="chart-sub">lower is better</div><div class="chart-box"><canvas id="ex-fault"></canvas></div></div>
        <div class="table-wrap"><table>
          <thead><tr><th>Policy / fault handling</th><th class="num">Hit per crash</th><th class="num">Lost per day</th><th class="num">Predicted</th><th class="num">False alarms/day</th><th class="num">Lead time</th></tr></thead>
          <tbody>${faultRows}</tbody></table></div>
      </div>
    </div>
    <div class="card">
      <div class="card-head"><h2>Saved study: Q-learning training</h2><p>Each training day has new weather, traffic and faults (from results/training_curve.csv)</p></div>
      <div class="grid-2">
        <div><div class="chart-title">Deadlines met during training (%)</div><div class="chart-box small"><canvas id="ex-train-met"></canvas></div></div>
        <div><div class="chart-title">Carbon per task during training (mg CO2e)</div><div class="chart-box small"><canvas id="ex-train-co2"></canvas></div></div>
      </div>
    </div>`;

  const hbar = (id, key, digits) => makeChart(id, {
    type: 'bar',
    data: { labels: sp.map(r => r.policy), datasets: [{ data: sp.map(r => r[key]), backgroundColor: sp.map(r => policyColor(r.policy)), barThickness: 16 }] },
    options: baseOptions({
      indexAxis: 'y', interaction: { mode: 'nearest', axis: 'y', intersect: false },
      plugins: { legend: { display: false }, tooltip: { callbacks: { label: c => fmt(c.raw, digits) } } },
      scales: { x: { grid: { color: css('--grid') }, border: { display: false }, ticks: { color: css('--muted') } }, y: { grid: { display: false }, ticks: { color: css('--ink-2') } } },
    }),
  });
  hbar('ex-co2', 'carbon_mg_per_task', 3);
  hbar('ex-met', 'deadline_met_pct', 1);

  if (fs.length) {
    const modes = ['NO_RECOVERY', 'REACTIVE', 'PROACTIVE'];
    const pols = [...new Set(fs.map(r => r.policy_mode.split(' / ')[0]))];
    const s = SERIES();
    makeChart('ex-fault', {
      type: 'bar',
      data: {
        labels: pols,
        datasets: modes.map((m, i) => ({
          label: { NO_RECOVERY: 'No recovery', REACTIVE: 'Reactive', PROACTIVE: 'Proactive (predict + migrate)' }[m],
          data: pols.map(p => (fs.find(r => r.policy_mode === `${p} / ${m}`) || {}).hit_tasks_per_crash),
          backgroundColor: s[i], borderColor: css('--surface'), borderWidth: 2,
        })),
      },
      options: baseOptions({ plugins: { legend: { position: 'bottom', align: 'start' }, tooltip: { callbacks: { label: c => `${c.dataset.label}: ${fmt(c.raw)}` } } } }),
    });
  }
  const tc = ex.training_curve || [];
  const agents = [...new Set(tc.map(r => r.agent))];
  const trainChart = (id, key, digits) => makeChart(id, {
    type: 'line',
    data: {
      labels: [...new Set(tc.map(r => r.day))],
      datasets: agents.map(a => ({ label: a, data: tc.filter(r => r.agent === a).map(r => r[key]), borderColor: policyColor(a), backgroundColor: policyColor(a), pointRadius: 3, tension: 0.2 })),
    },
    options: baseOptions({
      scales: { x: { grid: { display: false }, title: { display: true, text: 'training day', color: css('--muted') }, ticks: { color: css('--muted') } }, y: { grid: { color: css('--grid') }, border: { display: false }, ticks: { color: css('--muted') } } },
      plugins: { legend: { position: 'bottom', align: 'start' }, tooltip: { callbacks: { label: c => `${c.dataset.label}: ${fmt(c.raw, digits)}` } } },
    }),
  });
  trainChart('ex-train-met', 'deadline_met_pct', 1);
  trainChart('ex-train-co2', 'carbon_mg_per_task', 3);
}

/* ================================================================== SENSORS */
const KEY_FIELD = {
  video: ['people_count', 'people in frame'], ecg: ['heart_rate_bpm', 'heart rate (bpm)'],
  traffic: ['vehicle_count', 'vehicles counted'], air: ['pm2_5_ugm3', 'PM2.5 (µg/m³)'], energy: ['interval_kw', 'power draw (kW)'],
};
async function loadSensors() {
  const status = $('#sn-status');
  status.classList.remove('error');
  status.textContent = 'Generating...';
  try {
    state.sensors = await api(`/api/sensors?hour=${$('#sn-hour').value}&minutes=${$('#sn-min').value}`);
    state.sensorSelected = null;
    const shown = state.sensors.messages.length;
    status.textContent = `${fmt0(state.sensors.total)} messages from 24 sensors${shown < state.sensors.total ? ` (first ${fmt0(shown)} listed)` : ''}.`;
    renderSensors();
  } catch (e) {
    status.textContent = e.message; status.classList.add('error');
  }
}
function renderSensors() {
  const d = state.sensors;
  if (!d) return;
  const f = state.sensorFilter;
  $('#sn-chips').innerHTML = `<button class="chip" aria-pressed="${!f}" data-type="">all (${fmt0(d.total)})</button>` +
    Object.entries(d.counts).map(([t, c]) => `<button class="chip" aria-pressed="${f === t}" data-type="${t}">${t} (${fmt0(c)})</button>`).join('');
  $$('#sn-chips .chip').forEach(c => c.addEventListener('click', () => { state.sensorFilter = c.dataset.type || null; renderSensors(); }));
  const msgs = d.messages.filter(m => !f || m.type === f);
  $('#sn-feed-sub').textContent = `${fmt0(msgs.length)} messages${f ? ` of type ${f}` : ''}`;
  $('#sn-feed').innerHTML = msgs.slice(0, 600).map((m, i) => `<div class="feed-row${state.sensorSelected === m ? ' selected' : ''}" data-i="${i}">
      <span class="t">${hhmmss(m.t)}</span><span class="mono">${esc(m.topic)}</span></div>`).join('');
  $$('#sn-feed .feed-row').forEach(row => row.addEventListener('click', () => {
    state.sensorSelected = msgs[+row.dataset.i];
    $$('#sn-feed .feed-row').forEach(r => r.classList.toggle('selected', r === row));
    showSensorMessage();
  }));
  if (!state.sensorSelected && msgs.length) state.sensorSelected = msgs[0];
  showSensorMessage();
}
function showSensorMessage() {
  const m = state.sensorSelected;
  if (!m) { $('#sn-json').textContent = 'Select a message.'; return; }
  $('#sn-json').textContent = JSON.stringify({ topic: m.topic, qos: 1, payload: m.payload }, null, 2);
  const type = m.type, [field, label] = KEY_FIELD[type];
  const ofType = state.sensors.messages.filter(x => x.type === type);
  const devices = [...new Set(ofType.map(x => x.device))];
  const s = SERIES();
  $('#sn-chart-title').textContent = `${type}: ${label}`;
  $('#sn-chart-sub').textContent = `every ${type} sensor on campus over the window`;
  makeChart('sn-chart', {
    type: 'line',
    data: {
      datasets: devices.slice(0, 6).map((dev, i) => ({
        label: dev, data: ofType.filter(x => x.device === dev).map(x => ({ x: x.t, y: x.payload[field] })),
        borderColor: s[i], backgroundColor: s[i], pointRadius: 2, borderWidth: 1.5,
      })),
    },
    options: baseOptions({
      parsing: false, interaction: { mode: 'nearest', intersect: false },
      scales: {
        x: { type: 'linear', grid: { display: false }, ticks: { color: css('--muted'), callback: v => hhmmss(v), maxTicksLimit: 5 } },
        y: { grid: { color: css('--grid') }, border: { display: false }, ticks: { color: css('--muted') } },
      },
      plugins: { legend: { position: 'bottom', align: 'start' }, tooltip: { callbacks: { title: it => hhmmss(it[0].parsed.x), label: c => `${c.dataset.label}: ${c.parsed.y}` } } },
    }),
  });
}

/* ================================================================== shell */
async function loadInfo() {
  state.info = await api('/api/info');
  const pill = $('#train-pill');
  pill.classList.toggle('ok', state.info.trained);
  pill.classList.toggle('warn', !state.info.trained);
  $('#train-pill-text').textContent = state.info.trained
    ? `RL agents trained ${new Date(state.info.trainedAt).toLocaleString()}`
    : 'RL agents not trained yet';
  renderPolicyList();
}

function renderAll() {
  if (state.info) renderPolicyList();
  if (state.runs) { renderSimResults(); renderCampus(); }
  if (state.experiments) renderExperiments();
  if (state.sensors) renderSensors();
}

function switchTab(name) {
  $$('.tabs button').forEach(b => b.setAttribute('aria-selected', String(b.dataset.tab === name)));
  $$('.tab').forEach(t => t.classList.toggle('active', t.id === `tab-${name}`));
  try { localStorage.setItem('fog-tab', name); } catch (e) { /* ignore */ }
  if (name === 'experiments') loadExperiments();
  if (name === 'sensors' && !state.sensors) loadSensors();
  if (name === 'campus' && state.runs) drawCampusHour();
}

function bindControls() {
  const out = (inp, o, f) => { const u = () => { $(o).textContent = f($(inp).value); }; $(inp).addEventListener('input', u); u(); };
  out('#in-hours', '#out-hours', v => `${v} h`);
  out('#in-load', '#out-load', v => `${(+v).toFixed(1)}x`);
  out('#in-crash', '#out-crash', v => v);
  out('#in-days', '#out-days', v => v);
  out('#sn-hour', '#sn-hour-out', v => `${String(v).padStart(2, '0')}:00`);
  $('#run-btn').addEventListener('click', runSimulation);
  $('#train-btn').addEventListener('click', trainAgents);
  $('#preset-all').addEventListener('click', () => setPolicies(state.info.policies.map(p => p.name)));
  $('#preset-key').addEventListener('click', () => setPolicies(['rl-carbon-aware', 'latency-greedy', 'carbon-greedy', 'nearest-fog', 'cloud-only']));
  $('#preset-rl').addEventListener('click', () => setPolicies(['rl-carbon-aware', 'rl-latency-only']));
  $$('.tabs button').forEach(b => b.addEventListener('click', () => switchTab(b.dataset.tab)));
  $('#theme-btn').addEventListener('click', () => setTheme(isDark() ? 'light' : 'dark'));
  $('#hour-slider').addEventListener('input', e => { state.replay.hour = +e.target.value; drawCampusHour(); });
  $('#replay-policy').addEventListener('change', e => { state.replay.policy = e.target.value; drawCampusHour(); });
  $('#play-btn').addEventListener('click', togglePlay);
  $('#sn-btn').addEventListener('click', loadSensors);
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { if (!document.documentElement.dataset.theme) { applyChartDefaults(); renderAll(); } });
}

(async function init() {
  try { const t = localStorage.getItem('fog-theme'); if (t) document.documentElement.dataset.theme = t; } catch (e) { /* ignore */ }
  const qTheme = new URLSearchParams(location.search).get('theme');
  if (qTheme === 'light' || qTheme === 'dark') document.documentElement.dataset.theme = qTheme;
  applyChartDefaults();
  bindControls();
  try {
    await loadInfo();
  } catch (e) {
    $('#train-pill-text').textContent = 'Server not reachable - start it with .\\run.ps1 web';
    return;
  }
  // ?tab=campus&run=1&hours=24  -> open a tab and start a run straight away (handy for demos)
  const q = new URLSearchParams(location.search);
  let tab = 'simulate';
  try { tab = localStorage.getItem('fog-tab') || 'simulate'; } catch (e) { /* ignore */ }
  if (q.get('hours')) { $('#in-hours').value = q.get('hours'); $('#in-hours').dispatchEvent(new Event('input')); }
  if (q.get('run') !== '1') {
    // restore the most recent run after a page refresh
    try {
      const last = await api('/api/last-run');
      if (last && last.runs) {
        showRunResult(last);
        if (q.get('hour')) { state.replay.hour = Math.min(+q.get('hour'), last.config.hours - 1); renderCampus(); }
        $('#run-status').textContent = 'Showing the most recent run.';
      }
    } catch (e) { /* nothing to restore */ }
  }
  switchTab(q.get('tab') || tab);
  if (q.get('run') === '1') {
    await runSimulation();
    if (q.get('tab')) switchTab(q.get('tab'));
  }
})();
