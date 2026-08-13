export const HOST_CLUSTER_BOOTSTRAP_SCOPE = '__pulse_catalog__';

export function requestedHostClusterScope(selection: string | null) {
  return selection || HOST_CLUSTER_BOOTSTRAP_SCOPE;
}

export function selectHostCluster(selection: string | null, catalog: string[]) {
  if (selection && catalog.includes(selection)) return selection;
  return [...new Set(catalog)].sort()[0] || null;
}

export function hostClusterOptions(catalog: string[]) {
  return [...new Set(catalog)]
    .sort()
    .map(cluster => ({ label: cluster, value: cluster }));
}
