// `@trailblaze/scripting/surveys` — detect known features in finished sessions.
//
//   import { trailblaze } from "@trailblaze/scripting/surveys";
//
//   export const giftCardCheckout = trailblaze.survey(
//     { feature: "checkout.gift-card", description: "A sale was tendered with a gift card" },
//     (session) => {
//       const tender = session.network.find({ method: "POST", path: /\/payments$/, requestBody: "GIFT_CARD", status: "ok" });
//       if (!tender) return;
//       const receipt = session.analytics.find({ name: "Payment Complete" });
//       return { summary: "Gift card tender accepted", evidence: [tender, receipt].filter(Boolean) };
//     },
//   );
//
// Export one survey per feature from a `*.survey.ts` file, then run them over any number of
// sessions with the CLI in `cli.ts` or `runSurveys()` from code. Each session yields the list of
// features it exercised, each backed by the records that prove it.

export { survey, isSurvey, matchFootprint, surveyFeatures, withId } from "./survey.js";
export type { SurveyContext, SurveyDefinition, SurveyHandler, SurveyResult, FootprintSpec, FootprintQueries } from "./survey.js";
export { SurveySession, isSessionDirectory, parseIsoMs } from "./session.js";
export type { LogQuery } from "./session.js";
export { Records } from "./match.js";
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
export type { SurveyReport, FeatureSummary, RunOptions, SessionResult } from "./runner.js";
export { discoverSessions, loadSurveys } from "./discover.js";
export { loadTargetCatalog, sessionMatchesTarget, targetFromTrailmap, targetsOf } from "./targets.js";
export type { TargetCatalog, TargetDefinition } from "./targets.js";
export type { DiscoverSessionsOptions, LoadedSurvey } from "./discover.js";
export { renderMarkdown, renderSummaryLines } from "./report.js";
export type {
  AnalyticsEvent,
  SurveySpec,
  DeviceLogLine,
  SessionRecord,
  RecordKind,
  Finding,
  FindingInput,
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

/** Same namespace shape authors know from `trailblaze.tool`: `trailblaze.survey(...)`. */
export const trailblaze = { survey };
