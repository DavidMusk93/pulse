import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import {
  HOST_CLUSTER_BOOTSTRAP_SCOPE,
  hostClusterOptions,
  requestedHostClusterScope,
  selectHostCluster
} from '../src/main/frontend/src/hostClusterScope.ts';

test('uses a non-matching bootstrap scope before catalog discovery', () => {
  assert.equal(requestedHostClusterScope(null), HOST_CLUSTER_BOOTSTRAP_SCOPE);
});

test('selects the first catalog cluster and preserves a valid preference', () => {
  assert.equal(selectHostCluster(null, ['cdn2', 'doubao']), 'cdn2');
  assert.equal(selectHostCluster('doubao', ['cdn2', 'doubao']), 'doubao');
  assert.equal(selectHostCluster('removed', ['cdn2', 'doubao']), 'cdn2');
  assert.equal(selectHostCluster('removed', []), null);
});

test('cluster options never include an all-clusters choice', () => {
  assert.deepEqual(hostClusterOptions(['doubao', 'cdn2', 'cdn2']), [
    { label: 'cdn2', value: 'cdn2' },
    { label: 'doubao', value: 'doubao' }
  ]);
});

test('cluster options remain deterministic for a large catalog', () => {
  const catalog = Array.from(
    { length: 1_000 },
    (_, index) => `cluster-${String(999 - index).padStart(4, '0')}`
  );
  const options = hostClusterOptions(catalog);

  assert.equal(options.length, 1_000);
  assert.deepEqual(options[0], {
    label: 'cluster-0000',
    value: 'cluster-0000'
  });
  assert.deepEqual(options.at(-1), {
    label: 'cluster-0999',
    value: 'cluster-0999'
  });
});

test('scope changes retain host geometry until the next snapshot commits', async () => {
  const source = await readFile(
    new URL('../src/main/frontend/src/main.tsx', import.meta.url),
    'utf8'
  );
  const handler = source.match(
    /function changeHostClusterScope[\s\S]*?\n  }\n\n  async function refreshHosts/
  )?.[0] || '';

  assert.ok(handler, 'changeHostClusterScope handler is present');
  assert.doesNotMatch(handler, /setHosts\(\[\]\)/);
  assert.doesNotMatch(handler, /setLoading\(true\)/);
  assert.match(handler, /hostScopeRequestRef\.current = selected/);
  assert.match(
    source,
    /const hostScopePending = appliedHostClusterScope !== hostClusterScope/
  );
  const snapshotHandler = source.match(
    /events\.addEventListener\('hosts\.snapshot'[\s\S]*?\n    }\)\);/
  )?.[0] || '';
  assert.match(snapshotHandler, /if \(!isCurrentScopeRequest\(\)\) return;/);
  const hostSection = source.match(/<section\s+[\s\S]*?id="clusters"[\s\S]*?>/)?.[0] || '';
  assert.match(hostSection, /ref=\{hostClustersRef\}/);
  assert.match(hostSection, /aria-busy=\{hostScopePending\}/);
  assert.match(source, /hostClustersRef\.current\.inert = hostScopePending/);
});

test('metrics live compensation uses the same range budget as manual refresh', async () => {
  const source = await readFile(
    new URL('../src/main/frontend/src/main.tsx', import.meta.url),
    'utf8'
  );
  const compensation = source.match(
    /const patch = await queryController\.queryRange\(\{[\s\S]*?cache: false[\s\S]*?\}\);/
  )?.[0] || '';

  assert.ok(compensation, 'metrics live compensation query is present');
  assert.match(compensation, /stepMs: metricQueryStepMs\(rangeMinutes\)/);
  assert.match(compensation, /pointLimit: metricQueryPointLimit\(rangeMinutes\)/);
  assert.doesNotMatch(compensation, /stepMs: 10_000/);
  assert.doesNotMatch(compensation, /pointLimit: 20_000/);
});

test('metrics queries require explicit activation before loading or live compensation', async () => {
  const source = await readFile(
    new URL('../src/main/frontend/src/main.tsx', import.meta.url),
    'utf8'
  );
  const metricsPanel = source.match(
    /const MetricsPanel = memo\(function MetricsPanel[\s\S]*?sameMetricHostScope\(previous\.hosts, next\.hosts\)\);/
  )?.[0] || '';

  assert.ok(metricsPanel, 'MetricsPanel source is present');
  assert.match(metricsPanel, /const \[activeQueryKey, setActiveQueryKey\] = useState<string \| null>\(null\)/);
  assert.match(metricsPanel, /const metricsActivated = activeQueryKey === querySelectionKey && result !== null/);
  assert.doesNotMatch(
    metricsPanel,
    /useEffect\(\(\) => \{[\s\S]*?loadMetrics\(metric, visibleAgents, rangeMinutes/
  );
  assert.match(
    metricsPanel,
    /if \(!metricsActivatedRef\.current\) \{[\s\S]*?pendingQueryRef\.current\?\.key[\s\S]*?mergeInvalidation[\s\S]*?return;[\s\S]*?queryController\.invalidate\(\)/
  );
  assert.match(metricsPanel, /if \(!metricsActivated \|\| !invalidatedRange \|\| !metric \|\| !result\) return/);
  assert.match(metricsPanel, /const queryGenerationRef = useRef\(0\)/);
  assert.match(metricsPanel, /const compensationSequenceRef = useRef\(0\)/);
  assert.match(metricsPanel, /const generation = \+\+queryGenerationRef\.current/);
  assert.match(
    metricsPanel,
    /if \(selectionEpochKeyRef\.current === querySelectionKey\) return;[\s\S]*?queryGenerationRef\.current \+= 1;[\s\S]*?setLoading\(false\)/
  );
  assert.match(
    metricsPanel,
    /if \(queryGenerationRef\.current !== generation \|\| querySelectionKeyRef\.current !== requestKey\) return/
  );
  assert.match(metricsPanel, /if \(livePaused \|\| loading \|\| !activeQueryKey\) return/);
  assert.match(
    metricsPanel,
    /if \(compensationSequenceRef\.current !== compensationSequence[\s\S]*?\|\| queryGenerationRef\.current !== generation[\s\S]*?\|\| querySelectionKeyRef\.current !== compensationKey\) return/
  );
  assert.match(metricsPanel, /const compensationSequence = \+\+compensationSequenceRef\.current/);
  assert.match(metricsPanel, /compensationSequenceRef\.current !== compensationSequence/);
  assert.match(metricsPanel, /return \(\) => events\.close\(\);\n  }, \[queryController\]\);/);
  assert.match(metricsPanel, /\{metricsActivated \? '刷新时序' : '开始查询'\}/);
});
