/** Currency that keeps reviewer-relevant sub-cent model usage visible. */
export function formatUsd(cost: number): string {
  const amount = Math.abs(cost);
  if (cost > 0 && cost < 0.000001) return '<$0.000001';
  if (amount === 0 || amount >= 0.01) return `$${cost.toFixed(2)}`;
  return `$${cost.toFixed(6)}`;
}
