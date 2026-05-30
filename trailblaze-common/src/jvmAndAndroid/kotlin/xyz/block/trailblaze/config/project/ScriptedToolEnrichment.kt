package xyz.block.trailblaze.config.project

import xyz.block.trailblaze.config.InlineScriptToolConfig
import java.io.File

/**
 * Pluggable enrichment strategy for **meta-only** scripted-tool descriptors (see
 * [TrailmapScriptedToolFile.requiresEnrichment]).
 *
 * The loader can't run a Node-subprocess static analyzer itself — it lives in `:trailblaze-common`
 * `jvmAndAndroid`, shared between the JVM daemon and the Android-on-device runtime. Pulling
 * the analyzer (which spawns `node` + `ts-json-schema-generator`) into that source set would
 * drag a host-only dependency onto the device runtime classpath. Instead the loader accepts
 * an optional [ScriptedToolEnrichment] argument and the JVM host wires up the analyzer-
 * backed implementation.
 *
 * **When the loader uses this.** A trailmap manifest's `target.tools:` list names a tool, the
 * loader walks `<trailmap>/tools/` to find the matching descriptor, and the descriptor turns out
 * to be meta-only (no top-level `name:` AND no `tools:` list — `_meta:` + `script:` only).
 * Without enrichment the loader has no way to recover the tool's name or input schema.
 * With enrichment, the analyzer's extraction of the sibling `.ts` fills in both.
 *
 * **When it doesn't.** Descriptors with full YAML metadata (today's mode 1) skip enrichment
 * entirely; the YAML stays the source of truth for `name:` / `inputSchema:` / `description:`.
 * That keeps the existing path bit-for-bit identical and makes enrichment a strictly opt-in
 * upgrade for authors who want the slimmer YAML.
 *
 * **Why an interface, not a function type.** A future build-time enrichment strategy
 * (Gradle-side cached JSON sidecar from a prior `trailblaze compile` run) would plug into
 * the same seam without forcing the loader to learn about every concrete production path.
 * The interface keeps the substitution explicit at every call site.
 *
 * **JVM-only by design.** The interface takes `java.io.File`, which means it can only be
 * implemented in source sets that target the JVM (or Android, which honors `java.io.File`).
 * On-device QuickJS code paths don't go through this enrichment seam — meta-only
 * descriptors are a host-side authoring convenience that the daemon resolves before
 * shipping configs to the device. If a future on-device runtime needs runtime enrichment
 * (today: it doesn't), promote the parameters to `String` paths and move the interface to
 * `commonMain`.
 */
interface ScriptedToolEnrichment {

  /**
   * Resolve the meta-only descriptors discovered under [trailmapToolsDir] into runtime-shaped
   * [InlineScriptToolConfig]s. The implementation:
   *
   *  1. Walks the trailmap's `tools/` directory once via the analyzer (one Node subprocess
   *     per trailmap, not per descriptor) and indexes the analyzer's output by the source
   *     file's absolute path.
   *  2. For each [DeferredDescriptor] in [deferredDescriptors], looks up the analyzer
   *     entry for the descriptor's resolved `.ts` source path and synthesizes an
   *     [InlineScriptToolConfig] from the union of (descriptor's `_meta:` / `requiresHost` /
   *     `supportedPlatforms` / `runtime:`) and (analyzer's `name` / `inputSchema` /
   *     `description`).
   *
   * Implementations MUST be idempotent — the loader may call this multiple times during
   * a single resolution if a workspace has overlapping trailmap closures. The analyzer's own
   * caching pattern (see `PerTrailmapClientDtsEmitter.analyzeAllTrailmapsOnce`) is the canonical
   * way to deduplicate the subprocess cost.
   *
   * @return One [EnrichmentResult] per input descriptor, in input order. The loader
   *   distinguishes [EnrichmentResult.Resolved] (analyzer produced the typed declaration)
   *   from [EnrichmentResult.Failed] (descriptor's `.ts` had no typed declaration the
   *   analyzer could extract). Failures surface as load-time errors citing the
   *   descriptor path AND the analyzer's reason.
   *
   *   **`relativePath` correspondence contract.** Every returned
   *   [EnrichmentResult.relativePath] MUST equal a [DeferredDescriptor.relativePath] from
   *   the input — the loader looks the descriptor up by that key to recover the original
   *   parsed shape. Implementations that fabricate paths, omit input descriptors, or
   *   duplicate them violate the contract and the loader will throw a programmer-error
   *   `IllegalStateException` (rather than silently produce a malformed registry).
   *   Result ordering is unconstrained; the loader matches by `relativePath`, not index.
   */
  fun enrich(
    trailmapId: String,
    trailmapDir: File,
    trailmapToolsDir: File,
    deferredDescriptors: List<DeferredDescriptor>,
  ): List<EnrichmentResult>

  /**
   * One meta-only descriptor awaiting analyzer-derived resolution. Pairs the trailmap-relative
   * YAML path (for diagnostics) with the parsed descriptor (so the enrichment impl can
   * pull `script:`, `_meta:`, `requiresHost`, `supportedPlatforms`, `runtime:` off of it).
   */
  data class DeferredDescriptor(
    val relativePath: String,
    val descriptor: TrailmapScriptedToolFile,
  )

  /**
   * Outcome of resolving a single meta-only descriptor.
   *
   *  - [Resolved] — the analyzer extracted a typed declaration from the descriptor's
   *    `.ts` source and the enrichment produced one (or more) [InlineScriptToolConfig]s.
   *  - [Failed] — the analyzer ran but couldn't extract a typed declaration from the
   *    descriptor's `.ts` source (file missing, no `trailblaze.tool<...>` call, schema
   *    extraction error, etc.). The loader surfaces this as a load-time error citing
   *    the descriptor path and the analyzer's reason.
   */
  sealed class EnrichmentResult {
    abstract val relativePath: String

    data class Resolved(
      override val relativePath: String,
      val configs: List<InlineScriptToolConfig>,
    ) : EnrichmentResult()

    data class Failed(
      override val relativePath: String,
      val reason: String,
    ) : EnrichmentResult()
  }
}
