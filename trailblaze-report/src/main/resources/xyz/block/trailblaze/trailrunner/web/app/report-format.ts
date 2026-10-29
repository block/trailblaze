/** Currency that keeps reviewer-relevant sub-cent model usage visible. */
export function formatUsd(cost: number): string {
  const amount = Math.abs(cost);
  if (cost > 0 && cost < 0.000001) return '<$0.000001';
  if (amount === 0 || amount >= 0.01) return `$${cost.toFixed(2)}`;
  return `$${cost.toFixed(6)}`;
}

export function trailSourceDetails(raw: unknown): { url: string; repo: string; path: string; commit: string } | null {
  if (typeof raw !== 'string') return null;
  const match = /^https:\/\/github\.com\/([A-Za-z0-9][A-Za-z0-9._-]*)\/([A-Za-z0-9][A-Za-z0-9._-]*)\/blob\/([0-9a-f]{40})\/([^?#]+)$/.exec(raw);
  if (!match) return null;
  try {
    return { url: raw, repo: match[2], path: decodeURIComponent(match[4]), commit: match[3] };
  } catch (_) {
    return null;
  }
}
