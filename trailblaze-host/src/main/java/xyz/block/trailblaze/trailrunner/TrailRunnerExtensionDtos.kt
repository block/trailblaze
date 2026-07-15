package xyz.block.trailblaze.trailrunner

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// Trail Runner extension-surface DTOs.
//
// These are the data types exchanged across the [TrailRunnerExtension] seam: a
// downstream desktop build supplies the values, and
// the Trail Runner HTTP API serializes them to the web UI. They live in OSS
// `trailblaze-host` — alongside [TrailblazeDesktopAppConfig] and the extension
// interface that references them — so the seam itself is fully open-source, while
// the *implementations* that produce the values stay in the downstream build.
//
// They keep the `xyz.block.trailblaze.trailrunner` package they were authored under
// so the endpoint/route code and the DTO→TS codegen keep resolving them unchanged.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class IntegrationDto(
  val id: String,
  val name: String,
  val connected: Boolean,
  val detail: String,
  val action: IntegrationActionDto? = null,
)

@Serializable
data class IntegrationActionDto(val id: String, val label: String)

@Serializable
data class AnalyticsEventDto(
  val id: String,
  val name: String,
  val timeMs: Long,
  val source: String? = null,
  val properties: Map<String, String> = emptyMap(),
)

// ─── Blaze authoring: propose + review ─────────────────────────────────────────
// One proposed step. `kind` is "do" (a DirectionStep) or "verify" (a VerificationStep).
@Serializable
data class ProposedStep(val kind: String, val text: String)

// One AI authoring-assist suggestion from the "Review my trail" pass. Read-only advice the UI
// surfaces as an accept/reject card; applying it is a separate, explicit edit.
//  - kind "assertion-gap": a step acts on the UI but never verifies the outcome; [suggestedStep]
//    is a proposed "verify: …" the user can append.
//  - kind "fragile-selector": a step targets the UI by coordinate/index rather than semantics.
@Serializable
data class ReviewSuggestionDto(
  val kind: String,
  val title: String,
  val detail: String,
  val suggestedStep: String? = null,
)

// ─── Create: selector advice on a pending step ─────────────────────────────────
// The Create screen's confirm gate holds a proposed step (a chosen selector tool + the ranked
// alternatives). This assist asks a fast model for a second opinion on which candidate will
// replay most reliably. Strictly advisory and strictly time-boxed by the route: the pending
// card annotates if the verdict arrives inside the budget and shows nothing otherwise -
// confirming never waits on it.

/** One selector candidate the author could pick (mirrors the recorder's option card). */
@Serializable
data class SelectorAdviceOption(
  val label: String,
  val toolName: String,
  val yaml: String,
)

@Serializable
data class SelectorAdviceRequest(
  /** The currently-chosen tool series for the pending step (a `- tools:` recording item). */
  val stepYaml: String,
  /** The author's natural-language description of the step, if written. */
  val prompt: String? = null,
  /** All resolver-ranked candidates for the gesture (selector tiers + coordinate fallback). */
  val options: List<SelectorAdviceOption> = emptyList(),
  /** Plain-language element identity (visible label + friendly type) for grounding. */
  val elementLabel: String? = null,
  val elementType: String? = null,
  val platform: String? = null,
)

/** The verdict: a one-sentence reason, plus the option the model would pick when it disagrees. */
@Serializable
data class SelectorAdvice(
  /** Plain-language rationale shown on the reasoning strip. */
  val reason: String,
  /**
   * Label of the [SelectorAdviceOption] the model recommends INSTEAD of the current choice, or
   * null to endorse it. Must match an option's label verbatim so the UI can offer a one-click swap.
   */
  val preferOption: String? = null,
)
