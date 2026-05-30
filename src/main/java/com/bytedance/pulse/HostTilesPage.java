package com.bytedance.pulse;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class HostTilesPage {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private HostTilesPage() {}

    public static String render(String coordinatorId, List<HostView> hosts) {
        StringBuilder tiles = new StringBuilder();
        for (int i = 0; i < hosts.size(); i++) {
            HostView host = hosts.get(i);
            tiles.append(renderTile(host, i));
        }
        if (hosts.isEmpty()) {
            tiles.append("<section class=\"empty\">No hosts yet. POST /heartbeat to light up the board.</section>");
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
                      color-scheme: dark;
                      font-family: "Segoe UI", Arial, sans-serif;
                      background: #111827;
                      color: #f8fafc;
                    }
                    body {
                      margin: 0;
                      min-height: 100vh;
                      background:
                        radial-gradient(circle at top left, rgba(14, 165, 233, .3), transparent 30rem),
                        linear-gradient(135deg, #0f172a 0%, #111827 52%, #020617 100%);
                    }
                    header {
                      padding: 32px clamp(20px, 4vw, 56px) 18px;
                    }
                    h1 {
                      margin: 0;
                      font-size: clamp(34px, 6vw, 72px);
                      font-weight: 300;
                      letter-spacing: -0.05em;
                    }
                    .subtitle {
                      margin-top: 8px;
                      color: #cbd5e1;
                      font-size: 15px;
                    }
                    .tile-grid {
                      display: grid;
                      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
                      grid-auto-rows: minmax(190px, auto);
                      gap: 16px;
                      padding: 18px clamp(20px, 4vw, 56px) 56px;
                    }
                    .tile {
                      position: relative;
                      overflow: hidden;
                      min-height: 190px;
                      padding: 20px;
                      color: white;
                      box-shadow: 0 18px 45px rgba(0, 0, 0, .32);
                    }
                    .tile::after {
                      content: "";
                      position: absolute;
                      inset: auto -30px -46px auto;
                      width: 130px;
                      height: 130px;
                      border: 18px solid rgba(255,255,255,.16);
                      transform: rotate(18deg);
                    }
                    .tile:nth-child(6n+1) { background: #0078d7; }
                    .tile:nth-child(6n+2) { background: #00aba9; }
                    .tile:nth-child(6n+3) { background: #8e24aa; }
                    .tile:nth-child(6n+4) { background: #e67e22; }
                    .tile:nth-child(6n+5) { background: #107c10; }
                    .tile:nth-child(6n+6) { background: #d13438; }
                    .tile.expired {
                      background: #475569;
                      filter: grayscale(.2);
                    }
                    .tile-agent {
                      font-size: 13px;
                      opacity: .86;
                      text-transform: uppercase;
                      letter-spacing: .08em;
                    }
                    .tile-host {
                      margin-top: 18px;
                      font-size: 30px;
                      line-height: 1.05;
                      font-weight: 300;
                      word-break: break-word;
                    }
                    .tile-meta {
                      display: grid;
                      grid-template-columns: repeat(2, minmax(0, 1fr));
                      gap: 8px 12px;
                      margin-top: 22px;
                      font-size: 13px;
                    }
                    .tile-meta span {
                      display: block;
                      opacity: .72;
                      font-size: 11px;
                      text-transform: uppercase;
                    }
                    .status {
                      position: absolute;
                      right: 16px;
                      top: 16px;
                      border: 1px solid rgba(255,255,255,.5);
                      padding: 4px 8px;
                      font-size: 12px;
                    }
                    .empty {
                      grid-column: 1 / -1;
                      padding: 36px;
                      border: 1px dashed rgba(255,255,255,.35);
                      color: #cbd5e1;
                    }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>Pulse Hosts</h1>
                    <div class="subtitle">Coordinator __COORDINATOR_ID__ · Windows Phone style host tiles · auto refresh 5s</div>
                  </header>
                  <main class="tile-grid">__TILES__</main>
                </body>
                </html>
                """
                .replace("__COORDINATOR_ID__", escape(coordinatorId))
                .replace("__TILES__", tiles.toString());
    }

    private static String renderTile(HostView host, int index) {
        String cssClass = "tile " + escape(host.status());
        return """
                <section class="%s">
                  <div class="status">%s</div>
                  <div class="tile-agent">%s</div>
                  <div class="tile-host">%s</div>
                  <div class="tile-meta">
                    <div><span>IP</span>%s</div>
                    <div><span>Role</span>%s</div>
                    <div><span>Zone</span>%s</div>
                    <div><span>Load</span>%s</div>
                    <div><span>Seq</span>%d</div>
                    <div><span>Source</span>%s</div>
                    <div><span>Seen</span>%s</div>
                    <div><span>Tile</span>#%02d</div>
                  </div>
                </section>
                """.formatted(
                cssClass,
                escape(host.status()),
                escape(host.agentId()),
                escape(host.host()),
                escape(host.ip()),
                escape(host.role()),
                escape(host.zone()),
                escape(host.load()),
                host.seq(),
                escape(host.source()),
                escape(FORMATTER.format(Instant.ofEpochMilli(host.observedAtMs()))),
                index + 1);
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
