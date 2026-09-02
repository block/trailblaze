// Environment-agnostic core for the interactive run report, used by the in-app "Share" button
// (browser: share-export.jsx) — it already holds the derived trace/llmLogs and supplies screenshots
// fetched from /static, then calls buildRunReportHtml here.
//
// Entry module for a BUNDLED plain-JS artifact: the Gradle task `bundleRunReportCore`
// (:trailblaze-report) emits `run-report-core.js` from this module graph via `bun build
// --format=iife` into the module's generated JAR resources — the .ts sources are NOT packaged.
// Consumers load the emitted .js: the Trail Runner web app as a classic <script> (NOT
// type=text/babel), and the bun driver (run-report-cli.ts) via `require()`. The exported surface:
//  - Browser: every RUN_REPORT_EXPORTS key is assigned onto `window` (classic-script globals the
//    web app's screens call directly).
//  - bun/node `require()`: the bundler captures `module` inside the IIFE, so the Gradle task
//    appends a one-line CommonJS footer that republishes the exports from the
//    `__TRAILBLAZE_RUN_REPORT_CORE__` global set below.
//  - `require()` of this TS source (bun test): the ordinary ESM re-exports at the bottom.
// The viewer embedded into exported report HTML is a real prebuilt bundle
// (run-report-viewer-boot.ts via the bun macro in run-report-viewer-bundle.macro.ts) — no
// function serialization, so modules here may freely close over module scope.
import { RUN_REPORT_CSS } from './run-report-css';
import {
  describeAction, describeSelector, estimateLlmComp, extractLlmLogs, extractLlmTranscripts,
  extractTrace, localRunAgentPrompt, logClass, originalYamlFromLogs, packSessionInputsHierarchies,
  parseLlmResponse, slimLlmForShare, slimTraceForShare, stepText, summarizeToolArgs, toolChildren,
  toolDetail, toSessionPayloads, traceHierarchies, traceScreenshotFiles, transcriptCallMessages, truncate,
  yamlRootSection,
} from './run-report-extract';
import { buildMultiReportHtml, buildRunReportHtml } from './run-report-html';
// Out-of-directory like the selector-engine wrapper: the events module lives beside the bun driver
// (report/) because the driver imports it as a staged sibling; the viewer/zip surfaces reach the
// SAME implementation through this bundle so attachment detection can't fork per surface.
import {
  ATTACHMENT_EMBED_MAX_TOTAL_BYTES, ATTACHMENT_INLINE_MAX_BYTES, ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES, ATTACHMENT_MIME, MAX_ATTACHMENTS_PER_SESSION, MAX_EVENT_STREAM_BYTES, MAX_EVENT_STREAMS_TOTAL_CHARS, attachmentRefOf, buildEventStream,
  collectStreamAttachmentRefs, findAttachmentRefs, isSafeSessionRelativePath,
} from '../../../report/run-report-events';
import { isSelectorAnalyzableTree } from './run-report-selectors';
import { eventPrettyText, inflateEventsGz, inflateGzJsonRecord, inflateGzText, inflateLlmMessagesGz, normalizeEventPayload, rawPrettyText, rekeySprites, jsonToYaml, transcriptToolCallYaml, transcriptToolResultDisplay } from './run-report-payload';
import {
  buildExportSchedule, buildPlaybackSchedule, exportGapMs, playbackGapMs, playbackPositionAt,
  spriteFrameCss, videoEndMs, videoFrameAt, videoLoopFrame,
} from './run-report-playback';
import { RUN_REPORT_VIEWER } from './run-report-viewer';

// Export to the browser global scope (classic script); bun/node (require) get the same object via
// the Gradle-appended CommonJS footer reading __TRAILBLAZE_RUN_REPORT_CORE__ (see header).
const RUN_REPORT_EXPORTS = {
  truncate, logClass, originalYamlFromLogs, yamlRootSection, localRunAgentPrompt, extractTrace, toolChildren, describeAction, parseLlmResponse, extractLlmLogs, estimateLlmComp,
  extractLlmTranscripts, transcriptCallMessages, jsonToYaml, transcriptToolCallYaml, transcriptToolResultDisplay, stepText, toolDetail, summarizeToolArgs, describeSelector,
  slimTraceForShare, slimLlmForShare, toSessionPayloads, traceScreenshotFiles, traceHierarchies, packSessionInputsHierarchies, isSelectorAnalyzableTree, buildRunReportHtml, buildMultiReportHtml, inflateGzText, inflateEventsGz, inflateLlmMessagesGz, inflateGzJsonRecord, normalizeEventPayload, eventPrettyText, rawPrettyText, rekeySprites, RUN_REPORT_CSS, RUN_REPORT_VIEWER,
  attachmentRefOf, findAttachmentRefs, collectStreamAttachmentRefs, buildEventStream,
  ATTACHMENT_INLINE_MAX_BYTES, MAX_ATTACHMENTS_PER_SESSION, ATTACHMENT_EMBED_MAX_TOTAL_BYTES, ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES, ATTACHMENT_MIME, isSafeSessionRelativePath, MAX_EVENT_STREAM_BYTES, MAX_EVENT_STREAMS_TOTAL_CHARS,
  playbackGapMs, exportGapMs, videoFrameAt, videoEndMs, spriteFrameCss, buildPlaybackSchedule, buildExportSchedule, playbackPositionAt, videoLoopFrame,
};
(globalThis as Record<string, unknown>).__TRAILBLAZE_RUN_REPORT_CORE__ = RUN_REPORT_EXPORTS;
if (typeof window !== 'undefined') Object.assign(window, RUN_REPORT_EXPORTS);

export {
  truncate, logClass, originalYamlFromLogs, yamlRootSection, localRunAgentPrompt, extractTrace, toolChildren, describeAction, parseLlmResponse, extractLlmLogs, estimateLlmComp,
  extractLlmTranscripts, transcriptCallMessages, jsonToYaml, transcriptToolCallYaml, transcriptToolResultDisplay, stepText, toolDetail, summarizeToolArgs, describeSelector,
  slimTraceForShare, slimLlmForShare, toSessionPayloads, traceScreenshotFiles, traceHierarchies, packSessionInputsHierarchies, isSelectorAnalyzableTree, buildRunReportHtml, buildMultiReportHtml, inflateGzText, inflateEventsGz, inflateLlmMessagesGz, inflateGzJsonRecord, normalizeEventPayload, eventPrettyText, rawPrettyText, rekeySprites, RUN_REPORT_CSS, RUN_REPORT_VIEWER,
  attachmentRefOf, findAttachmentRefs, collectStreamAttachmentRefs, buildEventStream,
  ATTACHMENT_INLINE_MAX_BYTES, MAX_ATTACHMENTS_PER_SESSION, ATTACHMENT_EMBED_MAX_TOTAL_BYTES, ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES, ATTACHMENT_MIME, isSafeSessionRelativePath, MAX_EVENT_STREAM_BYTES, MAX_EVENT_STREAMS_TOTAL_CHARS,
  playbackGapMs, exportGapMs, videoFrameAt, videoEndMs, spriteFrameCss, buildPlaybackSchedule, buildExportSchedule, playbackPositionAt, videoLoopFrame,
};
