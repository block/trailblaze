// Runs one survey over one session. No filesystem access, so it serves the post-run runner, the
// browser page, and a survey asserted while the trail is still running.

import { validateFinding, type SurveyContext, type SurveyDefinition, type SurveyResult } from "./survey.js";
import type { SurveySession } from "./session.js";
import type { Finding, FindingInput, SessionRecord } from "./types.js";

export function compactRecord(e: SessionRecord): SessionRecord {
  const out: SessionRecord = { kind: e.kind, summary: e.summary, file: e.file };
  if (e.line !== undefined) out.line = e.line;
  if (e.timeMs !== undefined) out.timeMs = e.timeMs;
  if (e.detail !== undefined) out.detail = e.detail;
  return out;
}

function normalizeResult(result: SurveyResult, extra: FindingInput[]): FindingInput[] {
  const out: FindingInput[] = [...extra];
  if (result === undefined || result === null || result === false) return out;
  if (Array.isArray(result)) out.push(...(result as ReadonlyArray<FindingInput>));
  else out.push(result as FindingInput);
  return out;
}

/** A survey that threw or reported `ctx.error`. */
export interface SurveyError {
  survey: string;
  message: string;
}

/**
 * Fields surveys written before findings were free-form set beside `summary` and `evidence`, and
 * the `feature` and `kind` they defaulted on their spec. They are carried into `data`, whose own
 * keys win, so such a survey keeps reporting what it did until it moves them into `data` itself.
 */
const LEGACY_FIELDS = ["feature", "kind", "confidence"] as const;

function dataOf(f: FindingInput, spec: object): Record<string, unknown> | undefined {
  const legacy: Record<string, unknown> = {};
  for (const key of LEGACY_FIELDS) {
    const value = (f as unknown as Record<string, unknown>)[key] ?? (key === "confidence" ? undefined : (spec as Record<string, unknown>)[key]);
    if (value !== undefined) legacy[key] = value;
  }
  if (Object.keys(legacy).length === 0) return f.data;
  return { ...legacy, ...f.data };
}

/** Runs one survey over one loaded session. */
export async function runSurvey(
  def: SurveyDefinition,
  session: SurveySession,
  id: string,
  log?: (m: string) => void,
  onError?: (error: SurveyError) => void,
): Promise<Finding[]> {
  const found: FindingInput[] = [];
  const ctx: SurveyContext = {
    found(summary, evidence, data) {
      found.push(data === undefined ? { summary, evidence: [...evidence] } : { summary, evidence: [...evidence], data });
    },
    log(message) {
      log?.(`[${id}] ${message}`);
    },
    error(message) {
      onError?.({ survey: id, message });
    },
  };
  const result = await def.handler(session, ctx);
  return normalizeResult(result, found).map((f) => {
    validateFinding(f, id);
    const finding: Finding = { survey: id, session: session.id, summary: f.summary, evidence: f.evidence.map(compactRecord) };
    const data = dataOf(f, def.spec);
    if (data !== undefined) finding.data = data;
    return finding;
  });
}
