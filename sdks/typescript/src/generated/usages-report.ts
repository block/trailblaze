// AUTO-GENERATED — do not edit by hand.
//
// TypeScript types for the `trailblaze usages --json` report, derived from the Kotlin
// @Serializable models. Kotlin is canonical; this is the derived artifact.
//
// Field semantics, the diagnostic-kind registry, and the versioning policy are documented in
// `docs/usages-json.md`. Read that before gating CI on any field.
//
// Regenerate with `./gradlew :trailblaze-models:generateDtoTsUsagesReport`; CI's `verifyDtoTs`
// byte-diffs this file against a fresh generation and fails the build on drift, so hand edits
// are reverted on the next CI run.

export interface ChangedSinceSummary {
  ref: string;
  resolvedSha: string;
  added?: string[];
  removed?: string[];
  modified?: string[];
  impactedViaCallers?: string[];
  workingTree?: WorkingTreeState | null;
}

export interface ToolUsageResult {
  tool: string;
  usages: TrailToolUsage[];
  changeKind?: string;
  sourcePaths?: string[];
}

export interface ToolUsagesReport {
  schemaVersion?: number;
  trailsRoot: string;
  scannedRoots?: string[];
  tools: ToolUsageResult[];
  warnings?: string[];
  diagnostics?: UsagesDiagnostic[];
  changedSince?: ChangedSinceSummary | null;
  generatedBy?: string | null;
}

export interface TrailStepToolUsage {
  stepIndex?: number | null;
  step: string;
  classifiers: string[];
  declaredClassifiers: string[];
}

export interface TrailToolUsage {
  trail: string;
  path: string;
  root: string;
  title?: string | null;
  classifiers: string[];
  skip?: Record<string, string> | null;
  steps: TrailStepToolUsage[];
  devices?: string[];
  invokingDevices?: string[];
}

export interface UsagesDiagnostic {
  kind: string;
  subject: string;
  message: string;
  severity?: string;
}

export interface WorkingTreeState {
  headSha: string;
  dirty: boolean;
}
