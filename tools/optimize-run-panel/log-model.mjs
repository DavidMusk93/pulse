export function appendLogChunk(carries, stream, chunk) {
  let text = `${carries.get(stream) || ''}${chunk}`;
  const trailingCarriageReturn = text.endsWith('\r');
  if (trailingCarriageReturn) text = text.slice(0, -1);
  const lines = text.split(/\r\n|\n|\r/);
  carries.set(stream, `${lines.pop() || ''}${trailingCarriageReturn ? '\r' : ''}`);
  return lines;
}

export function flushLogStream(carries, stream) {
  const carry = carries.get(stream) || '';
  carries.set(stream, '');
  if (carry.endsWith('\r')) return [carry.slice(0, -1)];
  return carry ? [carry] : [];
}

export function visibleLineRange({
  lineCount,
  scrollTop,
  viewportHeight,
  lineHeight,
  overscan
}) {
  const visibleStart = Math.floor(scrollTop / lineHeight);
  const visibleCount = Math.ceil(viewportHeight / lineHeight);
  const start = Math.max(0, visibleStart - overscan);
  const end = Math.min(lineCount, visibleStart + visibleCount + overscan);
  return {
    start,
    end,
    totalHeight: lineCount * lineHeight,
    offsetTop: start * lineHeight
  };
}

export function reconcileLogDelta(current, incoming, totalLines) {
  const firstIncomingIndex = Number(totalLines) - incoming.length;
  if (!Number.isInteger(firstIncomingIndex) || firstIncomingIndex < 0) {
    return { status: 'gap', lines: current };
  }
  if (firstIncomingIndex > current.length) {
    return { status: 'gap', lines: current };
  }
  const missing = incoming.slice(Math.max(0, current.length - firstIncomingIndex));
  if (!missing.length) {
    return { status: 'duplicate', lines: current };
  }
  return { status: 'applied', lines: [...current, ...missing.map(String)] };
}

export function reconcileLogSnapshot(current, authoritative) {
  return authoritative.length >= current.length
    ? authoritative.map(String)
    : current;
}
