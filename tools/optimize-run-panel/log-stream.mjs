import { StringDecoder } from 'node:string_decoder';

import { appendLogChunk, flushLogStream } from './log-model.mjs';

export function createUtf8LogStream(onLines) {
  const decoder = new StringDecoder('utf8');
  const carries = new Map();
  const stream = 'combined';
  return {
    write(chunk) {
      const lines = appendLogChunk(carries, stream, decoder.write(chunk));
      if (lines.length) onLines(lines);
    },
    end() {
      const decoded = decoder.end();
      const lines = [
        ...appendLogChunk(carries, stream, decoded),
        ...flushLogStream(carries, stream)
      ];
      if (lines.length) onLines(lines);
    }
  };
}
