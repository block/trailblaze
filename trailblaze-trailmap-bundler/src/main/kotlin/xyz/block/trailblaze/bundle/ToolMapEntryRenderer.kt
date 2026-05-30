package xyz.block.trailblaze.bundle

/**
 * Shared renderer for a single `TrailblazeToolMap` entry — the `<name>: { args: ...; result: ... }`
 * block emitted by both [TrailblazeTrailmapBundler] (per-trailmap `tools.d.ts`) and
 * [WorkspaceClientDtsGenerator] (per-trailmap `client.d.ts`). Both files declaration-merge into
 * the same `TrailblazeToolMap` interface, so they MUST produce identical per-entry output —
 * a drift here is hard to spot until two consumers see contradictory types for the same tool.
 *
 * Callers wrap the entry block with their own header (`// GENERATED ...` comment) and the
 * surrounding `declare module "@trailblaze/scripting" { interface TrailblazeToolMap { ... } }`
 * scaffold. This renderer owns the *per-entry shape* only.
 *
 * **Parallel emitter-local shapes.** See
 * [WorkspaceClientDtsGenerator.ToolEntry] kdoc for the full rationale on why three shapes
 * (bundler-local, generator-local, this file's render-input shape) coexist rather than
 * being collapsed into one. Single authority avoids drift if the consolidation rationale
 * ever shifts.
 *
 * **`sourceAttribution`.** Optional per-entry source line surfaced inside the JSDoc block —
 * the per-trailmap bundler emits `Source: ./tools/foo.ts` for traceability, while the workspace
 * generator omits it because its entries can come from Kotlin descriptors that don't have a
 * single file source. Pass `null` (or blank string — treated the same) to skip.
 */
internal data class ToolMapEntry(
  val name: String,
  val description: String?,
  val params: List<ToolMapParam>,
  val sourceAttribution: String? = null,
  /**
   * When non-null, overrides the per-[params] decomposition and emits this literal type
   * as the entry's `args:` half verbatim. Set by the analyzer-aware codegen path so a
   * tool authored via `trailblaze.tool<I, O>({ handler })` emits the full `I` shape —
   * including nested objects and TSDoc — rather than the YAML-derived flat decomposition.
   * Renderer caller is responsible for choosing the right indentation when producing the
   * literal so it lines up under `args:` (see [JsonSchemaToTsRich.render]'s `baseIndent`).
   *
   * **Naming asymmetry.** This field carries the `Literal` qualifier (vs sibling
   * [resultTsType] without it) because `args:` has TWO render modes: the per-[params]
   * decomposition for legacy YAML-driven tools AND this literal override. Without
   * `Literal`, the field name would ambiguously suggest it always wins. `result:` has
   * only one rendered shape today (a single TS type, either the analyzer's `O` or the
   * fallback `string`), so its field name keeps it simple.
   */
  val argsLiteralTsType: String? = null,
  /**
   * When non-null, overrides the renderer's default `string` and emits this literal type
   * as the entry's `result:` half. Set by the analyzer-aware codegen path so a typed-
   * authored tool's `O` flows through verbatim. When null, the renderer still emits
   * `result: string;` — the today-default until [structuredContent] wire shape lands.
   *
   * Named without the `Literal` qualifier (vs sibling [argsLiteralTsType]) because
   * `result:` has only one rendered shape; there's no decomposition path to disambiguate
   * against — see [argsLiteralTsType] kdoc for the full asymmetry rationale.
   */
  val resultTsType: String? = null,
  /**
   * Optional `TrailblazeToolClass` annotation values for this tool, projected to
   * [ToolFrameworkMetadata]. When non-null, [renderToolMapEntry] emits one
   * `@trailblaze<Concept>` JSDoc tag line for each field that deviates from its default —
   * `@trailblazeHiddenFromLlm`, `@trailblazeNotRecordable`, `@trailblazeHostOnly`,
   * `@trailblazeTrailheadTo <waypoint>`. [ToolFrameworkMetadata.toJsDocTagLines] is the
   * single source of truth for the emitted tag set. All-defaults metadata (or `null`)
   * emits no tag block, so the generated `.d.ts` stays tight for the common case
   * (built-in surfaced-to-llm recordable device-capable tools).
   *
   * Scripted-tool emitters pass `null` until a separate mechanism surfaces equivalent
   * facts from the scripted-tool authoring side.
   */
  val frameworkMetadata: ToolFrameworkMetadata? = null,
)

internal data class ToolMapParam(
  val name: String,
  val tsType: String,
  val description: String?,
  val optional: Boolean,
)

/**
 * Renders one `TrailblazeToolMap` entry — JSDoc block, the `name: { args; result }` body —
 * with consistent 4-/6-/8-space indentation that matches the surrounding emitter scaffold.
 * Output ends with a trailing newline so consecutive entries are separated by a blank line
 * when the caller adds one between iterations.
 *
 * The `args:` and `result:` halves each pick one of two render modes per entry:
 *  - **Literal mode** ([ToolMapEntry.argsLiteralTsType] / [ToolMapEntry.resultTsType]
 *    non-null) — emit the supplied TS type verbatim. Used by the analyzer-driven path
 *    for tools authored via `trailblaze.tool<I, O>({ handler })`.
 *  - **Fallback mode** — `args` is decomposed from [ToolMapEntry.params] (or
 *    `Record<string, never>` for no params); `result` falls back to `string`. Used by
 *    YAML-authored tools and Kotlin descriptors that don't yet declare a typed result.
 */
internal fun renderToolMapEntry(entry: ToolMapEntry): String = buildString {
  // Normalize blank attribution → no attribution. Without this the renderer emits a
  // degenerate `     * Source: ` bare line when a caller passes `""` (vs `null`).
  // Cheaper than burdening every adapter with the normalization.
  val hasSource = !entry.sourceAttribution.isNullOrBlank()
  val frameworkTags = entry.frameworkMetadata?.toJsDocTagLines().orEmpty()
  val hasFrameworkTags = frameworkTags.isNotEmpty()
  appendLine("    /**")
  if (entry.description != null) {
    entry.description.lines().forEach { line ->
      appendLine("     * ${escapeJsDocComment(line)}")
    }
    if (hasSource || hasFrameworkTags) {
      appendLine("     *")
    }
  }
  if (hasSource) {
    appendLine("     * Source: ${entry.sourceAttribution}")
    if (hasFrameworkTags) {
      appendLine("     *")
    }
  }
  // Framework tags follow the description (and any source attribution) so a reader's eye
  // sees the "what this tool does" prose first, then the operational facts. Tag-only entries
  // (no description, no source) still produce a valid JSDoc block — the leading `/**` and
  // trailing `*/` are unconditional and the renderer never emits an empty `*` separator
  // line before the first tag because there's nothing to separate it from.
  frameworkTags.forEach { tag ->
    appendLine("     * $tag")
  }
  appendLine("     */")
  val tsName = if (isSafeTsIdentifier(entry.name)) entry.name else "\"${entry.name}\""
  appendLine("    $tsName: {")
  val argsLiteral = entry.argsLiteralTsType
  if (argsLiteral != null) {
    // Analyzer-driven path: a `trailblaze.tool<I, O>({ handler })` tool whose `I` was
    // serialized to a TS type literal by [JsonSchemaToTsRich]. The literal is already
    // indented to line up with the surrounding block — emit it verbatim. This branch is
    // the whole point of the per-trailmap `.d.ts` knowing about the analyzer: nested object
    // shapes, discriminated unions, etc. land here intact instead of being lossily
    // decomposed into the flat [params] vocabulary.
    appendLine("      args: $argsLiteral;")
  } else if (entry.params.isEmpty()) {
    appendLine("      args: Record<string, never>;")
  } else {
    appendLine("      args: {")
    entry.params.forEach { param ->
      if (param.description != null) {
        appendLine("        /** ${escapeJsDocComment(param.description)} */")
      }
      val maybeOptional = if (param.optional) "?" else ""
      val key = if (isSafeTsIdentifier(param.name)) param.name else "\"${param.name}\""
      appendLine("        $key$maybeOptional: ${param.tsType};")
    }
    appendLine("      };")
  }
  // Analyzer-driven path overrides the today-default `string`. When the analyzer has typed
  // the tool's output via `trailblaze.tool<I, O>({ handler })`, [resultTsType] carries the
  // serialized `O` shape (nested objects, unions, etc.) — emit it verbatim so the consumer
  // sees the real type on IDE hover.
  //
  // Wire-shape note: PR #3329 landed the `structuredContent` envelope and the SDK-side
  // unwrap, so a typed `result:` is no longer a compile-time lie — the runtime path returns
  // the typed payload directly to `client.tools.<name>` callers when the producer populates
  // `structuredContent`. For YAML-only tools (no analyzer entry, [resultTsType] null) the
  // renderer still falls back to `result: string;` — that path is the today-default until
  // YAML authoring surfaces a typed-result declaration too (separate follow-up).
  val resultLiteral = entry.resultTsType ?: "string"
  appendLine("      result: $resultLiteral;")
  appendLine("    };")
}

/**
 * Prevent an embedded JSDoc closer in a tool description from prematurely closing the
 * JSDoc block in the generated output. Mirrors the per-call-site `escapeComment` helpers
 * the two callers used to maintain independently.
 */
internal fun escapeJsDocComment(text: String): String = text.replace("*/", "* /")

/**
 * Project framework-metadata booleans + the trailhead waypoint string into a list of JSDoc
 * tag lines emitted above each tool's JSDoc block.
 *
 * **Emission rule.** Only fields whose value deviates from the annotation's default produce
 * a tag — the all-defaults case (surfaced-to-llm + recordable + on-device + non-verification
 * + non-trailhead + non-host-callback) emits nothing. This keeps the generated `.d.ts` quiet
 * for the overwhelming majority of tools and reserves visual weight for the outliers.
 *
 * **Tag spellings.** Prefixed with `trailblaze` (camelCase, per TSDoc convention for
 * multi-word tags) so an IDE hover unambiguously distinguishes them from standard
 * JSDoc/TSDoc tags (`@deprecated`, `@default`, `@param`, …) — and so a future TSDoc
 * standardization in this space won't collide with our names. Order is deterministic
 * (declaration order in the data class) so the generated file is byte-stable across
 * runs.
 */
internal fun ToolFrameworkMetadata.toJsDocTagLines(): List<String> = buildList {
  if (!surfaceToLlm) add("@trailblazeHiddenFromLlm")
  if (!isRecordable) add("@trailblazeNotRecordable")
  if (requiresHost) add("@trailblazeHostOnly")
  // `isNotBlank` (not `isNotEmpty`) for parity with the `sourceAttribution` check in
  // `renderToolMapEntry` — whitespace-only waypoint values should be treated the same as
  // empty (no tag emitted), otherwise we'd render `@trailblazeTrailheadTo    ` with a
  // payload that's literally whitespace.
  //
  // Escape the waypoint value through `escapeJsDocComment` for the same defense-in-depth reason
  // we apply it to descriptions and param comments — a `trailheadTo` value containing `*/` would
  // prematurely close the surrounding JSDoc block and syntax-error the rendered `.d.ts`. The
  // value comes from Kotlin annotation source today (low practical exposure), but the cost of
  // the escape is one call and pins the invariant against future drift where the value source
  // changes (e.g. a YAML-authored trailhead).
  if (trailheadTo.isNotBlank()) add("@trailblazeTrailheadTo ${escapeJsDocComment(trailheadTo)}")
}

/**
 * TypeScript identifier rule (subset): start with `[A-Za-z_$]`, then `[A-Za-z0-9_$]*`. The
 * conservative subset rather than the full Unicode-aware definition because tool/property
 * names in this codebase are ASCII snake/camelCase. A name failing this check is emitted as
 * a quoted property name (`"weird-name": ...`).
 */
internal fun isSafeTsIdentifier(name: String): Boolean {
  if (name.isEmpty()) return false
  val first = name[0]
  if (!(first.isLetter() || first == '_' || first == '$')) return false
  return name.all { it.isLetterOrDigit() || it == '_' || it == '$' }
}
