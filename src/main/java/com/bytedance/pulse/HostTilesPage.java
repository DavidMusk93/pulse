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
                  <link rel="preconnect" href="https://fonts.googleapis.com">
                  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                  <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Fira+Code:wght@400;500;600;700&family=Fira+Sans:wght@400;500;600;700&display=swap">
                  <style>
                    :root {
                      color-scheme: light;
                      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                      background: #f6f8fb;
                      color: #172033;
                      /* ui-ux-pro-max design tokens */
                      --color-primary: #1d4ed8;
                      --color-primary-hover: #1e3a8a;
                      --color-on-primary: #ffffff;
                      --color-accent: #b45309;
                      --color-surface: #ffffff;
                      --color-surface-muted: #f8fafc;
                      --color-foreground: #172033;
                      --color-muted-fg: #64748b;
                      --color-border: #e2e8f0;
                      --color-border-strong: #cbd5e1;
                      --color-ring: #2563eb;
                      --color-success: #15803d;
                      --color-success-bg: #dcfce7;
                      --color-danger: #b91c1c;
                      --color-danger-bg: #fee2e2;
                      --space-1: 4px;
                      --space-2: 8px;
                      --space-3: 12px;
                      --space-4: 16px;
                      --space-5: 24px;
                      --space-6: 32px;
                      --radius-sm: 12px;
                      --radius-md: 16px;
                      --radius-lg: 22px;
                      --radius-xl: 28px;
                      --motion-fast: 150ms;
                      --motion-base: 220ms;
                      --motion-slow: 320ms;
                      --motion-ease-out: cubic-bezier(.2,.8,.2,1);
                      --font-mono: "Fira Code", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                      --font-num: "Fira Code", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                    }
                    @media (prefers-reduced-motion: reduce) {
                      *, *::before, *::after {
                        animation-duration: 0ms !important;
                        animation-iteration-count: 1 !important;
                        transition-duration: 0ms !important;
                        scroll-behavior: auto !important;
                      }
                    }
                    body {
                      margin: 0;
                      min-height: 100vh;
                      background:
                        radial-gradient(circle at 10% 8%, rgba(251, 113, 133, .22), transparent 30%),
                        radial-gradient(circle at 88% 4%, rgba(56, 189, 248, .24), transparent 28%),
                        radial-gradient(circle at 50% 96%, rgba(250, 204, 21, .20), transparent 34%),
                        linear-gradient(180deg, #fff7ed 0%, #eef2ff 48%, #ecfeff 100%);
                    }
                    header {
                      padding: 28px clamp(18px, 4vw, 56px) 18px;
                    }
                    .hero-shell {
                      display: grid;
                      grid-template-columns: minmax(0, 1.12fr) minmax(280px, .88fr);
                      gap: 22px;
                      align-items: stretch;
                    }
                    .hero-card,
                    .demo-card,
                    .testimonial-card {
                      border: 1px solid rgba(255,255,255,.7);
                      border-radius: 34px;
                      background:
                        linear-gradient(145deg, rgba(255,255,255,.82), rgba(255,255,255,.48)),
                        radial-gradient(circle at 20% 0%, rgba(99,102,241,.18), transparent 36%);
                      box-shadow:
                        18px 18px 42px rgba(99,102,241,.16),
                        -12px -12px 32px rgba(255,255,255,.78),
                        inset 0 1px 0 rgba(255,255,255,.78);
                      backdrop-filter: blur(18px);
                    }
                    .hero-card {
                      padding: clamp(22px, 3vw, 34px);
                    }
                    .hero-eyebrow {
                      display: inline-flex;
                      align-items: center;
                      gap: 8px;
                      margin: 0 0 12px;
                      color: #7c3aed;
                      font-size: 12px;
                      font-weight: 900;
                      letter-spacing: .14em;
                      text-transform: uppercase;
                    }
                    h1 {
                      margin: 0;
                      max-width: 760px;
                      font-size: clamp(38px, 6vw, 76px);
                      line-height: .9;
                      font-weight: 900;
                      letter-spacing: -0.075em;
                      color: #111827;
                    }
                    .subtitle {
                      max-width: 680px;
                      margin-top: 16px;
                      color: #475569;
                      font-size: clamp(15px, 1.6vw, 18px);
                      line-height: 1.55;
                    }
                    .hero-actions {
                      display: flex;
                      flex-wrap: wrap;
                      gap: 12px;
                      margin-top: 22px;
                    }
                    .hero-cta,
                    .hero-secondary {
                      display: inline-flex;
                      align-items: center;
                      justify-content: center;
                      min-height: 44px;
                      border-radius: 999px;
                      padding: 0 18px;
                      font-weight: 850;
                      text-decoration: none;
                    }
                    .hero-cta {
                      color: #ffffff;
                      background: linear-gradient(135deg, #f97316, #ec4899 48%, #7c3aed);
                      box-shadow: 0 16px 34px rgba(236,72,153,.28);
                    }
                    .hero-secondary {
                      color: #334155;
                      border: 1px solid rgba(148,163,184,.44);
                      background: rgba(255,255,255,.66);
                    }
                    .demo-stack {
                      display: grid;
                      gap: 14px;
                    }
                    .demo-card {
                      padding: 18px;
                    }
                    .demo-card h2,
                    .testimonial-card h2 {
                      margin: 0 0 12px;
                      font-size: 18px;
                      letter-spacing: -.03em;
                    }
                    .catalog-preview {
                      display: grid;
                      grid-template-columns: repeat(2, minmax(0, 1fr));
                      gap: 10px;
                    }
                    .catalog-pill {
                      min-width: 0;
                      border-radius: 20px;
                      padding: 12px;
                      color: #172033;
                      background: linear-gradient(145deg, rgba(255,255,255,.84), rgba(255,255,255,.42));
                      box-shadow: inset 0 -10px 24px rgba(99,102,241,.08);
                    }
                    .catalog-pill strong {
                      display: block;
                      font-size: 13px;
                    }
                    .catalog-pill span {
                      display: block;
                      margin-top: 4px;
                      color: #64748b;
                      font-size: 11px;
                    }
                    .testimonial-card {
                      padding: 16px 18px;
                    }
                    .testimonial-card blockquote {
                      margin: 0;
                      color: #334155;
                      font-size: 13px;
                      line-height: 1.5;
                    }
                    .testimonial-card cite {
                      display: block;
                      margin-top: 10px;
                      color: #7c3aed;
                      font-style: normal;
                      font-weight: 850;
                    }
                    .app-status {
                      display: grid;
                      grid-template-columns: repeat(6, minmax(128px, 1fr));
                      gap: 12px;
                      padding: 0 clamp(18px, 4vw, 56px) 18px;
                      color: #64748b;
                      font-size: 13px;
                    }
                    .status-card {
                      min-width: 0;
                      border: 1px solid rgba(255,255,255,.72);
                      border-radius: 24px;
                      padding: 14px;
                      background: linear-gradient(145deg, rgba(255,255,255,.78), rgba(255,255,255,.44));
                      box-shadow:
                        12px 12px 28px rgba(99,102,241,.12),
                        -10px -10px 24px rgba(255,255,255,.76),
                        inset 0 1px 0 rgba(255,255,255,.7);
                    }
                    .status-card span {
                      display: block;
                      color: #64748b;
                      font-size: 11px;
                      font-weight: 800;
                      letter-spacing: .08em;
                      text-transform: uppercase;
                    }
                    .status-card strong {
                      display: block;
                      margin-top: 6px;
                      overflow: hidden;
                      text-overflow: ellipsis;
                      white-space: nowrap;
                      color: #172033;
                      font: 800 18px/1.15 var(--font-num);
                    }
                    .progress-track {
                      height: 8px;
                      margin-top: 11px;
                      overflow: hidden;
                      border-radius: 999px;
                      background: rgba(148,163,184,.22);
                    }
                    .progress-fill {
                      display: block;
                      width: var(--progress, 0%);
                      height: 100%;
                      border-radius: inherit;
                      background: linear-gradient(90deg, #22c55e, #14b8a6, #38bdf8);
                    }
                    .tile-grid {
                      display: grid;
                      grid-template-columns: repeat(auto-fill, minmax(208px, 1fr));
                      gap: 18px;
                    }
                    .tile {
                      position: relative;
                      overflow: hidden;
                      aspect-ratio: 1 / 1;
                      min-height: 0;
                      padding: 0;
                      color: white;
                      border: 1px solid rgba(255,255,255,.52);
                      border-radius: 28px;
                      background:
                        radial-gradient(circle at 16% 12%, rgba(255,255,255,.44), transparent 34%),
                        radial-gradient(circle at 86% 4%, rgba(255,255,255,.20), transparent 28%),
                        linear-gradient(135deg,
                          hsl(var(--cluster-hue) 74% calc(66% - var(--load-level) * 18%)),
                          hsl(calc(var(--cluster-hue) + 28) 76% calc(54% - var(--load-level) * 14%)));
                      box-shadow:
                        16px 18px 34px hsla(var(--cluster-hue), 60%, 34%, .20),
                        -10px -10px 28px rgba(255,255,255,.70),
                        inset 0 1px 0 rgba(255,255,255,.42);
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
                      font-size: 10px;
                      line-height: 1.25;
                      font-weight: 750;
                      letter-spacing: .04em;
                      text-transform: uppercase;
                      background: rgba(255,255,255,.20);
                      box-shadow: inset 0 1px 0 rgba(255,255,255,.26);
                      border-radius: 999px;
                    }
                    .tile-actions {
                      display: flex;
                      flex-direction: row;
                      align-items: center;
                      justify-content: flex-end;
                      flex-wrap: nowrap;
                      gap: 4px;
                    }
                    .run-button {
                      border: 1px solid rgba(255,255,255,.42);
                      border-radius: 999px;
                      color: white;
                      background: rgba(255,255,255,.22);
                      padding: 3px 8px;
                      font-size: 10px;
                      line-height: 1.25;
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
                      font-size: 23px;
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
                      border: 1px solid rgba(255,255,255,.32);
                      background: rgba(255,255,255,.16);
                      border-radius: 16px;
                      box-shadow: inset 0 1px 0 rgba(255,255,255,.18);
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
                      border: 1px solid rgba(255,255,255,.30);
                      border-radius: 17px;
                      background: rgba(255,255,255,.15);
                      box-shadow: inset 0 1px 0 rgba(255,255,255,.14);
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
                      border-radius: 0 0 28px 28px;
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
                      margin: 0 clamp(18px, 4vw, 56px) 26px;
                      padding: 18px;
                      border: 1px solid rgba(255,255,255,.58);
                      border-radius: 34px;
                      background:
                        linear-gradient(145deg, rgba(255,255,255,.56), rgba(255,255,255,.30)),
                        radial-gradient(circle at 0% 0%, hsla(var(--cluster-hue), 80%, 72%, .20), transparent 28%);
                      box-shadow:
                        18px 18px 44px rgba(99,102,241,.10),
                        -12px -12px 30px rgba(255,255,255,.68),
                        inset 0 1px 0 rgba(255,255,255,.66);
                      backdrop-filter: blur(16px);
                    }
                    .cluster-title {
                      display: flex;
                      align-items: center;
                      gap: var(--space-3);
                      margin: 0 0 16px;
                      width: 100%;
                      padding: 4px 6px;
                      border: none;
                      background: transparent;
                      color: inherit;
                      text-align: left;
                      cursor: pointer;
                      border-radius: var(--radius-md);
                      transition: background-color var(--motion-fast) var(--motion-ease-out);
                    }
                    .cluster-title:hover {
                      background: rgba(255,255,255,.36);
                    }
                    .cluster-title:focus-visible {
                      outline: none;
                      box-shadow: 0 0 0 3px rgba(37,99,235,.28);
                    }
                    .cluster-toggle {
                      display: inline-flex;
                      align-items: center;
                      justify-content: center;
                      width: 26px;
                      height: 26px;
                      flex: 0 0 auto;
                      border-radius: 999px;
                      background: linear-gradient(145deg, #ffffff, hsl(var(--cluster-hue) 76% 90%));
                      color: hsl(var(--cluster-hue) 44% 30%);
                      font: 900 14px/1 var(--font-mono);
                      box-shadow: 6px 6px 14px rgba(99,102,241,.16), inset 0 1px 0 #fff;
                      transition: transform var(--motion-base) var(--motion-ease-out);
                    }
                    .cluster-section.collapsed .cluster-toggle {
                      transform: rotate(-90deg);
                    }
                    .cluster-title h2 {
                      margin: 0;
                      font-size: 26px;
                      font-weight: 900;
                      letter-spacing: -.04em;
                      color: hsl(var(--cluster-hue) 44% 34%);
                    }
                    .cluster-title span {
                      color: var(--color-muted-fg);
                      font: 600 13px/1 var(--font-num);
                      font-variant-numeric: tabular-nums;
                    }
                    .cluster-section.collapsed .tile-grid {
                      display: none;
                    }
                    """
                + """
                    .task-modal {
                      position: fixed;
                      inset: 0;
                      z-index: 20;
                      display: none;
                      align-items: center;
                      justify-content: center;
                      padding: var(--space-3);
                      background: rgba(15,23,42,.58);
                      backdrop-filter: blur(12px);
                    }
                    .task-modal.open {
                      display: flex;
                      animation: task-modal-fade var(--motion-base) var(--motion-ease-out);
                    }
                    .task-modal.open .task-panel {
                      animation: task-panel-rise var(--motion-base) var(--motion-ease-out);
                    }
                    @keyframes task-modal-fade {
                      from { opacity: 0; }
                      to { opacity: 1; }
                    }
                    @keyframes task-panel-rise {
                      from { opacity: 0; transform: translateY(8px) scale(.98); }
                      to { opacity: 1; transform: translateY(0) scale(1); }
                    }
                    .task-panel {
                      position: relative;
                      display: flex;
                      flex-direction: column;
                      width: min(1680px, calc(100vw - 24px));
                      height: calc(100vh - 24px);
                      max-height: calc(100vh - 24px);
                      overflow: hidden;
                      border: 1px solid rgba(148,163,184,.32);
                      border-radius: var(--radius-xl);
                      background:
                        radial-gradient(circle at 0% 0%, rgba(37,99,235,.16), transparent 28%),
                        radial-gradient(circle at 100% 0%, rgba(14,165,233,.12), transparent 26%),
                        linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
                      color: var(--color-foreground);
                      box-shadow: 0 28px 90px rgba(15,23,42,.34);
                    }
                    .task-panel-head {
                      position: absolute;
                      top: 14px;
                      right: var(--space-5);
                      left: var(--space-5);
                      z-index: 1;
                      display: flex;
                      align-items: center;
                      justify-content: flex-end;
                      gap: var(--space-4);
                      pointer-events: none;
                    }
                    .task-panel-title {
                      display: none;
                      color: var(--color-muted-fg);
                      font: 700 12px/1 var(--font-num);
                      letter-spacing: .08em;
                      text-transform: uppercase;
                    }
                    .task-panel-close {
                      display: inline-flex;
                      align-items: center;
                      justify-content: center;
                      width: 36px;
                      height: 36px;
                      border: 1px solid var(--color-border-strong);
                      border-radius: 999px;
                      background: #ffffff;
                      color: var(--color-foreground);
                      font: 700 18px/1 var(--font-num);
                      cursor: pointer;
                      pointer-events: auto;
                    }
                    .task-panel-close:hover {
                      border-color: var(--color-ring);
                      color: var(--color-ring);
                    }
                    .task-panel-close:focus-visible {
                      outline: none;
                      box-shadow: 0 0 0 3px rgba(37,99,235,.35);
                    }
                    .task-shell {
                      display: grid;
                      grid-template-columns: minmax(310px, 380px) minmax(0, 1fr);
                      grid-template-rows: minmax(0, 1fr);
                      gap: 18px;
                      flex: 1 1 auto;
                      min-height: 0;
                      overflow: hidden;
                      padding: 18px;
                    }
                    .task-sidebar {
                      display: grid;
                      grid-template-columns: minmax(0, 1fr);
                      gap: 12px;
                      align-content: start;
                      min-width: 0;
                      min-height: 0;
                      overflow: auto;
                      padding-right: 2px;
                    }
                    .task-hero {
                      display: grid;
                      grid-template-columns: minmax(0, 1fr);
                      gap: 12px;
                      align-items: stretch;
                      min-width: 0;
                      padding: 16px;
                      border: 1px solid #dbeafe;
                      border-radius: 26px;
                      background:
                        radial-gradient(circle at 20% 0%, rgba(37,99,235,.14), transparent 34%),
                        linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
                      box-shadow: 0 18px 44px rgba(37,99,235,.09);
                    }
                    .task-hero > div {
                      min-width: 0;
                    }
                    .task-eyebrow {
                      margin: 0 0 6px;
                      color: #2563eb;
                      font-size: 12px;
                      font-weight: 800;
                      letter-spacing: .12em;
                      text-transform: uppercase;
                    }
                    .task-hero h2 {
                      margin: 0;
                      font-size: clamp(20px, 2.2vw, 26px);
                      line-height: 1.08;
                      letter-spacing: -.05em;
                      overflow-wrap: anywhere;
                    }
                    .task-trace {
                      display: none;
                    }
                    .task-toolbar {
                      display: grid;
                      grid-template-columns: 1fr 1fr;
                      gap: var(--space-2);
                      align-items: center;
                    }
                    .task-toolbar button,
                    .task-toolbar select {
                      border: 1px solid var(--color-border-strong);
                      border-radius: var(--radius-md);
                      background: rgba(255,255,255,.88);
                      color: var(--color-foreground);
                      padding: 11px 14px;
                      font: 600 14px/1.2 inherit;
                      transition: background-color var(--motion-fast) var(--motion-ease-out),
                                  border-color var(--motion-fast) var(--motion-ease-out),
                                  box-shadow var(--motion-fast) var(--motion-ease-out),
                                  transform var(--motion-fast) var(--motion-ease-out);
                    }
                    .task-toolbar select {
                      appearance: none;
                      -webkit-appearance: none;
                      grid-column: 1 / -1;
                      min-width: 0;
                      padding-right: 42px;
                      background:
                        linear-gradient(45deg, transparent 50%, #334155 50%) calc(100% - 20px) 50% / 6px 6px no-repeat,
                        linear-gradient(135deg, #334155 50%, transparent 50%) calc(100% - 16px) 50% / 6px 6px no-repeat,
                        rgba(255,255,255,.9);
                    }
                    .task-toolbar button {
                      cursor: pointer;
                    }
                    .task-toolbar button:hover:not(:disabled),
                    .task-toolbar select:hover {
                      border-color: var(--color-ring);
                      background: #ffffff;
                    }
                    .task-toolbar button:active:not(:disabled) {
                      transform: scale(.98);
                    }
                    .task-toolbar button:focus-visible,
                    .task-toolbar select:focus-visible {
                      outline: none;
                      box-shadow: 0 0 0 3px rgba(37,99,235,.35);
                      border-color: var(--color-ring);
                    }
                    .task-toolbar button:disabled {
                      cursor: not-allowed;
                      opacity: .45;
                    }
                    .task-primary {
                      border-color: var(--color-primary) !important;
                      background: linear-gradient(135deg, #2563eb, var(--color-primary)) !important;
                      color: var(--color-on-primary) !important;
                      box-shadow: 0 10px 24px rgba(37,99,235,.22);
                    }
                    .task-primary:hover:not(:disabled) {
                      background: linear-gradient(135deg, #1d4ed8, var(--color-primary-hover)) !important;
                      box-shadow: 0 12px 28px rgba(37,99,235,.32);
                    }
                    .task-close-button {
                      min-width: 0;
                    }
                    .task-summary {
                      display: grid;
                      grid-template-columns: 1fr;
                      gap: var(--space-2);
                    }
                    .task-stat {
                      min-width: 0;
                      padding: 10px 12px;
                      border: 1px solid var(--color-border);
                      border-radius: var(--radius-lg);
                      background: rgba(255,255,255,.78);
                    }
                    .task-stat span {
                      display: block;
                      color: var(--color-muted-fg);
                      font-size: 11px;
                      font-weight: 700;
                      letter-spacing: .08em;
                      text-transform: uppercase;
                    }
                    .task-stat strong {
                      display: block;
                      margin-top: 6px;
                      overflow-wrap: anywhere;
                      font: 600 14px/1.25 var(--font-num);
                      font-variant-numeric: tabular-nums;
                    }
                    .task-workspace {
                      display: grid;
                      grid-template-rows: minmax(0, 1fr);
                      gap: 0;
                      min-height: 0;
                      overflow: hidden;
                    }
                    .task-card {
                      min-width: 0;
                      border: 1px solid #dbe3ed;
                      border-radius: var(--radius-lg);
                      background: rgba(255,255,255,.86);
                      padding: 14px;
                      box-shadow: 0 12px 34px rgba(15,23,42,.06);
                    }
                    .task-card h3 {
                      margin: 0 0 var(--space-2);
                      font-size: 15px;
                      font-weight: 700;
                      letter-spacing: -.02em;
                    }
                    .task-card.execution-card {
                      display: grid;
                      grid-template-columns: 1fr;
                      gap: var(--space-2);
                      align-items: stretch;
                      align-self: stretch;
                      overflow: hidden;
                      padding-block: 12px;
                    }
                    .task-card.execution-card h3 {
                      margin: 0 0 2px;
                      white-space: nowrap;
                    }
                    .execution-card #task-execution {
                      max-height: 112px;
                      overflow-y: auto;
                      overflow-x: hidden;
                      padding-right: var(--space-1);
                    }
                    .task-card.completion-card {
                      display: grid;
                      grid-template-rows: auto minmax(0, 1fr);
                      gap: var(--space-3);
                      grid-column: span 1;
                      min-height: 0;
                      overflow: hidden;
                    }
                    #task-completion-meta {
                      min-height: 0;
                      overflow: visible;
                    }
                    .task-list {
                      display: grid;
                      gap: 8px;
                    }
                    .task-row {
                      min-width: 0;
                      border: 1px solid #e2e8f0;
                      border-radius: 16px;
                      background: #f8fafc;
                      padding: 10px;
                      overflow: hidden;
                    }
                    .task-row.compact {
                      padding: 10px;
                    }
                    .task-row-head {
                      display: flex;
                      align-items: center;
                      justify-content: space-between;
                      gap: 10px;
                      margin-bottom: 8px;
                      min-width: 0;
                    }
                    .task-name {
                      min-width: 0;
                      overflow: hidden;
                      text-overflow: ellipsis;
                      white-space: nowrap;
                      font-weight: 800;
                    }
                    .task-badge {
                      display: inline-flex;
                      align-items: center;
                      flex: 0 0 auto;
                      border-radius: 999px;
                      padding: 4px 9px;
                      background: #dbeafe;
                      color: #1d4ed8;
                      font-size: 11px;
                      font-weight: 850;
                      letter-spacing: .04em;
                      text-transform: uppercase;
                    }
                    .task-badge.completed {
                      background: var(--color-success-bg);
                      color: var(--color-success);
                    }
                    .task-badge.failed,
                    .task-badge.rejected,
                    .task-badge.timeout,
                    .task-badge.timed_out {
                      background: var(--color-danger-bg);
                      color: var(--color-danger);
                    }
                    .task-detail {
                      color: #475569;
                      font: 11px/1.55 var(--font-mono);
                      font-variant-numeric: tabular-nums;
                      overflow-wrap: anywhere;
                      word-break: break-word;
                    }
                    .completion-strip {
                      display: grid;
                      grid-template-columns: repeat(2, minmax(0, 1fr));
                      gap: var(--space-2);
                    }
                    .completion-strip div {
                      min-width: 0;
                      border: 1px solid var(--color-border);
                      border-radius: var(--radius-sm);
                      background: var(--color-surface-muted);
                      padding: 8px 10px;
                    }
                    .completion-strip span {
                      display: block;
                      color: var(--color-muted-fg);
                      font-size: 10px;
                      font-weight: 700;
                      letter-spacing: .08em;
                      text-transform: uppercase;
                    }
                    .completion-strip strong {
                      display: block;
                      margin-top: 4px;
                      overflow: hidden;
                      text-overflow: ellipsis;
                      white-space: nowrap;
                      font: 600 13px/1.3 var(--font-num);
                      font-variant-numeric: tabular-nums;
                    }
                    .incoming-task {
                      margin-bottom: 8px;
                      border-color: #bfdbfe;
                      background: #eff6ff;
                    }
                    .task-output {
                      min-height: 0;
                      height: auto;
                      align-self: stretch;
                      overflow: auto;
                      overflow-x: hidden;
                      border: 1px solid #dbe3ed;
                      border-radius: var(--radius-md);
                      background: var(--color-surface);
                    }
                    .task-output:focus-within {
                      border-color: var(--color-ring);
                      box-shadow: 0 0 0 3px rgba(37,99,235,.18);
                    }
                    .task-output-pre {
                      box-sizing: border-box;
                      min-height: 100%;
                      margin: 0;
                      padding: 16px 18px;
                      color: #1e293b;
                      white-space: pre-wrap;
                      overflow-wrap: anywhere;
                      word-break: break-word;
                      font: 12px/1.6 var(--font-mono);
                      font-variant-numeric: tabular-nums;
                    }
                    .task-output-lazy {
                      display: grid;
                      gap: 12px;
                      margin: 14px;
                      padding: 16px;
                      border: 1px dashed #bfdbfe;
                      border-radius: 16px;
                      background: #f8fbff;
                      color: #475569;
                    }
                    .task-output-lazy button {
                      justify-self: start;
                      border: 1px solid #bfdbfe;
                      border-radius: 999px;
                      background: #eff6ff;
                      color: #1d4ed8;
                      padding: 8px 12px;
                      font-weight: 800;
                      cursor: pointer;
                    }
                    .editor-hint {
                      color: #64748b;
                      font-size: 12px;
                      line-height: 1.5;
                    }
                    .task-empty {
                      border: 1px dashed #cbd5e1;
                      border-radius: 16px;
                      color: #64748b;
                      background: #f8fafc;
                      padding: 10px 12px;
                      font-size: 13px;
                    }
                    @media (max-width: 860px) {
                      .hero-shell {
                        grid-template-columns: 1fr;
                      }
                      .app-status {
                        grid-template-columns: repeat(2, minmax(0, 1fr));
                      }
                      .cluster-section {
                        margin-inline: 12px;
                        padding: 12px;
                      }
                      .tile-grid {
                        grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
                      }
                      .task-shell {
                        grid-template-columns: 1fr;
                        overflow: auto;
                      }
                      .task-sidebar {
                        grid-template-columns: 1fr;
                        overflow: visible;
                      }
                      .task-hero {
                        grid-template-columns: 1fr;
                      }
                      .task-summary {
                        grid-template-columns: 1fr;
                      }
                      .task-panel {
                        height: 92vh;
                      }
                    }
                  </style>
                </head>
                <body data-coordinator-id="__COORDINATOR_ID__">
                  <header>
                    <div class="hero-shell">
                      <section class="hero-card">
                        <p class="hero-eyebrow">Pulse 心跳平台</p>
                        <h1>统一查看主机心跳、任务与资源状态</h1>
                        <div class="subtitle">Coordinator __COORDINATOR_ID__ 通过精简消息机制承载任务管理、集群运维、资源管理、资源监控与告警。</div>
                        <div class="hero-actions">
                          <a class="hero-cta" href="#pulse-app">查看主机</a>
                          <a class="hero-secondary" href="#pulse-app">查看能力</a>
                        </div>
                      </section>
                      <aside class="demo-stack" aria-label="平台能力概览">
                        <section class="demo-card">
                          <h2>平台能力</h2>
                          <div class="catalog-preview">
                            <div class="catalog-pill"><strong>任务管理</strong><span>通过消息下发和回收执行结果。</span></div>
                            <div class="catalog-pill"><strong>集群运维</strong><span>按 cluster 与 group 编排节点动作。</span></div>
                            <div class="catalog-pill"><strong>资源管理</strong><span>按固定窗口聚合资源指标。</span></div>
                            <div class="catalog-pill"><strong>监控告警</strong><span>基于心跳识别异常节点与趋势。</span></div>
                          </div>
                        </section>
                        <section class="testimonial-card">
                          <h2>平台说明</h2>
                          <blockquote>统一消息面，统一心跳面，统一运维视图。页面只展示关键状态，避免无效噪声。</blockquote>
                          <cite>Pulse Coordinator</cite>
                        </section>
                      </aside>
                    </div>
                  </header>
                  <div id="pulse-status" class="app-status"></div>
                  <main id="pulse-app" data-framework="PulseView">
                    <section class="empty">正在加载主机状态...</section>
                  </main>
                  <div id="task-modal" class="task-modal" aria-hidden="true">
                    <section class="task-panel" role="dialog" aria-modal="true" aria-labelledby="task-title">
                      <div class="task-panel-head">
                        <div class="task-panel-title">远程任务</div>
                        <button id="task-close-x" class="task-panel-close" type="button" aria-label="关闭任务面板">×</button>
                      </div>
                      <div class="task-shell">
                        <aside class="task-sidebar">
                          <div class="task-hero">
                            <div>
                              <p class="task-eyebrow">任务执行</p>
                              <h2 id="task-title">执行任务</h2>
                              <div id="task-trace" class="task-trace">trace: pending</div>
                            </div>
                            <div class="task-toolbar">
                              <select id="task-type" aria-label="任务类型">
                                <option value="prepare_disk_layout_dry_run">prepare_disk_layout_dry_run</option>
                                <option value="analyze_block_layout_dry_run">analyze_block_layout_dry_run</option>
                              </select>
                              <button id="task-run" class="task-primary">执行 dry-run</button>
                              <button id="task-pop">弹出结果</button>
                              <button id="task-close" class="task-close-button" aria-label="关闭">关闭</button>
                            </div>
                          </div>
                          <div class="task-summary">
                            <div class="task-stat"><span>目标节点</span><strong id="task-agent">-</strong></div>
                            <div class="task-stat"><span>当前任务</span><strong id="task-current">空闲</strong></div>
                            <div class="task-stat"><span>结果队列</span><strong id="task-completion-count">0</strong></div>
                          </div>
                          <section class="task-card execution-card">
                            <h3>执行队列</h3>
                            <div id="task-execution"></div>
                          </section>
                          <section class="task-card completion-meta-card">
                            <h3>结果队列</h3>
                            <div id="task-completion-meta"></div>
                          </section>
                        </aside>
                        <main class="task-workspace">
                          <section class="task-card completion-card">
                            <h3>结果查看</h3>
                            <div id="task-output" class="task-output"></div>
                          </section>
                        </main>
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
                      const taskAgent = document.getElementById('task-agent');
                      const taskCurrent = document.getElementById('task-current');
                      const taskCompletionCount = document.getElementById('task-completion-count');
                      const taskExecution = document.getElementById('task-execution');
                      const taskCompletionMeta = document.getElementById('task-completion-meta');
                      const taskOutput = document.getElementById('task-output');
                      const taskType = document.getElementById('task-type');
                      const taskRun = document.getElementById('task-run');
                      const taskPop = document.getElementById('task-pop');
                      const taskClose = document.getElementById('task-close');
                      const taskCloseX = document.getElementById('task-close-x');
                      const loadAverageWindowMs = 5 * 60 * 1000;
                      const loadWindows = new Map();
                      let activeTaskAgentId = '';
                      let activeCompletionTaskId = '';
                      let activeRunTaskId = '';
                      let taskPollTimer = 0;
                      let taskSnapshotInFlight = false;
                      let activeTaskLabel = '';
                      let activeOutputText = '';
                      let renderedOutputText = null;
                      let renderOutputVersion = 0;
                      let outputEditor = null;
                      let monacoReady = null;
                      const largeOutputThreshold = 180000;
                      const previewOutputLimit = 60000;

                      const PulseView = {
                        state: {hosts: [], loading: true, error: null, updatedAt: null},
                        clusterSections: new Map(),
                        tiles: new Map(),
                        collapsedClusters: loadCollapsedClusters(),
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
                            this.renderMessage('error', '刷新 /api/hosts 失败：' + this.state.error);
                            return;
                          }
                          if (this.state.loading) {
                            this.renderMessage('empty', '正在加载主机状态...');
                            return;
                          }
                          if (!this.state.hosts.length) {
                            this.renderMessage('empty', '暂无主机心跳，请先写入 /heartbeat。');
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
                            const collapsed = this.collapsedClusters.has(cluster);
                            section.classList.toggle('collapsed', collapsed);
                            const titleBtn = section.querySelector('.cluster-title');
                            if (titleBtn) {
                              titleBtn.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
                            }
                            section.querySelector('[data-cluster-name]').textContent = cluster;
                            section.querySelector('[data-cluster-count]').textContent =
                              hosts.length + ' 台';
                            if (collapsed) {
                              // Skip DOM diffing of tiles when collapsed: avoids wide-area
                              // re-renders on the polling cadence.
                              section.querySelectorAll('[data-agent-id]').forEach(tile => {
                                this.tiles.delete(tile.dataset.agentId || '');
                              });
                              const grid = section.querySelector('.tile-grid');
                              if (grid && grid.firstChild) {
                                grid.replaceChildren();
                              }
                            } else {
                              this.updateTiles(section.querySelector('.tile-grid'), hosts);
                            }
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
                          section.innerHTML =
                            '<button type="button" class="cluster-title" aria-expanded="true">'
                            + '<span class="cluster-toggle" aria-hidden="true">v</span>'
                            + '<h2 data-cluster-name></h2><span data-cluster-count></span>'
                            + '</button><div class="tile-grid"></div>';
                          const titleBtn = section.querySelector('.cluster-title');
                          titleBtn.addEventListener('click', () => {
                            this.toggleCluster(cluster);
                          });
                          this.clusterSections.set(cluster, section);
                          return section;
                        },
                        toggleCluster(cluster) {
                          if (this.collapsedClusters.has(cluster)) {
                            this.collapsedClusters.delete(cluster);
                          } else {
                            this.collapsedClusters.add(cluster);
                          }
                          saveCollapsedClusters(this.collapsedClusters);
                          this.render();
                        },
                        updateTiles(grid, hosts) {
                          recordLoadSamples(hosts);
                          const sortedHosts = sortHosts(hosts);
                          const maxLoad = Math.max(0, ...sortedHosts.map(loadSortValue));
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
                        const updated = state.updatedAt ? state.updatedAt.toLocaleTimeString() : '等待中';
                        const aliveProgress = state.hosts.length ? Math.round((alive / state.hosts.length) * 100) : 0;
                        const expiredProgress = state.hosts.length ? Math.round((expired / state.hosts.length) * 100) : 0;
                        return `
                          <div class="status-card"><span>协调器</span><strong>${escapeHtml(coordinatorId)}</strong></div>
                          <div class="status-card"><span>主机总数</span><strong>${state.hosts.length} 台</strong><div class="progress-track"><i class="progress-fill" style="--progress:100%"></i></div></div>
                          <div class="status-card"><span>存活节点</span><strong>${alive} · ${aliveProgress}%</strong><div class="progress-track"><i class="progress-fill" style="--progress:${aliveProgress}%"></i></div></div>
                          <div class="status-card"><span>待处理</span><strong>${expired} · ${expiredProgress}%</strong><div class="progress-track"><i class="progress-fill" style="--progress:${expiredProgress}%"></i></div></div>
                          <div class="status-card"><span>最近刷新</span><strong>${escapeHtml(updated)}</strong></div>
                          <div class="status-card"><span>刷新模式</span><strong>Keyed DOM</strong></div>
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

                      const collapseStorageKey = 'pulse:collapsedClusters';
                      function loadCollapsedClusters() {
                        try {
                          const raw = window.localStorage.getItem(collapseStorageKey);
                          if (!raw) {
                            return new Set();
                          }
                          const parsed = JSON.parse(raw);
                          return new Set(Array.isArray(parsed) ? parsed : []);
                        } catch (error) {
                          return new Set();
                        }
                      }
                      function saveCollapsedClusters(set) {
                        try {
                          window.localStorage.setItem(collapseStorageKey, JSON.stringify([...set]));
                        } catch (error) {
                          /* storage unavailable: silently ignore */
                        }
                      }

                      function sortHosts(hosts) {
                        return [...hosts].sort((left, right) =>
                          loadSortValue(right) - loadSortValue(left)
                          || String(left.ip || '').localeCompare(String(right.ip || ''))
                          || String(left.agent_id || '').localeCompare(String(right.agent_id || '')));
                      }

                      function recordLoadSamples(hosts) {
                        const now = Date.now();
                        const activeAgents = new Set();
                        hosts.forEach(host => {
                          const agentId = host.agent_id || host.ip || '';
                          if (!agentId) {
                            return;
                          }
                          activeAgents.add(agentId);
                          const sample = loadValue(host);
                          const windowStart = now - (now % loadAverageWindowMs);
                          let state = loadWindows.get(agentId);
                          if (!state) {
                            loadWindows.set(agentId, {
                              windowStart,
                              sum: sample,
                              count: 1,
                              displayAvg: sample
                            });
                            return;
                          }
                          if (state.windowStart !== windowStart) {
                            state = {
                              windowStart,
                              sum: sample,
                              count: 1,
                              displayAvg: state.count ? state.sum / state.count : state.displayAvg
                            };
                            loadWindows.set(agentId, state);
                            return;
                          }
                          state.sum += sample;
                          state.count += 1;
                        });
                        [...loadWindows.keys()].forEach(agentId => {
                          if (!activeAgents.has(agentId)) {
                            loadWindows.delete(agentId);
                          }
                        });
                      }

                      function averageLoad(host) {
                        const agentId = host.agent_id || host.ip || '';
                        const state = loadWindows.get(agentId);
                        if (!state) {
                          return loadValue(host);
                        }
                        return state.displayAvg;
                      }

                      function loadSortValue(host) {
                        return averageLoad(host);
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
                                <button class="run-button" data-action="run-task" type="button">任务</button>
                              </div>
                            </div>
                            <div class="tile-host" data-field="ip_title"></div>
                            <div class="tile-meta">
                              <div><span>5min AVG</span><span data-field="load_avg"></span></div>
                              <div><span>区域</span><span data-field="area"></span></div>
                              <div><span>确认</span><span data-field="confirmations"></span></div>
                            </div>
                            <div class="worker-list" data-field="workers"></div>
                          </div>
                          <div class="load-bar" aria-hidden="true"></div>
                        `;
                        return tile;
                      }

                      function updateTile(tile, host, rank, maxLoad) {
                        const load = loadSortValue(host);
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
                        setText(tile, 'ip_title', host.ip || '未知 IP');
                        setText(tile, 'load_avg', averageLoad(host).toFixed(2));
                        setText(tile, 'area', host.area || '');
                        setText(tile, 'confirmations', String(host.heartbeat_confirmations || 0) + '/3，20s');
                        renderWorkers(tile.querySelector('[data-field="workers"]'), tideWorkers(host));
                      }

                      function tideWorkers(host) {
                        const workers = host.state && Array.isArray(host.state.tide_workers) ? host.state.tide_workers : [];
                        return workers.filter(Boolean);
                      }

                      function renderWorkers(container, workers) {
                        if (!workers.length) {
                          container.innerHTML = '<div class="worker-card">未发现 tide_worker</div>';
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
                        activeRunTaskId = '';
                        activeTaskLabel = agentId || '-';
                        activeOutputText = '';
                        taskTitle.textContent = '执行任务';
                        taskTrace.textContent = 'trace: 等待中';
                        taskAgent.textContent = activeTaskLabel;
                        taskCurrent.textContent = '加载中';
                        taskCompletionCount.textContent = '0';
                        taskCompletionMeta.innerHTML = '<div class="task-empty">正在等待任务快照...</div>';
                        renderOutput('');
                        taskModal.classList.add('open');
                        taskModal.setAttribute('aria-hidden', 'false');
                        startTaskPolling();
                        await refreshTaskSnapshot({silent: false});
                      }

                      function startTaskPolling() {
                        stopTaskPolling();
                        taskPollTimer = window.setInterval(() => {
                          refreshTaskSnapshot({silent: true});
                        }, 2000);
                      }

                      function stopTaskPolling() {
                        if (taskPollTimer) {
                          window.clearInterval(taskPollTimer);
                          taskPollTimer = 0;
                        }
                      }

                      async function refreshTaskSnapshot(options = {}) {
                        if (!activeTaskAgentId || taskSnapshotInFlight) {
                          return;
                        }
                        taskSnapshotInFlight = true;
                        try {
                          const response = await fetch('/api/agents/' + encodeURIComponent(activeTaskAgentId) + '/tasks', {cache: 'no-store'});
                          if (!response.ok) {
                            throw new Error('task snapshot HTTP ' + response.status);
                          }
                          renderTaskSnapshot(await response.json());
                        } catch (error) {
                          if (!options.silent) {
                            taskCompletionMeta.innerHTML = '<div class="task-empty">刷新任务快照失败：' + escapeHtml(error.message || String(error)) + '</div>';
                          }
                        } finally {
                          taskSnapshotInFlight = false;
                        }
                      }

                      function renderTaskSnapshot(snapshot) {
                        const execution = snapshot.execution_queue || [];
                        const completions = snapshot.completion_queue || [];
                        const asyncTasks = activeHostAsyncTasks();
                        const currentExecution = activeRunTaskId
                          ? execution.find(task => task.task_id === activeRunTaskId)
                          : (execution[execution.length - 1] || null);
                        const currentCompletion = activeRunTaskId
                          ? completions.find(result => result.task_id === activeRunTaskId)
                          : null;
                        const latest = currentCompletion || completions[completions.length - 1] || null;
                        const traceId = (latest && latest.trace_id)
                          || (currentExecution && currentExecution.trace_id)
                          || latestTraceId(snapshot.traces)
                          || '等待中';
                        activeCompletionTaskId = latest ? latest.task_id : '';
                        taskTrace.textContent = 'trace: ' + traceId;
                        taskCurrent.textContent = currentCompletion
                          ? (currentCompletion.status || 'completed')
                          : (currentExecution ? (currentExecution.status || 'queued') : (latest ? '上一条结果' : '空闲'));
                        taskCompletionCount.textContent = String(completions.length);
                        taskPop.disabled = !activeCompletionTaskId;
                        taskExecution.innerHTML = renderIncomingTasks(asyncTasks)
                          + (execution.length ? '<div class="task-list">'
                          + execution.map(renderExecutionTask).join('')
                          + '</div>' : '<div class="task-empty">当前没有待执行任务。来自 agent 心跳的任务会显示在上方。</div>');
                        if (latest) {
                          taskCompletionMeta.innerHTML = renderCompletionMeta(latest, activeRunTaskId && latest.task_id !== activeRunTaskId);
                          const outputText = taskOutputText(latest);
                          renderOutput(outputText);
                        } else {
                          taskCompletionMeta.innerHTML = '<div class="task-empty">暂无结果。面板打开期间每 2 秒自动刷新一次。</div>';
                          renderOutput(currentExecution
                            ? '正在等待 agent 心跳返回任务结果：' + (currentExecution.task_id || 'task') + '...'
                            : '');
                        }
                      }

                      function renderExecutionTask(task) {
                        return `
                          <div class="task-row compact">
                            <div class="task-row-head">
                              <div class="task-name">${escapeHtml(task.task_type || '')}</div>
                              <span class="task-badge ${escapeHtml(task.status || '')}">${escapeHtml(task.status || '')}</span>
                            </div>
                            <div class="task-detail">
                              task: ${escapeHtml(task.task_id || '')}<br>
                              trace: ${escapeHtml(task.trace_id || '')}<br>
                              delivered: ${escapeHtml(formatTime(task.delivered_at_ms))}<br>
                              accepted: ${escapeHtml(formatTime(task.accepted_at_ms))}
                            </div>
                          </div>
                        `;
                      }

                      function renderIncomingTasks(tasks) {
                        if (!tasks.length) {
                          return '';
                        }
                        return '<div class="task-list">'
                          + tasks.map(task => `
                            <div class="task-row compact incoming-task">
                              <div class="task-row-head">
                                <div class="task-name">待回收结果</div>
                                <span class="task-badge ${escapeHtml(task.status || '')}">${escapeHtml(task.status || '')}</span>
                              </div>
                              <div class="task-detail">
                                ${escapeHtml(task.task_type || '')}<br>
                                trace: ${escapeHtml(task.trace_id || '')}<br>
                                started: ${escapeHtml(formatTime(task.started_at_ms))}
                              </div>
                            </div>
                          `).join('')
                          + '</div>';
                      }

                      function renderCompletionMeta(result, showingPreviousResult) {
                        const previousNotice = showingPreviousResult
                          ? '<div class="task-empty">当前任务仍在等待中，先展示最近一次保留结果。</div>'
                          : '';
                        return previousNotice + `
                          <div class="completion-strip">
                            <div><span>状态</span><strong>${escapeHtml(result.status || '')}</strong></div>
                            <div><span>退出码</span><strong>${escapeHtml(result.exit_code ?? '-')}</strong></div>
                            <div><span>耗时</span><strong>${escapeHtml(result.duration_ms || 0)}ms</strong></div>
                            <div><span>结束时间</span><strong>${escapeHtml(formatTime(result.finished_at_ms))}</strong></div>
                            <div><span>类型</span><strong>${escapeHtml(result.output_type || detectOutputType(taskOutputText(result)))}</strong></div>
                            <div><span>编码</span><strong>${escapeHtml(result.output_encoding || '-')}</strong></div>
                            <div><span>字节数</span><strong>${escapeHtml(result.output_bytes ?? '-')}</strong></div>
                            <div><span>SHA256</span><strong title="${escapeHtml(result.output_sha256 || '')}">${escapeHtml(shortHash(result.output_sha256 || ''))}</strong></div>
                          </div>
                        `;
                      }

                      function activeHostAsyncTasks() {
                        const host = PulseView.state.hosts.find(item => (item.agent_id || '') === activeTaskAgentId);
                        const tasks = host && host.state && Array.isArray(host.state.async_tasks) ? host.state.async_tasks : [];
                        return activeRunTaskId ? tasks.filter(task => task.task_id === activeRunTaskId) : tasks;
                      }

                      function taskOutputText(result) {
                        const runnerError = result.runner_error || '';
                        return result.output || runnerError || '';
                      }

                      function outputLengthLabel(result) {
                        const length = taskOutputText(result).length;
                        return length + ' chars';
                      }

                      function renderOutput(text) {
                        activeOutputText = text || '';
                        if (activeOutputText === renderedOutputText) {
                          if (outputEditor) {
                            window.requestAnimationFrame(() => outputEditor && outputEditor.layout());
                          }
                          return;
                        }
                        renderedOutputText = activeOutputText;
                        const version = ++renderOutputVersion;
                        disposeOutputEditor();
                        if (!activeOutputText) {
                          taskOutput.replaceChildren();
                          return;
                        }
                        if (activeOutputText.length > largeOutputThreshold) {
                          renderLazyOutputPreview(activeOutputText);
                          return;
                        }
                        taskOutput.innerHTML = '<pre class="task-output-pre">' + escapeHtml(activeOutputText) + '</pre>';
                        scheduleIdle(() => {
                          if (version !== renderOutputVersion) {
                            return;
                          }
                          const language = detectOutputType(activeOutputText);
                          setupMonacoEditor().then(() => {
                            if (version !== renderOutputVersion) {
                              return;
                            }
                            updateMonacoValue(activeOutputText, language, true);
                          });
                        });
                      }

                      function renderLazyOutputPreview(text) {
                        const language = detectOutputType(text);
                        const preview = text.slice(0, previewOutputLimit);
                        taskOutput.innerHTML = `
                          <div class="task-output-lazy">
                            <div>
                              当前 ${escapeHtml(language)} 输出较大，结果已完整保存在内存中（${text.length} 字符）。
                              为保持页面响应，编辑器按需加载。
                            </div>
                            <button type="button" id="task-load-editor">加载完整结果</button>
                          </div>
                          <pre class="task-output-pre">${escapeHtml(preview)}\n\n... 当前仅展示预览，点击“加载完整结果”查看全部输出。</pre>
                        `;
                        const loadButton = document.getElementById('task-load-editor');
                        if (loadButton) {
                          loadButton.onclick = () => {
                            const version = ++renderOutputVersion;
                            taskOutput.innerHTML = '<pre class="task-output-pre">正在加载编辑器...</pre>';
                            scheduleIdle(() => {
                              if (version !== renderOutputVersion) {
                                return;
                              }
                              setupMonacoEditor().then(() => updateMonacoValue(text, language, false));
                            });
                          };
                        }
                      }

                      function updateMonacoValue(text, language, allowAutoFormat) {
                          if (!outputEditor || !window.monaco) {
                            taskOutput.innerHTML = '<pre class="task-output-pre">' + escapeHtml(text) + '</pre>';
                            return;
                          }
                          const model = outputEditor.getModel();
                          const viewState = outputEditor.saveViewState();
                          const scrollTop = outputEditor.getScrollTop();
                          const scrollLeft = outputEditor.getScrollLeft();
                          model.setValue(text);
                          window.monaco.editor.setModelLanguage(model, language);
                          outputEditor.layout();
                          if (viewState) {
                            outputEditor.restoreViewState(viewState);
                          }
                          outputEditor.setScrollPosition({scrollTop, scrollLeft});
                          if (allowAutoFormat && language === 'json' && text.length <= 100000) {
                            window.setTimeout(() => {
                              const formatAction = outputEditor.getAction('editor.action.formatDocument');
                              if (formatAction) {
                                const beforeFormatState = outputEditor.saveViewState();
                                const beforeFormatTop = outputEditor.getScrollTop();
                                const beforeFormatLeft = outputEditor.getScrollLeft();
                                formatAction.run().then(() => {
                                  if (beforeFormatState) {
                                    outputEditor.restoreViewState(beforeFormatState);
                                  }
                                  outputEditor.setScrollPosition({scrollTop: beforeFormatTop, scrollLeft: beforeFormatLeft});
                                });
                              }
                            }, 0);
                          }
                      }

                      function scheduleIdle(callback) {
                        if ('requestIdleCallback' in window) {
                          window.requestIdleCallback(callback, {timeout: 1200});
                          return;
                        }
                        window.setTimeout(callback, 0);
                      }

                      function disposeOutputEditor() {
                        if (outputEditor) {
                          outputEditor.dispose();
                          outputEditor = null;
                        }
                      }

                      function detectOutputType(text) {
                        const trimmed = String(text || '').trim();
                        if (!trimmed) {
                          return 'text';
                        }
                        if (trimmed.length > 100000) {
                          return (trimmed.startsWith('{') || trimmed.startsWith('[')) ? 'json' : 'text';
                        }
                        try {
                          JSON.parse(trimmed);
                          return 'json';
                        } catch (ignored) {
                          return 'text';
                        }
                      }

                      function setupMonacoEditor() {
                        if (outputEditor) {
                          return Promise.resolve(outputEditor);
                        }
                        if (monacoReady) {
                          return monacoReady.then(createOutputEditor);
                        }
                        monacoReady = new Promise(resolve => {
                          ensureMonacoLoader(() => {
                            if (!window.require) {
                              resolve(null);
                              return;
                            }
                            window.require.config({paths: {vs: 'https://cdn.jsdelivr.net/npm/monaco-editor@0.49.0/min/vs'}});
                            window.require(['vs/editor/editor.main'], () => resolve(window.monaco));
                          });
                        });
                        return monacoReady.then(createOutputEditor);
                      }

                      function ensureMonacoLoader(callback) {
                        if (window.require) {
                          callback();
                          return;
                        }
                        const existing = document.querySelector('script[data-monaco-loader]');
                        if (existing) {
                          existing.addEventListener('load', callback, {once: true});
                          return;
                        }
                        const script = document.createElement('script');
                        script.src = 'https://cdn.jsdelivr.net/npm/monaco-editor@0.49.0/min/vs/loader.js';
                        script.dataset.monacoLoader = 'true';
                        script.onload = callback;
                        script.onerror = callback;
                        document.head.appendChild(script);
                      }

                      function createOutputEditor() {
                        if (outputEditor || !window.monaco) {
                          return outputEditor;
                        }
                        taskOutput.replaceChildren();
                        outputEditor = window.monaco.editor.create(taskOutput, {
                              value: activeOutputText,
                              language: detectOutputType(activeOutputText),
                              readOnly: true,
                              theme: 'vs',
                              automaticLayout: true,
                              scrollBeyondLastLine: false,
                              minimap: {enabled: false},
                              wordWrap: 'on',
                              wrappingStrategy: 'advanced',
                              wrappingIndent: 'same',
                              renderLineHighlight: 'none',
                              contextmenu: true,
                              lineNumbersMinChars: 3,
                              glyphMargin: false,
                              folding: false,
                              overviewRulerLanes: 0,
                              scrollbar: {
                                vertical: 'visible',
                                horizontal: 'hidden',
                                useShadows: false
                              }
                            });
                        return outputEditor;
                      }

                      function shortHash(hash) {
                        return hash ? hash.slice(0, 12) : '-';
                      }

                      function latestTraceId(traces) {
                        if (!Array.isArray(traces) || !traces.length) {
                          return '';
                        }
                        const latest = traces[traces.length - 1] || {};
                        return latest.trace_id || '';
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
                          renderOutput('执行失败：HTTP ' + response.status);
                          return;
                        }
                        const snapshot = await response.json();
                        const execution = snapshot.execution_queue || [];
                        const latestTask = execution[execution.length - 1] || null;
                        activeRunTaskId = latestTask ? latestTask.task_id : '';
                        renderTaskSnapshot(snapshot);
                        startTaskPolling();
                      };

                      taskPop.onclick = async () => {
                        if (activeTaskAgentId && activeCompletionTaskId) {
                          const response = await fetch('/api/agents/' + encodeURIComponent(activeTaskAgentId) + '/tasks/completions/' + encodeURIComponent(activeCompletionTaskId) + '/pop', {method: 'POST'});
                          if (response.ok) {
                            renderTaskSnapshot(await response.json());
                          }
                        }
                      };

                      function closeTaskModal() {
                        stopTaskPolling();
                        taskModal.classList.remove('open');
                        taskModal.setAttribute('aria-hidden', 'true');
                        activeTaskAgentId = '';
                        activeRunTaskId = '';
                        activeCompletionTaskId = '';
                      }
                      taskClose.onclick = closeTaskModal;
                      taskCloseX.onclick = closeTaskModal;
                      taskModal.addEventListener('click', event => {
                        if (event.target === taskModal) {
                          closeTaskModal();
                        }
                      });
                      window.addEventListener('keydown', event => {
                        if (event.key === 'Escape' && taskModal.classList.contains('open')) {
                          closeTaskModal();
                        }
                      });

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

                      function formatTime(value) {
                        const millis = Number(value);
                        return Number.isFinite(millis) && millis > 0 ? new Date(millis).toLocaleString() : '-';
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
