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
  storage_bytes?: number;
  legacy_bytes?: number;
  max_bytes?: number;
  shard_count?: number;
  deleted_shards?: number;
  capacity_dropped_commands?: number;
  queue_high_watermark?: number;
  maintenance_duration_ms?: number;
  retention_lag_ms?: number;
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
  cluster?: string;
  rangeMinutes?: number;
  startMs?: number;
  endMs?: number;
  nowMs?: number;
  stepMs?: number;
  pointLimit?: number;
  seriesLimit?: number;
  topN?: number;
  cache?: boolean;
};

export type MetricInvalidation = {
  from: number;
  to: number;
  metrics: string[];
};

export type EventPluginField = {
  key: string;
  label: string;
  type: 'text' | 'password' | 'number' | 'boolean' | 'select';
  required?: boolean;
  secret?: boolean;
  default_value?: unknown;
  options?: string[];
  description?: string;
};

export type EventPluginDescriptor = {
  type: string;
  kind: 'source' | 'gate' | 'sink';
  name: string;
  description?: string;
  config_fields?: EventPluginField[];
};

export type EventTypeDefinition = {
  id: string;
  name: string;
  description?: string;
  severity: string;
  enabled: boolean;
};

export type EventSourceDefinition = {
  id: string;
  name: string;
  plugin_type: string;
  event_type: string;
  enabled: boolean;
  config: Record<string, unknown>;
};

export type EventSinkDefinition = {
  id: string;
  name: string;
  plugin_type: string;
  enabled: boolean;
  config: Record<string, unknown>;
};

export type EventRouteDefinition = {
  id: string;
  name: string;
  enabled: boolean;
  source_ids: string[];
  event_types: string[];
  sink_ids: string[];
  gate_type: string;
  gate_config: Record<string, unknown>;
};

export type EventBusConfig = {
  version: number;
  event_types: EventTypeDefinition[];
  sources: EventSourceDefinition[];
  sinks: EventSinkDefinition[];
  routes: EventRouteDefinition[];
};

export type EventRouteStatus = {
  last_attempt_at_ms?: number;
  last_success_at_ms?: number;
  last_active_count?: number;
  last_error?: string;
  last_delivery_id?: string;
  last_delivered_events?: number;
};

export type EventBusEvent = {
  event_id?: string;
  event_type?: string;
  source_id?: string;
  subject?: string;
  agent_id?: string;
  severity?: string;
  status?: string;
  summary?: string;
};

export type EventBusView = {
  config: EventBusConfig;
  plugins: EventPluginDescriptor[];
  route_status?: Record<string, EventRouteStatus>;
  active_events?: EventBusEvent[];
};

export type EventDeliveryReceipt = {
  upstream_id?: string;
  format?: string;
  delivered_events?: number;
  metadata?: Record<string, unknown>;
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

  eventBus() {
    return this.fetchJson<EventBusView>('/api/eventbus');
  }

  updateEventBus(config: EventBusConfig) {
    return this.fetchJson<EventBusView>('/api/eventbus/config', {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(config)
    });
  }

  testEventSink(sinkId: string) {
    return this.fetchJson<EventDeliveryReceipt>(`/api/eventbus/sinks/${encodeURIComponent(sinkId)}/test`, {
      method: 'POST'
    });
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
    if (request.topN && request.topN > 0) {
      params.set('top_n', String(request.topN));
    }
    if (request.cluster && request.cluster !== 'all') {
      params.set('cluster', request.cluster);
    }
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
