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
