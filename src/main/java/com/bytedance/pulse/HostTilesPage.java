package com.bytedance.pulse;

import java.util.List;

public final class HostTilesPage {
    private HostTilesPage() {}

    public static String render(String coordinatorId, List<HostView> hosts) {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Pulse 心跳平台</title>
                  <link rel="stylesheet" href="/assets/pulse-hosts.css">
                </head>
                <body>
                  <div id="root" data-ui-library="Ant Design" data-framework="React"></div>
                  <script type="module" src="/assets/pulse-hosts.js"></script>
                </body>
                </html>
                """;
    }
}
