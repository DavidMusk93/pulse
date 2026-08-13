import assert from 'node:assert/strict';
import test from 'node:test';

import {
  appendLogChunk,
  flushLogStream,
  reconcileLogDelta,
  reconcileLogSnapshot,
  visibleLineRange
} from './log-model.mjs';
import { createUtf8LogStream } from './log-stream.mjs';

test('reassembles complete logical lines across chunks without truncation', () => {
  const carries = new Map();
  const lines = [];

  lines.push(...appendLogChunk(carries, 'stdout', 'alpha\nbet'));
  lines.push(...appendLogChunk(carries, 'stdout', 'a\n\nlast'));
  lines.push(...flushLogStream(carries, 'stdout'));

  assert.deepEqual(lines, ['alpha', 'beta', '', 'last']);
});

test('does not create a blank line when CRLF is split across chunks', () => {
  const carries = new Map();
  const lines = [
    ...appendLogChunk(carries, 'combined', 'alpha\r'),
    ...appendLogChunk(carries, 'combined', '\nbeta\r\n'),
    ...flushLogStream(carries, 'combined')
  ];

  assert.deepEqual(lines, ['alpha', 'beta']);
});

test('keeps every completed line instead of a tail window', () => {
  const carries = new Map();
  const input = Array.from({ length: 30_002 }, (_, index) => `line-${index}`).join('\n');
  const lines = appendLogChunk(carries, 'stdout', `${input}\n`);

  assert.equal(lines.length, 30_002);
  assert.equal(lines[0], 'line-0');
  assert.equal(lines.at(-1), 'line-30001');
});

test('virtual range bounds DOM rows while preserving the full line count', () => {
  const range = visibleLineRange({
    lineCount: 30_002,
    scrollTop: 300_000,
    viewportHeight: 200,
    lineHeight: 20,
    overscan: 8
  });

  assert.equal(range.totalHeight, 600_040);
  assert.ok(range.end - range.start <= 26);
  assert.ok(range.start > 0);
  assert.ok(range.end < 30_002);
});

test('reconciles duplicate, contiguous and gapped SSE log deltas', () => {
  assert.deepEqual(
    reconcileLogDelta(['a', 'b'], ['b', 'c'], 3),
    { status: 'applied', lines: ['a', 'b', 'c'] }
  );
  assert.deepEqual(
    reconcileLogDelta(['a', 'b', 'c'], ['b', 'c'], 3),
    { status: 'duplicate', lines: ['a', 'b', 'c'] }
  );
  assert.deepEqual(
    reconcileLogDelta(['a'], ['c'], 3),
    { status: 'gap', lines: ['a'] }
  );
});

test('authoritative recovery never replaces a newer local log', () => {
  assert.deepEqual(
    reconcileLogSnapshot(['a', 'b', 'c'], ['a', 'b']),
    ['a', 'b', 'c']
  );
  assert.deepEqual(
    reconcileLogSnapshot(['a'], ['a', 'b']),
    ['a', 'b']
  );
});

test('preserves a UTF-8 character split across buffers', () => {
  const lines = [];
  const stream = createUtf8LogStream(next => lines.push(...next));
  const encoded = Buffer.from('A你B\n');

  stream.write(encoded.subarray(0, 2));
  stream.write(encoded.subarray(2, 4));
  stream.write(encoded.subarray(4));
  stream.end();

  assert.deepEqual(lines, ['A你B']);
});
