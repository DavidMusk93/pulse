export type MetricCatalogItem = {
  metric: string;
  title: string;
  unit: string;
};

export type MetricStorageHealth = {
  status?: string;
  queue_depth?: number;
  accepted_commands?: number;
  written_commands?: number;
  dropped_commands?: number;
  failed_commands?: number;
  maintenance_commands?: number;
  deleted_samples?: number;
  checkpoint_commands?: number;
  transaction_batches?: number;
  last_error?: string;
};

export type MetricPointView = {
  timestamp_ms?: number;
  timestampMs?: number;
  value?: number;
  metadata?: Record<string, any>;
};

export type MetricSeriesView = {
  labels?: Record<string, string>;
  points?: MetricPointView[];
};

export type MetricQueryResultView = {
  query_id?: string;
  queryId?: string;
  metric?: string;
  from?: number;
  to?: number;
  unit?: string;
  sample_policy?: string;
  samplePolicy?: string;
  truncated?: boolean;
  suggested_step_ms?: number;
  suggestedStepMs?: number;
  series_limit?: number;
  seriesLimit?: number;
  point_limit?: number;
  pointLimit?: number;
  series?: MetricSeriesView[];
};

export type MetricQueryRequest = {
  metric: string;
  agents: string[];
  rangeMinutes?: number;
  startMs?: number;
  endMs?: number;
  nowMs?: number;
  stepMs?: number;
  pointLimit?: number;
  seriesLimit?: number;
  cache?: boolean;
};

export type MetricInvalidation = {
  from: number;
  to: number;
  metrics: string[];
};

type FetchJson = <T>(url: string, init?: RequestInit) => Promise<T>;

export function metricPointTimestamp(point: MetricPointView) {
  return point.timestamp_ms ?? point.timestampMs ?? 0;
}

export function metricPointValue(point: MetricPointView) {
  const value = Number(point.value);
  return Number.isFinite(value) ? value : 0;
}

export class MetricQueryController {
  private readonly cache = new Map<string, MetricQueryResultView>();

  constructor(private readonly fetchJson: FetchJson) {}

  catalog() {
    return this.fetchJson<MetricCatalogItem[]>('/api/metrics/catalog');
  }

  storage() {
    return this.fetchJson<MetricStorageHealth>('/api/metrics/storage');
  }

  queryRange(request: MetricQueryRequest) {
    const params = this.queryParams(request);
    const key = params.toString();
    if (request.cache !== false) {
      const cached = this.cache.get(key);
      if (cached) return Promise.resolve(cached);
    }
    return this.fetchJson<MetricQueryResultView>(`/api/metrics/query_range?${key}`).then(result => {
      if (request.cache !== false) {
        this.cache.set(key, result);
        this.trimCache();
      }
      return result;
    });
  }

  queryParams(request: MetricQueryRequest) {
    const end = request.nowMs ?? Date.now();
    const start = end - (request.rangeMinutes ?? 30) * 60_000;
    const startMs = request.startMs ?? start;
    const endMs = request.endMs ?? end;
    const params = new URLSearchParams({
      metric: request.metric,
      start_ms: String(startMs),
      end_ms: String(endMs),
      step_ms: String(request.stepMs ?? 10_000),
      point_limit: String(request.pointLimit ?? 20_000),
      series_limit: String(request.seriesLimit ?? 12)
    });
    if (request.agents.length) {
      params.set('agents', request.agents.join(','));
    }
    return params;
  }

  invalidate() {
    this.cache.clear();
  }

  private trimCache() {
    const maxEntries = 24;
    while (this.cache.size > maxEntries) {
      const first = this.cache.keys().next().value;
      if (!first) return;
      this.cache.delete(first);
    }
  }
}

export class SeriesStore {
  constructor(private readonly result: MetricQueryResultView | null) {}

  seriesCount() {
    return this.result?.series?.length || 0;
  }

  pointCount() {
    return this.result?.series?.reduce((sum, series) => sum + (series.points?.length || 0), 0) || 0;
  }

  static merge(base: MetricQueryResultView | null, patch: MetricQueryResultView | null): MetricQueryResultView | null {
    if (!base) return patch;
    if (!patch) return base;
    const seriesByKey = new Map<string, MetricSeriesView>();
    [...(base.series || []), ...(patch.series || [])].forEach(series => {
      const key = JSON.stringify(series.labels || {});
      const current = seriesByKey.get(key);
      if (!current) {
        seriesByKey.set(key, { labels: series.labels || {}, points: [...(series.points || [])] });
        return;
      }
      current.points = mergePoints(current.points || [], series.points || []);
    });
    return {
      ...base,
      ...patch,
      from: Math.min(base.from ?? patch.from ?? 0, patch.from ?? base.from ?? 0),
      to: Math.max(base.to ?? patch.to ?? 0, patch.to ?? base.to ?? 0),
      truncated: Boolean(base.truncated || patch.truncated),
      series: [...seriesByKey.values()]
    };
  }
}

export function mergeInvalidation(current: MetricInvalidation | null, next: MetricInvalidation | null) {
  if (!next) return current;
  if (!current) return next;
  return {
    from: Math.min(current.from, next.from),
    to: Math.max(current.to, next.to),
    metrics: [...new Set([...current.metrics, ...next.metrics])]
  };
}

export function parseInvalidation(raw: string): MetricInvalidation | null {
  try {
    const payload = JSON.parse(raw) as { from?: number; to?: number; metrics?: string[] };
    const from = Number(payload.from);
    const to = Number(payload.to);
    if (!Number.isFinite(from) || !Number.isFinite(to)) return null;
    return {
      from,
      to,
      metrics: Array.isArray(payload.metrics) ? payload.metrics.map(String) : []
    };
  } catch {
    return null;
  }
}

function mergePoints(left: MetricPointView[], right: MetricPointView[]) {
  const byTimestamp = new Map<number, MetricPointView>();
  [...left, ...right].forEach(point => {
    byTimestamp.set(metricPointTimestamp(point), point);
  });
  return [...byTimestamp.entries()]
    .sort(([leftTs], [rightTs]) => leftTs - rightTs)
    .map(([, point]) => point);
}

export class RenderScheduler {
  private frame = 0;

  schedule(task: () => void) {
    this.cancel();
    this.frame = window.requestAnimationFrame(() => {
      this.frame = 0;
      task();
    });
  }

  cancel() {
    if (!this.frame) return;
    window.cancelAnimationFrame(this.frame);
    this.frame = 0;
  }
}

export type SparklineModel = {
  width: number;
  height: number;
  minValue: number;
  maxValue: number;
  series: string[];
};

export class SvgChartAdapter {
  readonly width = 720;
  readonly height = 220;

  model(result: MetricQueryResultView | null): SparklineModel | null {
    const series = result?.series || [];
    const points = series.flatMap(item => item.points || []);
    if (!points.length) return null;
    const timestamps = points.map(metricPointTimestamp);
    const values = points.map(metricPointValue);
    const minT = Math.min(...timestamps);
    const maxT = Math.max(...timestamps);
    const minValue = Math.min(...values);
    const maxValue = Math.max(...values);
    const spanT = Math.max(1, maxT - minT);
    const spanV = Math.max(1, maxValue - minValue);
    const toX = (timestamp: number) => 28 + (timestamp - minT) * (this.width - 56) / spanT;
    const toY = (value: number) => this.height - 26 - (value - minValue) * (this.height - 52) / spanV;

    return {
      width: this.width,
      height: this.height,
      minValue,
      maxValue,
      series: series.slice(0, 6).map(item => (item.points || [])
        .map(point => `${toX(metricPointTimestamp(point)).toFixed(1)},${toY(metricPointValue(point)).toFixed(1)}`)
        .join(' '))
    };
  }
}
