package xyz.block.trailblaze.config.project

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.JsonObjectYamlSerializer
import xyz.block.trailblaze.config.ScriptedToolRuntime

/**
 * Author-friendly per-file shape for scripted MCP tools owned by a trailmap.
 *
 * Each trailmap puts one of these YAML files under `<trailmap>/tools/<tool-name>.yaml` and references
 * it from the trailmap manifest's `target.tools:` list. The trailmap loader decodes each file with
 * this shape, then translates [inputSchema] into a fully conformant JSON Schema before
 * handing the result to the runtime as an [InlineScriptToolConfig] (Decision 038).
 *
 * The flat schema shape is the design point: authors declare parameters as a plain map of
 * `name -> { type, description, ... }` rather than the JSON Schema ceremony of
 * `{ type: object, properties: { ... }, required: [ ... ] }`. The wrapping is mechanical,
 * so the loader does it.
 *
 * Example (preferred shape — top-level shortcut fields, no `_meta:` ceremony, no
 * `inputSchema: {}` boilerplate when the tool takes no args, and the `.ts` source
 * co-located with the descriptor under the owning trailmap per the library/target trailmap
 * convention #2783):
 * ```yaml
 * # trails/config/trailmaps/playwrightSample/tools/playwrightSample_web_openFixtureAndVerifyText.yaml
 * script: ./playwrightSample_web_openFixtureAndVerifyText.ts
 * name: playwrightSample_web_openFixtureAndVerifyText
 * description: Open the Playwright sample page and verify visible text.
 * supportedPlatforms:
 *   - web
 * inputSchema:
 *   relativePath:
 *     type: string
 *     description: Sample-app fixture path relative to the playwright-native examples root.
 *   text:
 *     type: string
 *     description: Visible text to assert on the loaded page.
 * ```
 *
 * The top-level `supportedPlatforms` field is sugar — it's translated into the namespaced
 * `_meta.trailblaze/supportedPlatforms` key that downstream framework consumers actually
 * read. The escape-hatch `_meta:` block is still available for arbitrary keys that don't
 * have shortcuts yet, but most authors should never need it.
 *
 * Note: `trailblaze/supportedPlatforms` values are case-insensitive at parse time
 * (`TrailblazeToolMeta.fromJsonObject` normalizes to uppercase). `[web]`, `[WEB]`, and
 * `[Web]` all decode to the same canonical form. Lowercase is the conventional shape since
 * it matches the TS-side context envelope.
 *
 * Note: per-tool `_meta.trailblaze/toolset` is intentionally absent here. Toolset membership
 * is owned by the toolset YAML (it claims its tools), not declared inverted on each tool.
 * The runtime [TrailblazeToolMeta] still parses a `trailblaze/toolset` field for legacy
 * authors who set it on raw MCP-server tools, but new trailmap-shape tools shouldn't author it.
 */
@Serializable
data class TrailmapScriptedToolFile(
  /**
   * Absolute or relative path to the JS/TS module backing this tool (or multi-tool descriptor
   * — see [tools]).
   *
   * **Resolution rule**: relative `script:` paths resolve against the **directory containing
   * the descriptor YAML file** — i.e. trailmap-relative. This means a tool whose descriptor lives
   * at `trailmaps/mypack/tools/myTool.yaml` can declare `script: ./myTool.ts` and the runtime
   * finds it at `trailmaps/mypack/tools/myTool.ts`. Absolute paths pass through unchanged.
   *
   * **Authoring convention**: the `.ts` source should live next to its descriptor under the
   * owning trailmap — `trails/config/trailmaps/<trailmap>/tools/<tool>.ts` co-located with
   * `trails/config/trailmaps/<trailmap>/tools/<tool>.yaml`. The `script:` field is then simply
   * `./tool-name.ts` (or `.js` for pre-compiled files). This keeps the trailmap a self-contained
   * directory that can be zipped or published as-is.
   */
  val script: String,
  /**
   * Tool name (single-tool shape). **Required iff [tools] is null.** When [tools] is set,
   * the descriptor declares multiple tools — each entry's `name:` lives under [tools], and
   * this top-level field MUST be absent.
   */
  val name: String? = null,
  val description: String? = null,
  /**
   * Top-level shortcut for `_meta: { trailblaze/requiresHost: true }`. Setting this to `true`
   * marks the tool as host-only — the on-device agent skips it at registration via the same
   * `TrailblazeToolMeta.shouldRegister` gate that Kotlin
   * `@TrailblazeToolClass(requiresHost = true)` already uses. Use it for tools that need
   * Node/Bun APIs (`node:fs`, `node:child_process`) or otherwise can't run inside the
   * on-device QuickJS bundle.
   *
   * Translated by [toInlineScriptToolConfig] into `_meta["trailblaze/requiresHost"] = true`
   * on the runtime [InlineScriptToolConfig]. Default `false` is a no-op — any explicit
   * `_meta["trailblaze/requiresHost"]` you set still flows through unchanged.
   */
  val requiresHost: Boolean = false,
  /**
   * Top-level shortcut for `_meta: { trailblaze/supportedPlatforms: [...] }`. Limits the
   * tool to the listed platforms — Trailblaze's per-target / per-driver gates use this to
   * filter the tool out of sessions running on other platforms. Values are case-insensitive
   * (`[web]`, `[WEB]`, `[Web]` all collapse to canonical form at runtime).
   *
   * Translated by [toInlineScriptToolConfig] into `_meta["trailblaze/supportedPlatforms"]`
   * on the runtime [InlineScriptToolConfig]. `null` (the default) is a no-op — any explicit
   * `_meta["trailblaze/supportedPlatforms"]` you set still flows through unchanged.
   */
  val supportedPlatforms: List<String>? = null,
  /**
   * Runtime selector (`subprocess` or `inProcess`) — see [ScriptedToolRuntime].
   *
   * `null` (the default) means in-process QuickJS. Set this to `subprocess` only when the
   * tool's own code needs Node APIs (`node:fs`, `node:child_process`, file locks, etc.); bun
   * runs `.ts` natively. There is no extension heuristic — a `.js` / `.mjs` / `.cjs` file is
   * NOT auto-routed to a subprocess, so a Node-API tool must declare `runtime: subprocess`.
   */
  val runtime: ScriptedToolRuntime? = null,
  /**
   * Escape hatch for arbitrary `_meta:` keys that don't have a top-level shortcut yet
   * (e.g., custom MCP-spec extensions, future framework hints). Most authors should
   * never need to set this — prefer the top-level fields ([requiresHost],
   * [supportedPlatforms]). Values supplied here merge with the top-level fields'
   * translations; on key conflicts, the top-level field wins (so a `requiresHost: true`
   * top-level field overrides a `_meta: { trailblaze/requiresHost: false }`).
   */
  @SerialName("_meta")
  @Serializable(with = JsonObjectYamlSerializer::class)
  val meta: JsonObject? = null,
  /**
   * Flat map of parameter name to property descriptor. Order is preserved by the YAML
   * decoder, which matters for the `properties` map (LLMs sometimes anchor on declaration
   * order) and for the derived `required` array (we emit it in the same order).
   *
   * Defaults to empty when omitted from the YAML — tools that take no arguments don't
   * need to write `inputSchema: {}` explicitly.
   *
   * **Single-tool shape only.** When [tools] is set this MUST be empty — each entry
   * carries its own `inputSchema:` under [tools].
   */
  @SerialName("inputSchema") val inputSchema: Map<String, ScriptedToolProperty> = emptyMap(),
  /**
   * **Multi-tool shape.** When set, this one descriptor declares N tools, all backed by the
   * same [script] (one author module exporting N named functions). The framework's MCP wrapper
   * synthesizer (subprocess path) and QuickJS bundler (in-process path) dedup by [script] so
   * the file is loaded into one process / one engine regardless of how many tools it exposes.
   *
   * Authors get the ergonomic "one related-tools file, plain `export function name(args, ctx,
   * client)` per tool" surface without dropping down to `mcp_servers:` + `trailblaze.run()`
   * plumbing. The framework wraps each script in an MCP server underneath as an
   * implementation detail; the developer surface stays plain Trailblaze scripted tools.
   *
   * Mutual exclusion with the top-level [name] / [description] / [inputSchema] fields:
   *
   *  - `tools == null` (today's default) → single-tool shape: [name] is required and the
   *    top-level [description] / [inputSchema] are the single tool's metadata.
   *  - `tools != null` → multi-tool shape: [name] / [description] / [inputSchema] MUST be
   *    absent at top level (each entry declares them under [tools]). The top-level
   *    [requiresHost] / [supportedPlatforms] / [meta] fields, when set, act as **file-wide
   *    defaults** that apply to every entry unless the entry overrides them — convenient for
   *    the common case where every tool in the descriptor shares the same platform / host
   *    gating.
   *
   * Example multi-tool shape:
   * ```yaml
   * # trailmaps/myapp/tools/myapp_sign_in.yaml
   * script: ./myapp_sign_in.ts
   * runtime: subprocess
   * supportedPlatforms: [web]   # applies to both entries
   * tools:
   *   - name: myapp_signIn
   *     description: Resolves an account by key and delegates to the worker.
   *     inputSchema:
   *       key: { type: string, required: false }
   *       account: { type: string, required: false }
   *   - name: myapp_signInWithCredentials
   *     description: Explicit email+password worker, owns the cache + lock.
   *     inputSchema:
   *       email: { type: string }
   *       password: { type: string }
   *       cacheKey: { type: string, required: false }
   * ```
   *
   * Author module shape:
   * ```ts
   * // myapp_sign_in.ts
   * export async function myapp_signIn(args, ctx, client) { ... }
   * export async function myapp_signInWithCredentials(args, ctx, client) { ... }
   * ```
   *
   * Empty lists are rejected (`require(tools.isNotEmpty())`) — an empty `tools: []` is
   * almost certainly an author mistake and silently passing it would register zero tools for
   * the descriptor, confusing every downstream "tool not found" debugging session.
   */
  val tools: List<TrailmapScriptedToolEntry>? = null,
) {
  /**
   * Returns true when this descriptor needs analyzer enrichment to recover one or more of
   * its runtime fields (name / description / inputSchema) from the sibling `.ts`'s typed
   * `trailblaze.tool<I, O>({...})` declaration. Three authoring shapes route through
   * enrichment:
   *
   *  1. **Meta-only** (`script:` + optional `_meta:` only — no [name], no [tools]). The
   *     analyzer is the sole source of name / description / inputSchema; the YAML carries
   *     only the `script:` pointer and any ancillary gate keys.
   *
   *  2. **Partial single-tool** ([name] set but [description] is null AND [inputSchema] is
   *     empty). The YAML carries the load-bearing tool name (for `target.tools:` resolution
   *     and per-trailmap dup detection) plus optional ancillary gates; the analyzer fills
   *     description and inputSchema from the sibling `.ts`'s `<I>` generic and TSDoc.
   *
   *  3. **Partial multi-tool** ([tools] is set, with at least one entry missing
   *     description AND inputSchema). The analyzer fills the missing fields per entry,
   *     matched by name against the `.ts`'s exported `trailblaze.tool(...)` bindings.
   *
   * Loaders that admit these shapes (`TrailblazeProjectConfigLoader.resolveRuntime` when a
   * `ScriptedToolEnrichment` is wired up) route descriptors flagged here through enrichment
   * to recover the analyzer-derived metadata. Loaders that don't (build-time paths that
   * haven't grown analyzer awareness yet) should surface a clear error citing this property
   * — silently skipping the descriptor would drop the tool from the runtime registry
   * without an actionable diagnostic.
   *
   * ## Where partial / meta-only descriptors do NOT work yet
   *
   * Concretely: any trailmap routed through `TrailblazeBundledConfigPlugin`
   * (`generateBundledTrailblazeConfig`) or through the `TrailblazeTrailmapBundler`
   * build-time path with `bundleEnabled = true`. Both paths skip analyzer enrichment
   * today and reject partial / meta-only descriptors with `must declare a top-level name:`
   * (meta-only) or "tool advertisement missing description/schema" (partial). If your
   * trailmap is bundled at build time, either set `bundleEnabled = false` (the workspace
   * compile already does analyzer-backed extraction for IDE autocomplete) or fully populate
   * the descriptor with explicit `description:` / `inputSchema:` fields until the bundlers
   * grow analyzer awareness.
   */
  fun requiresEnrichment(): Boolean {
    // Shape 1: meta-only. The analyzer is the sole source of name + everything else.
    if (tools == null && name == null) return true
    // Partial descriptors (shapes 2 + 3 below) only make sense when the script the
    // analyzer would extract from is a `.ts` file — `.js` / `.mjs` scripts go straight
    // through the legacy full-YAML path because the analyzer can't parse them. A `.js`
    // descriptor with `name:` but no `inputSchema:` is the legacy "no-args tool" shape;
    // routing it through enrichment would fail with "no typed declaration" even though
    // the descriptor is well-formed. `ignoreCase = true` covers macOS / Windows
    // filesystems where an author might author `foo.TS` and expect it to behave the
    // same — the analyzer's own filename check applies the same tolerance.
    if (!script.endsWith(".ts", ignoreCase = true)) return false
    // Shape 2: partial single-tool. `name:` is authoritative; analyzer fills description +
    // inputSchema. The author either omitted both fields OR they decoded to empty defaults
    // (`description: String? = null`, `inputSchema: emptyMap()`) — both are treated as
    // "let the analyzer fill these in" so a partial YAML can be as short as `script:` +
    // `name:`.
    if (tools == null && name != null && description == null && inputSchema.isEmpty()) return true
    // Shape 3: partial multi-tool. Any entry missing both description AND inputSchema
    // triggers enrichment for the whole descriptor — the analyzer subprocess walks the
    // whole `tools/` dir once, so amortizing partial-extraction across the file's exports
    // is no more expensive than full-extraction would be.
    if (tools != null && tools.any { it.description == null && it.inputSchema.isEmpty() }) return true
    return false
  }
}

/**
 * One entry in a [TrailmapScriptedToolFile.tools] list. Mirrors the per-tool subset of
 * [TrailmapScriptedToolFile]'s fields (no `script:` / `runtime:` / nested `tools:` — those live
 * once at the file level).
 *
 * **Inheritance semantics.** [requiresHost] / [supportedPlatforms] / [meta] are optional on
 * each entry and inherit from the file-wide defaults when absent:
 *
 *  - `requiresHost` — `null` (default) inherits the file-wide value. Set to `true` / `false`
 *    explicitly to override.
 *  - `supportedPlatforms` — `null` (default) inherits the file-wide list. An explicit empty
 *    list `[]` is treated as "no override" (same as null) since an empty supported-platforms
 *    set would gate the tool out of every session and is almost never authored intentionally.
 *  - `meta` — merged with the file-wide `_meta` map; per-entry keys win on conflict so an
 *    entry can override a single file-wide meta key without restating the rest.
 */
@Serializable
data class TrailmapScriptedToolEntry(
  val name: String,
  val description: String? = null,
  /** `null` (default) = inherit file-wide [TrailmapScriptedToolFile.requiresHost]. */
  val requiresHost: Boolean? = null,
  /** `null` or empty (default) = inherit file-wide [TrailmapScriptedToolFile.supportedPlatforms]. */
  val supportedPlatforms: List<String>? = null,
  /**
   * Per-entry `_meta` keys. Merged with the file-wide [TrailmapScriptedToolFile.meta] — keys
   * present on both win on the entry side.
   */
  @SerialName("_meta")
  @Serializable(with = JsonObjectYamlSerializer::class)
  val meta: JsonObject? = null,
  @SerialName("inputSchema")
  val inputSchema: Map<String, ScriptedToolProperty> = emptyMap(),
)

/**
 * One parameter on a [TrailmapScriptedToolFile.inputSchema]. Mirrors the JSON Schema fields the
 * authored trailmaps in this repo actually use (`type`, `description`, `enum`); deliberately not
 * a complete JSON Schema modeller. If we hit a tool that needs `items`, `format`, `minimum`,
 * etc., extend this class — don't reach for a generic JsonObject escape hatch.
 *
 * [required] defaults to `true` because the overwhelming majority of authored tool params
 * are required; flipping the default keeps the YAML quiet for the common case.
 */
@Serializable
data class ScriptedToolProperty(
  val type: String,
  val description: String? = null,
  val enum: List<String>? = null,
  val required: Boolean = true,
)

/**
 * Translates the author-friendly flat shape into the JSON-Schema-conformant
 * [InlineScriptToolConfig.inputSchema] the runtime expects (`{ type: object, properties:
 * {...}, required: [...] }`). Done at trailmap load time so authors never write the wrapper
 * and the runtime never sees the flat shape.
 *
 * Also validates per-property invariants that the YAML schema can't catch on its own —
 * notably that any author-declared `enum` has at least one value, since JSON Schema's
 * `enum` keyword requires a non-empty array.
 *
 * **Single-tool shape only.** Throws if the descriptor uses the multi-tool shape (`tools:`
 * is set). Use [toInlineScriptToolConfigs] for callers that want to accept either shape.
 */
fun TrailmapScriptedToolFile.toInlineScriptToolConfig(): InlineScriptToolConfig {
  require(tools == null) {
    "TrailmapScriptedToolFile.toInlineScriptToolConfig() called on a multi-tool descriptor (script '$script'); " +
      "callers that handle multi-tool descriptors must use toInlineScriptToolConfigs() instead."
  }
  val resolvedName = requireNotNull(name) {
    "Single-tool descriptor for script '$script' is missing the required top-level `name:` field. " +
      "Either set `name:` (single-tool shape) or use the multi-tool shape with `tools:`."
  }
  // Validate the tool name with a YAML-author-facing error message before we hand off to
  // `InlineScriptToolConfig`'s init block — same regex (the source of truth lives on
  // `InlineScriptToolConfig.TOOL_NAME_PATTERN`), but this site has the `<trailmap>/tools/<id>.yaml`
  // file shape in mind and produces a more actionable message that names the descriptor.
  require(InlineScriptToolConfig.TOOL_NAME_PATTERN.matches(resolvedName)) {
    "Invalid scripted-tool name '$resolvedName' in per-file descriptor for script '$script': must match " +
      "${InlineScriptToolConfig.TOOL_NAME_PATTERN} (letters, digits, _, -, ., starting with a " +
      "letter or _). Update the descriptor YAML's `name:` field to use a supported character set."
  }
  inputSchema.forEach { (propName, prop) ->
    prop.enum?.let { values ->
      require(values.isNotEmpty()) {
        "Tool '$resolvedName' property '$propName': `enum` must declare at least one value (JSON Schema " +
          "rejects an empty enum array). Either remove the `enum` field or list the allowed values."
      }
    }
  }
  // Validate types on known `trailblaze/...` _meta keys before we merge shortcuts. Without this,
  // a typo like `_meta: { trailblaze/supportedPlatforms: "android" }` (string, not list) would
  // silently slip through — the conflicting top-level shortcut would overwrite it before any
  // consumer noticed, masking the author error. We'd rather fail loudly with a `<trailmap>/tools/<id>.yaml`-aware
  // message at parse time.
  validateKnownMetaShapes(resolvedName, meta)
  // Merge the top-level shortcut fields [supportedPlatforms] and [requiresHost] into the
  // [_meta] JsonObject so downstream consumers that read namespaced keys directly
  // (`TrailblazeToolMeta`, toolset filtering, the on-device QuickJS bundler's host-vs-device
  // dispatch gate, etc.) keep working without needing to know about the top-level shortcut
  // fields. Top-level wins on key conflicts — explicit YAML `requiresHost: true` /
  // `supportedPlatforms: [android]` overrides any stale `_meta: { trailblaze/requiresHost:
  // false }` an author might have copied from a different descriptor.
  //
  // Folding `requiresHost: true` into `_meta` is what makes the on-device dispatch gate
  // actually skip host-only workspace tools. Without it, the on-device QuickJS bundler reads
  // `_meta` (finds no `trailblaze/requiresHost` key, defaults to false), registers the tool
  // on-device, the host dispatcher routes the call via `executeToolViaRpc`, and the on-device
  // side can't find the workspace's `.ts` source → "Unknown tool 'X' is not registered". The
  // typed `InlineScriptToolConfig.requiresHost` field already propagates, but only the meta
  // route reaches the dispatch gate.
  val mergedMeta = mergeMetaShortcuts(
    explicitMeta = meta,
    supportedPlatforms = supportedPlatforms,
    requiresHost = requiresHost,
  )
  return InlineScriptToolConfig(
    script = script,
    name = resolvedName,
    description = description,
    requiresHost = requiresHost,
    runtime = runtime,
    meta = mergedMeta,
    inputSchema = buildInputSchemaObject(inputSchema),
  )
}

/**
 * Shape-agnostic entry point. Returns:
 *
 *  - **Single-tool descriptor** (`tools == null`): a singleton list — one
 *    [InlineScriptToolConfig] equivalent to [toInlineScriptToolConfig].
 *  - **Multi-tool descriptor** (`tools != null`): one [InlineScriptToolConfig] per
 *    [TrailmapScriptedToolEntry], all sharing the descriptor's `script:` / `runtime:`. The
 *    framework's MCP wrapper synthesizer and QuickJS bundler dedup by `script:` so the same
 *    author module loads exactly once regardless of how many entries the descriptor declares.
 *
 * Multi-tool validation, performed before any expansion:
 *
 *  - `tools` must be non-empty.
 *  - Top-level `name` / `description` / `inputSchema` must NOT be set when `tools` is —
 *    each entry declares those under its own [TrailmapScriptedToolEntry].
 *  - Top-level `requiresHost` / `supportedPlatforms` / `_meta` are file-wide defaults that
 *    each entry inherits unless it overrides. Keeps the common case (every tool in the
 *    descriptor shares the same platform / host gating) terse.
 *
 * Production trailmap loaders that don't care about the shape (`TrailblazeProjectConfigLoader`)
 * call this and `flatMap` the result so they get a flat `List<InlineScriptToolConfig>`
 * regardless of how authors structured the descriptors.
 */
fun TrailmapScriptedToolFile.toInlineScriptToolConfigs(): List<InlineScriptToolConfig> {
  if (tools == null) return listOf(toInlineScriptToolConfig())

  // Multi-tool shape — enforce mutual exclusion before expanding.
  require(tools.isNotEmpty()) {
    "Multi-tool descriptor for script '$script' has an empty `tools:` list. Either remove " +
      "`tools:` and use the single-tool shape, or list at least one entry."
  }
  require(name == null) {
    "Multi-tool descriptor for script '$script' must not set a top-level `name:` — each " +
      "entry declares its own name under `tools:`. Got top-level name '$name'."
  }
  require(description == null) {
    "Multi-tool descriptor for script '$script' must not set a top-level `description:` — each " +
      "entry declares its own description under `tools:`."
  }
  require(inputSchema.isEmpty()) {
    "Multi-tool descriptor for script '$script' must not set a top-level `inputSchema:` — each " +
      "entry declares its own inputSchema under `tools:`."
  }

  // File-wide _meta validation runs once against the defaults so a typo in the shared
  // _meta surface fails loudly at descriptor parse time rather than once per entry.
  validateKnownMetaShapes("(file-wide defaults for script '$script')", meta)

  return tools.map { entry ->
    // Validate per-entry name + enum shapes (same checks the single-tool path runs).
    require(InlineScriptToolConfig.TOOL_NAME_PATTERN.matches(entry.name)) {
      "Invalid scripted-tool name '${entry.name}' in multi-tool descriptor for script '$script': " +
        "must match ${InlineScriptToolConfig.TOOL_NAME_PATTERN} (letters, digits, _, -, ., " +
        "starting with a letter or _). Update the entry's `name:` field."
    }
    entry.inputSchema.forEach { (propName, prop) ->
      prop.enum?.let { values ->
        require(values.isNotEmpty()) {
          "Entry '${entry.name}' property '$propName': `enum` must declare at least " +
            "one value (JSON Schema rejects an empty enum array)."
        }
      }
    }
    validateKnownMetaShapes(entry.name, entry.meta)

    // Inheritance: per-entry overrides win, file-wide defaults fill in. An empty
    // `supportedPlatforms` list on the entry is treated as "inherit" since gating a tool
    // out of every platform is almost never the intent.
    val effectiveRequiresHost = entry.requiresHost ?: requiresHost
    val effectiveSupportedPlatforms =
      entry.supportedPlatforms?.takeIf { it.isNotEmpty() } ?: supportedPlatforms
    // Merge file-wide defaults under per-entry overrides — per-entry keys win.
    val mergedExplicitMeta = when {
      meta == null && entry.meta == null -> null
      meta == null -> entry.meta
      entry.meta == null -> meta
      else -> buildJsonObject {
        meta.forEach { (k, v) -> put(k, v) }
        entry.meta.forEach { (k, v) -> put(k, v) }
      }
    }
    val mergedMeta = mergeMetaShortcuts(
      explicitMeta = mergedExplicitMeta,
      supportedPlatforms = effectiveSupportedPlatforms,
      requiresHost = effectiveRequiresHost,
    )
    InlineScriptToolConfig(
      script = script,
      name = entry.name,
      description = entry.description,
      requiresHost = effectiveRequiresHost,
      runtime = runtime,
      meta = mergedMeta,
      inputSchema = buildInputSchemaObject(entry.inputSchema),
    )
  }
}

/**
 * Fails fast on author errors that the YAML schema can't catch on its own — specifically,
 * type mismatches on the namespaced `trailblaze/...` keys. The runtime would eventually
 * misbehave if a string slipped in where a JsonArray is expected, but that error would surface
 * far from the descriptor file. We'd rather throw here with the descriptor name in the message.
 *
 * Only validates keys we know about (`trailblaze/requiresHost`, `trailblaze/supportedPlatforms`);
 * arbitrary author keys flow through unchecked.
 */
private fun validateKnownMetaShapes(toolName: String, meta: JsonObject?) {
  if (meta == null) return
  meta["trailblaze/requiresHost"]?.let { v ->
    require(v is JsonPrimitive && v.isBooleanLiteral()) {
      "Tool '$toolName' `_meta.trailblaze/requiresHost`: expected a boolean, got ${v::class.simpleName}. " +
        "Prefer the top-level `requiresHost: true|false` shortcut instead of authoring this key directly."
    }
  }
  meta["trailblaze/supportedPlatforms"]?.let { v ->
    require(v is JsonArray && v.all { it is JsonPrimitive && it.isString }) {
      "Tool '$toolName' `_meta.trailblaze/supportedPlatforms`: expected a YAML list of strings " +
        "(e.g. `[android, web]`), got ${v::class.simpleName}. Prefer the top-level " +
        "`supportedPlatforms: [...]` shortcut instead of authoring this key directly."
    }
  }
}

/** Recognizes `true` and `false` JSON literals; rejects e.g. the string `"true"`. */
private fun JsonPrimitive.isBooleanLiteral(): Boolean =
  !isString && (content == "true" || content == "false")

/**
 * Folds the top-level shortcut field [TrailmapScriptedToolFile.supportedPlatforms] onto the
 * explicit `_meta` JsonObject, producing the runtime-shaped meta map. Returns `null` when both
 * sources are empty so [InlineScriptToolConfig.meta] stays absent rather than becoming an
 * empty object (downstream code distinguishes "no meta" from "empty meta" in some paths).
 */
private fun mergeMetaShortcuts(
  explicitMeta: JsonObject?,
  supportedPlatforms: List<String>?,
  requiresHost: Boolean,
): JsonObject? {
  val explicit = explicitMeta ?: JsonObject(emptyMap())
  val needsSupportedPlatforms = !supportedPlatforms.isNullOrEmpty()
  // Only fold the requiresHost shortcut into _meta when it's `true`. A false top-level value
  // is the schema default — emitting `trailblaze/requiresHost: false` into the meta object
  // would change the wire shape for the common case (descriptor without an explicit
  // requiresHost) and could surprise downstream consumers that distinguish "key absent"
  // from "key explicitly false". Authors that need the explicit-false shape can author it
  // directly in `_meta`.
  val needsRequiresHost = requiresHost
  if (explicit.isEmpty() && !needsSupportedPlatforms && !needsRequiresHost) {
    return null
  }
  return buildJsonObject {
    // Write explicit map first, then write shortcut keys after. `buildJsonObject`'s `put` is
    // last-write-wins, so the shortcut overrides any conflicting key the author copied into
    // their `_meta:` block.
    explicit.forEach { (k, v) -> put(k, v) }
    if (needsSupportedPlatforms) {
      put(
        "trailblaze/supportedPlatforms",
        buildJsonArray { supportedPlatforms!!.forEach { add(JsonPrimitive(it)) } },
      )
    }
    if (needsRequiresHost) {
      put("trailblaze/requiresHost", JsonPrimitive(true))
    }
  }
}

/**
 * Translates a flat author-shape `Map<paramName, ScriptedToolProperty>` into the
 * JSON-Schema object the runtime expects (`{ type: "object", properties: { ... },
 * required: [ ... ] }`). Used by both the legacy full-YAML path (via
 * [toInlineScriptToolConfig]) and the analyzer-enrichment path
 * (`AnalyzerScriptedToolEnrichment` in `:trailblaze-host`) when a partial descriptor's
 * `inputSchema:` is non-empty and should win over the analyzer's
 * `<I>`-generic-extracted schema. Promoted from `private` to public so the
 * cross-module enrichment call site can reach it without duplicating the
 * translation rules.
 */
fun buildInputSchemaObject(properties: Map<String, ScriptedToolProperty>): JsonObject =
  buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("properties", buildPropertiesObject(properties))
    // Iterate `entries` rather than `filterValues(...).keys` so the resulting list ordering
    // is unambiguously declaration-order — Kotlin's stdlib LinkedHashMap preserves insertion
    // order, but the test suite asserts on order and we want the contract on the surface,
    // not relying on stdlib impl details.
    val requiredNames = properties.entries.filter { it.value.required }.map { it.key }
    if (requiredNames.isNotEmpty()) {
      put("required", buildRequiredArray(requiredNames))
    }
  }

private fun buildPropertiesObject(properties: Map<String, ScriptedToolProperty>): JsonObject =
  buildJsonObject {
    properties.forEach { (name, prop) ->
      put(name, buildPropertyObject(prop))
    }
  }

private fun buildPropertyObject(prop: ScriptedToolProperty): JsonObject =
  buildJsonObject {
    put("type", JsonPrimitive(prop.type))
    prop.description?.let { put("description", JsonPrimitive(it)) }
    prop.enum?.let { values ->
      put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }
  }

private fun buildRequiredArray(requiredNames: List<String>): JsonArray =
  buildJsonArray { requiredNames.forEach { add(JsonPrimitive(it)) } }
