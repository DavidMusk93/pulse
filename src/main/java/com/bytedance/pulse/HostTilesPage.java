package com.bytedance.pulse;

import java.util.List;

public final class HostTilesPage {
    private HostTilesPage() {}

    public static String render(String coordinatorId, List<HostView> hosts) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Pulse Coordinator Hosts</title>
                  <style>
                    :root {
                      color-scheme: light;
                      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                      background: #f6f8fb;
                      color: #172033;
                    }
                    body {
                      margin: 0;
                      min-height: 100vh;
                      background: linear-gradient(180deg, #fbfcff 0%, #eef3f8 100%);
                    }
                    header {
                      padding: 30px clamp(18px, 4vw, 56px) 14px;
                    }
                    h1 {
                      margin: 0;
                      font-size: clamp(32px, 5vw, 56px);
                      font-weight: 750;
                      letter-spacing: -0.06em;
                    }
                    .subtitle {
                      margin-top: 8px;
                      color: #64748b;
                      font-size: 15px;
                    }
                    .app-status {
                      display: flex;
                      flex-wrap: wrap;
                      gap: 10px;
                      padding: 0 clamp(18px, 4vw, 56px) 18px;
                      color: #64748b;
                      font-size: 13px;
                    }
                    .app-status strong {
                      color: #172033;
                    }
                    .tile-grid {
                      display: grid;
                      grid-template-columns: repeat(auto-fill, minmax(184px, 1fr));
                      gap: 14px;
                    }
                    .tile {
                      position: relative;
                      overflow: hidden;
                      aspect-ratio: 1 / 1;
                      min-height: 0;
                      padding: 0;
                      color: white;
                      border-radius: 22px;
                      background:
                        linear-gradient(135deg,
                          hsl(var(--cluster-hue) 44% calc(68% - var(--load-level) * 24%)),
                          hsl(var(--cluster-hue) 42% calc(54% - var(--load-level) * 18%)));
                      box-shadow: none;
                      isolation: isolate;
                    }
                    .tile.expired {
                      background: linear-gradient(135deg, #94a3b8, #64748b);
                      filter: grayscale(.2);
                    }
                    .tile-scroll {
                      height: 100%;
                      box-sizing: border-box;
                      overflow: auto;
                      padding: 14px;
                      scrollbar-width: thin;
                      scrollbar-color: rgba(255,255,255,.55) transparent;
                    }
                    .tile-scroll::-webkit-scrollbar {
                      width: 6px;
                    }
                    .tile-scroll::-webkit-scrollbar-thumb {
                      background: rgba(255,255,255,.5);
                      border-radius: 999px;
                    }
                    .tile-head {
                      display: flex;
                      align-items: flex-start;
                      justify-content: space-between;
                      gap: 8px;
                      min-width: 0;
                    }
                    .status {
                      flex: 0 0 auto;
                      border: 1px solid rgba(255,255,255,.6);
                      padding: 3px 7px;
                      font-size: 11px;
                      font-weight: 750;
                      letter-spacing: .04em;
                      text-transform: uppercase;
                      background: rgba(255,255,255,.14);
                      border-radius: 999px;
                    }
                    .tile-actions {
                      display: flex;
                      flex-direction: column;
                      align-items: flex-end;
                      gap: 6px;
                    }
                    .run-button {
                      border: 1px solid rgba(255,255,255,.42);
                      border-radius: 999px;
                      color: white;
                      background: rgba(15,23,42,.16);
                      padding: 4px 9px;
                      font-size: 11px;
                      font-weight: 750;
                      cursor: pointer;
                    }
                    .run-button:disabled {
                      cursor: not-allowed;
                      opacity: .45;
                    }
                    .tile-agent {
                      min-width: 0;
                      font-size: 13px;
                      opacity: .9;
                      text-transform: uppercase;
                      letter-spacing: .08em;
                      overflow-wrap: anywhere;
                    }
                    .tile-host {
                      margin-top: 16px;
                      font-size: 21px;
                      line-height: 1.08;
                      font-weight: 800;
                      letter-spacing: -.04em;
                      overflow-wrap: anywhere;
                    }
                    .worker-list {
                      display: grid;
                      gap: 8px;
                      margin-top: 14px;
                    }
                    .worker-card {
                      padding: 9px;
                      border: 1px solid rgba(255,255,255,.24);
                      background: rgba(15,23,42,.16);
                      border-radius: 14px;
                    }
                    .worker-title {
                      display: flex;
                      justify-content: space-between;
                      gap: 8px;
                      font-size: 12px;
                      font-weight: 750;
                    }
                    .worker-grid {
                      display: grid;
                      grid-template-columns: 1fr 1fr;
                      gap: 6px 8px;
                      margin-top: 7px;
                      font-size: 11px;
                    }
                    .worker-grid span {
                      display: block;
                      opacity: .72;
                      font-size: 9px;
                      line-height: 1.5;
                      text-transform: uppercase;
                      letter-spacing: .08em;
                    }
                    .tile-meta {
                      display: grid;
                      grid-template-columns: 1fr;
                      gap: 8px;
                      margin-top: 14px;
                      font-size: 12px;
                    }
                    .tile-meta div {
                      min-width: 0;
                      overflow-wrap: anywhere;
                      padding: 7px 8px;
                      border: 1px solid rgba(255,255,255,.16);
                      border-radius: 12px;
                      background: rgba(15,23,42,.1);
                    }
                    .tile-meta span {
                      display: block;
                      opacity: .72;
                      font-size: 10px;
                      line-height: 1.5;
                      text-transform: uppercase;
                      letter-spacing: .08em;
                    }
                    .load-bar {
                      position: absolute;
                      left: 0;
                      right: 0;
                      bottom: 0;
                      height: 7px;
                      background: rgba(15, 23, 42, .24);
                      border-radius: 0 0 22px 22px;
                      overflow: hidden;
                    }
                    .load-bar::after {
                      content: "";
                      display: block;
                      width: calc(18% + var(--load-level) * 82%);
                      height: 100%;
                      background: hsl(var(--cluster-hue) 48% 24%);
                      box-shadow: 0 0 0 1px rgba(255,255,255,.26) inset;
                    }
                    .empty {
                      grid-column: 1 / -1;
                      padding: 36px;
                      border: 1px dashed #94a3b8;
                      color: #64748b;
                    }
                    .error {
                      margin: 0 clamp(18px, 4vw, 56px) 20px;
                      padding: 14px 16px;
                      color: #3f3f46;
                      background: #fef3c7;
                      border: 1px solid #fde68a;
                    }
                    .cluster-section {
                      padding: 0 clamp(18px, 4vw, 56px) 34px;
                    }
                    .cluster-title {
                      display: flex;
                      align-items: baseline;
                      gap: 12px;
                      margin: 12px 0 16px;
                    }
                    .cluster-title h2 {
                      margin: 0;
                      font-size: 26px;
                      font-weight: 800;
                      letter-spacing: -.04em;
                      color: hsl(var(--cluster-hue) 44% 34%);
                    }
                    .cluster-title span {
                      color: #64748b;
                      font-size: 13px;
                    }
                    .task-modal {
                      position: fixed;
                      inset: 0;
                      z-index: 20;
                      display: none;
                      align-items: center;
                      justify-content: center;
                      padding: 24px;
                      background: rgba(15,23,42,.46);
                    }
                    .task-modal.open {
                      display: flex;
                    }
                    .task-panel {
                      width: min(960px, 92vw);
                      max-height: 88vh;
                      overflow: auto;
                      border-radius: 24px;
                      background: #f8fafc;
                      color: #172033;
                      box-shadow: 0 24px 80px rgba(15,23,42,.22);
                    }
                    .task-panel header {
                      padding: 22px;
                    }
                    .task-grid {
                      display: grid;
                      grid-template-columns: 1fr 1.4fr;
                      gap: 14px;
                      padding: 0 22px 22px;
                    }
                    .task-card {
                      border: 1px solid #dbe3ed;
                      border-radius: 18px;
                      background: white;
                      padding: 14px;
                    }
                    .task-output {
                      min-height: 220px;
                      max-height: 360px;
                      overflow: auto;
                      border-radius: 14px;
                      background: #0f172a;
                      color: #dbeafe;
                      padding: 12px;
                      white-space: pre-wrap;
                      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                      font-size: 12px;
                    }
                    .task-actions {
                      display: flex;
                      flex-wrap: wrap;
                      gap: 10px;
                      padding: 0 22px 22px;
                    }
                    .task-actions button,
                    .task-actions select {
                      border: 1px solid #cbd5e1;
                      border-radius: 999px;
                      background: white;
                      color: #172033;
                      padding: 9px 12px;
                    }
                  </style>
                </head>
                <body data-coordinator-id="__COORDINATOR_ID__">
                  <header>
                    <h1>Pulse Hosts</h1>
                    <div class="subtitle">Coordinator __COORDINATOR_ID__ · PulseView keyed dashboard · JSON refresh 5s</div>
                  </header>
                  <div id="pulse-status" class="app-status"></div>
                  <main id="pulse-app" data-framework="PulseView">
                    <section class="empty">Loading hosts from /api/hosts...</section>
                  </main>
                  <div id="task-modal" class="task-modal" aria-hidden="true">
                    <section class="task-panel">
                      <header>
                        <h2 id="task-title">Run Task</h2>
                        <div id="task-trace" class="subtitle">trace: pending</div>
                      </header>
                      <div class="task-grid">
                        <section class="task-card">
                          <h3>Execution Queue</h3>
                          <div id="task-execution"></div>
                        </section>
                        <section class="task-card">
                          <h3>Completion Queue</h3>
                          <div id="task-completion-meta"></div>
                          <pre id="task-output" class="task-output"></pre>
                        </section>
                      </div>
                      <div class="task-actions">
                        <select id="task-type">
                          <option value="prepare_disk_layout_dry_run">prepare_disk_layout_dry_run</option>
                          <option value="analyze_block_layout_dry_run">analyze_block_layout_dry_run</option>
                        </select>
                        <button id="task-run">Run dry-run</button>
                        <button id="task-keep">Keep result</button>
                        <button id="task-pop">Pop and show next</button>
                        <button id="task-close">Close</button>
                      </div>
                    </section>
                  </div>
                  <script>
                    (() => {
                      const refreshMs = 5000;
                      const palette = [205, 188, 168, 146, 126, 95, 48, 215, 200, 178];
                      const app = document.getElementById('pulse-app');
                      const status = document.getElementById('pulse-status');
                      const coordinatorId = document.body.dataset.coordinatorId || 'unknown';
                      const taskModal = document.getElementById('task-modal');
                      const taskTitle = document.getElementById('task-title');
                      const taskTrace = document.getElementById('task-trace');
                      const taskExecution = document.getElementById('task-execution');
                      const taskCompletionMeta = document.getElementById('task-completion-meta');
                      const taskOutput = document.getElementById('task-output');
                      const taskType = document.getElementById('task-type');
                      const taskRun = document.getElementById('task-run');
                      const taskKeep = document.getElementById('task-keep');
                      const taskPop = document.getElementById('task-pop');
                      const taskClose = document.getElementById('task-close');
                      let activeTaskAgentId = '';
                      let activeCompletionTaskId = '';

                      const PulseView = {
                        state: {hosts: [], loading: true, error: null, updatedAt: null},
                        clusterSections: new Map(),
                        tiles: new Map(),
                        setState(patch) {
                          this.state = {...this.state, ...patch};
                          this.render();
                        },
                        async refresh() {
                          try {
                            const response = await fetch('/api/hosts', {cache: 'no-store'});
                            if (!response.ok) {
                              throw new Error(`HTTP ${response.status}`);
                            }
                            const hosts = await response.json();
                            this.setState({hosts, loading: false, error: null, updatedAt: new Date()});
                          } catch (error) {
                            this.setState({loading: false, error: error.message || String(error)});
                          }
                        },
                        render() {
                          const viewport = {left: window.scrollX, top: window.scrollY};
                          status.innerHTML = renderStatus(this.state);
                          this.renderApp();
                          this.restoreViewportScroll(viewport);
                        },
                        restoreViewportScroll(viewport) {
                          window.requestAnimationFrame(() => {
                            window.scrollTo(viewport.left, viewport.top);
                          });
                        },
                        renderApp() {
                          if (this.state.error) {
                            this.renderMessage('error', 'Failed to refresh /api/hosts: ' + this.state.error);
                            return;
                          }
                          if (this.state.loading) {
                            this.renderMessage('empty', 'Loading hosts from /api/hosts...');
                            return;
                          }
                          if (!this.state.hosts.length) {
                            this.renderMessage('empty', 'No hosts yet. POST /heartbeat to light up the board.');
                            return;
                          }
                          this.ensureDashboardMode();
                          this.updateClusters(groupByCluster(this.state.hosts));
                        },
                        renderMessage(className, text) {
                          if (app.dataset.mode !== className) {
                            this.clusterSections.clear();
                            this.tiles.clear();
                            app.replaceChildren();
                            const section = document.createElement('section');
                            section.className = className;
                            app.appendChild(section);
                            app.dataset.mode = className;
                          }
                          app.firstElementChild.textContent = text;
                        },
                        ensureDashboardMode() {
                          if (app.dataset.mode !== 'dashboard') {
                            app.replaceChildren();
                            this.clusterSections.clear();
                            this.tiles.clear();
                            app.dataset.mode = 'dashboard';
                          }
                        },
                        updateClusters(groups) {
                          const activeClusters = new Set();
                          groups.forEach(([cluster, hosts], index) => {
                            activeClusters.add(cluster);
                            const section = this.getOrCreateClusterSection(cluster);
                            const hue = clusterHue(cluster, index);
                            section.style.setProperty('--cluster-hue', String(hue));
                            section.dataset.cluster = cluster;
                            section.querySelector('[data-cluster-name]').textContent = cluster;
                            section.querySelector('[data-cluster-count]').textContent =
                              hosts.length + ' host' + (hosts.length === 1 ? '' : 's');
                            this.updateTiles(section.querySelector('.tile-grid'), hosts);
                            placeChild(app, section, index);
                          });
                          [...this.clusterSections.entries()].forEach(([cluster, section]) => {
                            if (!activeClusters.has(cluster)) {
                              section.querySelectorAll('[data-agent-id]').forEach(tile => {
                                this.tiles.delete(tile.dataset.agentId || '');
                              });
                              section.remove();
                              this.clusterSections.delete(cluster);
                            }
                          });
                        },
                        getOrCreateClusterSection(cluster) {
                          let section = this.clusterSections.get(cluster);
                          if (section) {
                            return section;
                          }
                          section = document.createElement('section');
                          section.className = 'cluster-section';
                          section.innerHTML = '<div class="cluster-title"><h2 data-cluster-name></h2><span data-cluster-count></span></div><div class="tile-grid"></div>';
                          this.clusterSections.set(cluster, section);
                          return section;
                        },
                        updateTiles(grid, hosts) {
                          const sortedHosts = sortHosts(hosts);
                          const maxLoad = Math.max(0, ...sortedHosts.map(loadValue));
                          const activeAgents = new Set();
                          sortedHosts.forEach((host, rank) => {
                            const agentId = host.agent_id || '';
                            activeAgents.add(agentId);
                            const tile = this.getOrCreateTile(agentId);
                            updateTile(tile, host, rank, maxLoad);
                            placeChild(grid, tile, rank);
                          });
                          grid.querySelectorAll('[data-agent-id]').forEach(tile => {
                            if (!activeAgents.has(tile.dataset.agentId || '')) {
                              tile.remove();
                              this.tiles.delete(tile.dataset.agentId || '');
                            }
                          });
                        },
                        getOrCreateTile(agentId) {
                          let tile = this.tiles.get(agentId);
                          if (tile) {
                            return tile;
                          }
                          tile = createTile(agentId);
                          this.tiles.set(agentId, tile);
                          return tile;
                        },
                        start() {
                          this.render();
                          this.refresh();
                          window.setInterval(() => this.refresh(), refreshMs);
                        }
                      };

                      function placeChild(parent, child, index) {
                        const current = parent.children[index] || null;
                        if (current !== child) {
                          parent.insertBefore(child, current);
                        }
                      }

                      function renderStatus(state) {
                        const alive = state.hosts.filter(host => host.status === 'alive').length;
                        const expired = state.hosts.filter(host => host.status === 'expired').length;
                        const updated = state.updatedAt ? state.updatedAt.toLocaleTimeString() : 'pending';
                        return `
                          <span><strong>${escapeHtml(coordinatorId)}</strong></span>
                          <span>Total <strong>${state.hosts.length}</strong></span>
                          <span>Alive <strong>${alive}</strong></span>
                          <span>Expired <strong>${expired}</strong></span>
                          <span>Updated <strong>${escapeHtml(updated)}</strong></span>
                          <span>Mode <strong>Keyed DOM refresh</strong></span>
                        `;
                      }

                      function groupByCluster(hosts) {
                        const groups = new Map();
                        hosts.forEach(host => {
                          const cluster = host.cluster || 'unknown';
                          if (!groups.has(cluster)) {
                            groups.set(cluster, []);
                          }
                          groups.get(cluster).push(host);
                        });
                        return [...groups.entries()].sort(([left], [right]) => left.localeCompare(right));
                      }

                      function sortHosts(hosts) {
                        return [...hosts].sort((left, right) =>
                          loadValue(right) - loadValue(left)
                          || String(left.ip || '').localeCompare(String(right.ip || ''))
                          || String(left.agent_id || '').localeCompare(String(right.agent_id || '')));
                      }

                      function createTile(agentId) {
                        const tile = document.createElement('section');
                        tile.className = 'tile';
                        tile.dataset.agentId = agentId;
                        tile.innerHTML = `
                          <div class="tile-scroll">
                            <div class="tile-head">
                              <div class="tile-agent" data-field="seen"></div>
                              <div class="tile-actions">
                                <div class="status" data-field="status"></div>
                                <button class="run-button" data-action="run-task" type="button">Run</button>
                              </div>
                            </div>
                            <div class="tile-host" data-field="ip_title"></div>
                            <div class="tile-meta">
                              <div><span>Load</span><span data-field="load"></span></div>
                              <div><span>IP</span><span data-field="ip"></span></div>
                              <div><span>Area</span><span data-field="area"></span></div>
                              <div><span>Confirm</span><span data-field="confirmations"></span></div>
                            </div>
                            <div class="worker-list" data-field="workers"></div>
                          </div>
                          <div class="load-bar" aria-hidden="true"></div>
                        `;
                        return tile;
                      }

                      function updateTile(tile, host, rank, maxLoad) {
                        const load = loadValue(host);
                        const level = maxLoad <= 0 ? 0 : Math.max(0, Math.min(1, load / maxLoad));
                        const statusClass = host.status === 'expired' ? 'expired' : 'alive';
                        const agentId = host.agent_id || '';
                        tile.dataset.agentId = agentId;
                        tile.className = 'tile ' + statusClass;
                        tile.style.setProperty('--load-level', level.toFixed(3));
                        setText(tile, 'seen', formatSeen(host.observed_at_ms));
                        setText(tile, 'status', host.status || '');
                        const runButton = tile.querySelector('[data-action="run-task"]');
                        runButton.disabled = host.status !== 'alive';
                        runButton.onclick = () => openTaskModal(agentId, host.ip || agentId);
                        setText(tile, 'ip_title', host.ip || 'unknown ip');
                        setText(tile, 'load', host.load || '');
                        setText(tile, 'ip', host.ip || '');
                        setText(tile, 'area', host.area || '');
                        setText(tile, 'confirmations', String(host.heartbeat_confirmations || 0) + '/3 in 20s');
                        renderWorkers(tile.querySelector('[data-field="workers"]'), tideWorkers(host));
                      }

                      function tideWorkers(host) {
                        const workers = host.state && Array.isArray(host.state.tide_workers) ? host.state.tide_workers : [];
                        return workers.filter(Boolean);
                      }

                      function renderWorkers(container, workers) {
                        if (!workers.length) {
                          container.innerHTML = '<div class="worker-card">No tide_worker detected</div>';
                          return;
                        }
                        container.innerHTML = workers.map(worker => `
                          <div class="worker-card">
                            <div class="worker-title">
                              <span>tide_worker</span>
                              <span>pid ${escapeHtml(worker.pid || '')}</span>
                            </div>
                            <div class="worker-grid">
                              <div><span>CPU</span>${escapeHtml(percent(worker.cpu_percent))}</div>
                              <div><span>MEM</span>${escapeHtml(percent(worker.mem_percent))}</div>
                              <div><span>PORT1</span>${escapeHtml(worker.port1 || '-')}</div>
                              <div><span>Version</span>${escapeHtml(worker.component_version || '-')}</div>
                            </div>
                          </div>
                        `).join('');
                      }

                      function setText(root, field, value) {
                        root.querySelector('[data-field="' + field + '"]').textContent = value;
                      }

                      async function openTaskModal(agentId, label) {
                        activeTaskAgentId = agentId;
                        activeCompletionTaskId = '';
                        taskTitle.textContent = 'Run Task · ' + label;
                        taskTrace.textContent = 'trace: pending';
                        taskModal.classList.add('open');
                        taskModal.setAttribute('aria-hidden', 'false');
                        await refreshTaskSnapshot();
                      }

                      async function refreshTaskSnapshot() {
                        if (!activeTaskAgentId) {
                          return;
                        }
                        const response = await fetch('/api/agents/' + encodeURIComponent(activeTaskAgentId) + '/tasks', {cache: 'no-store'});
                        if (!response.ok) {
                          throw new Error('task snapshot HTTP ' + response.status);
                        }
                        renderTaskSnapshot(await response.json());
                      }

                      function renderTaskSnapshot(snapshot) {
                        const execution = snapshot.execution_queue || [];
                        const completions = snapshot.completion_queue || [];
                        const latest = completions[completions.length - 1] || null;
                        activeCompletionTaskId = latest ? latest.task_id : '';
                        taskExecution.innerHTML = execution.length
                          ? execution.map(task => `<div class="tile-meta"><div><span>${escapeHtml(task.status || '')}</span>${escapeHtml(task.task_type || '')}<br>${escapeHtml(task.trace_id || '')}</div></div>`).join('')
                          : '<div class="subtitle">Execution queue is empty.</div>';
                        if (latest) {
                          taskTrace.textContent = 'trace: ' + latest.trace_id;
                          taskCompletionMeta.innerHTML = `<div>Status <strong>${escapeHtml(latest.status || '')}</strong> · Exit <strong>${escapeHtml(latest.exit_code ?? '-')}</strong> · Duration <strong>${escapeHtml(latest.duration_ms || 0)}ms</strong></div>`;
                          taskOutput.textContent = ['STDOUT', latest.stdout_tail || '', '', 'STDERR', latest.stderr_tail || '', latest.runner_error ? '\\nERROR\\n' + latest.runner_error : ''].join('\\n');
                        } else {
                          taskTrace.textContent = 'trace: pending';
                          taskCompletionMeta.innerHTML = '<div class="subtitle">No completion yet.</div>';
                          taskOutput.textContent = '';
                        }
                      }

                      taskRun.onclick = async () => {
                        if (!activeTaskAgentId) {
                          return;
                        }
                        const response = await fetch('/api/agents/' + encodeURIComponent(activeTaskAgentId) + '/tasks', {
                          method: 'POST',
                          headers: {'content-type': 'application/json'},
                          body: JSON.stringify({task_type: taskType.value})
                        });
                        if (!response.ok) {
                          taskOutput.textContent = 'Run failed: HTTP ' + response.status;
                          return;
                        }
                        renderTaskSnapshot(await response.json());
                      };

                      taskKeep.onclick = async () => {
                        if (activeTaskAgentId && activeCompletionTaskId) {
                          await fetch('/api/agents/' + encodeURIComponent(activeTaskAgentId) + '/tasks/completions/' + encodeURIComponent(activeCompletionTaskId) + '/keep', {method: 'POST'});
                          await refreshTaskSnapshot();
                        }
                      };

                      taskPop.onclick = async () => {
                        if (activeTaskAgentId && activeCompletionTaskId) {
                          const response = await fetch('/api/agents/' + encodeURIComponent(activeTaskAgentId) + '/tasks/completions/' + encodeURIComponent(activeCompletionTaskId) + '/pop', {method: 'POST'});
                          if (response.ok) {
                            renderTaskSnapshot(await response.json());
                          }
                        }
                      };

                      taskClose.onclick = () => {
                        taskModal.classList.remove('open');
                        taskModal.setAttribute('aria-hidden', 'true');
                      };

                      function clusterHue(cluster, index) {
                        if (!cluster) {
                          return palette[index % palette.length];
                        }
                        let hash = 0;
                        for (let i = 0; i < cluster.length; i++) {
                          hash = ((hash << 5) - hash) + cluster.charCodeAt(i);
                          hash |= 0;
                        }
                        return palette[Math.abs(hash) % palette.length];
                      }

                      function loadValue(host) {
                        const value = Number.parseFloat(host.load);
                        return Number.isFinite(value) ? value : 0;
                      }

                      function formatSeen(value) {
                        const millis = Number(value);
                        return Number.isFinite(millis) ? new Date(millis).toLocaleString() : '';
                      }

                      function percent(value) {
                        const number = Number.parseFloat(value);
                        return Number.isFinite(number) ? number.toFixed(2) + '%' : '-';
                      }

                      function escapeHtml(value) {
                        return String(value)
                          .replaceAll('&', '&amp;')
                          .replaceAll('<', '&lt;')
                          .replaceAll('>', '&gt;')
                          .replaceAll('"', '&quot;')
                          .replaceAll("'", '&#39;');
                      }

                      window.PulseView = PulseView;
                      PulseView.start();
                    })();
                  </script>
                </body>
                </html>
                """
                .replace("__COORDINATOR_ID__", escape(coordinatorId));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
