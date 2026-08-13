const app = document.querySelector('#app');
const encoder = new TextEncoder();
let state = null;

const gateLabels = {
  reconstruction_match: '精确重建',
  raw_v3_cells_compatible: 'Raw V3 兼容',
  value_dictionary_snapshot_match: '值字典快照',
  value_dictionary_delta_match: '值字典 Delta',
  invalid_value_data_rejected: '非法引用拒绝',
  null_and_raw_integer_match: 'Null / 原始整数',
  one_clone_per_entity: '单实体单次 Clone',
  deletion_match: '删除语义',
  scope_leak_count: 'Scope 泄漏',
  out_of_order_rejected: '乱序拒绝',
  authoritative_recovery_match: '权威恢复',
  stale_scope_action_count: '旧 Scope 操作',
  task_output_survives_host_resync: '任务流隔离',
  empty_delta_render_invalidations: '空 Delta 重绘',
  server_encode_cpu_ratio: '编码 CPU',
  server_allocated_bytes_ratio: '编码分配',
  client_apply_cpu_ratio: 'Client Apply',
  all_hosts_reconnect_ratio: '重连成本'
};

const phaseOrder = [
  ['spec', 'Spec', '指标、门禁、可变范围'],
  ['baseline', 'Baseline', '稳定性重复测量'],
  ['experiment', 'Experiment', '隔离 worktree 假设实现'],
  ['measure', 'Measure', '字节、CPU、分配、apply'],
  ['integrate', 'Integrate', '协议与消费者接线'],
  ['verify', 'Verify', '测试、提交与部署证据']
];

function escape(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

function number(value, digits = 2) {
  return Number.isFinite(Number(value)) ? Number(value).toFixed(digits) : '-';
}

function bytes(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return '-';
  if (numeric >= 1024 * 1024) return `${(numeric / 1024 / 1024).toFixed(2)} MB`;
  if (numeric >= 1024) return `${(numeric / 1024).toFixed(1)} KB`;
  return `${Math.round(numeric)} B`;
}

function gatePass(check, value) {
  if (value === undefined || value === null) return null;
  const match = String(check || '').trim().match(/^(>=|<=|>|<|==|!=)\s*(-?\d+(?:\.\d+)?)$/);
  if (!match) return null;
  const actual = Number(value);
  const expected = Number(match[2]);
  if (!Number.isFinite(actual)) return false;
  if (match[1] === '>=') return actual >= expected;
  if (match[1] === '<=') return actual <= expected;
  if (match[1] === '>') return actual > expected;
  if (match[1] === '<') return actual < expected;
  if (match[1] === '==') return actual === expected;
  return actual !== expected;
}

function phaseState(index) {
  const experiments = state?.run?.experiments || [];
  const measured = Boolean(state?.runner?.metrics || state?.best?.iteration > 0);
  if (index <= 1) return 'done';
  if (index === 2) return experiments.length ? 'done' : 'active';
  if (index === 3) {
    if (state?.runner?.status === 'running') return 'active';
    return measured ? 'done' : experiments.length ? 'active' : 'locked';
  }
  if (index === 4) return state?.isolation?.production_api_changed ? 'done' : measured ? 'active' : 'locked';
  return state?.isolation?.deployed ? 'done' : 'locked';
}

function metricCard(label, baseline, current, unit = '') {
  const hasCurrent = current !== undefined && current !== null;
  const base = Number(baseline);
  const value = Number(current);
  const delta = hasCurrent && Number.isFinite(base) && base !== 0
    ? ((value - base) / base) * 100
    : null;
  const formatter = unit === 'B' ? bytes : candidate => `${number(candidate)}${unit}`;
  return `
    <article class="metric-card ${hasCurrent ? 'has-current' : ''}">
      <span>${escape(label)}</span>
      <strong>${formatter(hasCurrent ? value : base)}</strong>
      <div>
        <small>Baseline ${formatter(base)}</small>
        ${delta === null ? '<em>等待 Run</em>' : `<em class="${delta <= 0 ? 'good' : 'bad'}">${delta > 0 ? '+' : ''}${delta.toFixed(1)}%</em>`}
      </div>
    </article>`;
}

function renderStructure() {
  const experiments = state?.run?.experiments || [];
  const kept = [...experiments].reverse().find(experiment => experiment.outcome === 'kept');
  const baseline = state?.baseline?.diagnostics || {};
  const best = state?.best?.metrics || {};
  return `
    <section class="panel structure-panel">
      <header class="section-heading">
        <div><span class="eyebrow">CORE DATA EVOLUTION</span><h2>本轮数据结构变化</h2></div>
        <span class="intent">${escape(state.run.primary?.name || 'primary metric')} · ${escape(state.run.primary?.direction || '')}</span>
      </header>
      <div class="structure-compare">
        <article class="structure-card legacy">
          <div class="structure-title"><span>BASE</span><b>Dictionary + Sparse Columns</b></div>
          <pre><code>fields[] + entities[]
columns[field][entity]
patch: [field, entity_indexes, values]</code></pre>
          <ul>
            <li>结构放大率 ${number(baseline.steady_byte_amplification, 3)}x</li>
            <li>Delta ${bytes(baseline.serialized_delta_bytes)}</li>
            <li>Reconnect ${number(baseline.all_hosts_reconnect_kb)} KB</li>
          </ul>
        </article>
        <div class="transform-arrow"><i></i><span>normalize</span><i></i></div>
        <article class="structure-card target">
          <div class="structure-title"><span>BEST</span><b>${escape(kept?.hypothesis || '等待实验')}</b></div>
          <pre><code>dictionaries.values:
  [field_index, repeated_values[]]
cell: integer value_ref | raw null
delta.values: append-only extensions</code></pre>
          <ul>
            <li>结构放大率 ${number(best.steady_byte_amplification, 3)}x</li>
            <li>Delta ${bytes(best.serialized_delta_bytes)}</li>
            <li>Reconnect ${number(best.all_hosts_reconnect_kb)} KB</li>
          </ul>
        </article>
      </div>
      <div class="invariant-row">
        <span><b>Entity index</b> connection-stable</span>
        <span><b>Revision</b> strict chain</span>
        <span><b>Remove</b> tombstone</span>
        <span><b>Reconnect</b> compact snapshot</span>
      </div>
    </section>`;
}

function renderFlow() {
  return `
    <section class="panel">
      <header class="section-heading">
        <div><span class="eyebrow">OPTIMIZATION FLOW</span><h2>Run 正在做什么</h2></div>
        <span class="intent">每一步都必须留下可复查证据</span>
      </header>
      <div class="flow">
        ${phaseOrder.map((phase, index) => {
          const status = phaseState(index);
          return `<article class="flow-step ${status}">
            <span class="step-index">${index + 1}</span>
            <b>${phase[1]}</b>
            <small>${phase[2]}</small>
            <i>${status === 'done' ? '完成' : status === 'active' ? '进行中' : '未开始'}</i>
          </article>`;
        }).join('')}
      </div>
    </section>`;
}

function renderGates(metrics) {
  const baselineGates = state?.baseline?.gates || {};
  const source = state?.runner?.metrics || state?.best?.gates || state?.experiment?.result?.gates || baselineGates;
  const definitions = state?.run?.gates || [];
  return `
    <section class="panel">
      <header class="section-heading">
        <div><span class="eyebrow">CORRECTNESS & COST GATES</span><h2>不能用正确性换指标</h2></div>
        <span class="intent">${metrics ? '当前测量' : 'Baseline 门禁'}</span>
      </header>
      <div class="gate-grid">
        ${definitions.map(gate => {
          const key = gate.name;
          const label = gateLabels[key] || key.replaceAll('_', ' ');
          const pass = gatePass(gate.check, source[key]);
          return `<div class="gate ${pass === null ? 'waiting' : pass ? 'pass' : 'fail'}">
            <span></span><b>${escape(label)}</b><code>${source[key] ?? '-'}</code>
          </div>`;
        }).join('')}
      </div>
    </section>`;
}

function renderEvidence() {
  const activeFiles = state?.experiment?.files || [];
  const integrationFiles = state?.integration?.files || [];
  const kept = [...(state?.run?.experiments || [])].reverse()
    .find(experiment => experiment.outcome === 'kept');
  const files = activeFiles.length
    ? activeFiles
    : integrationFiles.length
      ? integrationFiles.map(path => ({ status: 'integrate', path }))
      : (kept?.changes || []).map(change => ({ status: 'kept', path: change.file }));
  const output = state?.runner?.output || [];
  return `
    <section class="evidence-grid">
      <article class="panel evidence-panel">
        <header class="section-heading compact">
          <div><span class="eyebrow">EVIDENCE</span><h2>实验与集成改动</h2></div>
          <span class="count">${files.length} files</span>
        </header>
        <div class="file-list">
          ${files.length ? files.map(file => `<div><code>${escape(file.status)}</code><span>${escape(file.path)}</span></div>`).join('') : '<p class="empty">尚未产生实验改动</p>'}
        </div>
      </article>
      <article class="panel evidence-panel">
        <header class="section-heading compact">
          <div><span class="eyebrow">RUN LOG</span><h2>本地执行日志</h2></div>
          <span class="count">${output.length} lines</span>
        </header>
        <pre class="run-log">${output.length ? escape(output.join('\n')) : escape(kept?.learnings || '等待本地 Benchmark...')} </pre>
      </article>
    </section>`;
}

function render() {
  if (!state) return;
  const baseline = state.baseline?.diagnostics || {};
  const metrics = state.runner?.metrics
    || state.best?.metrics
    || state.experiment?.result?.diagnostics
    || null;
  const isolation = state.isolation || {};
  const running = state.runner?.status === 'running';
  const amp = metrics?.steady_byte_amplification;
  const currentDelta = metrics?.serialized_delta_bytes;
  app.innerHTML = `
    <div class="page-shell">
      <header class="run-header">
        <div class="run-identity">
          <span class="local-badge"><i></i> LOCAL ONLY</span>
          <h1>${escape(state.run.name)}</h1>
          <p>${escape(state.run.description)}</p>
        </div>
        <div class="run-actions">
          <div class="run-meta">
            <span>${escape(state.run.branch)}</span>
            <code>${escape(state.run.head)}</code>
          </div>
          <button id="measure" ${running || !state.run.benchmark_available ? 'disabled' : ''}>
            <span class="${running ? 'spinner' : 'run-icon'}"></span>
            ${running ? 'Benchmark 运行中' : '运行一次 Benchmark'}
          </button>
        </div>
      </header>

      <section class="isolation-strip">
        <span class="status-dot"></span>
        <b>开发隔离</b>
        <span>127.0.0.1</span>
        <span>只读 experiment state</span>
        <span>线上 API ${isolation.production_api_changed ? '已修改' : '未修改'}</span>
        <span>生产静态资源 ${isolation.production_assets_changed ? '已修改' : '未修改'}</span>
        <span>部署 ${isolation.deployed ? '已发生' : '未发生'}</span>
      </section>

      <section class="metric-grid">
        ${metricCard('结构放大率', baseline.steady_byte_amplification, amp, 'x')}
        ${metricCard('All steady', baseline.all_hosts_steady_kbps, metrics?.all_hosts_steady_kbps, ' KB/s')}
        ${metricCard('Reconnect', baseline.all_hosts_reconnect_kb, metrics?.all_hosts_reconnect_kb, ' KB')}
        ${metricCard('Serialized delta', baseline.serialized_delta_bytes, currentDelta, 'B')}
        ${metricCard('Server allocation', baseline.server_allocated_bytes, metrics?.server_allocated_bytes, 'B')}
        ${metricCard('Client apply p50', baseline.client_decode_apply_p50_us, metrics?.client_decode_apply_p50_us, ' us')}
      </section>

      ${renderStructure()}
      ${renderFlow()}
      ${renderGates(metrics)}
      ${renderEvidence()}

      <footer>
        <span>revision ${state.revision}</span>
        <span>${escape(state.generated_at)}</span>
        <span>payload ${bytes(encoder.encode(JSON.stringify(state)).byteLength)}</span>
      </footer>
    </div>`;

  document.querySelector('#measure')?.addEventListener('click', async event => {
    const button = event.currentTarget;
    button.disabled = true;
    try {
      const response = await fetch('/api/measure', { method: 'POST' });
      if (!response.ok) throw new Error(await response.text());
    } catch (error) {
      button.disabled = false;
      window.alert(`Benchmark 启动失败: ${error.message}`);
    }
  });
}

const events = new EventSource('/api/stream');
events.addEventListener('run.snapshot', event => {
  state = JSON.parse(event.data);
  render();
});
events.onerror = () => {
  document.documentElement.dataset.stream = 'reconnecting';
};
events.onopen = () => {
  delete document.documentElement.dataset.stream;
};
