import assert from 'node:assert/strict';
import test from 'node:test';

import {
  applyHostDeltaV3,
  decodeHostSnapshotV3
} from '../src/main/frontend/src/hostStreamV3.ts';

const snapshot = {
  schema: 'hosts.v3',
  revision: 10,
  scope: [],
  catalog: ['cdn2'],
  dictionaries: {
    fields: ['agent_id', 'seq', 'status', 'cluster'],
    entities: ['agent-1'],
    values: [[2, ['alive']], [3, ['cdn2']]]
  },
  columns: [['agent-1'], [1], [0], [0]]
};

test('decodes value references into public host rows', () => {
  const state = decodeHostSnapshotV3(snapshot);

  assert.deepEqual(state.hosts, [{
    agent_id: 'agent-1',
    seq: 1,
    status: 'alive',
    cluster: 'cdn2'
  }]);
});

test('applies all sparse field changes with one row clone', () => {
  const state = decodeHostSnapshotV3(snapshot);
  const previous = state.hosts[0];
  const next = applyHostDeltaV3(state, {
    schema: 'hosts.v3',
    base_revision: 10,
    revision: 11,
    add: [],
    remove: [],
    values: [[2, ['warming']]],
    columns: [[1, [0], [2]], [2, [0], [1]]],
    unset: []
  });

  assert.equal(next.hosts.length, 1);
  assert.notEqual(next.hosts[0], previous);
  assert.equal(next.hosts[0].seq, 2);
  assert.equal(next.hosts[0].status, 'warming');
});

test('empty delta advances revision without invalidating host collection', () => {
  const state = decodeHostSnapshotV3(snapshot);
  const next = applyHostDeltaV3(state, {
    schema: 'hosts.v3',
    base_revision: 10,
    revision: 11,
    add: [],
    remove: [],
    columns: [],
    unset: []
  });

  assert.equal(next.hosts, state.hosts);
  assert.equal(next.rows, state.rows);
  assert.equal(next.entities, state.entities);
  assert.equal(next.valueDictionaries, state.valueDictionaries);
});

test('invalid reference fails before mutating the previous state', () => {
  const state = decodeHostSnapshotV3(snapshot);

  assert.throws(() => applyHostDeltaV3(state, {
    schema: 'hosts.v3',
    base_revision: 10,
    revision: 11,
    add: [],
    remove: [],
    columns: [[2, [0], [99]]],
    unset: []
  }), /value reference/);
  assert.equal(state.revision, 10);
  assert.equal(state.hosts[0].status, 'alive');
});

test('applies dictionary extension, append, tombstone, unset and catalog atomically', () => {
  const state = decodeHostSnapshotV3(snapshot);
  const next = applyHostDeltaV3(state, {
    schema: 'hosts.v3',
    base_revision: 10,
    revision: 11,
    values: [[2, ['draining']]],
    add: [[1, 'agent-2', ['agent-2', 1, 1, 0]]],
    remove: [0],
    columns: [],
    unset: [[3, [1]]],
    catalog: ['cdn2', 'other']
  });

  assert.deepEqual(next.hosts, [{
    agent_id: 'agent-2',
    seq: 1,
    status: 'draining'
  }]);
  assert.deepEqual(next.entities, ['agent-1', 'agent-2']);
  assert.equal(next.rows[0], null);
  assert.deepEqual(next.catalog, ['cdn2', 'other']);
  assert.deepEqual(next.valueDictionaries.get(2), ['alive', 'draining']);
});

test('rejects revision gaps and non-append-only dictionary extensions', () => {
  const state = decodeHostSnapshotV3(snapshot);
  const base = {
    schema: 'hosts.v3',
    add: [],
    remove: [],
    columns: [],
    unset: []
  };

  assert.throws(
    () => applyHostDeltaV3(state, { ...base, base_revision: 9, revision: 11 }),
    /revision gap/
  );
  assert.throws(
    () => applyHostDeltaV3(state, {
      ...base,
      base_revision: 10,
      revision: 11,
      values: [[2, ['alive']]]
    }),
    /not append-only/
  );
  assert.deepEqual(state.valueDictionaries.get(2), ['alive']);
});

test('rejects malformed snapshot dictionaries and columns', () => {
  assert.throws(
    () => decodeHostSnapshotV3({ ...snapshot, schema: 'hosts.v2' }),
    /contract mismatch/
  );
  assert.throws(
    () => decodeHostSnapshotV3({
      ...snapshot,
      dictionaries: { ...snapshot.dictionaries, fields: ['agent_id', 'agent_id'] }
    }),
    /duplicates/
  );
  assert.throws(
    () => decodeHostSnapshotV3({ ...snapshot, columns: [['agent-1']] }),
    /align/
  );
  assert.throws(
    () => decodeHostSnapshotV3({
      ...snapshot,
      columns: [['other'], [1], [0], [0]]
    }),
    /entity dictionary mismatch/
  );
});
