package xyz.block.trailblaze.bundle

/**
 * Codegen-side projection of `xyz.block.trailblaze.toolcalls.TrailblazeToolClass` annotation
 * fields that the per-trailmap `trailblaze-client.d.ts` emitter surfaces as structured JSDoc
 * tags on each tool's TS entry.
 *
 * Default values mirror the annotation's defaults so an emitter that doesn't supply metadata
 * for a tool emits no tags — the all-defaults case, which is the norm for built-in tools.
 * Only the deviating field(s) produce a tag line; the codegen never emits affirmative
 * `@trailblazeSurfacedToLlm` / `@trailblazeRecordable` noise.
 *
 * **`isVerification` deliberately omitted.** The annotation field exists for three
 * framework-internal consumers — the verify-mode dispatch gate in `StepToolSet`, the
 * recording-dedup walk in `TrailblazeRecordingGenerator`, and the mirrored field on
 * `TrailblazeLog` that lets the KMP recording generator dedup without JVM reflection.
 * None of those are touched by scripted-tool authors. The semantic content ("this is a
 * read-only assertion") is already conveyed by the tool names themselves
 * (`assertVisible`, `assertEquals`, `web_verifyTextVisible`, …) — a TS author reading
 * `client.tools.assertVisible(...)` doesn't need a tag to know it's an assertion.
 * Surfacing it as `.d.ts` JSDoc would advertise framework plumbing as if it were a
 * contract authors should code against — informational noise without an authorial use case.
 *
 * **Why a separate data class instead of passing the annotation directly:** the annotation
 * lives in `trailblaze-models` alongside the `TrailblazeTool` hierarchy. The codegen package
 * stays annotation-agnostic — adapters (e.g. `PerTrailmapClientDtsEmitter`) project
 * annotation values into this shape at the module boundary, and the renderer downstream
 * doesn't depend on the reflective surface.
 *
 * **`resultTsType` is not a JSDoc tag like the other fields** — it flows into
 * [xyz.block.trailblaze.bundle.WorkspaceClientDtsGenerator]'s `ToolEntry.resultTsType` and
 * renders as the tool's `result:` type literal instead of the `string` fallback. Null (the
 * default) means the tool's `@TrailblazeToolClass` didn't declare a `resultType` — the
 * renderer's existing `entry.resultTsType ?: "string"` fallback applies unchanged. Populated by
 * `PerTrailmapClientDtsEmitter` via the same `SerialDescriptorTsCodegen` walk
 * `BuiltInToolResultTsBindings` uses for the hand-curated SDK surface's built-ins, so a Kotlin
 * tool with a declared `resultType` renders identically on both sides — see the "MUST declare
 * identical result types" note in `built-in-tools.ts`.
 */
data class ToolFrameworkMetadata(
  val surfaceToLlm: Boolean = true,
  val isRecordable: Boolean = true,
  val requiresHost: Boolean = false,
  val trailheadTo: String = "",
  val resultTsType: String? = null,
)
