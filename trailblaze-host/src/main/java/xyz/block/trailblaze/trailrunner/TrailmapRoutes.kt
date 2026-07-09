package xyz.block.trailblaze.trailrunner

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.config.PlatformConfig
import xyz.block.trailblaze.config.TargetIconConvention
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.project.LoadedTrailblazeProjectConfig
import xyz.block.trailblaze.config.project.ResolvedTrailmap
import xyz.block.trailblaze.config.project.TrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifestLoader
import xyz.block.trailblaze.config.project.TrailmapTargetConfig
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment
import xyz.block.trailblaze.util.Console
import java.io.File

// Builds the Trailmaps screen's catalog from the SAME resolved-config pipeline `trailblaze run`
// itself uses to dispatch tools (TrailblazeProjectConfigLoader.resolveRuntime) — not a separate,
// hand-rolled parser. This used to be a set of lightweight regexes over raw file text, which could
// (and did) disagree with the real resolution about what counts as a trailhead. Read-only
// inventory: names + relative paths; bodies are fetched lazily via the tool-source endpoint's
// ?path= form.
object TrailmapCatalogBuilder {

  // Convention table already shared by both the classpath and workspace-filesystem loaders
  // (TrailblazeConfigPaths kdoc) — reused here so a relPath reconstruction can't drift from what
  // those loaders actually read from.
  private val TOOL_LAYOUT_BY_DIR = TrailblazeConfigPaths.TRAILMAP_TOOL_LAYOUT.associateBy { it.dir }

  // `ToolYamlConfig`/`InlineScriptToolConfig` carry no source-file path (confirmed: neither type
  // has a path field), so a component whose declared name differs from its filename — a multi-tool
  // descriptor exporting several names from one file, or a component nested under a subdirectory
  // like `shortcuts/web/` — can't have its relPath reconstructed from `id`/`name` alone. Extract the
  // real `id:` from each YAML file's own text (cheap, same weight class as DISPLAY_NAME/CLASS_KEY)
  // to look up its REAL discovered path instead of guessing one from the resolved name.
  private val ID_FIELD = Regex("(?m)^\\s*id:\\s*[\"']?(.+?)[\"']?\\s*$")

  fun build(
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
    workspaceConfigDir: File? = WorkspaceConfigDirHolder.resolver(),
  ): List<TrailmapEntry> {
    // resolveRuntime does its own filesystem/classpath discovery (it can't consume the abstract
    // ConfigResourceSource — see TrailblazeTrailmapManifestLoader's kdoc on why trailmap-manifest
    // discovery bypasses that abstraction), so it needs a real anchor directory. The `sourceFile`
    // itself never needs to exist — only its parent dir matters (same synthesis CompileCommand
    // uses for `check`/`compile`): `raw = TrailblazeProjectConfig()` with empty `targets` triggers
    // auto-discovery of every target trailmap under `<anchor>/trailmaps/`, exactly like the
    // ConfigResourceSource-based tool/trailmap discovery this replaces.
    val anchorFile = File(workspaceConfigDir ?: File(".").absoluteFile, TrailblazeConfigPaths.CONFIG_FILENAME)
    val loaded = LoadedTrailblazeProjectConfig(raw = TrailblazeProjectConfig(), sourceFile = anchorFile)
    val resolved = runCatching {
      TrailblazeProjectConfigLoader.resolveRuntime(
        loaded,
        includeClasspathTrailmaps = true,
        scriptedToolEnrichment = AnalyzerScriptedToolEnrichment.resolveFromEnvironment(),
        // This is a browsing/listing screen, not a specific target's dispatch graph — a library
        // trailmap nothing declares as a `dependencies:` edge (e.g. one only ever referenced by id
        // through the global tool/toolset registry) is still a real, authored trailmap that
        // belongs in the picker.
        includeOrphanTrailmaps = true,
      )
    }.getOrElse {
      Console.log("[TrailmapCatalogBuilder] resolveRuntime failed: ${it.message}")
      null
    }
    val dir = TrailblazeConfigPaths.TRAILMAPS_DIR

    // Real discovered relPath for every YAML component, keyed by "<trailmapId>::<id>" — see
    // ID_FIELD's kdoc for why this can't be reconstructed from the resolved model alone. A cheap
    // scan (three suffix walks, regex over text), same weight class as the rest of this file.
    val yamlRelPathById: Map<String, String> = buildMap {
      listOf(".tool.yaml", ".trailhead.yaml", ".shortcut.yaml").forEach { suffix ->
        runCatching { resourceSource.discoverAndLoadRecursive(dir, suffix) }.getOrElse {
          Console.log("[TrailmapCatalogBuilder] discovery of '$suffix' failed: ${it.message}")
          emptyMap()
        }.forEach { (r, content) ->
          val trailmapId = r.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotBlank() } ?: return@forEach
          val id = ID_FIELD.find(content)?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: return@forEach
          putIfAbsent("$trailmapId::$id", "$dir/$r")
        }
      }
    }

    val entriesById = resolved?.resolvedTrailmaps
      ?.associate { it.manifest.id to it.toTrailmapEntry(yamlRelPathById) }
      .orEmpty()

    // System-prompt markdown living at the trailmap root (id/<file>.md) stays on this lightweight
    // file scan intentionally, NOT sourced from the resolved model: AppTargetYamlConfig.systemPrompt
    // is a single already-resolved string, but this field needs every .md file at a trailmap's root
    // as a list — a real shape mismatch, not an oversight. No other consumer resolves this
    // differently, so there's no "two parsers disagreeing" risk here.
    val systemPromptsById = runCatching { resourceSource.discoverAndLoadRecursive(dir, ".md") }
      .getOrElse {
        Console.log("[TrailmapCatalogBuilder] discovery of '.md' failed: ${it.message}")
        emptyMap()
      }
      .keys
      .mapNotNull { r ->
        val seg = r.split('/')
        val id = seg.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (seg.size != 2) return@mapNotNull null
        id to TrailmapComponent(seg.last().removeSuffix(".md"), "$dir/$r")
      }
      .groupBy({ it.first }, { it.second })

    // This catalog deliberately auto-discovers EVERY trailmap (it's a browsing screen), but the
    // runtime honors a non-empty `targets:` allow-list in the workspace's real trailblaze.yaml —
    // an unlisted target trailmap never loads no matter what's installed. Surface that as a flag
    // rather than filtering, so the Target picker can suppress cards that could never activate
    // while the Trailmaps screen keeps browsing everything. Missing/malformed config (or an
    // empty/omitted list) means auto-discovery: everything is listed.
    val targetAllowList = runCatching { TrailblazeProjectConfigLoader.load(anchorFile)?.raw?.targets }
      .getOrElse {
        // Same convention as the discovery scans above — a malformed trailblaze.yaml silently
        // disabling the allow-list (every card flips to listed) must be explainable from the log.
        Console.log("[TrailmapCatalogBuilder] targets allow-list parse failed: ${it.message}")
        null
      }.orEmpty()

    val allIds = (entriesById.keys + systemPromptsById.keys).toSortedSet()
    return allIds.map { id ->
      val entry = entriesById[id] ?: TrailmapEntry(id = id)
      entry.copy(
        systemPrompts = systemPromptsById[id].orEmpty().sortedBy { it.name },
        workspaceListed = targetAllowList.isEmpty() || id in targetAllowList,
      )
    }
  }

  private fun ResolvedTrailmap.toTrailmapEntry(yamlRelPathById: Map<String, String>): TrailmapEntry {
    val trailmapId = manifest.id
    val dir = TrailblazeConfigPaths.TRAILMAPS_DIR

    // Best-effort fallback ONLY — correct when a component's declared id/name equals its filename
    // stem (the common case), wrong for a multi-tool descriptor or a nested subdirectory. Prefer
    // the real discovered path (yamlRelPathById / InlineScriptToolConfig.script) wherever available;
    // this exists so a lookup miss degrades to a plausible-but-possibly-wrong path instead of a
    // crash, matching this file's existing tolerance for partial/best-effort data everywhere else.
    fun fallbackRelPath(subdir: String, name: String, suffix: String) = "$dir/$trailmapId/$subdir/$name$suffix"

    fun yamlRelPath(id: String, subdir: String, suffix: String): String =
      yamlRelPathById["$trailmapId::$id"] ?: fallbackRelPath(subdir, id, suffix)

    // Absolute InlineScriptToolConfig.script -> catalog-relative path, by locating this trailmap's
    // own root segment in the path. Robust to both workspace and classpath-extracted sources, since
    // both preserve the `trailmaps/<id>/...` layout; only the path prefix before it differs.
    fun scriptedRelPath(scriptAbsolutePath: String): String? {
      val normalized = scriptAbsolutePath.replace(File.separatorChar, '/')
      val marker = "trailmaps/$trailmapId/"
      val idx = normalized.lastIndexOf(marker)
      if (idx == -1) return null
      return "${TrailblazeConfigPaths.CONFIG_DIR}/${normalized.substring(idx)}"
    }

    // Operational YAML-composed tools — never scripted; those live on target.tools below.
    // `Mode.METADATA` (trailhead-/shortcut-only YAMLs) is handled by branching on the field
    // itself rather than a separate mode dispatch.
    val yamlTools = tools.map { t ->
      val subdir = when {
        t.trailhead != null -> "trailheads"
        t.shortcut != null -> "shortcuts"
        else -> "tools"
      }
      val suffix = TOOL_LAYOUT_BY_DIR.getValue(subdir).suffix
      TrailmapComponent(
        name = t.id,
        relPath = yamlRelPath(t.id, subdir, suffix),
        flavor = if (t.toolClass != null) ToolFlavor.KOTLIN else ToolFlavor.YAML,
      )
    }
    // Scripted tools live only on target.tools (null for library trailmaps) — confirmed no real
    // trailmap in this workspace ships scripted tools without a bound target, and the loader
    // itself forbids a target-less trailmap from declaring a trailhead-carrying scripted tool.
    val scriptedTools = target?.tools.orEmpty().map { st ->
      TrailmapComponent(
        st.name,
        scriptedRelPath(st.script) ?: fallbackRelPath("tools", st.name, ".ts"),
        flavor = ToolFlavor.SCRIPTED,
      )
    }

    // Trailheads: union of YAML-sourced (`trailhead != null`) and scripted-sourced
    // (InlineScriptToolConfig.trailhead != null, analyzer-resolved) — mirrors the same union
    // ToolDiscoveryToolSet.computeRoleNames already does for the CLI/desktop, now backed by the
    // identical resolved data instead of a parallel regex.
    val trailheads = (
      tools.filter { it.trailhead != null }
        .map { TrailmapComponent(it.id, yamlRelPath(it.id, "trailheads", TOOL_LAYOUT_BY_DIR.getValue("trailheads").suffix)) } +
        target?.tools.orEmpty().filter { it.trailhead != null }
          .map { TrailmapComponent(it.name, scriptedRelPath(it.script) ?: fallbackRelPath("tools", it.name, ".ts")) }
      ).sortedBy { it.name }

    return TrailmapEntry(
      id = trailmapId,
      // null for library trailmaps (no `target:` block) — matches prior regex-based behavior,
      // which never found a `display_name:` in such a file either.
      displayName = target?.displayName,
      manifestPath = "${TrailblazeConfigPaths.TRAILMAPS_DIR}/$trailmapId/${TrailblazeConfigPaths.TRAILMAP_MANIFEST_FILENAME}",
      tools = (yamlTools + scriptedTools).sortedBy { it.name },
      trailheads = trailheads,
      systemPrompts = emptyList(), // filled in by build() from the file-scan above.
      platforms = target?.platforms?.keys?.sorted().orEmpty(),
    )
  }
}

// Where each component type lives inside a trailmap, and the file suffix it uses.
private val TRAILMAP_COMPONENT_DIR = mapOf(
  "tools" to "tools", "trailheads" to "trailheads",
)
private val TRAILMAP_COMPONENT_SUFFIX = mapOf(
  "tools" to ".ts", "trailheads" to ".trailhead.yaml",
)
// Names may include sub-dirs (e.g. android/foo) but nothing path-escaping.
private val SAFE_COMPONENT_NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_/-]*$")

internal fun trailmapComponentSkeleton(kind: String, name: String): String = when (kind) {
  "tools" -> {
    val id = name.substringAfterLast('/')
    """
    import { trailblaze } from "@trailblaze/scripting";

    /**
     * TODO: Describe what this tool does and when the agent should use it. This text
     * is exactly what the model sees when deciding to call this tool.
     */
    export const $id = trailblaze.tool(
      { supportedPlatforms: ["android", "ios", "web"], requiresContext: true },
      async (_input, ctx) => {
        // TODO: implement. Compose other tools via ctx.tools.*, read ctx.target, etc.
        return "TODO: describe the result";
      },
    );
    """.trimIndent() + "\n"
  }
  "trailheads" -> "description: \"\"\ntrailhead:\n  to: \"\"\ntools: []\n"
  else -> ""
}

/**
 * A [NewComponentResponse] plus the HTTP status the REST route should use. The RPC handler ignores
 * [status] (it returns the body in an RpcResult); [status] keeps the REST route's exact codes:
 * a bad request shape (unknown kind, invalid name) is a 400, while a conflict (unknown trailmap,
 * path escape, already-exists, write error) is a 409 — distinct, as the route was before.
 */
internal data class NewComponentOutcome(val status: HttpStatusCode, val body: NewComponentResponse)

/**
 * Scaffolds a new component file (trailhead/tool) inside an existing
 * trailmap with a type-appropriate skeleton — the shared source for both the REST
 * `POST /api/trailmap/component` route and the `NewComponentRequest` RPC handler. Refuses to
 * overwrite. Every failure rides in `NewComponentResponse.ok=false` + `error`.
 */
internal suspend fun buildNewComponentResponse(request: NewComponentRequest): NewComponentOutcome {
  val kind = request.kind.trim()
  val name = request.name.trim().removeSuffix("/")
  val trailmap = request.trailmap.trim()
  val dir = TRAILMAP_COMPONENT_DIR[kind]
  val suffix = TRAILMAP_COMPONENT_SUFFIX[kind]
  if (dir == null || suffix == null) {
    return NewComponentOutcome(HttpStatusCode.BadRequest, NewComponentResponse(ok = false, error = "unknown component kind '$kind'"))
  }
  if (name.isEmpty() || !SAFE_COMPONENT_NAME.matches(name) || name.contains("..")) {
    return NewComponentOutcome(
      HttpStatusCode.BadRequest,
      NewComponentResponse(ok = false, error = "invalid name — use letters, numbers, _ - / only"),
    )
  }
  return withContext(Dispatchers.IO) {
    val base = ToolSourceFiles.trailmapBaseDir(trailmap)
      ?: return@withContext NewComponentOutcome(HttpStatusCode.Conflict, NewComponentResponse(ok = false, error = "unknown trailmap '$trailmap'"))
    val relInside = "$dir/$name$suffix"
    val file = File(base, relInside)
    val baseCanon = base.canonicalPath
    if (!file.canonicalPath.startsWith(baseCanon + File.separator)) {
      return@withContext NewComponentOutcome(HttpStatusCode.Conflict, NewComponentResponse(ok = false, error = "resolved path escapes the trailmap"))
    }
    if (file.exists()) {
      return@withContext NewComponentOutcome(HttpStatusCode.Conflict, NewComponentResponse(ok = false, error = "a $kind named '$name' already exists"))
    }
    runCatching {
      file.parentFile?.mkdirs()
      file.writeText(trailmapComponentSkeleton(kind, name))
      NewComponentOutcome(
        HttpStatusCode.OK,
        NewComponentResponse(
          ok = true,
          relPath = "${TrailblazeConfigPaths.TRAILMAPS_DIR}/$trailmap/$relInside",
          savedPath = file.absolutePath,
        ),
      )
    }.getOrElse { NewComponentOutcome(HttpStatusCode.Conflict, NewComponentResponse(ok = false, error = it.message ?: "could not write file")) }
  }
}

// A brand-new trailmap id must be a single path segment (it becomes the directory name under
// `<workspace>/trailmaps/`) — no `/`, and no `.` so `..` traversal is impossible by construction.
private val SAFE_TRAILMAP_ID = Regex("^[A-Za-z0-9_][A-Za-z0-9_-]*$")

/**
 * Patches the `target:` block of a `trailmap.yaml` — the shared source for the
 * `SaveTargetConfigRequest` RPC handler. Adds a `target:` block if the trailmap currently has none
 * (a "library trailmap" per this file's manifest docs), or updates the existing one. Without
 * [SaveTargetConfigRequest.createIfMissing], fails if the trailmap id doesn't resolve or its
 * directory has no `trailmap.yaml` yet; with it, that missing trailmap is bootstrapped instead —
 * directory + minimal manifest (`id`, `dependencies: [trailblaze]`, the requested `target:` block,
 * the shape `docs/your-first-trailmap.md` teaches), then the same patch logic below applies to the
 * synthetic empty manifest. A successful bootstrap also best-effort registers the id in the
 * workspace `trailblaze.yaml` — see [registerCreatedTargetBestEffort] for when that's needed.
 *
 * Per-platform edits are a patch, not a replace: [SaveTargetConfigRequest.platforms] only carries
 * the platform keys the caller wants to touch, and within a touched platform only [PlatformConfig.appIds],
 * [PlatformConfig.baseUrl], and [PlatformConfig.icon] are replaced — every other field on that
 * platform (and every platform not mentioned in the request) is carried over from the existing
 * manifest untouched. A patch with [SaveTargetPlatformPatch.remove] set drops that platform key
 * entirely instead of merging it. The whole manifest is re-serialized to write the file, so
 * hand-authored YAML comments/formatting elsewhere in it are not preserved — an accepted tradeoff
 * for now.
 *
 * A successful write is also the trigger point for icon extraction — see
 * [notifyIconExtractionTriggerCandidates] — fired only for platforms whose `appIds`/`baseUrl` this
 * save actually changed, not for every platform the request happens to mention.
 */
internal suspend fun buildSaveTargetConfigResponse(request: SaveTargetConfigRequest): SaveTargetConfigResponse =
  withContext(Dispatchers.IO) {
    val trailmapId = request.trailmapId.trim()
    val base = ToolSourceFiles.trailmapBaseDir(trailmapId)
      ?: run {
        if (!request.createIfMissing) {
          return@withContext SaveTargetConfigResponse(ok = false, error = "unknown trailmap '$trailmapId'")
        }
        // Validate the id before resolving its would-be directory, so a path-escaping id sent via
        // direct RPC gets the clear "invalid id" reject instead of the containment check's
        // misleading "no active workspace" (the containment check stays as defense in depth).
        if (!SAFE_TRAILMAP_ID.matches(trailmapId)) {
          return@withContext SaveTargetConfigResponse(
            ok = false,
            error = "invalid trailmap id '$trailmapId' — use letters, numbers, _ and - only",
          )
        }
        ToolSourceFiles.newTrailmapBaseDir(trailmapId)
          ?: return@withContext SaveTargetConfigResponse(
            ok = false,
            error = "no active workspace to create trailmap '$trailmapId' in",
          )
      }
    val manifestFile = File(base, "trailmap.yaml")
    val creating = !manifestFile.isFile
    if (creating && !request.createIfMissing) {
      return@withContext SaveTargetConfigResponse(
        ok = false,
        error = "trailmap '$trailmapId' has no trailmap.yaml yet — creating a brand-new trailmap isn't supported here",
      )
    }
    if (creating && !SAFE_TRAILMAP_ID.matches(trailmapId)) {
      return@withContext SaveTargetConfigResponse(
        ok = false,
        error = "invalid trailmap id '$trailmapId' — use letters, numbers, _ and - only",
      )
    }
    val manifest = if (creating) {
      // Minimal bootstrap manifest; `dependencies: [trailblaze]` pulls in the framework's standard
      // per-platform toolsets so the new target is actually driveable, not an empty shell.
      TrailblazeTrailmapManifest(id = trailmapId, dependencies = listOf("trailblaze"))
    } else {
      runCatching { TrailblazeTrailmapManifestLoader.load(manifestFile) }
        .getOrElse {
          return@withContext SaveTargetConfigResponse(ok = false, error = "failed to parse trailmap.yaml: ${it.message}")
        }
        .manifest
    }
    val existingTarget = manifest.target
    val existingPlatforms = existingTarget?.platforms ?: emptyMap()
    val mergedPlatforms = buildMap<String, PlatformConfig> {
      putAll(existingPlatforms)
      request.platforms.forEach { (key, edits) ->
        if (edits.remove) {
          remove(key)
        } else {
          put(
            key,
            (existingPlatforms[key] ?: PlatformConfig()).copy(
              appIds = edits.appIds,
              baseUrl = edits.baseUrl,
              icon = edits.icon,
            ),
          )
        }
      }
    }
    // Platforms whose appIds/baseUrl this save actually changed — the icon-extraction trigger
    // point (see notifyIconExtractionTriggerCandidates) only fires for these, not for every
    // platform the Edit Target form resent unchanged (it resends current values for fields the
    // user didn't touch, per this function's kdoc).
    val platformsWithChangedIconInputs = computePlatformsWithChangedIconInputs(existingPlatforms, mergedPlatforms, request.platforms)
    val newTarget = (existingTarget ?: TrailmapTargetConfig(displayName = request.displayName)).copy(
      displayName = request.displayName,
      icon = request.icon,
      platforms = mergedPlatforms.takeIf { it.isNotEmpty() },
    )
    runCatching {
      val yaml = TrailblazeConfigYaml.instance.encodeToString(
        TrailblazeTrailmapManifest.serializer(),
        manifest.copy(target = newTarget),
      )
      // No-op on the edit path; materializes `<workspace>/trailmaps/<id>/` on the bootstrap path.
      manifestFile.parentFile.mkdirs()
      // Write to a sibling temp file first, then rename over the real file — a rename is atomic
      // on the same filesystem, so a crash/disk-full mid-write can never leave manifestFile itself
      // truncated or partially written; worst case an orphaned .tmp file is left behind.
      val tmp = File.createTempFile("trailmap", ".yaml.tmp", manifestFile.parentFile)
      tmp.writeText(yaml)
      if (!tmp.renameTo(manifestFile)) {
        tmp.delete()
        error("could not replace ${manifestFile.absolutePath}")
      }
      // Isolated from the write-result reporting above: a future non-blocking extractor dispatch
      // failing here must never surface as "the manifest write failed" — the write already
      // succeeded by this point.
      runCatching {
        platformsWithChangedIconInputs.forEach { key ->
          val platformConfig = mergedPlatforms.getValue(key)
          notifyIconExtractionTriggerCandidates(trailmapId, key, platformConfig.appIds, platformConfig.baseUrl)
        }
      }.onFailure {
        Console.log("[TrailmapRoutes] icon-extraction trigger notification failed (manifest write already succeeded): ${it.message}")
      }
      SaveTargetConfigResponse(
        ok = true,
        created = creating,
        // On EVERY createIfMissing save, not just the bootstrapping one: registration is
        // idempotent (computeWorkspaceTargetsAfterCreate no-ops when already listed), so a
        // caller whose earlier registration failed can re-send the same request to re-attempt
        // it instead of the retry silently taking the edit path and never registering.
        warning = if (request.createIfMissing) registerCreatedTargetBestEffort(trailmapId) else null,
      )
    }.getOrElse {
      Console.log("[TrailmapRoutes] failed to write ${manifestFile.absolutePath}: ${it.message}")
      SaveTargetConfigResponse(ok = false, error = it.message ?: "could not write trailmap.yaml")
    }
  }

/**
 * The `targets:` list a workspace `trailblaze.yaml` should carry after bootstrapping [newId], or
 * null when no rewrite is needed: an **empty** list already auto-discovers every workspace
 * trailmap (rewriting it to `[newId]` would silently narrow the workspace to an explicit
 * allow-list of one), and an already-listed id needs nothing. Pure so it's unit-testable with
 * plain list inputs, independent of [registerCreatedTargetBestEffort]'s file I/O.
 */
internal fun computeWorkspaceTargetsAfterCreate(existingTargets: List<String>, newId: String): List<String>? =
  when {
    existingTargets.isEmpty() -> null
    newId in existingTargets -> null
    else -> existingTargets + newId
  }

// Serializes every read-modify-write of the workspace trailblaze.yaml's `targets:` list. Two
// concurrent creates would otherwise both read the same base list, each append only their own id,
// and the second rename would silently drop the first — with no warning, since each call's own
// write succeeded. The manifest writes don't need this (they're per-trailmap files).
private val workspaceTargetsWriteLock = Any()

/**
 * After a `createIfMissing` save, make sure the workspace will actually load the trailmap: a
 * `trailblaze.yaml` with a non-empty `targets:` list only loads the listed ids, so the id is
 * appended there (atomic tmp-file+rename, same as the manifest write; comments are lost to the
 * re-serialize — the same accepted tradeoff). No config file, or an empty/omitted `targets:` list,
 * means auto-discovery already covers the trailmap and nothing is written. Idempotent — an
 * already-listed id is a no-op — so it runs on every `createIfMissing` save, letting an API
 * caller whose registration previously failed re-attempt it (the web form's own recovery is the
 * manual instruction carried in the warning text — it blocks known-duplicate ids client-side).
 *
 * Best-effort by design: the trailmap itself was already created, so a failure here must not read
 * as "create failed." Returns a user-facing warning string when the registration was needed but
 * couldn't be applied (surfaced via [SaveTargetConfigResponse.warning]), null otherwise.
 */
private fun registerCreatedTargetBestEffort(trailmapId: String): String? {
  val configDir = WorkspaceConfigDirHolder.resolver() ?: return null
  val configFile = File(configDir, TrailblazeConfigPaths.CONFIG_FILENAME)
  return runCatching {
    synchronized(workspaceTargetsWriteLock) {
      val raw = TrailblazeProjectConfigLoader.load(configFile)?.raw ?: return@synchronized null
      val updatedTargets = computeWorkspaceTargetsAfterCreate(raw.targets, trailmapId) ?: return@synchronized null
      val yaml = TrailblazeConfigYaml.instance.encodeToString(
        TrailblazeProjectConfig.serializer(),
        raw.copy(targets = updatedTargets),
      )
      val tmp = File.createTempFile("trailblaze", ".yaml.tmp", configFile.parentFile)
      tmp.writeText(yaml)
      if (!tmp.renameTo(configFile)) {
        tmp.delete()
        error("could not replace ${configFile.absolutePath}")
      }
      null
    }
  }.getOrElse {
    Console.log("[TrailmapRoutes] created trailmap '$trailmapId' but could not update ${configFile.absolutePath}: ${it.message}")
    "Created the trailmap, but couldn't update trails/config/trailblaze.yaml — since it lists " +
      "targets: explicitly, add '$trailmapId' there for the new target to load."
  }
}

/**
 * Which of [requestedPlatforms]' keys had their resolved `appIds`/`baseUrl` actually change —
 * comparing each key's config in [existingPlatforms] (before the save) against [mergedPlatforms]
 * (after). A platform with [SaveTargetPlatformPatch.remove] set has nothing left to extract an
 * icon for, so it's excluded rather than treated as a change. Pure so it's unit-testable with
 * plain map inputs, independent of [buildSaveTargetConfigResponse]'s YAML I/O.
 */
internal fun computePlatformsWithChangedIconInputs(
  existingPlatforms: Map<String, PlatformConfig>,
  mergedPlatforms: Map<String, PlatformConfig>,
  requestedPlatforms: Map<String, SaveTargetPlatformPatch>,
): Set<String> =
  requestedPlatforms.filterValues { !it.remove }.keys.filterTo(mutableSetOf()) { key ->
    val before = existingPlatforms[key]
    val after = mergedPlatforms.getValue(key)
    before?.appIds != after.appIds || before?.baseUrl != after.baseUrl
  }

/**
 * The trigger point for per-platform icon extraction (Android via `aapt` automation and web via a
 * favicon service remain not-yet-built; iOS is implemented, via [IosAppIconExtractor]). Called
 * once per platform whose `app_ids` or `base_url` [buildSaveTargetConfigResponse] actually
 * changed, right after the manifest write succeeds.
 *
 * Extraction must NEVER run implicitly off routine `trailblaze run` / daemon-startup /
 * target-discovery paths — carried over from the design discussion on GitHub block/trailblaze
 * issues 200/201. This is the one place it fires from: an explicit, occasional Edit Target save.
 *
 * iOS: iterates every id in [appIds] (a platform can declare multiple, each a distinct convention
 * filename — [xyz.block.trailblaze.config.TargetIconConvention.iosIconPath]) and dispatches
 * extraction non-blocking on a background scope, so the save's RPC response (and the Edit Target
 * modal closing) never waits on subprocess/device I/O — the icon appears on next refresh.
 * [IosAppIconExtractor] is itself best-effort (no booted simulator with the app installed, or no
 * custom icon configured, both degrade to a log rather than an error).
 *
 * Android/web remain a documented no-op beyond a diagnostic log — no extractor exists for either
 * yet.
 */
private fun notifyIconExtractionTriggerCandidates(
  trailmapId: String,
  platform: String,
  appIds: List<String>?,
  baseUrl: String?,
) {
  if (appIds.isNullOrEmpty() && baseUrl.isNullOrBlank()) {
    // A platform can land here already flagged as "changed" (its appIds/baseUrl differed from
    // before) yet still have nothing to extract — e.g. this save cleared both fields. Log why
    // there's no candidate rather than silently no-op, so "why didn't extraction trigger fire"
    // is answerable from the daemon log alone.
    Console.log(
      "[TrailmapRoutes] icon extraction skipped for target=$trailmapId platform=$platform — " +
        "appIds/baseUrl changed but both are now empty",
    )
    return
  }
  if (platform == "ios" && !appIds.isNullOrEmpty()) {
    val configDir = WorkspaceConfigDirHolder.resolver()
    if (configDir == null) {
      Console.log("[TrailmapRoutes] icon extraction skipped for $trailmapId/ios: no workspace config dir resolved")
      return
    }
    // One scope for the whole trigger, not one per bundle id — a launch per id already gives each
    // extraction its own concurrent coroutine; a fresh CoroutineScope per id is unnecessary
    // overhead with nothing to show for it (nobody cancels or joins on it individually).
    val extractionScope = CoroutineScope(Dispatchers.IO)
    appIds.forEach { bundleId ->
      if (!isValidBundleIdForIconPath(bundleId)) {
        Console.log("[TrailmapRoutes] icon extraction skipped for $trailmapId/ios/$bundleId: not a safe filename component")
        return@forEach
      }
      extractionScope.launch {
        val outFile = File(configDir, TargetIconConvention.iosIconPath(bundleId))
        runCatching { IosAppIconExtractor.extractIconIfPossible(bundleId, outFile) }
          .onFailure { Console.log("[TrailmapRoutes] icon extraction threw for $trailmapId/ios/$bundleId: ${it.message}") }
      }
    }
    return
  }
  Console.log(
    "[TrailmapRoutes] icon extraction candidate (no extractor implemented yet): " +
      "target=$trailmapId platform=$platform appIds=${appIds.orEmpty()} baseUrl=${baseUrl.orEmpty()}",
  )
}

/**
 * Guards [bundleId] before it's interpolated into a convention filename
 * ([TargetIconConvention.iosIconPath]) — same permissive-but-non-pathological charset as
 * [RunToolsRoutes]'s `?appId=` query-param filter (letters, digits, `_`, `-`, `.`; a reverse-DNS
 * bundle id never legitimately needs anything else). Rejecting everything else — in particular
 * `/` — means the composed path can never escape the single `ios_<bundleId>.png` filename segment
 * under `assets/icons/`, regardless of what a crafted `app_ids` entry in trailmap.yaml contains.
 */
internal fun isValidBundleIdForIconPath(bundleId: String): Boolean =
  bundleId.isNotEmpty() && bundleId.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }

internal fun Route.trailmapRoutes() {
  // Scaffold a new component file (trailhead/tool) inside an
  // existing trailmap, with a type-appropriate skeleton. Refuses to overwrite.
  post("$PATH_BASE/api/trailmap/component") {
    val body = runCatching { call.receive<NewComponentRequest>() }.getOrNull()
    if (body == null) {
      call.respond(HttpStatusCode.BadRequest, NewComponentResponse(ok = false, error = "invalid request body"))
      return@post
    }
    val outcome = buildNewComponentResponse(body)
    call.respond(outcome.status, outcome.body)
  }
}
