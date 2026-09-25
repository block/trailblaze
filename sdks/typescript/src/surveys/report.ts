// Renders a collection report for humans. JSON is the machine format and needs no help.

import type { SurveyReport, SessionResult } from "./runner.js";
import type { SessionRecord } from "./types.js";

/** One markdown table cell. Backslashes first, or escaping a pipe would turn `\|` into `\\|`. */
function cell(v: unknown): string {
  return String(v ?? "").replace(/\\/g, "\\\\").replace(/\|/g, "\\|").replace(/\n/g, " ");
}

function locate(e: SessionRecord): string {
  return e.line !== undefined ? `${e.file}:${e.line}` : e.file;
}

/** Markdown: a feature table, then per-session findings with their evidence. */
export function renderMarkdown(report: SurveyReport, options: { evidence?: boolean } = {}): string {
  const showEvidence = options.evidence ?? true;
  const lines: string[] = [];
  const total = report.sessions.length;
  lines.push(`# Session survey report`);
  lines.push("");
  lines.push(`${total} session${total === 1 ? "" : "s"}, ${report.surveys.length} survey${report.surveys.length === 1 ? "" : "s"}, generated ${report.generatedAt}`);
  lines.push("");
  lines.push(`## Features`);
  lines.push("");
  const kinds = new Set(report.features.map((f) => f.kind));
  const showKind = kinds.size > 1 || !kinds.has("feature");
  lines.push(showKind ? `| Feature | Kind | Sessions | Findings | Surveys |` : `| Feature | Sessions | Findings | Surveys |`);
  lines.push(showKind ? `|---|---|---:|---:|---|` : `|---|---:|---:|---|`);
  for (const f of report.features) {
    const kind = showKind ? ` ${cell(f.kind)} |` : "";
    lines.push(`| ${cell(f.feature)} |${kind} ${f.sessionsMatched}/${total} | ${f.findings} | ${cell(f.surveys.join(", "))} |`);
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
    lines.push(`_No features detected._`);
  }
  for (const f of s.findings) {
    const kind = f.kind === "feature" ? "" : `${cell(f.kind)}: `;
    lines.push(`- **${kind}${cell(f.feature)}** — ${cell(f.summary)}${f.confidence ? ` _(${f.confidence})_` : ""}`);
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

/** One line per session: id, outcome, and the features found. */
export function renderSummaryLines(report: SurveyReport): string[] {
  return report.sessions.map((s) => {
    const features = [...new Set(s.findings.map((f) => f.feature))];
    const errors = s.errors.length > 0 ? ` (${s.errors.length} survey error${s.errors.length === 1 ? "" : "s"})` : "";
    return `${s.session.outcome.padEnd(9)} ${s.session.id}  →  ${features.length > 0 ? features.join(", ") : "-"}${errors}`;
  });
}
