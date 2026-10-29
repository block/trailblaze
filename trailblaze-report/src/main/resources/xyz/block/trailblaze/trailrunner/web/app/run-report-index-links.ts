import { VIEWER_ROUTE_KEYS } from './run-report-route';

// Index cells name one session archive each. A comparison opens those archives together in the
// same viewer, so the index never needs to fetch their logs or embed their screenshots.
function externalRun(reportUrl: unknown): { viewer: URL; archive: string } | null {
  try {
    const viewer = new URL(String(reportUrl || ''));
    if (!['http:', 'https:'].includes(viewer.protocol) || viewer.username || viewer.password) return null;
    const archives = viewer.searchParams.getAll('zip');
    if (archives.length !== 1 || !archives[0]) return null;
    const archive = new URL(archives[0]);
    if (!['http:', 'https:'].includes(archive.protocol) || archive.username || archive.password) return null;
    viewer.searchParams.delete('zip');
    VIEWER_ROUTE_KEYS.forEach((key) => viewer.searchParams.delete(key));
    viewer.hash = '';
    return { viewer, archive: archive.href };
  } catch (_) {
    return null;
  }
}

export const isExternalRunComparable = (reportUrl: unknown): boolean => externalRun(reportUrl) != null;

export function hasExternalComparison(reportUrls: unknown[]): boolean {
  const groups = new Map<string, Set<string>>();
  for (const url of reportUrls) {
    const run = externalRun(url);
    if (!run) continue;
    const key = run.viewer.href;
    const archives = groups.get(key) || new Set<string>();
    archives.add(run.archive);
    groups.set(key, archives);
    if (archives.size > 1) return true;
  }
  return false;
}

export function externalComparisonUrl(reportUrls: unknown[]): string | null {
  if (reportUrls.length < 2) return null;
  const runs = reportUrls.map(externalRun);
  const first = runs[0];
  if (!first || runs.some((run) => !run || run.viewer.href !== first.viewer.href)) return null;
  const archives = [...new Set(runs.map((run) => run!.archive))];
  if (archives.length < 2) return null;
  const url = first.viewer;
  archives.forEach((archive) => url.searchParams.append('zip', archive));
  url.searchParams.set('view', 'compare');
  url.searchParams.set('pick', archives.map((_, i) => i).join(','));
  return url.href;
}
