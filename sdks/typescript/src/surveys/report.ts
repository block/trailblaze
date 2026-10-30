// Renders a collection report for humans. JSON is the machine format and needs no help.

import type { SurveyReport, SessionResult } from "./runner.js";
import type { LabelRecord, SessionRecord } from "./types.js";

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

/** Markdown: a survey table, the labels with the findings and runs behind them, then per-session findings with their data and evidence. */
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
  const labelled = report.labels.length > 0 || report.labelErrors.length > 0;
  lines.push(labelled ? `| Survey | Sessions | Findings | Labels | Description |` : `| Survey | Sessions | Findings | Description |`);
  lines.push(labelled ? `|---|---:|---:|---:|---|` : `|---|---:|---:|---|`);
  const surveys = [...report.surveys].sort((a, b) => b.sessionsMatched - a.sessionsMatched || a.id.localeCompare(b.id));
  for (const s of surveys) {
    // A survey the run included only for another's `dependsOn` says so, and how much it reused.
    const why = s.neededBy ? ` (for ${s.neededBy.join(", ")}; stored findings reused in ${s.reused ?? 0})` : "";
    const description = cell(`${s.description ?? ""}${why}`.trim());
    lines.push(labelled ? `| ${cell(s.id)} | ${s.sessionsMatched}/${total} | ${s.findings} | ${s.labels} | ${description} |` : `| ${cell(s.id)} | ${s.sessionsMatched}/${total} | ${s.findings} | ${description} |`);
  }
  lines.push("");
  if (labelled) lines.push(...renderLabels(report));
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

/** What one label is called: its type, when it has one, then its name. */
export function labelName(l: Pick<LabelRecord, "type" | "name">): string {
  return l.type ? `${l.type}: ${l.name}` : l.name;
}

/** Labels, then under each the findings behind it grouped by the run they were found in. */
function renderLabels(report: SurveyReport): string[] {
  const lines = [`## Labels`, ""];
  const titles = new Map(report.sessions.map((s) => [s.session.id, s.session.title]));
  for (const l of report.labels) {
    lines.push(`### ${cell(labelName(l))}`);
    lines.push("");
    lines.push(`From **${cell(l.survey)}**, ${l.runs.length} run${l.runs.length === 1 ? "" : "s"}.${l.reasoning ? ` ${cell(l.reasoning)}` : ""}`);
    lines.push("");
    for (const run of l.runs) {
      const title = titles.get(run);
      lines.push(`- ${cell(run)}${title ? ` — ${cell(title)}` : ""}`);
      for (const f of l.findings.filter((x) => x.session === run)) lines.push(`  - **${cell(f.survey)}** — ${cell(f.summary)}`);
    }
    lines.push("");
  }
  for (const e of report.labelErrors) lines.push(`- ⚠️ ${cell(e.survey)}: ${cell(e.message)}`);
  if (report.labelErrors.length > 0) lines.push("");
  return lines;
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
  if (s.labels.length > 0) {
    lines.push("");
    lines.push(`Labels: ${s.labels.map((l) => cell(labelName(l))).join(", ")}`);
  }
  lines.push("");
  return lines;
}

/** One line per session: id, outcome, and the surveys that found something. */
export function renderSummaryLines(report: SurveyReport): string[] {
  const sessions = report.sessions.map((s) => {
    const found = [...new Set(s.findings.map((f) => f.survey))];
    const errors = s.errors.length > 0 ? ` (${s.errors.length} survey error${s.errors.length === 1 ? "" : "s"})` : "";
    const labels = s.labels.length > 0 ? `  [${s.labels.map(labelName).join(", ")}]` : "";
    return `${s.session.outcome.padEnd(9)} ${s.session.id}  →  ${found.length > 0 ? found.join(", ") : "-"}${labels}${errors}`;
  });
  // A label phase belongs to no one session, so its failure gets a line of its own.
  return [...sessions, ...report.labelErrors.map((e) => `LABELS    ${e.survey}  →  ${e.message}`)];
}
