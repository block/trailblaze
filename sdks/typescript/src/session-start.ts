// The trail fields a session's summary takes from its `Started` log. Shared by the finished-session
// reader (`surveys/session.ts`) and `ctx.surveys` mid-trail (`session-surveys.ts`), so a survey reads
// the same values either way. No imports, so it compiles for the in-process QuickJS runtime too.

function asRecord(v: unknown): Record<string, unknown> | undefined {
  return v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
}

function asString(v: unknown): string | undefined {
  return typeof v === "string" ? v : undefined;
}

/** The trail fields a session's `Started` log gives its summary, before `session_result.json` overrides them. */
export interface StartedTrailFields {
  title?: string;
  trailId?: string;
  testKey?: string;
  target?: string;
  appVersion?: string;
  metadata: Record<string, string>;
}

/**
 * Reads [StartedTrailFields] out of a `Started` log's `trailConfig` and `targetAppInfo`. One function
 * for a finished session's log on disk and for the start record the host sends mid-trail, so a
 * survey reads the same values either way. A list or map metadata value is kept as JSON text.
 */
export function trailFieldsFromStarted(started: unknown): StartedTrailFields {
  const trailConfig = asRecord(asRecord(started)?.trailConfig);
  const appInfo = asRecord(asRecord(started)?.targetAppInfo);
  const metadata: Record<string, string> = {};
  for (const [k, v] of Object.entries(asRecord(trailConfig?.metadata) ?? {})) {
    const text = metadataText(v);
    if (text !== undefined) metadata[k] = text;
  }
  const trailId = asString(trailConfig?.id);
  return {
    title: asString(trailConfig?.title),
    trailId,
    testKey: trailId,
    target: asString(trailConfig?.target),
    appVersion: asString(appInfo?.versionName) ?? asString(appInfo?.version),
    metadata,
  };
}

/** A metadata value as summary text: a string as it is, a list or map as JSON. */
export function metadataText(v: unknown): string | undefined {
  return typeof v === "string" ? v : v !== null && typeof v === "object" ? JSON.stringify(v) : undefined;
}
