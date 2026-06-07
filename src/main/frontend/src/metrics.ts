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
  rangeMinutes: number;
  nowMs?: number;
  stepMs?: number;
  pointLimit?: number;
  seriesLimit?: number;
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
  constructor(private readonly fetchJson: FetchJson) {}

  catalog() {
    return this.fetchJson<MetricCatalogItem[]>('/api/metrics/catalog');
  }

  storage() {
    return this.fetchJson<MetricStorageHealth>('/api/metrics/storage');
  }

  queryRange(request: MetricQueryRequest) {
    return this.fetchJson<MetricQueryResultView>(`/api/metrics/query_range?${this.queryParams(request).toString()}`);
  }

  queryParams(request: MetricQueryRequest) {
    const end = request.nowMs ?? Date.now();
    const start = end - request.rangeMinutes * 60_000;
    const params = new URLSearchParams({
      metric: request.metric,
      start_ms: String(start),
      end_ms: String(end),
      step_ms: String(request.stepMs ?? 10_000),
      point_limit: String(request.pointLimit ?? 20_000),
      series_limit: String(request.seriesLimit ?? 12)
    });
    if (request.agents.length) {
      params.set('agents', request.agents.join(','));
    }
    return params;
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
