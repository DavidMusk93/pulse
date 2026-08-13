import assert from 'node:assert/strict';
import test from 'node:test';

import {
  compatibleRunner,
  gatePassed,
  panelMetrics,
  semanticFingerprint
} from './model.mjs';

test('stale runner state cannot override the current run best', () => {
  const runner = {
    run_id: 'old-run',
    source_head: 'old-head',
    status: 'completed',
    metrics: { steady_byte_amplification: 1.56 }
  };

  assert.equal(compatibleRunner(runner, 'current-run', 'new-head'), false);
  assert.deepEqual(panelMetrics({
    best: { metrics: { steady_byte_amplification: 1.07 } },
    runner,
    runId: 'current-run',
    head: 'new-head'
  }), { steady_byte_amplification: 1.07 });
});

test('a benchmark from the same run and head is visible', () => {
  const runner = {
    run_id: 'current-run',
    source_head: 'new-head',
    status: 'completed',
    metrics: { steady_byte_amplification: 1.02 }
  };

  assert.deepEqual(panelMetrics({
    best: { metrics: { steady_byte_amplification: 1.07 } },
    runner,
    runId: 'current-run',
    head: 'new-head'
  }), runner.metrics);
});

test('failed benchmark state remains visible for diagnostics', () => {
  const runner = {
    run_id: 'current-run',
    source_head: 'new-head',
    status: 'failed',
    execution_id: 'failed-execution'
  };

  assert.equal(compatibleRunner(runner, 'current-run', 'new-head'), true);
});

test('gate checks are interpreted from the optimization spec', () => {
  assert.equal(gatePassed('== 1', 1), true);
  assert.equal(gatePassed('== 0', 0), true);
  assert.equal(gatePassed('<= 1.10', 1.08), true);
  assert.equal(gatePassed('<= 1.00', 1.01), false);
});

test('semantic fingerprint ignores transport-only revision and timestamp', () => {
  const first = {
    revision: 1,
    generated_at: '2026-08-12T00:00:00Z',
    best: { iteration: 1 }
  };
  const second = {
    revision: 999,
    generated_at: '2026-08-13T00:00:00Z',
    best: { iteration: 1 }
  };

  assert.equal(semanticFingerprint(first), semanticFingerprint(second));
});
