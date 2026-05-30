package com.bytedance.pulse;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class HostTilesPage {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private HostTilesPage() {}

    public static String render(String coordinatorId, List<HostView> hosts) {
        StringBuilder groups = new StringBuilder();
        if (hosts.isEmpty()) {
            groups.append("<section class=\"empty\">No hosts yet. POST /heartbeat to light up the board.</section>");
        } else {
            Map<String, List<HostView>> byCluster = hosts.stream()
                    .collect(Collectors.groupingBy(HostView::cluster, LinkedHashMap::new, Collectors.toList()));
            int clusterIndex = 0;
            for (Map.Entry<String, List<HostView>> entry : byCluster.entrySet()) {
                groups.append(renderCluster(entry.getKey(), entry.getValue(), clusterIndex++));
            }
        }

        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta http-equiv="refresh" content="5">
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
                          hsl(var(--cluster-hue) 78% calc(64% - var(--load-level) * 20%)),
                          hsl(var(--cluster-hue) 72% calc(48% - var(--load-level) * 14%)));
                      box-shadow: none;
                      isolation: isolate;
                    }
                    .tile::before {
                      content: "";
                      position: absolute;
                      inset: -36% -42%;
                      z-index: -1;
                      background:
                        radial-gradient(ellipse at 15% 35%, rgba(255,255,255,.34), transparent 26%),
                        radial-gradient(ellipse at 70% 65%, rgba(255,255,255,.18), transparent 28%),
                        linear-gradient(115deg, transparent 28%, rgba(255,255,255,.22) 48%, transparent 68%);
                      transform: translateX(-14%) rotate(8deg);
                      animation: liquid-flow 7s ease-in-out infinite;
                      opacity: .72;
                    }
                    .tile:hover::before {
                      opacity: .98;
                      animation-duration: 3.8s;
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
                      height: 6px;
                      background: rgba(255,255,255,.25);
                    }
                    .load-bar::after {
                      content: "";
                      display: block;
                      width: calc(18% + var(--load-level) * 82%);
                      height: 100%;
                      background: rgba(255,255,255,.86);
                    }
                    .empty {
                      grid-column: 1 / -1;
                      padding: 36px;
                      border: 1px dashed #94a3b8;
                      color: #64748b;
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
                      color: hsl(var(--cluster-hue) 72% 36%);
                    }
                    .cluster-title span {
                      color: #64748b;
                      font-size: 13px;
                    }
                    @keyframes liquid-flow {
                      0%, 100% { transform: translateX(-16%) translateY(-2%) rotate(7deg); }
                      50% { transform: translateX(13%) translateY(4%) rotate(11deg); }
                    }
                    @media (prefers-reduced-motion: reduce) {
                      .tile::before {
                        animation: none;
                      }
                    }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>Pulse Hosts</h1>
                    <div class="subtitle">Coordinator __COORDINATOR_ID__ · flat square host tiles · load-sorted · auto refresh 5s</div>
                  </header>
                  <main>__GROUPS__</main>
                </body>
                </html>
                """
                .replace("__COORDINATOR_ID__", escape(coordinatorId))
                .replace("__GROUPS__", groups.toString());
    }

    private static String renderCluster(String cluster, List<HostView> hosts, int clusterIndex) {
        StringBuilder tiles = new StringBuilder();
        List<HostView> sortedHosts = hosts.stream()
                .sorted(Comparator.comparingDouble(HostTilesPage::loadValue).reversed()
                        .thenComparing(HostView::host)
                        .thenComparing(HostView::agentId))
                .toList();
        double maxLoad = sortedHosts.stream().mapToDouble(HostTilesPage::loadValue).max().orElse(0.0);
        int hue = clusterHue(cluster, clusterIndex);
        for (int i = 0; i < sortedHosts.size(); i++) {
            tiles.append(renderTile(sortedHosts.get(i), i, maxLoad));
        }
        return """
                <section class="cluster-section" data-cluster="%s" style="--cluster-hue:%d;">
                  <div class="cluster-title">
                    <h2>%s</h2>
                    <span>%d host%s</span>
                  </div>
                  <div class="tile-grid">%s</div>
                </section>
                """.formatted(
                escape(cluster),
                hue,
                escape(cluster),
                hosts.size(),
                hosts.size() == 1 ? "" : "s",
                tiles);
    }

    private static String renderTile(HostView host, int index, double maxLoad) {
        String cssClass = "tile " + escape(host.status());
        double load = loadValue(host);
        double level = maxLoad <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, load / maxLoad));
        String levelStyle = String.format(Locale.ROOT, "--load-level:%.3f;", level);
        return """
                <section class="%s" style="%s">
                  <div class="tile-scroll">
                    <div class="tile-head">
                      <div class="tile-agent">%s</div>
                      <div class="status">%s</div>
                    </div>
                    <div class="tile-host">%s</div>
                    <div class="tile-meta">
                      <div><span>Load</span>%s</div>
                      <div><span>IP</span>%s</div>
                      <div><span>Area</span>%s</div>
                      <div><span>Role</span>%s</div>
                      <div><span>Zone</span>%s</div>
                      <div><span>Seq</span>%d</div>
                      <div><span>Source</span>%s</div>
                      <div><span>Seen</span>%s</div>
                      <div><span>Rank</span>#%02d</div>
                    </div>
                  </div>
                  <div class="load-bar" aria-hidden="true"></div>
                </section>
                """.formatted(
                cssClass,
                levelStyle,
                escape(host.agentId()),
                escape(host.status()),
                escape(host.host()),
                escape(host.load()),
                escape(host.ip()),
                escape(host.area()),
                escape(host.role()),
                escape(host.zone()),
                host.seq(),
                escape(host.source()),
                escape(FORMATTER.format(Instant.ofEpochMilli(host.observedAtMs()))),
                index + 1);
    }

    private static int clusterHue(String cluster, int index) {
        int[] palette = {205, 176, 148, 28, 265, 338, 118, 12, 232, 296};
        if (cluster == null || cluster.isBlank()) {
            return palette[index % palette.length];
        }
        return palette[Math.floorMod(cluster.hashCode(), palette.length)];
    }

    private static double loadValue(HostView host) {
        try {
            return Double.parseDouble(host.load());
        } catch (Exception ignored) {
            return 0.0;
        }
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
