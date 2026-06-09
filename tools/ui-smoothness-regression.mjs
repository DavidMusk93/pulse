#!/usr/bin/env node
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';

const args = parseArgs(process.argv.slice(2));
const url = args.url || 'http://127.0.0.1:9966/';
const durationMs = Number(args.durationMs || args.duration || 8000);
const waitMs = Number(args.waitMs || args.wait || 2000);
const maxP95FrameMs = Number(args.maxP95FrameMs || 24);
const maxLongTaskCount = Number(args.maxLongTaskCount || 0);
const maxLongTaskMs = Number(args.maxLongTaskMs || 80);

if (args.help) {
  console.log(`Usage: node tools/ui-smoothness-regression.mjs --url <url> [--duration-ms 8000]

Budgets:
  --max-p95-frame-ms 24
  --max-long-task-count 0
  --max-long-task-ms 80

The script launches Chrome, auto-scrolls the page, and fails when the UI exceeds the smoothness budget.`);
  process.exit(0);
}

async function main() {
  const chromePath = args.chrome || findChrome();
  if (!chromePath) {
    console.error('Chrome not found. Pass --chrome /path/to/chrome.');
    process.exit(2);
  }

  const userDataDir = await mkdtemp(join(tmpdir(), 'pulse-ui-smoothness-'));
  let chrome;
  try {
    chrome = spawn(chromePath, [
      `--user-data-dir=${userDataDir}`,
      '--headless=new',
      '--disable-gpu',
      '--disable-background-timer-throttling',
      '--disable-backgrounding-occluded-windows',
      '--disable-renderer-backgrounding',
      '--remote-debugging-port=0',
      'about:blank',
    ], { stdio: ['ignore', 'ignore', 'pipe'] });

    chrome.stderr.on('data', chunk => {
      const text = String(chunk);
      if (args.verbose && text.trim()) process.stderr.write(text);
    });

    const port = await waitForDevToolsPort(userDataDir);
    const target = await findPageTarget(port);
    const cdp = await CdpClient.connect(target.webSocketDebuggerUrl);
    try {
      await cdp.send('Page.enable');
      await cdp.send('Runtime.enable');
      await cdp.send('Page.navigate', { url });
      await waitForPageReady(cdp, waitMs);
      const result = await runScrollBenchmark(cdp, durationMs);
      const report = summarize(result, { url, durationMs, waitMs });
      console.log(JSON.stringify(report, null, 2));
      const failures = [];
      if (report.frame.p95_ms > maxP95FrameMs) {
        failures.push(`frame p95 ${report.frame.p95_ms}ms > ${maxP95FrameMs}ms`);
      }
      if (report.long_tasks.count > maxLongTaskCount) {
        failures.push(`long tasks ${report.long_tasks.count} > ${maxLongTaskCount}`);
      }
      if (report.long_tasks.max_ms > maxLongTaskMs) {
        failures.push(`long task max ${report.long_tasks.max_ms}ms > ${maxLongTaskMs}ms`);
      }
      if (failures.length) {
        console.error('UI smoothness budget failed: ' + failures.join('; '));
        process.exitCode = 1;
      }
    } finally {
      cdp.close();
    }
  } finally {
    await terminateChrome(chrome);
    await rm(userDataDir, { recursive: true, force: true });
  }
}

function parseArgs(values) {
  const parsed = {};
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (!value.startsWith('--')) continue;
    const key = value.slice(2).replace(/-([a-z])/g, (_, char) => char.toUpperCase());
    const next = values[index + 1];
    if (!next || next.startsWith('--')) {
      parsed[key] = true;
    } else {
      parsed[key] = next;
      index += 1;
    }
  }
  return parsed;
}

function findChrome() {
  const candidates = [
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    process.env.CHROME_BIN,
  ].filter(Boolean);
  return candidates.find(Boolean);
}

async function waitForDevToolsPort(userDataDir) {
  const file = join(userDataDir, 'DevToolsActivePort');
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    try {
      const text = await readFile(file, 'utf8');
      const [port] = text.trim().split('\n');
      if (port) return Number(port);
    } catch {
      await sleep(100);
    }
  }
  throw new Error('Chrome DevTools port was not created');
}

async function findPageTarget(port) {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    const targets = await fetchJson(`http://127.0.0.1:${port}/json`);
    const page = targets.find(target => target.type === 'page' && target.webSocketDebuggerUrl);
    if (page) return page;
    await sleep(100);
  }
  throw new Error('Chrome page target was not created');
}

async function waitForPageReady(cdp, waitMs) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    const ready = await cdp.evaluate('document.readyState === "complete"');
    if (ready) break;
    await sleep(100);
  }
  await sleep(waitMs);
}

async function runScrollBenchmark(cdp, durationMs) {
  const expression = `(() => new Promise(resolve => {
    const durationMs = ${JSON.stringify(durationMs)};
    const frames = [];
    const longTasks = [];
    let observer = null;
    try {
      observer = new PerformanceObserver(list => {
        for (const entry of list.getEntries()) {
          longTasks.push({ start: entry.startTime, duration: entry.duration, name: entry.name });
        }
      });
      observer.observe({ type: 'longtask' });
    } catch (error) {}
    const scrollRoot = document.scrollingElement || document.documentElement;
    const maxY = Math.max(0, scrollRoot.scrollHeight - window.innerHeight);
    let start = 0;
    let last = 0;
    function frame(now) {
      if (!start) {
        start = now;
        last = now;
      }
      frames.push(now - last);
      last = now;
      const elapsed = now - start;
      const phase = (elapsed % 3000) / 3000;
      const direction = Math.floor(elapsed / 3000) % 2 === 0 ? phase : 1 - phase;
      window.scrollTo(0, Math.round(maxY * direction));
      if (elapsed < durationMs) {
        requestAnimationFrame(frame);
      } else {
        if (observer) observer.disconnect();
        resolve({
          frames,
          longTasks,
          scrollHeight: scrollRoot.scrollHeight,
          viewportHeight: window.innerHeight,
          visibleHostTiles: document.querySelectorAll('.host-tile').length,
          userAgent: navigator.userAgent
        });
      }
    }
    requestAnimationFrame(frame);
  }))()`;
  return cdp.evaluate(expression, true);
}

function summarize(result, context) {
  const frames = result.frames.slice(1).filter(value => Number.isFinite(value) && value >= 0).sort((a, b) => a - b);
  const longTasks = result.longTasks.map(item => item.duration).filter(Number.isFinite).sort((a, b) => a - b);
  const p95 = percentile(frames, 95);
  return {
    ...context,
    scroll_height: result.scrollHeight,
    viewport_height: result.viewportHeight,
    host_tiles: result.visibleHostTiles,
    frame: {
      count: frames.length,
      avg_ms: round(avg(frames)),
      p95_ms: round(p95),
      max_ms: round(frames[frames.length - 1] || 0),
      over_24ms: frames.filter(value => value > 24).length,
      over_50ms: frames.filter(value => value > 50).length,
    },
    long_tasks: {
      count: longTasks.length,
      max_ms: round(longTasks[longTasks.length - 1] || 0),
      total_ms: round(longTasks.reduce((sum, value) => sum + value, 0)),
    },
    user_agent: result.userAgent,
  };
}

function percentile(values, p) {
  if (!values.length) return 0;
  const index = Math.min(values.length - 1, Math.max(0, Math.ceil(values.length * p / 100) - 1));
  return values[index];
}

function avg(values) {
  return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0;
}

function round(value) {
  return Math.round(value * 10) / 10;
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function terminateChrome(chrome) {
  if (!chrome || chrome.exitCode !== null || chrome.signalCode !== null) return;
  const exited = new Promise(resolve => chrome.once('exit', resolve));
  chrome.kill('SIGTERM');
  await Promise.race([exited, sleep(1000)]);
  if (chrome.exitCode === null && chrome.signalCode === null) {
    chrome.kill('SIGKILL');
    await Promise.race([exited, sleep(1000)]);
  }
}

async function fetchJson(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url} ${response.status}`);
  return response.json();
}

class CdpClient {
  static connect(url) {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(url);
      const client = new CdpClient(socket);
      socket.addEventListener('open', () => resolve(client), { once: true });
      socket.addEventListener('error', reject, { once: true });
    });
  }

  constructor(socket) {
    this.socket = socket;
    this.nextId = 1;
    this.pending = new Map();
    socket.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      if (!message.id) return;
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(message.error.message));
      else pending.resolve(message.result);
    });
  }

  send(method, params = {}) {
    const id = this.nextId++;
    this.socket.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
    });
  }

  async evaluate(expression, awaitPromise = false) {
    const result = await this.send('Runtime.evaluate', {
      expression,
      awaitPromise,
      returnByValue: true,
    });
    if (result.exceptionDetails) {
      throw new Error(result.exceptionDetails.text || 'Runtime.evaluate failed');
    }
    return result.result.value;
  }

  close() {
    this.socket.close();
  }
}

await main();
