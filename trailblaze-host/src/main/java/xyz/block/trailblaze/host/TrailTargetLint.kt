package xyz.block.trailblaze.host

import java.io.File
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifestLoader
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinition
import xyz.block.trailblaze.yaml.unified.UnifiedTrail

/**
 * Gate on the target a trail names: `config.target:` (and any per-device `target:` override) must
 * be an id something actually registers. An id that registers nowhere is accepted today, silently
 * degrades to the workspace default target, and the run still reports PASSED — so the trail exercises
 * a different app than it claims to and nothing says so.
 *
 * ## Why an unregistered id is invisible without this
 *
 * Resolution is `Iterable<TrailblazeHostAppTarget>.findById` — an id match, nothing else. A miss
 * falls back to the workspace default (`defaults.target:`), which on any real workspace is a
 * perfectly working target, so every step runs, every assertion passes, and the report is green.
 * Real trails carried an Android applicationId in that field for months for exactly
 * that reason. The runtime warning added alongside them only helps whoever is reading a log.
 *
 * ## Registration is PER WORKSPACE
 *
 * A repo can hold several independent trail workspaces, each with its own
 * `trails/config/trailmaps/` (or `trailblaze-config/trailmaps/`) root, and a workspace sees only
 * its own trailmaps — a nested workspace's trailmaps are not visible to the one above it. So the
 * resolvable set is computed per workspace by [resolvableTargetIds], never once for a whole
 * checkout. A trail whose target is registered by some *other* workspace is a real finding: running
 * it from its own workspace degrades to that workspace's default exactly like a typo would.
 *
 * Three sources make up the set, matching what the runtime resolves from:
 *  - everything the owning workspace registers (`CheckCommand.listAllWorkspaceTargetIds`): its
 *    `<configDir>/trailmaps/` manifests AND the hand-authored / compiled `<configDir>/targets/<name>.yaml`
 *    that declare a target without a trailmap,
 *  - classpath-bundled trailmap manifests ([classpathTrailmapIds]) — the framework's own, plus any a
 *    consumer ships on the classpath,
 *  - [KOTLIN_SURFACED_TARGET_IDS], the targets that exist as Kotlin objects with no manifest to find.
 *
 * A trailmap contributes only the id it actually registers, which may be none: LIBRARY trailmaps
 * (`web`, `trailblaze`, `android`, … — manifests with no `target:` block) register nothing, so
 * naming one is the same silent degrade as a typo. See [manifestTargetNames]. Comparison is
 * case-insensitive because [xyz.block.trailblaze.model.findById] is.
 */
object TrailTargetLint {

  /** Env kill-switch: `1`/`true` (case-insensitive) skips the gate entirely. */
  const val DISABLE_ENV_VAR: String = "TRAILBLAZE_DISABLE_TRAIL_TARGET_GATE"

  /**
   * Targets surfaced as Kotlin objects rather than by a `trailmap.yaml`, so no manifest discovery
   * can find them. Only the neutral built-in stand-in today
   * ([TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget]) — a trail naming it is asking for the
   * fallback on purpose, which is a different thing from getting it by accident.
   */
  val KOTLIN_SURFACED_TARGET_IDS: Set<String> =
    setOf(TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id)

  private const val MAX_SUGGESTIONS = 3

  /** One target reference that resolves to nothing. */
  data class UnresolvedTarget(
    /** The id as written. */
    val target: String,
    /**
     * The `config.devices:` path of the cast member whose `target:` override this came from
     * (`<configuration>.<member>`), or null for the trail's session-level `config.target:`. Only a
     * member of a multi-device configuration can have one that means anything — see
     * [unresolvedCastMemberTargets].
     */
    val devicePath: String? = null,
  )

  /** One finding per offending trail. */
  data class Finding(
    val trailRelPath: String,
    /** How to name the workspace that owns this trail in output — a path, or "." for the scan root. */
    val workspaceLabel: String,
    val unresolved: List<UnresolvedTarget>,
    /** Every id that workspace CAN resolve, so the message can show the menu and suggest a near miss. */
    val registeredTargetIds: Set<String>,
    /**
     * Where a `trailmap.yaml` would go for this workspace (`<configDir>/trailmaps`), so the remedy
     * can print a path the reader can paste. Null when the caller doesn't know it; the remedy then
     * names the directory generically.
     */
    val trailmapsDir: String? = null,
  )

  /**
   * Every target id a trail owned by one workspace may name: that workspace's target ids, the
   * classpath-bundled ones, and the Kotlin-surfaced ones.
   *
   * Filtered by the runtime's own id rule, because declaring a target is not the same as registering
   * one. `TrailblazeHostAppTarget` throws in its `init` on an id outside `^[a-zA-Z0-9_-]+$` and
   * `AppTargetYamlLoader.loadAllFromConfigs` catches that and drops the config, so a YAML declaring
   * `id: com.example.app` registers nothing. Accepting the declared id here would make the gate go
   * quiet on exactly the trail whose target silently falls back at run time.
   */
  fun resolvableTargetIds(
    workspaceTargetIds: Set<String>,
    classpathTrailmapIds: Set<String>,
  ): Set<String> = (workspaceTargetIds + classpathTrailmapIds + KOTLIN_SURFACED_TARGET_IDS)
    .filterTo(mutableSetOf(), TrailblazeHostAppTarget::isValidId)

  /**
   * The target ids every trailmap manifest reachable on the current classpath registers.
   *
   * Discovery failure yields an empty set (logged): a degraded but safe input, since a smaller
   * resolvable set can only produce a finding that names the real reason it could not see the manifest.
   */
  fun classpathTrailmapIds(): Set<String> =
    runCatching { TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath() }
      .onFailure { e ->
        Console.error(
          "trail-target gate: classpath trailmap discovery failed (continuing with workspace " +
            "trailmaps only): " + (e.message ?: e::class.simpleName),
        )
      }
      .getOrDefault(emptyList())
      .flatMap { manifestTargetNames(it.manifest) }
      .toSet()

  /**
   * The target id one trailmap manifest registers — exactly what `TrailblazeProjectConfigLoader`
   * emits for it, which is at most one name and sometimes none.
   *
   * A LIBRARY trailmap (`web`, `trailblaze`, `android`, `compose`, … — no `target:` block) registers
   * nothing at all: the loader builds a target only from that block. So `config.target: web` misses
   * `findById`, falls back to the workspace default, and reports PASSED — the precise failure this
   * gate exists for. Counting a library id as resolvable would blind the gate to it.
   *
   * And when a `target:` block sets its own `id:`, that id REPLACES the manifest id
   * (`toAppTargetYamlConfig` resolves `id ?: defaultId`) rather than adding to it — the manifest id
   * is then not resolvable, so returning both would go quiet on a trail naming the wrong one.
   *
   * Shared with `CheckCommand.listAllWorkspaceTargetIds` so a workspace manifest and a bundled one
   * are read by the same rule.
   */
  fun manifestTargetNames(manifest: TrailblazeTrailmapManifest): List<String> =
    listOfNotNull(manifest.target?.let { it.id ?: manifest.id })

  /**
   * The per-workspace resolvable sets for one lint run, memoized by workspace root.
   *
   * Both lanes that apply this gate walk a tree that can span several workspaces, and a workspace
   * owns hundreds of trails, so recomputing a set per trail would re-scan the same trailmaps dir
   * hundreds of times. Sharing the resolver is also what keeps the two lanes honest: they cannot
   * drift into disagreeing about which ids a given workspace registers.
   *
   * Both inputs are lambdas so that constructing a resolver costs nothing: classpath manifest
   * discovery and the workspace scan only happen once a trail is actually linted, which is what
   * makes the kill-switch skip the gate's WORK rather than just its findings.
   */
  class TargetIdResolver(
    private val workspaceTargetIds: (File) -> Set<String>,
    classpathTrailmapIds: () -> Set<String> = ::classpathTrailmapIds,
  ) {
    private val classpathIds: Set<String> by lazy(classpathTrailmapIds)
    private val byWorkspaceRoot = mutableMapOf<File, Set<String>>()

    fun forWorkspace(workspaceRoot: File): Set<String> = byWorkspaceRoot.getOrPut(workspaceRoot) {
      resolvableTargetIds(
        workspaceTargetIds = workspaceTargetIds(workspaceRoot),
        classpathTrailmapIds = classpathIds,
      )
    }
  }

  /**
   * PURE. Lint one decoded trail's target references against [registeredTargetIds]. Returns null
   * when every reference resolves, including when the trail names no target at all — an absent
   * `config.target:` is a legitimate way to say "whatever the workspace defaults to".
   */
  fun lint(
    trailRelPath: String,
    workspaceLabel: String,
    trail: UnifiedTrail,
    registeredTargetIds: Set<String>,
    trailmapsDir: String? = null,
  ): Finding? {
    val known = registeredTargetIds.map { it.lowercase() }.toSet()

    // Blank means "absent" for the session target — `findById("")` misses and the run falls back to
    // the workspace default, the same outcome as omitting the key, so there is nothing to report.
    // It does NOT mean absent for a cast member's override: `MultiDeviceConfigurationResolver`
    // inherits the session target only on a null, and throws on a blank that misses lookup. Blanket
    // blank-skipping would hide the one case that hard-fails at run time.
    fun unresolved(target: String?, devicePath: String?): UnresolvedTarget? = target
      ?.takeIf { (devicePath != null || it.isNotBlank()) && it.lowercase() !in known }
      ?.let { UnresolvedTarget(it, devicePath) }

    val findings = buildList {
      unresolved(trail.config.target, devicePath = null)?.let(::add)
      trail.config.devices.orEmpty().forEach { (key, definition) ->
        addAll(unresolvedCastMemberTargets(key, definition, ::unresolved))
      }
    }
    return if (findings.isEmpty()) {
      null
    } else {
      Finding(trailRelPath, workspaceLabel, findings, registeredTargetIds, trailmapsDir)
    }
  }

  /**
   * The `target:` of each member of a multi-device CONFIGURATION, and nothing else.
   *
   * A configuration's own entry cannot carry a `target:` — `TrailblazeDeviceDefinition`'s decode
   * rejects that outright — and a top-level single-device entry's `target:` is decoded but read by
   * nothing: `MultiDeviceConfigurationResolver` filters `config.devices:` to entries that ARE
   * configurations before it collects any override, so a `target:` on a plain classifier entry has
   * no effect at run time. Reporting one would be a finding about a key that does nothing, dressed
   * up as a target that resolves wrong — a separate lint, and not this one's claim to make.
   *
   * One level of nesting is all the format has (configurations don't nest), so this walks members
   * directly rather than recursing.
   */
  private fun unresolvedCastMemberTargets(
    path: String,
    definition: TrailblazeDeviceDefinition,
    unresolved: (String?, String?) -> UnresolvedTarget?,
  ): List<UnresolvedTarget> = definition.devices.orEmpty().mapNotNull { (memberKey, member) ->
    unresolved(member.target, "$path.$memberKey")
  }

  /**
   * Fatal rendering, for the repo-wide corpus gate. See [render] for why both severities exist.
   */
  fun renderFailures(findings: List<Finding>): String = render(findings, fatal = true)

  /**
   * Advisory rendering, for `trailblaze check`.
   *
   * The CLI half deliberately warns rather than failing. There is no `trailblaze init` and no
   * trailmap generator, so the only remedy for a naming mistake is to hand-write a `trailmap.yaml`
   * — and telling a first-time user their build is broken with no command to run is a worse
   * outcome than the mis-targeted trail, which still runs. Registering targets is the recommended
   * path, not a precondition for using the framework. The ratchet that keeps this repo honest is
   * the corpus test, which stays fatal.
   */
  fun renderWarnings(findings: List<Finding>): String = render(findings, fatal = false)

  /**
   * Grouped by owning workspace: the resolvable menu is a property of the workspace, not of the
   * trail, and printing it per trail would bury the one thing an author has to choose from.
   *
   * Both severities carry the same remedy block. A reader who hits this almost certainly does not
   * know what a trailmap file looks like, and there is no command that writes one for them, so the
   * message has to BE the instructions — including the option to register nothing at all.
   */
  private fun render(findings: List<Finding>, fatal: Boolean): String = buildString {
    val references = findings.flatMap { it.unresolved }
    val label = if (fatal) "gate (FATAL)" else "check (warning)"
    appendLine("── trail-target $label ───────────────────────────────────")
    appendLine(
      "${references.size} target reference(s) in ${findings.size} trail(s) name an id that no " +
        "trailmap and no built-in target registers." +
        if (fatal) "" else " This does not fail `trailblaze check`.",
    )
    // The two kinds of reference behave OPPOSITELY at run time, so say only what applies. Claiming a
    // silent pass for a device override would understate it; claiming a hard failure for a session
    // target would overstate it and invite someone to dismiss the real green-but-wrong case.
    if (references.any { it.devicePath == null }) {
      appendLine(
        "  A `config.target:` that resolves to nothing silently falls back to the workspace default " +
          "target, so the trail exercises a different app than it names and still reports PASSED.",
      )
    }
    if (references.any { it.devicePath != null }) {
      appendLine(
        "  A per-device `target:` that resolves to nothing is a hard error: the run fails to start " +
          "rather than falling back.",
      )
    }
    findings.groupBy { it.workspaceLabel }.toSortedMap().forEach { (workspaceLabel, group) ->
      val registered = group.first().registeredTargetIds
      val verdict = if (fatal) "FAIL" else "WARN"
      appendLine("")
      appendLine("  workspace $workspaceLabel resolves: ${registered.sorted().joinToString()}")
      group.sortedBy { it.trailRelPath }.forEach { finding ->
        finding.unresolved.forEach { entry ->
          val where = entry.devicePath?.let { "config.devices.$it.target" } ?: "config.target"
          val suggestion = suggest(entry.target, registered)
            ?.let { " — did you mean $it?" }
            .orEmpty()
          // A blank per-device target is a real finding (it throws at run time) but prints as
          // nothing, so name it rather than emitting a line that trails off.
          val shown = entry.target.ifBlank { "(blank)" }
          appendLine("  $verdict ${finding.trailRelPath}: $where: $shown$suggestion")
        }
      }
      // Inside the group, not once at the end: the trailmaps directory to create the file in and the
      // menu of ids to choose instead are both properties of THIS workspace. A single remedy would
      // send half the findings in a multi-workspace run to another workspace's directory.
      append(renderRemedy(group))
    }
  }

  /**
   * The three ways out, cheapest first, for one workspace's findings. Written against that
   * workspace's own first unresolved id and its own trailmaps path so it is copy-pasteable rather
   * than a pointer to the docs.
   */
  private fun renderRemedy(findings: List<Finding>): String {
    val first = findings.first()
    val named = first.unresolved.first().target
    val id = suggestedTrailmapId(named)
    val appId = if (named.contains('.')) named else "com.example.$id"
    val dir = first.trailmapsDir ?: "<workspace>/trailblaze-config/trailmaps"
    // "Drop the key" is the cheapest way out, but WHICH key depends on where the bad id is. Telling
    // someone with an unresolved cast-member override to drop `config.target:` leaves the override
    // in place and the run still throws on it. A member with no `target:` inherits the session
    // target (`resolveMemberTargets` returns it on a null), so dropping the member's own key is the
    // equivalent move one level down.
    val references = findings.flatMap { it.unresolved }
    val dropKey = buildString {
      if (references.any { it.devicePath == null }) {
        append(
          "Drop `config.target:` from the trail. A trail with no target uses the workspace default,\n" +
            "|     which is a fine way to start — targets are recommended, not required.",
        )
      }
      if (references.any { it.devicePath != null }) {
        if (isNotEmpty()) append("\n|     ")
        append(
          "Drop the `target:` from the device listed above. A cast member without one\n" +
            "|     runs the trail's own `config.target:`.",
        )
      }
    }
    // Only worth a line when the snippet had to rename what the trail said, which is exactly the
    // applicationId case — otherwise the reader would paste a trailmap the trail still can't find.
    val rename = if (id == named) {
      ""
    } else {
      "\n|     `$named` can't be an id (letters, digits, `_` and `-` only), so the trailmap is\n" +
        "|     `$id` — update the trail's `target:` to match."
    }
    return """
      |
      |  To fix, pick one:
      |
      |  1. $dropKey
      |  2. Name one of the ids listed above, if one of them is the app you meant.
      |  3. Register it by creating $dir/$id/trailmap.yaml:
      |
      |${trailmapSkeleton(id, appId).prependIndent("       ")}
      |$rename
      |     Swap `android:` for `ios:` (`app_ids:` = bundle ids) or `web:` as needed. See
      |     docs/your-first-trailmap.md for the fuller version.
      |
    """.trimMargin()
  }

  /**
   * The `trailmap.yaml` the remedy tells the reader to paste, unindented.
   *
   * Hand-written rather than encoded from [TrailblazeTrailmapManifest]: the encoder drops the
   * explanatory comment and emits every defaulted field, which is the opposite of the smallest
   * thing that works. The safety net is a test instead — `TrailTargetLintTest` decodes this exact
   * string through the real trailmap loader and asserts the target it produces, so renaming a field
   * on the manifest breaks the build rather than shipping instructions that cannot load.
   *
   * Internal only so that test can reach it; nothing else should render a trailmap from here.
   */
  internal fun trailmapSkeleton(id: String, appId: String): String = """
    id: $id
    target:
      display_name: $id
      platforms:
        android:
          app_ids: [$appId]   # the package the target launches
  """.trimIndent()

  /**
   * A trailmap id the runtime will actually accept, derived from what the trail named.
   *
   * [TrailblazeHostAppTarget] throws in its own `init` on any id outside `^[a-zA-Z0-9_-]+$`, and the
   * mistake this gate exists to catch is naming an applicationId — so echoing the id verbatim into a
   * "paste this" snippet would hand the reader a trailmap that cannot load. Take the last dotted
   * segment (`xyz.block.…examples.sampleapp` → `sampleapp`, which is the id the author meant), drop
   * anything still illegal, and fall back to a placeholder if nothing usable survives.
   */
  private fun suggestedTrailmapId(named: String): String = named
    .substringAfterLast('.')
    .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
    .takeIf { it.isNotEmpty() && TrailblazeHostAppTarget.isValidId(it) }
    ?: "myapp"

  /**
   * Up to [MAX_SUGGESTIONS] registered ids that look like [target], for the "did you mean" tail.
   * Substring containment in either direction, case-insensitively — deliberately cruder than an edit
   * distance, because the mistakes this gate catches are not typos: they are an applicationId
   * (`xyz.block.trailblaze.examples.sampleapp` for `sampleapp`) or a name from a neighbouring
   * workspace. Containment catches the first shape; nothing catches the second, and offering a bad
   * guess there is worse than offering none.
   */
  private fun suggest(target: String, registered: Set<String>): String? {
    val needle = target.lowercase()
    return registered
      .filter { it.lowercase() in needle || needle in it.lowercase() }
      .sorted()
      .take(MAX_SUGGESTIONS)
      .joinToString(" / ")
      .ifEmpty { null }
  }
}
