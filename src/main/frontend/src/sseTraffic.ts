import { useSyncExternalStore } from 'react';

export type SseTrafficSnapshot = {
  totalBytes: number;
  totalEvents: number;
  bytesPerSecond: number;
  eventsPerSecond: number;
  lastEventAt: number;
};

type Sample = {
  at: number;
  bytes: number;
};

const windowMs = 60_000;
const encoder = new TextEncoder();
const listeners = new Set<() => void>();
const samples: Sample[] = [];
let totalBytes = 0;
let totalEvents = 0;
let lastEventAt = 0;
let frame: number | null = null;
let expiryTimer: number | null = null;
let snapshot: SseTrafficSnapshot = {
  totalBytes: 0,
  totalEvents: 0,
  bytesPerSecond: 0,
  eventsPerSecond: 0,
  lastEventAt: 0
};

function publish() {
  frame = null;
  const now = Date.now();
  while (samples.length && samples[0].at <= now - windowMs) {
    samples.shift();
  }
  const windowStart = samples[0]?.at ?? now;
  const elapsedSeconds = Math.max(1, Math.min(windowMs, now - windowStart) / 1_000);
  snapshot = {
    totalBytes,
    totalEvents,
    bytesPerSecond: samples.reduce((sum, sample) => sum + sample.bytes, 0) / elapsedSeconds,
    eventsPerSecond: samples.length / elapsedSeconds,
    lastEventAt
  };
  listeners.forEach(listener => listener());
  if (expiryTimer !== null) {
    window.clearTimeout(expiryTimer);
    expiryTimer = null;
  }
  if (samples.length) {
    expiryTimer = window.setTimeout(publish, Math.max(1, samples[0].at + windowMs - now));
  }
}

export function recordSseEvent(event: MessageEvent<string>) {
  const bytes = encoder.encode(event.data).byteLength;
  const at = Date.now();
  totalBytes += bytes;
  totalEvents++;
  lastEventAt = at;
  samples.push({ at, bytes });
  if (frame === null) {
    frame = window.requestAnimationFrame(publish);
  }
}

export function trackedSseListener(
  listener: (event: MessageEvent<string>) => void
) {
  return (event: Event) => {
    const message = event as MessageEvent<string>;
    recordSseEvent(message);
    listener(message);
  };
}

export function trackOnlySseEvent(event: Event) {
  recordSseEvent(event as MessageEvent<string>);
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function getSnapshot() {
  return snapshot;
}

export function useSseTraffic() {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}
