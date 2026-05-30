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
                      border-radius: 0;
                      background:
                        linear-gradient(135deg,
                          hsl(var(--cluster-hue) 44% calc(68% - var(--load-level) * 24%)),
                          hsl(var(--cluster-hue) 42% calc(54% - var(--load-level) * 18%)));
                      box-shadow: none;
                      isolation: isolate;
                    }
                    .tile::before {
                      content: "";
                      position: absolute;
                      left: -20%;
                      right: -20%;
                      bottom: -34%;
                      height: 62%;
                      z-index: -1;
                      background:
                        radial-gradient(70% 86% at 50% 100%, rgba(255,255,255,.18), transparent 66%),
                        repeating-radial-gradient(ellipse at 50% 100%,
                          rgba(255,255,255,.16) 0 2px,
                          transparent 3px 18px);
                      background-size: 220px 120px, 180px 90px;
                      animation: water-ripple 12s linear infinite;
                      opacity: .38;
                    }
                    .tile:hover::before {
                      opacity: .48;
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
                      padding-bottom: 7px;
                      border-bottom: 1px solid rgba(255,255,255,.2);
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
                    @keyframes water-ripple {
                      from { background-position: 0 0, 0 0; }
                      to { background-position: 220px 0, 180px 0; }
                    }
                    @media (prefers-reduced-motion: reduce) {
                      .tile::before {
                        animation: none;
                      }
                    }
                  </style>
                </head>
                <body data-coordinator-id="__COORDINATOR_ID__">
                  <header>
                    <h1>Pulse Hosts</h1>
                    <div class="subtitle">Coordinator __COORDINATOR_ID__ · PulseView reactive dashboard · JSON refresh 5s</div>
                  </header>
                  <div id="pulse-status" class="app-status"></div>
                  <main id="pulse-app" data-framework="PulseView">
                    <section class="empty">Loading hosts from /api/hosts...</section>
                  </main>
                  <script>
                    (() => {
                      const refreshMs = 5000;
                      const palette = [205, 188, 168, 146, 126, 95, 48, 215, 200, 178];
                      const app = document.getElementById('pulse-app');
                      const status = document.getElementById('pulse-status');
                      const coordinatorId = document.body.dataset.coordinatorId || 'unknown';

                      const PulseView = {
                        state: {hosts: [], loading: true, error: null, updatedAt: null},
                        scrollPositions: new Map(),
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
                          this.captureTileScroll();
                          status.innerHTML = renderStatus(this.state);
                          app.innerHTML = renderApp(this.state);
                          this.restoreTileScroll();
                        },
                        captureTileScroll() {
                          app.querySelectorAll('[data-agent-id] .tile-scroll').forEach(scroller => {
                            const tile = scroller.closest('[data-agent-id]');
                            if (!tile) {
                              return;
                            }
                            this.scrollPositions.set(tile.dataset.agentId, {
                              top: scroller.scrollTop,
                              left: scroller.scrollLeft
                            });
                          });
                        },
                        restoreTileScroll() {
                          app.querySelectorAll('[data-agent-id] .tile-scroll').forEach(scroller => {
                            const tile = scroller.closest('[data-agent-id]');
                            const position = tile ? this.scrollPositions.get(tile.dataset.agentId) : null;
                            if (!position) {
                              return;
                            }
                            scroller.scrollTop = position.top;
                            scroller.scrollLeft = position.left;
                          });
                        },
                        start() {
                          this.render();
                          this.refresh();
                          window.setInterval(() => this.refresh(), refreshMs);
                        }
                      };

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
                          <span>Mode <strong>JSON diff refresh</strong></span>
                        `;
                      }

                      function renderApp(state) {
                        if (state.error) {
                          return `<section class="error">Failed to refresh /api/hosts: ${escapeHtml(state.error)}</section>`;
                        }
                        if (state.loading) {
                          return '<section class="empty">Loading hosts from /api/hosts...</section>';
                        }
                        if (!state.hosts.length) {
                          return '<section class="empty">No hosts yet. POST /heartbeat to light up the board.</section>';
                        }
                        return groupByCluster(state.hosts)
                          .map(([cluster, hosts], index) => renderCluster(cluster, hosts, index))
                          .join('');
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

                      function renderCluster(cluster, hosts, index) {
                        const sortedHosts = [...hosts].sort((left, right) =>
                          loadValue(right) - loadValue(left)
                          || String(left.host || '').localeCompare(String(right.host || ''))
                          || String(left.agent_id || '').localeCompare(String(right.agent_id || '')));
                        const maxLoad = Math.max(0, ...sortedHosts.map(loadValue));
                        const hue = clusterHue(cluster, index);
                        return `
                          <section class="cluster-section" data-cluster="${escapeHtml(cluster)}" style="--cluster-hue:${hue};">
                            <div class="cluster-title">
                              <h2>${escapeHtml(cluster)}</h2>
                              <span>${hosts.length} host${hosts.length === 1 ? '' : 's'}</span>
                            </div>
                            <div class="tile-grid">
                              ${sortedHosts.map((host, rank) => renderTile(host, rank, maxLoad)).join('')}
                            </div>
                          </section>
                        `;
                      }

                      function renderTile(host, rank, maxLoad) {
                        const load = loadValue(host);
                        const level = maxLoad <= 0 ? 0 : Math.max(0, Math.min(1, load / maxLoad));
                        const statusClass = host.status === 'expired' ? 'expired' : 'alive';
                        const agentId = host.agent_id || '';
                        return `
                          <section class="tile ${statusClass}" data-agent-id="${escapeHtml(agentId)}" style="--load-level:${level.toFixed(3)};">
                            <div class="tile-scroll">
                              <div class="tile-head">
                                <div class="tile-agent">${escapeHtml(agentId)}</div>
                                <div class="status">${escapeHtml(host.status || '')}</div>
                              </div>
                              <div class="tile-host">${escapeHtml(host.host || '')}</div>
                              <div class="tile-meta">
                                <div><span>Load</span>${escapeHtml(host.load || '')}</div>
                                <div><span>IP</span>${escapeHtml(host.ip || '')}</div>
                                <div><span>Area</span>${escapeHtml(host.area || '')}</div>
                                <div><span>Role</span>${escapeHtml(host.role || '')}</div>
                                <div><span>Zone</span>${escapeHtml(host.zone || '')}</div>
                                <div><span>Seq</span>${escapeHtml(String(host.seq || 0))}</div>
                                <div><span>Source</span>${escapeHtml(host.source || '')}</div>
                                <div><span>Seen</span>${escapeHtml(formatSeen(host.observed_at_ms))}</div>
                                <div><span>Rank</span>#${String(rank + 1).padStart(2, '0')}</div>
                              </div>
                            </div>
                            <div class="load-bar" aria-hidden="true"></div>
                          </section>
                        `;
                      }

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
