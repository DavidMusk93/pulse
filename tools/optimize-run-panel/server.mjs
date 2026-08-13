#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process';
import {
  createReadStream,
  existsSync,
  mkdirSync,
  readFileSync,
  statSync,
  watch,
  writeFileSync
} from 'node:fs';
import http from 'node:http';
import { extname, join, relative, resolve } from 'node:path';
import process from 'node:process';
import { compatibleRunner, semanticFingerprint } from './model.mjs';

const repo = resolve(process.env.OPTIMIZE_REPO_ROOT || process.cwd());
const bind = process.env.OPTIMIZE_RUN_UI_BIND || '127.0.0.1';
const port = Number(process.env.OPTIMIZE_RUN_UI_PORT || 19876);
const runRoot = resolve(
  repo,
  process.env.OPTIMIZE_RUN_ROOT
    || '.context/compound-engineering/ce-optimize/host-sse-core-v3'
);
const hasExperimentWorktree = Boolean(process.env.OPTIMIZE_WORKTREE);
const worktree = resolve(
  repo,
  process.env.OPTIMIZE_WORKTREE || '.'
);
const staticRoot = resolve(repo, 'tools/optimize-run-panel/public');
const evaluator = join(worktree, relative(repo, runRoot), 'evaluate.mjs');
const stateRoot = resolve(repo, '.tmp/data/optimize-run-panel');
const specAtStart = yaml(join(runRoot, 'spec.yaml')) || {};
const logAtStart = yaml(join(runRoot, 'experiment-log.yaml')) || {};
const runId = logAtStart.run_id || specAtStart.name || 'unknown-run';
const stateFileName = runId.replaceAll(/[^a-zA-Z0-9._-]/g, '_');
const latestResultPath = join(stateRoot, `${stateFileName}.json`);
const clients = new Set();
const runner = {
  status: 'idle',
  startedAt: null,
  finishedAt: null,
  output: [],
  metrics: null,
  error: null
};
if (existsSync(latestResultPath)) {
  try {
    const persisted = JSON.parse(readFileSync(latestResultPath, 'utf8'));
    const head = git(['rev-parse', 'HEAD'], worktree);
    if (compatibleRunner(persisted, runId, head)) {
      Object.assign(runner, persisted);
    }
  } catch {
    // A partial local review artifact must not prevent the UI from starting.
  }
}
let revision = 0;
let notifyTimer = null;
let lastFingerprint = '';
let currentSnapshot = null;

function yaml(path) {
  if (!existsSync(path)) return null;
  const script = [
    'require "yaml"',
    'require "json"',
    'value = YAML.load_file(ARGV.fetch(0))',
    'puts JSON.generate(value)'
  ].join(';');
  const result = spawnSync('ruby', ['-e', script, path], {
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024
  });
  if (result.status !== 0) {
    return { parse_error: result.stderr.trim() || 'YAML parse failed' };
  }
  return JSON.parse(result.stdout);
}

function git(args, cwd = repo) {
  const result = spawnSync('git', args, { cwd, encoding: 'utf8' });
  return result.status === 0 ? result.stdout.trim() : '';
}

function worktreeFiles() {
  if (!hasExperimentWorktree || !existsSync(worktree)) return [];
  return git(['status', '--short'], worktree)
    .split('\n')
    .filter(Boolean)
    .map(line => ({
      status: line.slice(0, 2).trim() || '?',
      path: line.slice(3)
    }))
    .filter(file => !file.path.startsWith('.trae/'));
}

function phase(files, log) {
  if (runner.status === 'running') return 'measuring';
  if (runner.status === 'failed') return 'measurement_failed';
  if ((log?.experiments || []).some(experiment => experiment.outcome === 'kept')) return 'kept';
  if (runner.metrics) return 'measurement_ready';
  if (files.some(file => file.path.endsWith('HostStreamV3Codec.java'))) return 'codec_ready';
  if ((log?.experiments || []).length) return 'experiment_recorded';
  return 'baseline_ready';
}

function readSnapshot() {
  const spec = yaml(join(runRoot, 'spec.yaml')) || {};
  const log = yaml(join(runRoot, 'experiment-log.yaml')) || {};
  const result = hasExperimentWorktree ? yaml(join(worktree, 'result.yaml')) : null;
  const files = worktreeFiles();
  const head = git(['rev-parse', 'HEAD'], worktree);
  const activeRunner = compatibleRunner(runner, log.run_id, head)
    ? { ...runner }
    : { status: 'idle', metrics: null, output: [], error: null };
  return {
    isolation: {
      local_only: bind === '127.0.0.1',
      bind,
      production_api_changed: false,
      production_assets_changed: false,
      deployed: false
    },
    run: {
      run_id: log.run_id || null,
      name: spec.name || 'host-sse-core-v3',
      description: spec.description || '',
      branch: git(['branch', '--show-current'], worktree),
      head: head.slice(0, 7),
      phase: phase(files, log),
      started_at: log.started_at || null,
      max_iterations: spec.stopping?.max_iterations || null,
      experiments: log.experiments || [],
      backlog: log.hypothesis_backlog || [],
      primary: spec.metric?.primary || null,
      gates: spec.metric?.degenerate_gates || [],
      constraints: spec.constraints || [],
      benchmark_available: existsSync(evaluator)
    },
    baseline: log.baseline || null,
    best: log.best || null,
    experiment: {
      worktree: hasExperimentWorktree ? worktree : null,
      exists: hasExperimentWorktree && existsSync(worktree),
      files,
      result
    },
    runner: activeRunner
  };
}

function snapshot() {
  const next = readSnapshot();
  const fingerprint = semanticFingerprint(next);
  if (!currentSnapshot || fingerprint !== lastFingerprint) {
    lastFingerprint = fingerprint;
    currentSnapshot = {
      ...next,
      revision: ++revision,
      generated_at: new Date().toISOString()
    };
  }
  return currentSnapshot;
}

function writeJson(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'content-length': Buffer.byteLength(body)
  });
  response.end(body);
}

function broadcast() {
  const previousRevision = currentSnapshot?.revision;
  const value = snapshot();
  if (previousRevision === value.revision) return;
  const event = `event: run.snapshot\ndata: ${JSON.stringify(value)}\n\n`;
  for (const response of clients) {
    response.write(event);
  }
}

function scheduleBroadcast() {
  clearTimeout(notifyTimer);
  notifyTimer = setTimeout(broadcast, 120);
}

function appendOutput(stream, chunk) {
  const lines = chunk.toString('utf8').split(/\r?\n/).filter(Boolean);
  runner.output.push(...lines);
  runner.output = runner.output.slice(-80);
  for (let index = lines.length - 1; index >= 0; index--) {
    try {
      const value = JSON.parse(lines[index]);
      if (value && typeof value === 'object') {
        runner.metrics = value;
        break;
      }
    } catch {
      // Build output and diagnostics remain visible as plain log lines.
    }
  }
  scheduleBroadcast();
}

function runBenchmark(response) {
  if (runner.status === 'running') {
    writeJson(response, 409, { error: 'Benchmark already running' });
    return;
  }
  if (!existsSync(evaluator)) {
    writeJson(response, 404, { error: `Evaluator not found: ${evaluator}` });
    return;
  }
  runner.status = 'running';
  runner.run_id = runId;
  runner.source_head = git(['rev-parse', 'HEAD'], worktree);
  runner.startedAt = new Date().toISOString();
  runner.finishedAt = null;
  runner.output = [];
  runner.metrics = null;
  runner.error = null;
  broadcast();
  const child = spawn(process.execPath, [evaluator], {
    cwd: worktree,
    env: process.env,
    stdio: ['ignore', 'pipe', 'pipe']
  });
  child.stdout.on('data', chunk => appendOutput('stdout', chunk));
  child.stderr.on('data', chunk => appendOutput('stderr', chunk));
  child.on('error', error => {
    runner.status = 'failed';
    runner.error = error.message;
    runner.finishedAt = new Date().toISOString();
    broadcast();
  });
  child.on('close', code => {
    runner.status = code === 0 && runner.metrics ? 'completed' : 'failed';
    runner.error = code === 0 ? null : `Evaluator exited with code ${code}`;
    runner.finishedAt = new Date().toISOString();
    mkdirSync(stateRoot, { recursive: true });
    writeFileSync(latestResultPath, `${JSON.stringify(runner, null, 2)}\n`);
    broadcast();
  });
  writeJson(response, 202, { status: 'started', pid: child.pid });
}

function staticFile(pathname, response) {
  const normalized = pathname === '/' ? '/index.html' : pathname;
  const path = resolve(staticRoot, `.${normalized}`);
  if (!path.startsWith(staticRoot) || !existsSync(path) || statSync(path).isDirectory()) {
    response.writeHead(404);
    response.end('Not found');
    return;
  }
  const types = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8'
  };
  response.writeHead(200, {
    'content-type': types[extname(path)] || 'application/octet-stream',
    'cache-control': 'no-store'
  });
  createReadStream(path).pipe(response);
}

const server = http.createServer((request, response) => {
  const url = new URL(request.url, `http://${request.headers.host || `${bind}:${port}`}`);
  if (request.method === 'GET' && url.pathname === '/api/run') {
    writeJson(response, 200, snapshot());
    return;
  }
  if (request.method === 'GET' && url.pathname === '/api/stream') {
    response.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
      'x-accel-buffering': 'no'
    });
    response.write(`event: run.snapshot\ndata: ${JSON.stringify(snapshot())}\n\n`);
    clients.add(response);
    const keepalive = setInterval(() => response.write(': keepalive\n\n'), 15_000);
    request.on('close', () => {
      clearInterval(keepalive);
      clients.delete(response);
    });
    return;
  }
  if (request.method === 'POST' && url.pathname === '/api/measure') {
    runBenchmark(response);
    return;
  }
  if (request.method === 'GET') {
    staticFile(url.pathname, response);
    return;
  }
  response.writeHead(405);
  response.end('Method not allowed');
});

const watchedDirectories = hasExperimentWorktree ? [runRoot, worktree] : [runRoot];
for (const directory of watchedDirectories) {
  if (!existsSync(directory)) continue;
  try {
    watch(directory, { recursive: true }, scheduleBroadcast);
  } catch (error) {
    console.error(`watch failed path=${directory} error=${error.message}`);
  }
}

server.listen(port, bind, () => {
  console.log(`OPTIMIZE_RUN_UI=http://${bind}:${port}`);
  console.log(`RUN_ROOT=${runRoot}`);
  console.log(`WORKTREE=${worktree}`);
  console.log('PRODUCTION_IMPACT=none');
});
