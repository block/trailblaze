// `@trailblaze/scripting/surveys` — named checks over sessions, each finding pinned to the records that prove it.
//
//   import { trailblaze } from "@trailblaze/scripting/surveys";
//
//   export const giftCardCheckout = trailblaze.survey(
//     { description: "A sale was tendered with a gift card" },
//     (session) => {
//       const tender = session.network.find({ method: "POST", path: /\/payments$/, requestBody: "GIFT_CARD", status: "ok" });
//       if (!tender) return;
//       const receipt = session.analytics.find({ name: "Payment Complete" });
//       return { summary: "Gift card tender accepted", evidence: [tender, receipt].filter(Boolean), data: { amount: tender.detail } };
//     },
//   );
//
// Export surveys from a `*.survey.ts` file; each one's name is its export name. Run them over any
// number of finished sessions with the CLI in `cli.ts` or `runSurveys()` from code, or from a tool
// while the trail runs (`ctx.surveys.<name>()`). A finding is a summary, the records that prove it,
// and whatever `data` its survey chose to carry. After a batch of runs, a survey's optional label
// phase turns findings into labels: what the runs add up to, each backed by the findings behind it.

export { survey, explainFinding, isSurvey, matchFinding, withId } from "./survey.js";
export type { DataOf, LabelBatch, LabelOf, LabelResult, SurveyContext, SurveyDefinition, SurveyHandler, FindResult, SurveyResult, FindingExplanation, FindingSpec, FindingQueries, BatchFinding, Dependency } from "./survey.js";
export { labelOrder, planSurveys, runLabelPhases } from "./labels.js";
export type { LabelOutcome, PlannedSurvey } from "./labels.js";
export { STORED_SURVEYS_FILE, currentEntry, readStoredSurveys, writeStoredSurveys } from "./stored.js";
export type { StoredSurvey, StoredSurveys } from "./stored.js";
export { runSurveyCli } from "./cli-main.js";
// Running a survey while the trail runs, over `ctx.events`. Also the only part a survey bundle sees
// in the sandbox (`./in-process.ts`).
export { FinishedSessionRequiredError, LiveSessionReader, runNamedLiveSurvey, runLiveSurvey } from "./live.js";
export type { LiveSurveyOptions, LiveSurveyResult, LiveEventSource, LiveRead, LiveSessionOptions, LiveSurveyOutcome } from "./live.js";
export { SurveySession, isSessionDirectory, parseIsoMs } from "./session.js";
export type { LogQuery } from "./session.js";
export { Records } from "./match.js";
export type { LogRecords, Predicate, TrailblazeLogOf, TrailblazeLogType } from "./match.js";
export type {
  AnalyticsQuery,
  ContainsMatch,
  DeviceLogQuery,
  EventQuery,
  NetworkQuery,
  ObjectiveQuery,
  ScreenTextQuery,
  TextMatch,
  ToolQuery,
  TraceQuery,
  ValueMatch,
} from "./match.js";
export { compactRecord, runSurvey, runSurveys } from "./runner.js";
export type { SurveyReport, SurveySummary, RunOptions, SessionResult, SurveyError } from "./runner.js";
export { discoverSessions, loadSurveys } from "./discover.js";
export { findTrailmapFiles, loadTargetCatalog, sessionMatchesTarget, targetFromTrailmap, targetsOf } from "./targets.js";
export type { TargetCatalog, TargetDefinition } from "./targets.js";
export type { DiscoverSessionsOptions, LoadSurveysOptions, LoadedSurvey } from "./discover.js";
export { findWorkspaceConfigDir, loadWorkspace } from "./workspace.js";
export type { Workspace } from "./workspace.js";
export { labelName, renderMarkdown, renderSummaryLines } from "./report.js";
export {
  anyNode,
  classifierChain,
  loadYamlWaypoints,
  matchNode,
  matchScreen,
  mergeWaypoints,
  repositoryPath,
  repositoryRoot,
  resolveSpec,
  screensOf,
  screenshotName,
  selectorFromYaml,
  sessionChain,
  waypointFindings,
} from "./waypoints.js";
export type {
  Anchor,
  FieldMatch,
  HierarchyNode,
  NodeSelector,
  Screen,
  ScreenOptions,
  ScreenSpec,
  SelectorPattern,
  Waypoint,
  WaypointExample,
} from "./waypoints.js";
// A session frozen to JSON and read back, so a survey's tests can run it over a recorded session
// without the session directory.
export { REQUEST_BODY_LIMIT, SNAPSHOT_FORMAT } from "./snapshot/format.js";
export type { SessionSnapshot } from "./snapshot/format.js";
export { NotInSnapshotError, sessionFromSnapshot } from "./snapshot/read.js";
export { snapshotOf } from "./snapshot/write.js";
export type {
  AnalyticsEvent,
  SurveySpec,
  DeviceLogLine,
  SessionRecord,
  RecordKind,
  Finding,
  FindingInput,
  SurveyFinding,
  FindingRef,
  Label,
  LabelInput,
  LabelRecord,
  LogRecord,
  NetworkRequest,
  Objective,
  ScreenText,
  SessionSummary,
  StreamEvent,
  ToolCall,
  TraceSpan,
} from "./types.js";

import { survey } from "./survey.js";

/**
 * Same namespace shape authors know from `trailblaze.tool`: `trailblaze.survey(...)`.
 *
 * This module runs on the host, not in a browser: it reads sessions through `node:fs`, and its
 * YAML readers (`loadYamlWaypoints`, trailmap targets) need Bun.
 */
export const trailblaze = { survey };
