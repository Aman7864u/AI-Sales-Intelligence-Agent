(() => {
  'use strict';

  const $ = (sel, root = document) => root.querySelector(sel);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  const state = { cfg: null, period: null, periodLabel: '', data: null, charts: {}, busy: false };
  let money = (v) => String(v);

  // ------------------------------------------------------------------ helpers
  async function api(path, options) {
    const res = await fetch(path, options);
    if (!res.ok) {
      let msg = res.statusText;
      try { msg = (await res.json()).error || msg; } catch (_) { /* not json */ }
      throw new Error(msg);
    }
    return res.json();
  }

  const fmtInt = (n) => new Intl.NumberFormat('en-US').format(Math.round(n));
  const fmtPct = (p) => `${p > 0 ? '+' : p < 0 ? '\u2212' : ''}${Math.abs(p).toFixed(1)}%`;
  const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

  // ------------------------------------------------------------------ theme
  function initTheme() {
    const saved = localStorage.getItem('theme');
    const dark = saved ? saved === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    $('#themeBtn').addEventListener('click', () => {
      const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
      document.documentElement.dataset.theme = next;
      localStorage.setItem('theme', next);
      if (state.data) renderCharts(state.data);
    });
  }

  // ------------------------------------------------------------------ dashboard
  async function loadDashboard(key) {
    const [year, month] = key.split('-').map(Number);
    $('#dashError').hidden = true;
    try {
      const data = await api(`/api/dashboard?year=${year}&month=${month}`);
      state.data = data;
      state.period = key;
      state.periodLabel = data.current.label;
      renderVerdict(data);
      renderFacts(data);
      renderMovers(data.allProducts);
      renderCategories(data);
      renderTop(data.topProducts);
      renderCharts(data);
      renderChips();
    } catch (e) {
      $('#dashError').textContent = `Could not load the dashboard: ${e.message}. Check that MongoDB is running.`;
      $('#dashError').hidden = false;
    }
  }

  function renderVerdict(d) {
    const cur = d.current;
    const prevName = d.previous.label.split(' ')[0];
    if (cur.orders === 0) {
      $('#headline').textContent = `No sales recorded for ${cur.label}`;
      $('#subline').textContent = 'Pick another month from the list above.';
      return;
    }
    const dir = d.growthPct >= 0 ? 'up' : 'down';
    const word = d.growthPct >= 0 ? 'up' : 'down';
    $('#headline').innerHTML =
      `${esc(cur.label)} revenue was ${esc(money(cur.revenue))}, <span class="${dir}">${word} ${Math.abs(d.growthPct).toFixed(1)}%</span> on ${esc(prevName)}.`;

    const losers = d.allProducts.filter((p) => p.revenueChange < 0);
    const worst = d.allProducts[0];
    const best = d.allProducts[d.allProducts.length - 1];
    const parts = [];
    parts.push(losers.length === 0 ? 'No product lost ground.' : `${losers.length} of ${d.allProducts.length} products lost ground.`);
    if (worst && worst.changePct < 0) parts.push(`${worst.productName} fell the most (${fmtPct(worst.changePct)}).`);
    if (best && best.changePct > 0) parts.push(`${best.productName} grew the most (${fmtPct(best.changePct)}).`);
    $('#subline').textContent = parts.join(' ');
  }

  function renderFacts(d) {
    const cur = d.current, prev = d.previous;
    const diff = cur.revenue - prev.revenue;
    const items = [
      ['Revenue', money(cur.revenue), `${money(prev.revenue)} in ${prev.label.split(' ')[0]}`],
      ['Change in revenue', `${diff >= 0 ? '+' : '\u2212'}${money(Math.abs(diff))}`, fmtPct(d.growthPct)],
      ['Units sold', fmtInt(cur.units), `${fmtInt(prev.units)} before`],
      ['Average order', money(cur.avgOrderValue), `${fmtInt(cur.orders)} orders`],
    ];
    $('#facts').innerHTML = items.map(([k, v, s]) =>
      `<div class="fact"><dt>${esc(k)}</dt><dd>${esc(v)}</dd><small>${esc(s)}</small></div>`).join('');
  }

  function renderMovers(all) {
    const max = Math.max(1, ...all.map((p) => Math.abs(p.changePct)));
    $('#movers').innerHTML = all.map((p) => {
      const neg = p.changePct < 0;
      const width = (Math.abs(p.changePct) / max) * 50;
      return `<button type="button" class="mover" data-product="${esc(p.productName)}" title="Ask why ${esc(p.productName)} moved">
        <span class="name">${esc(p.productName)}<small>${esc(p.category)}, ${esc(money(p.revenue))}</small></span>
        <span class="axis"><span class="bar ${neg ? 'neg' : 'pos'}" data-w="${width.toFixed(1)}"></span></span>
        <span class="pct ${neg ? 'neg' : 'pos'}">${fmtPct(p.changePct)}</span>
      </button>`;
    }).join('');
    requestAnimationFrame(() => requestAnimationFrame(() => {
      document.querySelectorAll('#movers .bar').forEach((b) => { b.style.width = `${b.dataset.w}%`; });
    }));
  }

  function renderCategories(d) {
    const top = d.categories[0];
    $('#catNote').textContent = top ? `${top.category} is ${top.sharePct.toFixed(0)}% of revenue.` : '';
    $('#catList').innerHTML = d.categories.map((c) =>
      `<li><span>${esc(c.category)}</span><span>${esc(money(c.revenue))} <span class="sub">${fmtPct(c.changePct)}</span></span></li>`).join('');
  }

  function renderTop(list) {
    $('#topList').innerHTML = list.map((p) =>
      `<li><span class="nm">${esc(p.productName)}</span><span class="rv">${esc(money(p.revenue))}</span>
       <span class="meta">${fmtInt(p.units)} units, ${fmtPct(p.changePct)} on the previous month</span></li>`).join('');
  }

  function renderCharts(d) {
    if (!window.Chart) return;
    const ink = css('--ink'), muted = css('--muted'), rule = css('--rule'), accent = css('--accent'), paper = css('--bg');
    Chart.defaults.font.family = css('--body');
    Chart.defaults.color = muted;

    // trend
    const labels = d.trend.map((m) => m.label);
    const selected = d.trend.length - 1;
    state.charts.trend?.destroy();
    state.charts.trend = new Chart($('#trendChart'), {
      type: 'line',
      data: {
        labels,
        datasets: [{
          data: d.trend.map((m) => m.revenue),
          borderColor: accent, borderWidth: 2.5, tension: 0.3, fill: true,
          backgroundColor: (ctx) => {
            const { ctx: c, chartArea } = ctx.chart;
            if (!chartArea) return 'transparent';
            const g = c.createLinearGradient(0, chartArea.top, 0, chartArea.bottom);
            g.addColorStop(0, accent + '40'); g.addColorStop(1, accent + '00');
            return g;
          },
          pointRadius: (c) => (c.dataIndex === selected ? 6 : 0),
          pointBackgroundColor: ink, pointBorderColor: paper, pointBorderWidth: 2, pointHoverRadius: 5,
        }],
      },
      options: {
        responsive: true, maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: { legend: { display: false }, tooltip: { callbacks: { label: (c) => money(c.parsed.y) } } },
        scales: {
          x: { grid: { display: false }, border: { color: rule } },
          y: { grid: { color: rule }, border: { display: false }, ticks: { callback: (v) => money(v) } },
        },
      },
    });

    // category doughnut
    const palette = [accent, '#11795a', '#cc3a1d', '#d99a1f', '#7a6bd6', '#4f8fd1'];
    state.charts.cat?.destroy();
    state.charts.cat = new Chart($('#catChart'), {
      type: 'doughnut',
      data: {
        labels: d.categories.map((c) => c.category),
        datasets: [{ data: d.categories.map((c) => c.revenue), backgroundColor: palette, borderColor: paper, borderWidth: 3 }],
      },
      options: {
        responsive: true, maintainAspectRatio: false, cutout: '64%',
        plugins: {
          legend: { position: 'right', labels: { boxWidth: 10, boxHeight: 10, usePointStyle: true } },
          tooltip: { callbacks: { label: (c) => `${c.label}: ${money(c.parsed)}` } },
        },
      },
    });
  }

  // ------------------------------------------------------------------ agent
  const STEPS = ['Understand', 'Query sales data', 'Read reports', 'Recommend'];

  function renderChips() {
    const m = state.periodLabel;
    const questions = [
      `Which products performed poorly in ${m}, and what actions should we consider?`,
      `How did ${m} revenue compare with the previous month?`,
      `Which categories are growing and which are shrinking in ${m}?`,
      `Which declining products should we consider for clearance under our pricing policy?`,
    ];
    const chips = $('#chips');
    if (!chips) return;
    chips.innerHTML = questions.map((q) => `<button type="button" class="chip">${esc(q)}</button>`).join('');
  }

  function md(text) {
    const inline = (s) => s
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/`(.+?)`/g, '<code>$1</code>')
      .replace(/(^|[^*])\*(?!\s)(.+?)\*/g, '$1<em>$2</em>');
    let html = '', list = null;
    const close = () => { if (list) { html += `</${list}>`; list = null; } };
    for (const raw of esc(text).split('\n')) {
      const line = raw.trim();
      let m;
      if (!line) { close(); continue; }
      if ((m = line.match(/^#{1,4}\s+(.*)$/))) { close(); html += `<h4>${inline(m[1])}</h4>`; }
      else if ((m = line.match(/^\*\*([^*]+?)\*\*:?$/))) { close(); html += `<h4>${m[1]}</h4>`; }
      else if ((m = line.match(/^[-*\u2022]\s+(.*)$/))) { if (list !== 'ul') { close(); html += '<ul>'; list = 'ul'; } html += `<li>${inline(m[1])}</li>`; }
      else if ((m = line.match(/^\d+[.)]\s+(.*)$/))) { if (list !== 'ol') { close(); html += '<ol>'; list = 'ol'; } html += `<li>${inline(m[1])}</li>`; }
      else { close(); html += `<p>${inline(line)}</p>`; }
    }
    close();
    return html;
  }

  const stepsHtml = (active, counts = {}) =>
    `<ol class="steps">${STEPS.map((s, i) =>
      `<li class="step ${i <= active ? 'on' : ''} ${i === active && !counts.done ? 'active' : ''}">${esc(s)}${counts[i] ? `<br>${esc(counts[i])}` : ''}</li>`).join('')}</ol>`;

  function addMessage(kind, html) {
    $('#empty')?.remove();
    const el = document.createElement('div');
    el.className = `msg ${kind}`;
    el.innerHTML = html;
    $('#thread').appendChild(el);
    $('#thread').scrollTop = $('#thread').scrollHeight;
    return el;
  }

  async function ask(question) {
    question = question.trim();
    if (!question || state.busy) return;
    state.busy = true;
    $('#send').disabled = true;
    if (window.matchMedia('(max-width: 1100px)').matches) document.body.dataset.view = 'agent';

    addMessage('user', esc(question));
    const bot = addMessage('bot', `${stepsHtml(0)}<p class="wait">Working on it. A local model can take a minute. <span id="clock">0s</span></p>`);

    let step = 0;
    const t0 = Date.now();
    const timer = setInterval(() => {
      step = Math.min(step + 1, 3);
      const clock = Math.round((Date.now() - t0) / 1000);
      bot.innerHTML = `${stepsHtml(step)}<p class="wait">Working on it. A local model can take a minute. ${clock}s</p>`;
      $('#thread').scrollTop = $('#thread').scrollHeight;
    }, 2500);

    try {
      const res = await api('/api/agent/ask', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ question }),
      });
      clearInterval(timer);
      const calls = res.toolCalls || [], sources = res.sources || [];
      const how = `<details class="how"><summary>How this answer was built (${(res.elapsedMs / 1000).toFixed(1)}s, ${esc(res.model)})</summary>
        <h5>Sales data queries</h5>
        ${calls.length ? calls.map((c) => `<div class="call"><b>${esc(c.tool)}</b> (${esc(c.arguments)})<span>${esc(c.result)}</span></div>`).join('')
          : '<p class="none">The model did not call any sales tool. Treat the figures with caution.</p>'}
        <h5>Reports and notes used as context</h5>
        ${sources.length ? sources.map((s) => `<div class="src"><b>${esc(s.title)}</b> (${esc(s.docType)})<span>${esc(s.snippet)}</span></div>`).join('')
          : '<p class="none">No document matched closely enough.</p>'}
      </details>`;
      bot.innerHTML = stepsHtml(3, { done: true, 1: `${calls.length} queries`, 2: `${sources.length} documents` }) +
        `<div class="answer">${md(res.answer)}</div>${how}`;
    } catch (e) {
      clearInterval(timer);
      bot.innerHTML = `<p class="failed">${esc(e.message)}</p><p class="wait">Check that Ollama is running and the model has been pulled, then ask again.</p>`;
    } finally {
      state.busy = false;
      $('#send').disabled = false;
      $('#thread').scrollTop = $('#thread').scrollHeight;
    }
  }

  // ------------------------------------------------------------------ boot
  function wire() {
    $('#movers').addEventListener('click', (ev) => {
      const row = ev.target.closest('.mover');
      if (row) ask(`Why did ${row.dataset.product} change in ${state.periodLabel}, and what should we do about it?`);
    });
    $('#chips').addEventListener('click', (ev) => {
      const chip = ev.target.closest('.chip');
      if (chip) ask(chip.textContent);
    });
    $('#composer').addEventListener('submit', (ev) => {
      ev.preventDefault();
      const q = $('#q');
      const text = q.value;
      q.value = '';
      ask(text);
    });
    $('#q').addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter' && !ev.shiftKey) { ev.preventDefault(); $('#composer').requestSubmit(); }
    });
    document.querySelectorAll('.switch button').forEach((b) => b.addEventListener('click', () => {
      document.body.dataset.view = b.dataset.go;
      document.querySelectorAll('.switch button').forEach((x) => x.classList.toggle('on', x === b));
    }));
  }

  async function init() {
    initTheme();
    wire();
    try {
      state.cfg = await api('/api/config');
      money = new Intl.NumberFormat('en-US', { style: 'currency', currency: state.cfg.currency, maximumFractionDigits: 0 }).format;
      $('#modelPill').textContent = state.cfg.chatModel;

      const list = await api('/api/months');
      const sel = $('#period');
      sel.innerHTML = list.months.map((m) => `<option value="${esc(m.key)}">${esc(m.label)}</option>`).join('');
      if (!list.months.length) {
        $('#headline').textContent = 'No sales data yet';
        $('#subline').textContent = 'Start the app with app.seed.enabled=true or import sales into the "sales" collection.';
        return;
      }
      sel.value = list.defaultKey;
      sel.addEventListener('change', () => loadDashboard(sel.value));
      await loadDashboard(sel.value);
    } catch (e) {
      $('#headline').textContent = 'The dashboard is not available';
      $('#dashError').textContent = e.message;
      $('#dashError').hidden = false;
    }

  }

  init();
})();
