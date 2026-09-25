// Which sessions a survey applies to: its platforms, its app ids, and its targets.
//
// Its own file because two runners need the same answer and only one of them has a filesystem. The
// CLI runner reads session directories; a browser page can run surveys over
// snapshots. A survey scoped to one app that the page ran over every session would report findings
// the CLI never would — the same survey, two answers — so both call this, and nothing here imports
// anything that touches the disk.

import type { SurveySpec, SessionSummary } from "./types.js";

export interface TargetDefinition {
  id: string;
  /** Every app id across platforms. */
  appIds: string[];
  /** App ids per platform key as the trailmap spells it (`android`, `ios`, `web`, ...). */
  platforms: Record<string, string[]>;
  /** The trailmap file this came from, when loaded from disk. */
  file?: string;
}

/** Target id → definition. Build one by hand or with `loadTargetCatalog`. */
export type TargetCatalog = Record<string, TargetDefinition>;

/** Whether `session` belongs to `targetId`: its trail named that target, or it drove one of the target's apps. */
export function sessionMatchesTarget(session: SessionSummary, targetId: string, catalog?: TargetCatalog): boolean {
  if (session.target === targetId) return true;
  const def = catalog?.[targetId];
  return def !== undefined && session.appId !== undefined && def.appIds.includes(session.appId);
}

/** Every target id `session` belongs to under `catalog`, the recorded one first. */
export function targetsOf(session: SessionSummary, catalog?: TargetCatalog): string[] {
  const out: string[] = [];
  if (session.target) out.push(session.target);
  for (const id of Object.keys(catalog ?? {})) {
    if (!out.includes(id) && sessionMatchesTarget(session, id, catalog)) out.push(id);
  }
  return out;
}

/** Whether a survey with this spec runs on this session at all. A session it does not is "skipped". */
export function surveyApplies(spec: SurveySpec, s: SessionSummary, catalog?: TargetCatalog): boolean {
  const { platforms, appIds, targets } = spec;
  if (platforms && (!s.platform || !platforms.map((p) => p.toLowerCase()).includes(s.platform.toLowerCase()))) return false;
  if (appIds && (!s.appId || !appIds.includes(s.appId))) return false;
  if (targets && !targets.some((t) => sessionMatchesTarget(s, t, catalog))) return false;
  return true;
}
