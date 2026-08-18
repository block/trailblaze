package xyz.block.trailblaze.toolcalls

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.Console

/**
 * The resolved set of tools the LLM agent sees at session start for [target] running on
 * [driverType]. Mirrors the runtime composition in
 * `TrailblazeMcpServer.kt`'s inner-agent-tools-provider: declared toolsets resolved through
 * the driver-aware catalog, plus the target's custom tools, minus its driver-scoped
 * exclusions. The [surfaceToLlm] filter (applied at descriptor build time via
 * `toKoogToolDescriptor()`) is applied here too so callers don't have to re-filter.
 *
 * This is the single authoritative resolver — the `trailblaze check` markdown report, the
 * `toolbox` discovery surface, and any other "what does the LLM see?" caller should route
 * through this function so they stay consistent with what actually registers at session
 * start. Diverging here is the source of bug
 * `2026-05-22-agent-toolbox-report-driver-leak.md`.
 *
 * The LLM-visible surface has three kinds: class-backed ([toolClasses]), YAML-defined
 * ([yamlToolNames]), and scripted (`.ts` / `.js`) tools delivered by a toolset's `tools:`
 * ([scriptedToolNames]). Scripted tools are bundled + registered through a separate per-session
 * runtime, but they're still advertised to the LLM, so a resolver that claims to answer "what
 * does the LLM see?" must include them. Omitting them is what let `openUrl` — converted from a
 * class-backed tool to a scripted one in PR #3803 — silently disappear from this resolver while
 * the `trailblaze check` report still surfaced it (its driver-agnostic "Scripted tools" section).
 */
data class ResolvedAgentToolbox(
  override val toolClasses: Set<KClass<out TrailblazeTool>>,
  override val yamlToolNames: Set<ToolName>,
  override val scriptedToolNames: Set<ToolName> = emptySet(),
) : TrailblazeToolSurface

/**
 * One (target, driver) tool-scope resolution — **the** single source of truth for "which tools does
 * this session have?", shared by every runtime: on-device rules, the host runner, and the daemon.
 *
 * Two views derive from it and nothing else re-derives the scope:
 * - [getAgentToolboxForDriver] narrows it to the **advertised** view (applies the `surfaceToLlm`
 *   and YAML-config-presence filters) — what the LLM is offered.
 * - [xyz.block.trailblaze.model.toCustomTrailblazeToolsForDriver] turns it into a session tool
 *   repo — the **dispatchable** superset, which is deliberately wider so a recorded step can call
 *   a tool the LLM was never shown.
 *
 * Keeping the scope in one place is load-bearing, not tidiness. Each runtime previously composed
 * its own: the host runner and daemon resolved against the whole catalog while on-device resolved
 * against the trailmap, so the same (target, driver) got different tools depending on where it ran
 * — and the device's version exceeded the providers' 128-tool array cap.
 *
 * The partitions are kept separate from their union because [CustomTrailblazeTools] distinguishes
 * "the target's own tools" from "what the trailmap's toolsets delivered".
 */
data class ResolvedTargetToolScope(
  val driverType: TrailblazeDriverType,
  /**
   * The target's trailmap `tool_sets:` for this driver. Non-empty means this is the authoritative
   * catalog scope. **Empty means unconfigured, not "no tools"** — see [isScoped].
   */
  val declaredToolSetIds: List<String>,
  /** What [declaredToolSetIds] (plus `always_enabled`) delivered, before customs and exclusions. */
  val fromTrailmap: ResolvedAgentToolbox,
  val customToolClasses: Set<KClass<out TrailblazeTool>>,
  val customYamlToolNames: Set<ToolName>,
  /** `platforms.<p>.tools:` scripted names, plus the target's root `tools:` inline scripts. */
  val customScriptedToolNames: Set<ToolName>,
  val excluded: ResolvedToolExclusions,
  /**
   * The target this scope was resolved for. Carried so consumers can name it in logs — a daemon
   * session can have several targets bound, and a bare `driver=` line doesn't say which one a
   * given surface belongs to.
   *
   * Declared after the original parameters, like [CustomTrailblazeTools.toolSetIds], so adding it
   * doesn't shift the generated `componentN()` / `copy()` positions — those positions are public
   * API.
   */
  val targetId: String = "",
  /**
   * Human-readable problems with this target's declared `tool_sets:` — a typo'd id, one whose
   * `drivers:` exclude this driver, or none of them resolving at all.
   *
   * Computed here, at resolve time, rather than by whoever logs it: this is the only place the
   * catalog the scope was actually resolved against is in hand. Passing a catalog to a separate
   * reporting call let a caller resolve against a workspace overlay and report against the default,
   * inventing "no matching catalog entry" for toolsets that exist. It is also plain data, so the
   * three conditions can be asserted directly instead of by capturing log output.
   */
  val declaredToolSetProblems: List<String> = emptyList(),
) : TrailblazeToolSurface {
  override val toolClasses: Set<KClass<out TrailblazeTool>> =
    fromTrailmap.toolClasses + customToolClasses - excluded.toolClasses
  override val yamlToolNames: Set<ToolName> =
    fromTrailmap.yamlToolNames + customYamlToolNames - excluded.yamlToolNames
  override val scriptedToolNames: Set<ToolName> =
    fromTrailmap.scriptedToolNames + customScriptedToolNames - excluded.scriptedToolNames

  /**
   * Whether this target actually declared a scope for this driver.
   *
   * A target that declares nothing hasn't opted out of every toolset — it simply hasn't been
   * configured for this driver, and a session narrowed to `always_enabled` alone can't verify
   * anything or navigate. `getDeclaredToolSetIdsForDriver` can't distinguish "unset" from
   * "declared empty", so empty is read as unset and the session keeps the whole driver-compatible
   * catalog, exactly as it did before scoping existed.
   *
   * [fromTrailmap] has already applied that fallback, so consumers read [toolClasses] and friends
   * without re-deciding. This flag exists for the one consumer that needs the distinction itself:
   * `toolSetIds` is only meaningful when a scope was actually declared.
   *
   * Reachable in practice: `DefaultTrailblazeHostAppTarget` (the discovery fallback) declares
   * nothing, and a target with no `platforms.web` block declares nothing on a web driver.
   */
  val isScoped: Boolean = declaredToolSetIds.isNotEmpty()
}

/**
 * Resolves the [ResolvedTargetToolScope] for this target on [driverType]. Every session composition
 * starts here; see that class for why there is exactly one of these.
 */
fun TrailblazeHostAppTarget.resolveToolScopeForDriver(
  driverType: TrailblazeDriverType,
  catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
): ResolvedTargetToolScope {
  val declaredToolSetIds = getDeclaredToolSetIdsForDriver(driverType)
  return ResolvedTargetToolScope(
    driverType = driverType,
    declaredToolSetIds = declaredToolSetIds,
    // The unconfigured -> whole-catalog decision lives HERE, not in a consumer, so every view built
    // on this scope inherits it. When only the repo composer applied it, an unconfigured target
    // advertised `always_enabled` alone through `getAgentToolboxForDriver` and the daemon's
    // `tools/list` while its session repo dispatched the whole catalog — the same advertise-vs-
    // dispatch split this class exists to prevent, just moved one layer down.
    fromTrailmap = if (declaredToolSetIds.isEmpty()) {
      ResolvedAgentToolbox(
        toolClasses = TrailblazeToolSetCatalog.defaultToolClassesForDriver(driverType, catalog),
        yamlToolNames = TrailblazeToolSetCatalog.defaultYamlToolNamesForDriver(driverType, catalog),
        scriptedToolNames =
          TrailblazeToolSetCatalog.defaultScriptedToolNamesForDriver(driverType, catalog),
      )
    } else {
      TrailblazeToolSetCatalog.resolveForDriver(
        driverType = driverType,
        requestedIds = declaredToolSetIds,
        catalog = catalog,
      ).let { ResolvedAgentToolbox(it.toolClasses, it.yamlToolNames, it.scriptedToolNames) }
    },
    customToolClasses = getCustomToolsForDriver(driverType),
    customYamlToolNames = getCustomYamlToolNamesForDriver(driverType),
    // Both scripted inclusion buckets a target can author: a bare name in `platforms.<p>.tools:`,
    // and a root `tools:` inline script. Unioned here so no consumer has to remember both exist —
    // forgetting the second is what under-reported the surface for self-authoring targets.
    customScriptedToolNames = getCustomScriptedToolNamesForDriver(driverType) +
      getInlineScriptTools().map { ToolName(it.name) },
    // Single entry point for the target's `excluded_tools:` opt-outs across all three backings
    // (class / YAML / scripted) — see [getExcludedToolSurfaceForDriver]. Reading the surface once
    // here is what keeps this resolver from re-introducing the "subtract class + YAML but forget
    // scripted" drift that hand-rolled per-partition unions repeatedly caused.
    excluded = getExcludedToolSurfaceForDriver(driverType),
    targetId = id,
    declaredToolSetProblems =
      declaredToolSetProblems(id, driverType, declaredToolSetIds, catalog),
  )
}

/**
 * The problems with [declaredToolSetIds] against [catalog], as messages. Pure — see
 * [ResolvedTargetToolScope.declaredToolSetProblems] for why this is computed at resolve time.
 */
private fun declaredToolSetProblems(
  targetId: String,
  driverType: TrailblazeDriverType,
  declaredToolSetIds: List<String>,
  catalog: List<ToolSetCatalogEntry>,
): List<String> {
  if (declaredToolSetIds.isEmpty()) return emptyList()
  // Two distinct causes, reported separately because the fixes differ: a typo (no such toolset
  // anywhere) versus a real toolset whose `drivers:` exclude this driver — the second looks
  // identical at runtime but means the target declared the wrong toolset for the platform.
  val byId = catalog.associateBy { it.id }
  val unknown = declaredToolSetIds.distinct().filterNot { it in byId }
  val incompatible =
    declaredToolSetIds.distinct().filter { byId[it]?.isCompatibleWith(driverType) == false }
  return buildList {
    if (unknown.isNotEmpty()) {
      add(
        "Tool scope: target '$targetId' declares tool_sets $unknown on " +
          "driver=${driverType.yamlKey} with no matching catalog entry; those tools will be absent.",
      )
    }
    if (incompatible.isNotEmpty()) {
      add(
        "Tool scope: target '$targetId' declares tool_sets $incompatible on " +
          "driver=${driverType.yamlKey}, but those toolsets aren't compatible with that driver; " +
          "those tools will be absent.",
      )
    }
    // Not just "some id was bad" — NO declared id survived. The target still counts as scoped, so
    // it does NOT get the unconfigured whole-catalog fallback, leaving the session with
    // always-enabled tools alone: an agent that can't verify or navigate. Worth its own line,
    // because the per-id warnings above read as partial degradation rather than a dead session.
    if (declaredToolSetIds.none { byId[it]?.isCompatibleWith(driverType) == true }) {
      add(
        "Tool scope: target '$targetId' declares tool_sets $declaredToolSetIds on " +
          "driver=${driverType.yamlKey}, but NONE of them resolved; this session gets only " +
          "always-enabled tools and likely cannot verify or navigate.",
      )
    }
  }
}

/** Already-reported `(target, driver, problem)` triples — see [logDeclaredToolSetProblemsOnce]. */
private val reportedToolSetProblems = ConcurrentHashMap.newKeySet<String>()

/**
 * Emits [ResolvedTargetToolScope.declaredToolSetProblems], at most once per distinct
 * (target, driver, problem) in this process.
 *
 * The dedupe is the point, not a nicety. Warning from the resolver repeated a typo'd toolset on
 * every `tools/list`, descriptor build and `blaze()` call. Moving it to the session composer looked
 * like "once per session" but isn't: the daemon's `ensureSessionScriptToolRuntime` composes the
 * repo and then returns WITHOUT caching whenever a session has no scripted runtime — which is
 * exactly what a target whose `tool_sets:` resolved to nothing produces. So the misconfiguration
 * that most needs reporting was the one that would have re-reported forever. Deduping on the
 * message makes the guarantee hold wherever it's called from.
 *
 * `info`, not `log`: these are misconfigurations in the target's own YAML, and the host CLI runs
 * `Console.log` in quiet mode by default, which would swallow them exactly where the person who
 * can fix them is watching. (On-device both land on Logcat; quiet mode is a no-op there.)
 */
fun ResolvedTargetToolScope.logDeclaredToolSetProblemsOnce() {
  for (problem in declaredToolSetProblems) {
    if (reportedToolSetProblems.add("$targetId|${driverType.yamlKey}|$problem")) {
      Console.info(problem)
    }
  }
}

/**
 * Re-arms [logDeclaredToolSetProblemsOnce], so a misconfiguration it has already reported is
 * reported again.
 *
 * Without this the dedupe lasts as long as the process, which is "once per run" on the CLI and on
 * device but "once per daemon uptime" on a long-lived daemon — a session days later, run by someone
 * who never saw the first message, would be told nothing about a broken `tool_sets:`.
 *
 * Two things re-arm it, because either can change what a declared id resolves to:
 * - **A new session** — the daemon, when it creates an MCP session.
 * - **A workspace toolset re-registration** — [TrailblazeToolSetCatalog.registerWorkspaceToolSets],
 *   which every discovery pass calls to replace the overlay that `tool_sets:` resolve against.
 *   Trail Runner's create-target flow re-runs full discovery in a live daemon, so this fires
 *   without a new session. Without it, a developer who fixes a typo'd `tool_sets:`, then
 *   reintroduces that same typo, would hear nothing the second time.
 *
 * It re-arms the whole process, and that is the limit of what it promises. The emitter is reached
 * from the session composer and the advertise path, neither of which is handed a session id, so
 * there is nothing to key on without threading one through the shared composer API. The cost is
 * that with several concurrent daemon sessions a new connection re-arms the ones already running,
 * and a live session can hear the same problem a second time — bounded at one repeat per live
 * session per new session, not the per-request repetition this exists to stop.
 */
fun resetDeclaredToolSetProblemReporting() {
  reportedToolSetProblems.clear()
}

fun TrailblazeHostAppTarget.getAgentToolboxForDriver(
  driverType: TrailblazeDriverType,
  catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
  yamlToolConfigsByName: Map<ToolName, ToolYamlConfig> =
    TrailblazeSerializationInitializer.buildYamlDefinedTools(),
): ResolvedAgentToolbox {
  val scope = resolveToolScopeForDriver(driverType, catalog)
  // The advertise side reports misconfiguration too. This is the resolver behind the `toolbox`
  // route and discovery — where someone asking "why is this tool missing?" is actually looking —
  // and it never composes a session repo, so routing the warning solely through the composer made
  // it silent here. Deduped, so answering that question repeatedly doesn't spam.
  scope.logDeclaredToolSetProblemsOnce()
  val toolClasses = scope.toolClasses
    // Fail-fast like `KClass.toKoogToolDescriptor()` at LLM-registration time: a tool
    // class without a `@TrailblazeToolClass` annotation is a configuration bug, not a
    // recoverable condition.
    .filter { it.trailblazeToolClassAnnotation().surfaceToLlm }
    .toSet()
  // Mirror `KoogToolExt.buildDescriptorsForYamlDefined`: skip YAML tool names with no
  // registered config (with a warning, same shape as the runtime) and drop names whose
  // config declared `surface_to_llm: false`. Without the missing-config filter the
  // resolver over-reports — a typo in `tool_sets:` would appear visible here but get
  // skipped at LLM registration.
  val yamlToolNames = scope.yamlToolNames
    .filter { name ->
      val config = yamlToolConfigsByName[name]
      when {
        config == null -> {
          Console.log(
            "getAgentToolboxForDriver: no YAML config registered for tool '${name.toolName}' " +
              "on driver=${driverType.yamlKey}; will be skipped at LLM registration.",
          )
          false
        }
        config.surfaceToLlm == false -> false
        else -> true
      }
    }
    .toSet()
  // Scripted (`.ts` / `.js`) tool names the LLM sees — the canonical advertised surface (report /
  // discovery / CLI). Three sources:
  //   1. Toolset-delivered (`resolveForDriver(...).scriptedToolNames`) — e.g. `openUrl` via
  //      `core_interaction`. `resolveForDriver` already dropped toolsets incompatible with this
  //      driver, so `openUrl` surfaces under android/ios but not web (core_interaction is
  //      mobile-only). The daemon registers these per session via
  //      `TrailblazeMcpServer.ensureSessionScriptToolRuntime`.
  //   2. Target custom scripted tools (`getCustomScriptedToolNamesForDriver(driverType)`) — a
  //      scripted name listed directly in the target's `platforms.<p>.tools:`. This is the
  //      inclusion-side mirror of `customYamlNames`/`customClasses` above; before it, such a name
  //      was classified into neither the class nor YAML inclusion bucket and silently dropped as an
  //      "unknown tool". (The daemon's in-process scripted-tool launcher bundles only catalog-
  //      delivered scripted tools today, so wiring a *bare-`tools:`* scripted tool through the live
  //      runtime is follow-up parity work — this resolver advertises it either way.)
  //   3. Target-root inline tools (`target.tools:`, via `getInlineScriptTools()`) — synthesized
  //      as a subprocess at runtime, but still advertised to the LLM, so they belong here too.
  //      Omitting them under-reported the surface for targets that author their own scripted
  //      tools (Codex review on PR #3851).
  // Then subtract the target's `excluded_tools:` scripted opt-outs (`excluded.scriptedToolNames`)
  // — the scripted-partition parallel of the class / YAML exclusions applied above, from the same
  // surface. Before this, a target's `excluded_tools: [openUrl]` was honored for class-backed and
  // YAML tools but silently ignored for toolset-delivered scripted tools, so `openUrl` stayed
  // advertised to the LLM. No `surfaceToLlm` filtering still: scripted tools have no
  // `@TrailblazeToolClass` annotation to read; the report's "Scripted tools" section — the surface
  // this resolver is pinned against — applies the same exclusion subtraction.
  return ResolvedAgentToolbox(
    toolClasses = toolClasses,
    yamlToolNames = yamlToolNames,
    scriptedToolNames = scope.scriptedToolNames,
  )
}


/**
 * The target's resolved tool *exclusions* for [driverType] — the `excluded_tools:` opt-outs split
 * by backing (class-backed / YAML-defined / scripted), exposed as one [TrailblazeToolSurface].
 *
 * This is the exclusion-side mirror of the inclusion surface ([TrailblazeToolSurface.allToolNames]):
 * a **single entry point** so consumers read one shape instead of re-unioning
 * [TrailblazeHostAppTarget.getExcludedToolsForDriver] +
 * [TrailblazeHostAppTarget.getExcludedYamlToolNamesForDriver] +
 * [TrailblazeHostAppTarget.getExcludedScriptedToolNamesForDriver] by hand. Hand-rolled unions are
 * exactly what dropped the scripted partition before — a target's `excluded_tools: [openUrl]` was
 * honored for class/YAML tools but silently ignored for scripted ones. Every site that subtracts a
 * target's `excluded_tools:` (this resolver, the daemon inner-agent provider, the scripted-tool
 * runtime repo, the discovery layer, on-device rule wiring) routes through here so a future tool
 * backing can't be excluded in some sites but not others.
 *
 * An extension (not a method on [TrailblazeHostAppTarget]) for the same module-direction reason as
 * [getAgentToolboxForDriver]: [TrailblazeToolSurface] lives in `trailblaze-common`, the target in
 * `trailblaze-models`.
 */
fun TrailblazeHostAppTarget.getExcludedToolSurfaceForDriver(
  driverType: TrailblazeDriverType,
): ResolvedToolExclusions = ResolvedToolExclusions(
  toolClasses = getExcludedToolsForDriver(driverType),
  yamlToolNames = getExcludedYamlToolNamesForDriver(driverType),
  scriptedToolNames = getExcludedScriptedToolNamesForDriver(driverType),
)

/**
 * The three-way split of a target's `excluded_tools:` opt-outs for a driver, as a
 * [TrailblazeToolSurface]. Produced by [getExcludedToolSurfaceForDriver]; the exclusion-side analog
 * of [ResolvedAgentToolbox].
 */
data class ResolvedToolExclusions(
  override val toolClasses: Set<KClass<out TrailblazeTool>>,
  override val yamlToolNames: Set<ToolName>,
  override val scriptedToolNames: Set<ToolName>,
) : TrailblazeToolSurface
