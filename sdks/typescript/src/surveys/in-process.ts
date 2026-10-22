// `@trailblaze/scripting/surveys` as a survey sees it while the trail runs: inside a scripted tool's
// sandbox, on the host or on a device. Survey bundles alias the package here. Nothing in this module
// reads files, so a survey that imports what is not here — `SurveySession.load`, the waypoint and
// workspace readers, `node:*` — fails to bundle, which is the cue to set `requiresFinishedSession`.

export { explainFinding, explainFootprint, isSurvey, matchFinding, matchFootprint, survey, withId } from "./survey.js";
export type { FindingExplanation, FindingQueries, FindingSpec, FootprintExplanation, FootprintQueries, FootprintSpec, SurveyContext, SurveyDefinition, SurveyHandler, SurveyResult } from "./survey.js";
export type { SurveySession } from "./session.js";
export { Records } from "./match.js";
export type {
  AnalyticsQuery,
  ContainsMatch,
  DeviceLogQuery,
  EventQuery,
  LogQuery,
  NetworkQuery,
  ObjectiveQuery,
  ScreenTextQuery,
  TextMatch,
  ToolQuery,
  TraceQuery,
  ValueMatch,
} from "./match.js";
export { compactRecord, runSurvey } from "./run-one.js";
export { FinishedSessionRequiredError, LiveSessionReader, runNamedLiveSurvey, runLiveSurvey } from "./live.js";
export type { LiveSurveyOptions, LiveSurveyResult, LiveEventSource, LiveRead, LiveSessionOptions, LiveSurveyOutcome } from "./live.js";
export type {
  AnalyticsEvent,
  DeviceLogLine,
  Finding,
  FindingInput,
  LogRecord,
  NetworkRequest,
  Objective,
  RecordKind,
  ScreenText,
  SessionRecord,
  SessionSummary,
  StreamEvent,
  SurveySpec,
  ToolCall,
  TraceSpan,
} from "./types.js";

import { survey } from "./survey.js";

/** `trailblaze.survey(...)`, the same namespace shape as the full entry. */
export const trailblaze = { survey };
