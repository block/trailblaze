// Renders a collection report for humans. JSON is the machine format and needs no help.

import type { SurveyReport, SessionResult } from "./runner.js";
import type { SessionRecord } from "./types.js";

/** One markdown table cell. Backslashes first, or escaping a pipe would turn `\|` into `\\|`. */
function cell(v: unknown): string {
  return String(v ?? "").replace(/\\/g, "\\\\").replace(/\|/g, "\\|").replace(/\n/g, " ");
}

/**
 * `text` as one inline code span. Markdown takes a code span's content literally, so a backslash
 * is not an escape there; only a backtick can end it, so the fence is one longer than its
 * longest backtick run.
 */
function code(text: string): string {
  const longest = Math.max(0, ...(text.match(/`+/g) ?? []).map((run) => run.length));
  const fence = "`".repeat(longest + 1);
  const pad = text.startsWith("`") || text.endsWith("`") ? " " : "";
  return `${fence}${pad}${text.replace(/\n/g, " ")}${pad}${fence}`;
}

function locate(e: SessionRecord): string {
  return e.line !== undefined ? `${e.file}:${e.line}` : e.file;
}

/** Markdown: a survey table, then per-session findings with their data and evidence. */
export function renderMarkdown(report: SurveyReport, options: { evidence?: boolean } = {}): string {
  const showEvidence = options.evidence ?? true;
  const lines: string[] = [];
  const total = report.sessions.length;
  lines.push(`# Session survey report`);
  lines.push("");
  lines.push(`${total} session${total === 1 ? "" : "s"}, ${report.surveys.length} survey${report.surveys.length === 1 ? "" : "s"}, generated ${report.generatedAt}`);
  lines.push("");
  lines.push(`## Surveys`);
  lines.push("");
  lines.push(`| Survey | Sessions | Findings | Description |`);
  lines.push(`|---|---:|---:|---|`);
  const surveys = [...report.surveys].sort((a, b) => b.sessionsMatched - a.sessionsMatched || a.id.localeCompare(b.id));
  for (const s of surveys) {
    lines.push(`| ${cell(s.id)} | ${s.sessionsMatched}/${total} | ${s.findings} | ${cell(s.description)} |`);
  }
  lines.push("");
  lines.push(`## Sessions`);
  lines.push("");
  for (const s of report.sessions) lines.push(...renderSession(s, showEvidence));
  if (report.unreadable.length > 0) {
    lines.push(`## Unreadable`);
    lines.push("");
    for (const u of report.unreadable) lines.push(`- ${cell(u.path)}: ${cell(u.message)}`);
    lines.push("");
  }
  return lines.join("\n");
}

function renderSession(s: SessionResult, showEvidence: boolean): string[] {
  const lines: string[] = [];
  const title = s.session.title ? ` — ${s.session.title}` : "";
  lines.push(`### ${s.session.id}${title}`);
  lines.push("");
  const facts = [
    s.session.outcome,
    s.session.platform,
    s.session.appId ? `${s.session.appId}${s.session.appVersion ? " " + s.session.appVersion : ""}` : undefined,
    s.session.hasNetworkCapture ? "network" : undefined,
    s.session.eventStreams.length > 0 ? `${s.session.eventStreams.length} event stream${s.session.eventStreams.length === 1 ? "" : "s"}` : undefined,
  ].filter(Boolean);
  lines.push(facts.join(" · "));
  lines.push("");
  if (s.findings.length === 0) {
    lines.push(`_No findings._`);
  }
  for (const f of s.findings) {
    const data = f.data !== undefined && Object.keys(f.data).length > 0 ? ` ${code(JSON.stringify(f.data))}` : "";
    lines.push(`- **${cell(f.survey)}** — ${cell(f.summary)}${data}`);
    if (showEvidence) {
      for (const e of f.evidence) {
        lines.push(`  - ${e.kind}: ${cell(e.summary)} \`${locate(e)}\``);
      }
    }
  }
  for (const err of s.errors) {
    lines.push(`- ⚠️ ${cell(err.survey)} threw: ${cell(err.message)}`);
  }
  lines.push("");
  return lines;
}

/** One line per session: id, outcome, and the surveys that found something. */
export function renderSummaryLines(report: SurveyReport): string[] {
  return report.sessions.map((s) => {
    const found = [...new Set(s.findings.map((f) => f.survey))];
    const errors = s.errors.length > 0 ? ` (${s.errors.length} survey error${s.errors.length === 1 ? "" : "s"})` : "";
    return `${s.session.outcome.padEnd(9)} ${s.session.id}  →  ${found.length > 0 ? found.join(", ") : "-"}${errors}`;
  });
}
