package xyz.block.trailblaze.toolcalls

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Structural gate: **`@TrailblazeToolClass(requiresHost = true)` is an allowlist.** Only the
 * low-level framework tools in [HOST_ONLY_BY_DESIGN] may declare it; any other class-backed tool
 * that takes the flag fails this test and has to argue its way onto the list in a diff.
 *
 * This lives in the framework's own test source set, not in a downstream module, so it ships with
 * the subtree it guards. The scan itself is repo-wide rather than framework-only, so wherever this
 * test runs it covers every module present.
 *
 * ## Why an allowlist and not a ban
 *
 * The flag is load-bearing where it is set, and the four tools that carry it genuinely cannot run
 * anywhere but a host machine: `exec` and `runCommand` fork a subprocess, `switchDevice` needs a
 * second bound device, `assertWaypoint` reads a registry only `TrailblazeHostYamlRunner` populates.
 * Deleting it from those would delete a true fact — and their generated contract pages are where a
 * reader looks it up.
 *
 * The failure mode this gate exists for is the opposite one: the flag SPREADING to tools that don't
 * need it. It reads like harmless caution, an agent or author copies it from a neighbouring tool,
 * and on a class-backed tool the cost is quiet — a false host-only constraint published in the
 * generated TypeScript tool metadata and in the `Host-only:` row of the tool's contract page. It
 * stops being quiet one copy later: the same flag on a SCRIPTED tool is dropped at on-device
 * registration, so every on-device session behaves as if the tool does not exist. A downstream login
 * helper documents exactly that — as a host-only scripted tool it dropped at registration and every
 * CI trail composing it failed at step one with "no tool registered with that name". The fewer tools
 * carry the flag, the less there is to copy.
 *
 * ## What the flag is NOT
 *
 * It is not the way to say "don't route this through the device driver". That is
 * `HostLocalExecutableTrailblazeTool` — "execute in whichever JVM runs the agent loop", which is the
 * host daemon locally and the on-device runner in CI. The two are orthogonal, and a tool needing
 * only the second must not reach for the first:
 * - `sleep`, the downstream login and seed helpers: marker only. Dispatch in-process, run fine
 *   on-device.
 * - the four here: BOTH. The flag for the machine requirement, the marker for the dispatch.
 *
 * They do overlap in one place, which is why the marker is not a substitute: every host dispatch fork
 * (`HostOnDeviceRpcTrailblazeAgent`, `HostAccessibilityRpcClient`,
 * `TrailblazeMcpBridgeImpl.resolveToolDispatchRoute`) branches on
 * `tool is HostLocalExecutableTrailblazeTool || tool.requiresHostInstance()`, so either declaration
 * alone gets host-side routing. The flag carries the additional fact.
 *
 * ## Boundaries this does not cover
 * 1. **Scripted, YAML and subprocess tools are out of scope.** They have no class to hang an
 *    interface on, and their `requiresHost` lives in a `*.tool.yaml` or a TS `_meta` block —
 *    `QuickJsToolMeta.shouldRegister` / `TrailblazeToolMeta.shouldRegister` read it there, never this
 *    annotation. That is the layer where the registration drop is implemented.
 * 2. **`DelegatingTrailblazeTool` routes differently.** `TrailblazeMcpBridgeImpl` matches
 *    `tool is DelegatingTrailblazeTool && requiresHostInstance()` → `HOST_EXPAND` *before* the
 *    `HOST_LOCAL` branch, so a delegating tool's route depends on the flag in a way a non-delegating
 *    one's does not. None of the four is delegating.
 *
 * ## Why source-level rather than registry-level
 *
 * A registry-based version can only see tools a registry resolves, which silently omits
 * `runCommand` — a real flagged tool with no bundled `.tool.yaml`. Scanning source sees every
 * declaration, including ones no trailmap references, and — because the walk starts at the repo root
 * rather than a list of module names — including ones in modules that did not exist when this test
 * was written.
 */
class RequiresHostIsReservedForHostOnlyToolsTest {

  @Test
  fun `only the allowlisted low-level tools declare requiresHost`() {
    val declarations = scanToolClassDeclarations()

    // Floor check: if the walk stops finding source or the annotation is reformatted past the
    // parser, the filter below would match nothing and this test would pass having examined zero
    // files. Counted over the FRAMEWORK subtree only, because that is the part of the tree present
    // wherever this test runs — a downstream repo adds modules on top, and a floor that counted
    // those would be unmeetable in the framework repo alone.
    val frameworkDeclarations = declarations.filter { it.isFrameworkOwned }
    assertTrue(
      frameworkDeclarations.size >= EXPECTED_MINIMUM_FRAMEWORK_DECLARATIONS,
      "Expected at least $EXPECTED_MINIMUM_FRAMEWORK_DECLARATIONS @TrailblazeToolClass declarations " +
        "in the framework subtree, found ${frameworkDeclarations.size} (of ${declarations.size} " +
        "repo-wide). Did the walk stop reaching source, or the annotation change shape?",
    )

    val flagged = declarations.filter { it.requiresHost }
    val unexpected = flagged.filterNot { it.name in HOST_ONLY_BY_DESIGN }
    val missing = HOST_ONLY_BY_DESIGN - flagged.mapNotNull { it.name }.toSet()

    if (unexpected.isNotEmpty()) {
      fail(
        "These class-backed tools set @TrailblazeToolClass's requiresHost but are not on the " +
          "host-only allowlist:\n" +
          unexpected.map { "  - ${it.label} (${it.path})" }.sorted().joinToString("\n") +
          "\n\nThe flag means the tool CANNOT RUN ANYWHERE BUT A HOST MACHINE. On a class-backed " +
          "tool it PUBLISHES that constraint — in the generated TypeScript tool metadata and in the " +
          "`Host-only:` row of the tool's contract page — so a tool that does run on-device " +
          "documents a falsehood there, and becomes the declaration the equivalent SCRIPTED tool " +
          "gets copied from, where the same flag really is dropped at on-device registration. If you " +
          "only meant \"don't route this through the device driver\", declare " +
          "`: HostLocalExecutableTrailblazeTool` instead; that runs in whichever JVM holds the " +
          "session, on-device included. If the tool really is host-only, add it to " +
          "HOST_ONLY_BY_DESIGN with a one-line reason so the next reader can see why."
      )
    }

    if (missing.isNotEmpty()) {
      fail(
        "HOST_ONLY_BY_DESIGN lists ${missing.sorted()}, but no class-backed tool declares " +
          "requiresHost under that name. Either the tool was renamed, or the flag was " +
          "dropped — if dropped, confirm the tool really can run off a host machine and remove the " +
          "allowlist entry too, so the list cannot rot into a no-op."
      )
    }
  }

  /**
   * The gate is only as good as its reading of the annotation, and the flag has more than one legal
   * spelling. A scan that recognized just the literal `requiresHost = true` would report a
   * constant-valued or positional declaration as unflagged — exempting it from BOTH the allowlist
   * check and the rot check, which is the one failure mode this file cannot afford, because it looks
   * exactly like passing.
   */
  @Test
  fun `requiresHost is read in every spelling, and an unreadable one counts as set`() {
    val constants = mapOf("HOST_ONLY" to true, "NOT_HOST_ONLY" to false)

    fun read(body: String) = resolveRequiresHost(body, constants)

    // Named, literal — the spelling all four allowlisted tools use.
    assertTrue(read(""""exec", surfaceToLlm = false, requiresHost = true"""))
    assertFalse(read(""""exec", surfaceToLlm = false, requiresHost = false"""))
    assertFalse(read(""""exec", surfaceToLlm = false"""), "absent means unset")

    // Named, via a compile-time constant.
    assertTrue(read(""""x", requiresHost = HOST_ONLY"""))
    assertTrue(read(""""x", requiresHost = Flags.HOST_ONLY"""), "qualified reference resolves")
    assertFalse(read(""""x", requiresHost = NOT_HOST_ONLY"""))

    // Positional: `name, surfaceToLlm, isRecordable, requiresHost`.
    assertTrue(read(""""x", false, false, true"""))
    assertFalse(read(""""x", false, false, false"""))
    assertTrue(read(""""x", false, false, HOST_ONLY"""))
    // A named argument before position 4 means nothing lands there positionally.
    assertFalse(read(""""x", surfaceToLlm = false, false, true"""))

    // Fail closed: an argument the scan cannot evaluate is reported rather than dropped.
    assertTrue(read(""""x", requiresHost = someRuntimeCall()"""), "unresolvable counts as set")

    // A comma inside a nested call must not shift the positional reading.
    assertFalse(read(""""x", false, false, false, resultType = Foo::class"""))
  }

  @Test
  fun `every advertisedToolName matches the annotation name beside it`() {
    // getToolNameFromAnnotation() prefers `advertisedToolName` when a tool implements
    // InstanceNamedTrailblazeTool, and that name reaches session logs and the canonical encoder. A
    // typo there fails nothing — it silently records the tool under a name no trailmap knows. The
    // override is a hand-copied duplicate of the annotation's `name` on every marker tool, so the
    // copies are exactly what needs pinning.
    val declarations = scanToolClassDeclarations()
    val checked = declarations.filter { it.advertisedToolNames.isNotEmpty() && it.name != null }

    // Second floor, for the same reason as the first, and framework-scoped for the same reason. The
    // regex has already been too narrow once: requiring an explicit `: String` skipped every
    // `get() = "…"` tool, leaving this test examining barely a third of the overrides while
    // reporting green.
    val frameworkChecked = checked.filter { it.isFrameworkOwned }
    assertTrue(
      frameworkChecked.size >= EXPECTED_MINIMUM_FRAMEWORK_ADVERTISED_OVERRIDES,
      "Expected at least $EXPECTED_MINIMUM_FRAMEWORK_ADVERTISED_OVERRIDES resolvable " +
        "advertisedToolName overrides in the framework subtree, found ${frameworkChecked.size} (of " +
        "${checked.size} repo-wide). Did the override's spelling change past ADVERTISED_NAME?",
    )

    val mismatches =
      checked
        .filterNot { it.name in it.advertisedToolNames }
        .map {
          "${it.path}: annotation name=\"${it.name}\" advertisedToolName=" +
            it.advertisedToolNames.sorted().joinToString("/") { name -> "\"$name\"" }
        }
        .sorted()

    if (mismatches.isNotEmpty()) {
      fail(
        "These tools advertise a name differing from their @TrailblazeToolClass(name = ...):\n" +
          mismatches.joinToString("\n") { "  - $it" } +
          "\n\nThe advertised name wins in getToolNameFromAnnotation(), so the tool would be " +
          "logged and encoded under a name nothing resolves. Make the two literals equal."
      )
    }
  }

  private data class ToolClassDeclaration(
    val path: String,
    /**
     * The tool's advertised id, or null when the annotation names it through a constant this scan
     * could not resolve. Null is reported by file path instead — a declaration whose name we cannot
     * read must never be silently DROPPED, which is how an earlier version of this scan lost
     * `switchDevice` (positional name argument) and `runCommand` (name via constant) while still
     * reporting green.
     */
    val name: String?,
    val requiresHost: Boolean,
    /**
     * Every `advertisedToolName` literal in the declaring FILE — a set rather than a single value
     * so a file holding two annotated classes does not attribute one class's override to the other.
     * Empty when the file has no override at all.
     */
    val advertisedToolNames: Set<String>,
    /**
     * True when this declaration lives in the framework subtree — the part of the tree that exists
     * wherever this test runs. Only the vacuity floors read it; the offender check is repo-wide.
     */
    val isFrameworkOwned: Boolean,
  ) {
    /** What to call this declaration in a failure message when [name] is unresolvable. */
    val label: String
      get() = name ?: "<name via unresolved constant> ($path)"
  }

  /**
   * Every `@TrailblazeToolClass(...)` in non-test Kotlin source anywhere in the repo.
   *
   * [ToolClassDeclaration.advertisedToolNames] is collected per FILE, not per class, so a file
   * declaring two annotated classes offers both names to each. A mismatch test that accepts either
   * can miss a crossed pair in such a file; the alternative — attributing the first override to
   * every class — reports a false mismatch instead. No such file exists today.
   */
  private fun scanToolClassDeclarations(): List<ToolClassDeclaration> {
    val repoRoot = findRepoRoot()
    // "" when the framework IS the repo root, otherwise the subtree it sits in.
    val frameworkPrefix = findFrameworkRoot(repoRoot).toRelativeString(repoRoot)
      .replace(File.separatorChar, '/')

    val sources =
      repoRoot
        .walkTopDown()
        .onEnter {
          it == repoRoot || it.name !in PRUNED_DIRECTORY_NAMES && !it.name.startsWith(".")
        }
        .filter { it.isFile && it.extension == "kt" }
        .filterNot { TEST_SOURCE_SET.containsMatchIn(it.invariantSeparatorsPath) }
        .toList()
        .map { it to it.readText() }

    // `@TrailblazeToolClass(name = RUN_COMMAND_TOOL_NAME)` is legal and used, so resolve string
    // constants repo-wide first. Built from every scanned file because the constant is often
    // declared in a different file from the annotation that references it.
    val stringConstants: Map<String, String> =
      sources
        .flatMap { (_, source) ->
          STRING_CONSTANT.findAll(source).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }
        .toMap()

    // Same reason, for the Boolean the gate turns on: `requiresHost = HOST_ONLY` compiles to true
    // and would otherwise read as unset.
    val booleanConstants: Map<String, Boolean> =
      sources
        .flatMap { (_, source) ->
          BOOLEAN_CONSTANT.findAll(source)
            .map { it.groupValues[1] to (it.groupValues[2] == "true") }
            .toList()
        }
        .toMap()

    return sources.flatMap { (file, source) ->
      if (!source.contains(ANNOTATION_OPEN)) return@flatMap emptyList()
      val advertised =
        ADVERTISED_NAME.findAll(source)
          .mapNotNull { match ->
            match.groupValues[1].takeIf { it.isNotEmpty() } ?: stringConstants[match.groupValues[2]]
          }
          .toSet()
      val relativePath = file.toRelativeString(repoRoot).replace(File.separatorChar, '/')
      annotationBodies(source).map { body ->
        ToolClassDeclaration(
          path = relativePath,
          name = resolveToolName(body, stringConstants),
          // Comments stripped first: a `// requiresHost = true removed, see the marker` note left
          // INSIDE the annotation is exactly the kind of thing this gate's own theme invites, and it
          // must not read as a declaration of the flag.
          requiresHost = resolveRequiresHost(stripComments(body), booleanConstants),
          advertisedToolNames = advertised,
          isFrameworkOwned =
            frameworkPrefix.isEmpty() || relativePath.startsWith("$frameworkPrefix/"),
        )
      }
    }
  }

  /** Blanks out `//` and `/* … */` comments, preserving length so nothing else shifts. */
  private fun stripComments(body: String): String =
    body
      .replace(LINE_COMMENT) { " ".repeat(it.value.length) }
      .replace(BLOCK_COMMENT) { " ".repeat(it.value.length) }

  /**
   * The tool id an annotation body declares, across all three spellings in the tree: `name = "x"`,
   * a bare positional `"x"` (`@TrailblazeToolClass("switchDevice", surfaceToLlm = false)`), and
   * `name = SOME_CONSTANT` / a positional constant, resolved through [stringConstants].
   */
  private fun resolveToolName(body: String, stringConstants: Map<String, String>): String? {
    NAME_ARG_LITERAL.find(body)?.let {
      return it.groupValues[1]
    }
    NAME_ARG_IDENTIFIER.find(body)?.let {
      return stringConstants[it.groupValues[1]]
    }
    val firstArg = body.substringBefore(',').trim()
    if (firstArg.startsWith('"') && firstArg.endsWith('"') && firstArg.length >= 2) {
      return firstArg.removeSurrounding("\"")
    }
    return stringConstants[firstArg.takeIf { IDENTIFIER.matches(it) }]
  }

  /**
   * Whether an annotation body sets `requiresHost` to true, across every spelling Kotlin allows —
   * not just the literal `requiresHost = true`.
   *
   * Matching that literal text is a bypass twice over. Kotlin accepts a compile-time constant
   * (`requiresHost = HOST_ONLY`) and it accepts the flag positionally (it is the 4th parameter), and
   * a gate that reads neither reports a flagged tool as unflagged — silently exempting it from both
   * the allowlist check and the rot check.
   *
   * Fail-closed on anything else: an argument this cannot evaluate counts as SET, so the gate names
   * the declaration and a human decides, rather than the declaration disappearing from the scan.
   */
  private fun resolveRequiresHost(body: String, booleanConstants: Map<String, Boolean>): Boolean {
    val arguments = splitTopLevelArguments(body)
    val value =
      arguments
        .firstNotNullOfOrNull { argument ->
          val name = argument.substringBefore('=', missingDelimiterValue = "").trim()
          argument.substringAfter('=').trim().takeIf { name == REQUIRES_HOST_PARAMETER_NAME }
        }
        // Positional form: `@TrailblazeToolClass("x", false, false, true)`. Only a body whose first
        // four arguments are all positional can reach the flag that way, so a named argument
        // anywhere before it rules the positional reading out.
        ?: arguments
          .takeIf { it.size > REQUIRES_HOST_PARAMETER_INDEX }
          ?.takeIf { args ->
            args.take(REQUIRES_HOST_PARAMETER_INDEX + 1).none { it.contains('=') }
          }
          ?.get(REQUIRES_HOST_PARAMETER_INDEX)
          ?.trim()
        ?: return false

    return when {
      value == "true" -> true
      value == "false" -> false
      // A qualified reference (`Flags.HOST_ONLY`) resolves on its last segment; the constant map is
      // keyed by bare name.
      else -> booleanConstants[value.substringAfterLast('.')] ?: true
    }
  }

  /**
   * An annotation body's arguments, split on commas that are not nested inside parentheses, brackets
   * or a string literal — so a call or collection in a default (`arrayOf("a", "b")`) stays one
   * argument instead of becoming two.
   *
   * Angle brackets deliberately do NOT nest: a class literal cannot carry type arguments, so no
   * annotation argument here can hold a comma inside `<>`, while `->` in an argument would make a
   * `>`-counting parser lose track of depth entirely.
   */
  private fun splitTopLevelArguments(body: String): List<String> = buildList {
    var depth = 0
    var inString = false
    var start = 0
    body.forEachIndexed { index, char ->
      when {
        inString -> if (char == '"' && body.getOrNull(index - 1) != '\\') inString = false
        char == '"' -> inString = true
        char == '(' || char == '[' -> depth++
        char == ')' || char == ']' -> depth--
        char == ',' && depth == 0 -> {
          add(body.substring(start, index))
          start = index + 1
        }
      }
    }
    body.substring(start).takeIf { it.isNotBlank() }?.let { add(it) }
  }

  /**
   * The argument list of each `@TrailblazeToolClass(...)`, found by balancing parens rather than by
   * regex — an argument's own parentheses (a default like `Unit::class`, a nested call) end a lazy
   * regex match early and hide whatever follows, `requiresHost = true` included.
   */
  private fun annotationBodies(source: String): List<String> = buildList {
    var searchFrom = 0
    while (true) {
      val start = source.indexOf(ANNOTATION_OPEN, searchFrom)
      if (start < 0) return@buildList
      // A real annotation starts its line; a kdoc or `//` mention of one never does (it follows
      // `* ` or prose). Without this, every doc paragraph explaining why NOT to use
      // `@TrailblazeToolClass(requiresHost = true)` — including this test's own rationale, quoted
      // across the tree — counts as a declaration of it.
      if (!startsItsLine(source, start)) {
        searchFrom = start + ANNOTATION_OPEN.length
        continue
      }
      val openParen = start + ANNOTATION_OPEN.length - 1
      var depth = 0
      var i = openParen
      while (i < source.length) {
        when (source[i]) {
          '(' -> depth++
          ')' -> {
            depth--
            if (depth == 0) break
          }
        }
        i++
      }
      if (i >= source.length) return@buildList
      add(source.substring(openParen + 1, i))
      searchFrom = i + 1
    }
  }

  /** True when only whitespace separates [index] from the start of its line. */
  private fun startsItsLine(source: String, index: Int): Boolean {
    var i = index - 1
    while (i >= 0 && source[i] != '\n') {
      if (!source[i].isWhitespace()) return false
      i--
    }
    return true
  }

  private fun findRepoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      // A normal clone has a `.git` directory; a git worktree has a `.git` file. Accept either.
      if (File(dir, ".git").exists()) return dir
      dir = dir.parentFile
    }
    error("Could not locate the repository root from ${System.getProperty("user.dir")}")
  }

  /**
   * The directory the framework's Gradle modules sit directly under: the repo root itself in the
   * framework repo, and the mirrored subtree in a repo that vendors it. Resolved by walking up from
   * the working dir looking for a known framework source root, the same way
   * `BuiltInToolsBindingDriftTest` locates its anchor, so neither layout needs to be hardcoded.
   */
  private fun findFrameworkRoot(repoRoot: File): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      if (File(dir, FRAMEWORK_SOURCE_ANCHOR).isDirectory) return dir
      if (dir == repoRoot) break
      dir = dir.parentFile
    }
    fail(
      "Could not locate $FRAMEWORK_SOURCE_ANCHOR by walking up from " +
        "${System.getProperty("user.dir")} to $repoRoot. The framework module layout moved; update " +
        "FRAMEWORK_SOURCE_ANCHOR.",
    )
  }

  companion object {
    /**
     * The only class-backed tools allowed to declare `@TrailblazeToolClass(requiresHost = true)`,
     * each because it cannot run anywhere but a host machine:
     * - `exec`, `runCommand` — fork a host subprocess.
     * - `switchDevice` — hands the session to a second bound device, which only a host-orchestrated
     *   session can hold.
     * - `assertWaypoint` — resolves against a registry only `TrailblazeHostYamlRunner` populates.
     *
     * Adding an entry is a claim that an on-device session cannot run the tool AT ALL, not that the
     * tool should skip the device driver. State the reason here in one line; if you can't, the tool
     * wants `HostLocalExecutableTrailblazeTool` instead.
     */
    private val HOST_ONLY_BY_DESIGN = setOf(
      "assertWaypoint",
      "exec",
      "runCommand",
      "switchDevice",
    )

    /**
     * The scan walks the WHOLE repo rather than a list of module names. A named-roots version
     * silently omitted four modules that declare `@TrailblazeToolClass`, so adding the flag in any
     * of them passed the gate. A gate that has to be taught about each new module is a gate that is
     * wrong by default; this one is wrong only if someone prunes a directory below.
     */
    private val PRUNED_DIRECTORY_NAMES = setOf("build", "node_modules", "out", "vendor")
    private val TEST_SOURCE_SET = Regex("/src/[^/]*[Tt]est[^/]*/")
    private const val ANNOTATION_OPEN = "@TrailblazeToolClass("
    private const val FRAMEWORK_SOURCE_ANCHOR =
      "trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze"
    private val NAME_ARG_LITERAL = Regex("""\bname\s*=\s*"([^"]+)"""")
    private val NAME_ARG_IDENTIFIER = Regex("""\bname\s*=\s*([A-Za-z_][A-Za-z0-9_.]*)\s*[,)]?""")
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_.]*""")
    private val STRING_CONSTANT =
      Regex("""\b(?:const\s+)?val\s+([A-Z][A-Z0-9_]*)\s*(?::\s*String\s*)?=\s*"([^"]*)"""")
    private val BOOLEAN_CONSTANT =
      Regex("""\b(?:const\s+)?val\s+([A-Z][A-Z0-9_]*)\s*(?::\s*Boolean\s*)?=\s*(true|false)\b""")
    private const val REQUIRES_HOST_PARAMETER_NAME = "requiresHost"

    /**
     * `requiresHost` is the 4th parameter of `TrailblazeToolClass`, after `name`, `surfaceToLlm` and
     * `isRecordable` — so it is reachable positionally, and the scan has to read that form too.
     */
    private const val REQUIRES_HOST_PARAMETER_INDEX = 3
    private val LINE_COMMENT = Regex("""//[^\n]*""")
    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Every spelling of the override in this tree: `= "x"` / `get() = "x"`, with or without an
     * explicit `: String`, and with the name given as a literal (group 1) or as a constant (group
     * 2, resolved through the same repo-wide constant map the annotation's `name` uses). Requiring
     * an explicit `: String` and a literal is how an earlier version of this test came to examine
     * barely a third of the overrides — the majority house style is `: String get() = …`.
     *
     * Group 2 also matches a non-constant expression (`advertisedName.toolName`,
     * `inner.advertisedToolName` on the scripting wrappers). Those resolve to null and drop out —
     * there is no literal to compare, and the wrapper's name comes from the tool it wraps.
     */
    private val ADVERTISED_NAME =
      Regex(
        """\badvertisedToolName\s*(?::\s*String\s*)?(?:get\(\)\s*)?=\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_.]*))"""
      )

    /**
     * A floor, not a census — scoped to the framework subtree so it holds in the framework repo on
     * its own as well as in a repo that vendors it. It exists so a parser or scan regression cannot
     * pass by examining nothing, which is exactly how the first version of this scan silently
     * dropped `switchDevice` and `runCommand`, and how named scan roots dropped four whole modules.
     */
    private const val EXPECTED_MINIMUM_FRAMEWORK_DECLARATIONS = 100

    /**
     * The same floor for the name-mismatch test, whose parser is narrower: 5 framework declarations
     * resolve both an annotation name and at least one `advertisedToolName` today (28 repo-wide).
     */
    private const val EXPECTED_MINIMUM_FRAMEWORK_ADVERTISED_OVERRIDES = 4
  }
}
