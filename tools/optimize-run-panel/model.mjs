import { createHash } from 'node:crypto';

export function compatibleRunner(runner, runId, head) {
  return Boolean(
    runner
    && runner.run_id === runId
    && runner.source_head === head
    && (runner.status === 'running' || runner.status === 'completed')
  );
}

export function panelMetrics({ best, runner, runId, head }) {
  if (compatibleRunner(runner, runId, head) && runner.metrics) {
    return runner.metrics;
  }
  return best?.metrics || null;
}

export function gatePassed(check, value) {
  if (value === undefined || value === null || typeof check !== 'string') {
    return null;
  }
  const match = check.trim().match(/^(>=|<=|>|<|==|!=)\s*(-?\d+(?:\.\d+)?)$/);
  if (!match) return null;
  const actual = Number(value);
  const expected = Number(match[2]);
  if (!Number.isFinite(actual)) return false;
  switch (match[1]) {
    case '>=': return actual >= expected;
    case '<=': return actual <= expected;
    case '>': return actual > expected;
    case '<': return actual < expected;
    case '==': return actual === expected;
    case '!=': return actual !== expected;
    default: return null;
  }
}

export function semanticFingerprint(snapshot) {
  const stable = { ...snapshot };
  delete stable.revision;
  delete stable.generated_at;
  return createHash('sha256').update(JSON.stringify(stable)).digest('hex');
}
