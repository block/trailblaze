// The run's warnings: what its warning surveys (`warning: true`) found, read from the session's
// `surveys.json`, and each derived stream that failed to derive, read from `events/derived.json`. Shared by the bun driver (from the session directory) and the zip viewer (from an
// archive entry), so the two cannot disagree on which warnings a report shows.
//
// A warning never changes the run's verdict; the report shows it beside the verdict for triage. An
// archive is untrusted, so a warning is plain text the viewer escapes, capped in count and in the
// length of its survey id and summary.
//
// Types come from the ambient run-report-types.d.ts (RunWarning).

/** The session file warning surveys write (STORED_SURVEYS_FILE in the TypeScript SDK). */
export const SURVEYS_FILE = "surveys.json";

/** Past this the file is left out: a session's survey results are a few KB. */
export const MAX_SURVEYS_BYTES = 1024 * 1024;

const MAX_WARNINGS = 50;
const MAX_WARNING_SUMMARY_CHARS = 500;
/** A survey's id is its export name; a longer one is not a survey's, and is cut like a summary. */
const MAX_WARNING_SURVEY_CHARS = 100;

/**
 * The warnings in a session's `surveys.json` content: one per finding of each warning survey, in survey
 * order. Null when the content is not survey results or holds no warning.
 */
export function runWarningsFromSurveys(text: string): RunWarning[] | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  const surveys = (parsed as { surveys?: unknown } | null)?.surveys;
  if (!surveys || typeof surveys !== "object" || Array.isArray(surveys)) return null;
  const warnings: RunWarning[] = [];
  for (const id of Object.keys(surveys).sort()) {
    const entry = (surveys as Record<string, unknown>)[id] as { warning?: unknown; findings?: unknown } | null;
    if (!entry || entry.warning !== true || !Array.isArray(entry.findings)) continue;
    const survey = clip(id, MAX_WARNING_SURVEY_CHARS);
    for (const finding of entry.findings) {
      const summary = (finding as { summary?: unknown } | null)?.summary;
      if (typeof summary !== "string" || summary.trim() === "") continue;
      if (warnings.length === MAX_WARNINGS) return warnings;
      warnings.push({ survey, summary: clip(summary, MAX_WARNING_SUMMARY_CHARS) });
    }
  }
  return warnings.length ? warnings : null;
}

/** Where a session's derived-stream manifest sits (DERIVED_MANIFEST in the TypeScript SDK). */
export const DERIVED_MANIFEST_PATH = "events/derived.json";

/** Past this the manifest is left out, as the report caps it for lanes (DERIVED_STREAMS_MAX_BYTES). */
export const MAX_DERIVED_MANIFEST_BYTES = 1024 * 1024;

/**
 * One warning per derived stream that failed to derive (an `error` in `events/derived.json`), by
 * stream name. Its lane is missing from the report, and the run still passes, so without a warning
 * nobody notices. A later report retries the stream. Null when none failed or the content is not a
 * manifest.
 */
export function runWarningsFromDerivedStreams(text: string): RunWarning[] | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  const streams = (parsed as { streams?: unknown } | null)?.streams;
  if (!streams || typeof streams !== "object" || Array.isArray(streams)) return null;
  const warnings: RunWarning[] = [];
  for (const name of Object.keys(streams).sort()) {
    const error = ((streams as Record<string, unknown>)[name] as { error?: unknown } | null)?.error;
    // An empty message is still a failure, as Run details lists it.
    if (typeof error !== "string") continue;
    if (warnings.length === MAX_WARNINGS) break;
    const stream = clip(name, MAX_WARNING_SURVEY_CHARS);
    // The SDK names the stream in its own errors; the warning already does.
    const own = `stream ${JSON.stringify(name)}: `;
    const cause = (error.startsWith(own) ? error.slice(own.length) : error).trim() || "failed without a message";
    warnings.push({
      survey: `derived stream ${stream}`,
      summary: clip(`The ${stream} stream failed, so its lane is missing from this report: ${cause}`, MAX_WARNING_SUMMARY_CHARS),
    });
  }
  return warnings.length ? warnings : null;
}

/** Both kinds of warning in one list, under the same cap. Null when there are none. */
export function mergeRunWarnings(...lists: (RunWarning[] | null | undefined)[]): RunWarning[] | null {
  const all = lists.flatMap((list) => list ?? []).slice(0, MAX_WARNINGS);
  return all.length ? all : null;
}

function clip(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max)}…` : text;
}
