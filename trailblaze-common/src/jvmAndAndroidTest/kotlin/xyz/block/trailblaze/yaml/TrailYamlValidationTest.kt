package xyz.block.trailblaze.yaml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.config.ScriptedToolNameDiscoverer
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.project.TrailDiscovery
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.util.GitUtils
import xyz.block.trailblaze.yaml.unified.TrailDocument
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

/**
 * Validates that every trail YAML file (trailblaze.yaml and *.trail.yaml) in the git repository
 * parses under the STRICT parser — unknown keys (typos, stale/removed fields, mis-nested
 * selectors) fail the build instead of being silently dropped at decode. This is the repo gate
 * that keeps malformed trails from ever landing.
 *
 * Coverage boundary: strictness only bites on CLOSED shapes the parser has a serializer for —
 * trail structure (config/step/recording keys), selectors, and class-backed tools registered on
 * this test's classpath. A tool call whose name isn't on the classpath (e.g. workspace-local
 * tools declared under `trails/config/trailmaps`, which `:trailblaze-common:jvmTest` doesn't load)
 * decodes to [xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool], which stores its args as
 * a raw open map — so an unknown/misspelled ARG on such a tool is NOT caught here. Those tool-arg
 * types are checked separately by `trailblaze check`'s recording type-validation against each
 * trailmap's generated `tools/trailblaze-client.d.ts`.
 */
class TrailYamlValidationTest {

  @Test
  fun `validate all trail yaml files can be parsed`() = runBlocking {
    // Get the git repository root directory using the existing GitUtils utility
    val gitRootPath = GitUtils.getGitRootViaCommand()
      ?: error("Failed to determine git repository root")
    val gitRoot = File(gitRootPath)
    val trailFiles = TrailYamlValidator.findAllTrailYamlFiles(gitRoot)

    Console.log("Found ${trailFiles.size} trail YAML files to validate in git repository")

    // Strict parser: kaml throws UnknownPropertyException on any key outside a closed shape.
    // Immutable/stateless for decoding, so one instance is safe to share across coroutines.
    val strictParser = createTrailblazeYaml(strict = true)

    // Parse all files in parallel using coroutines
    val results = trailFiles.map { file ->
      async(Dispatchers.Default) {
        TrailYamlValidator.validateTrailFile(file, strictParser)
      }
    }.awaitAll()

    // Collect all failures
    val failures = results.filterNotNull()

    // Report results
    val successCount = results.size - failures.size
    Console.log("\n=== Trail YAML Validation Results ===")
    Console.log("Total files: ${trailFiles.size}")
    Console.log("Successful: $successCount")
    Console.log("Failed: ${failures.size}")

    // If there are failures, print details
    if (failures.isNotEmpty()) {
      Console.log("\n=== Failed Files ===")
      failures.forEachIndexed { index, failure ->
        Console.log("\n${index + 1}. ${failure.filePath}")
        Console.log("   Error: ${failure.errorMessage}")
        Console.log("   Exception: ${failure.exception::class.simpleName}: ${failure.exception.message}")
      }

      // Fail the test with a summary
      val failureMessage = buildString {
        appendLine("Failed to parse ${failures.size} trail YAML file(s):")
        failures.forEach { failure ->
          appendLine("  - ${failure.filePath}: ${failure.errorMessage}")
        }
      }
      assertTrue(false, failureMessage)
    } else {
      Console.log("\n✓ All trail YAML files parsed successfully!")
    }
  }

  /**
   * Guards the gate itself: proves the strict parser actually REJECTS an unknown key, and that the
   * lenient default silently accepts the same input. Without this, the corpus test above would
   * still pass if strictness ever regressed to lenient (a clean corpus fails 0 files either way),
   * leaving the gate silently dead. `bogusUnknownArg` on a registered tool is the known-bad input;
   * the assertion keys off that name, not the full wording, so it survives message rewording.
   */
  @Test
  fun `strict parser rejects unknown keys but lenient accepts them`() {
    val knownBad = """
      config:
        id: probe/strict-gate
        title: probe
        target: square
      trail:
        - step: s
          recording:
            ios:
              - assertVisibleBySelector:
                  reason: r
                  nodeSelector:
                    iosMaestro:
                      textRegex: More
                  bogusUnknownArg: 1
    """.trimIndent()

    val strictFailure = assertFailsWith<Exception> {
      createTrailblazeYaml(strict = true).decodeTrailDocument(knownBad)
    }
    assertTrue(
      strictFailure.message?.contains("bogusUnknownArg") == true,
      "Strict parse should fail because of the unknown key, but was: ${strictFailure.message}",
    )

    // The lenient default drops the unknown key instead of throwing — this is what strictness fixes.
    createTrailblazeYaml().decodeTrailDocument(knownBad)
  }

  /**
   * `assertMatchCount` is a closed, classpath-registered shape, so a misspelled bound is rejected
   * rather than dropped. This matters more than for a presence assert: a silently-dropped `exact`
   * leaves the surviving `min` as a weaker-but-still-passing assertion, which is the false-green
   * this tool exists to catch. Also pins that the documented authoring shape parses under strict.
   */
  @Test
  fun `strict parser accepts the documented assertMatchCount shape and rejects a misspelled bound`() {
    val documented = """
      config:
        id: probe/assert-match-count
        title: probe
        target: square
      trail:
        - step: s
          recording:
            android:
              - assertMatchCount:
                  reason: The report should list at least one item row.
                  min: 1
                  nodeSelector:
                    androidAccessibility:
                      textRegex: Net sales by item
    """.trimIndent()

    createTrailblazeYaml(strict = true).decodeTrailDocument(documented)

    val misspelledBound = documented.replace("min: 1", "exsct: 3")
    val strictFailure = assertFailsWith<Exception> {
      createTrailblazeYaml(strict = true).decodeTrailDocument(misspelledBound)
    }
    assertTrue(
      strictFailure.message?.contains("exsct") == true,
      "Strict parse should fail on the misspelled bound, but was: ${strictFailure.message}",
    )
  }

  /**
   * Fails when a trail calls a tool NAME that no backing in this repo claims — the gap the
   * strictness above cannot see. Tool-name decode is permissive by construction
   * ([TrailblazeToolYamlWrapperSerializer][xyz.block.trailblaze.yaml.serializers.TrailblazeToolYamlWrapperSerializer]
   * falls back to [xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool] for any unrecognized
   * key), so a misspelled tool parses cleanly and only fails hours later on a device, inside
   * `TrailblazeToolRepo.toolCallToTrailblazeTool` — at whatever step happens to look first, which
   * misattributes the cause.
   *
   * Names come from the real decoder (never a regex over the YAML) so this can't drift from what
   * the runtime actually reads. See [ToolNameBackings] for how the known-name set is derived and
   * where it is deliberately a superset.
   */
  @Test
  fun `every tool name a trail references resolves to a known backing`() {
    val gitRoot = File(
      GitUtils.getGitRootViaCommand() ?: error("Failed to determine git repository root"),
    )
    val backings = ToolNameBackings.scan(gitRoot)
    val strictParser = createTrailblazeYaml(strict = true)

    val trailFiles = TrailYamlValidator.findAllTrailYamlFiles(gitRoot)
    val referencedIn = mutableMapOf<String, MutableSet<String>>()
    var callSites = 0
    var undecodable = 0
    trailFiles.forEach { file ->
      val names = try {
        ToolNameBackings.toolNamesReferencedBy(file.readText(), strictParser)
      } catch (_: Exception) {
        // A file that doesn't parse is the corpus test above's failure to report, not this one's.
        undecodable++
        return@forEach
      }
      callSites += names.size
      names.forEach { name ->
        referencedIn.getOrPut(name) { mutableSetOf() }.add(file.relativeTo(gitRoot).path)
      }
    }

    val unknown = referencedIn.keys.filterNot { it in backings.known }.sorted()
    // Gradle never prints a test's stdout, so a census that only went to the log would be invisible on
    // the runs that matter. It rides in every failure message below as well.
    val census = backings.census(referencedIn.keys, trailFiles.size, callSites, undecodable, unknown)
    Console.log(census)

    // A resolution regression that empties either side of the comparison must not read as a pass.
    assertTrue(
      referencedIn.isNotEmpty(),
      "Adjudicated 0 tool names across ${trailFiles.size} trail file(s) — the corpus walk " +
        "resolved nothing, so this gate proved nothing. Check that trail discovery and " +
        "`decodeTrailDocument` still surface `recording:` tool calls.\n$census",
    )
    assertTrue(
      backings.known.isNotEmpty(),
      "Known-tool-name set is empty, so every name would read as a typo. Check tool discovery " +
        "(${ToolNameBackings.REGISTRY_ENTRY_POINTS}) and the worktree scan under $gitRoot.\n$census",
    )
    assertTrue(
      unknown.isEmpty(),
      buildString {
        appendLine("${unknown.size} trail tool name(s) match no known backing in this repo:")
        unknown.forEach { name ->
          appendLine("  - $name")
          referencedIn.getValue(name).sorted().forEach { appendLine("      $it") }
        }
        appendLine(
          "Each is a tool name no `.tool.yaml` id, scripted-tool declaration or " +
            "@TrailblazeToolClass in the tree claims. Fix the spelling in the trail, or declare " +
            "the tool.",
        )
        append(census)
      },
    )
  }

  /**
   * Guards the gate itself, the same way `strict parser rejects unknown keys` guards strictness:
   * a clean corpus passes whether the gate works or is silently dead, so prove both halves move.
   * The decoder must SURFACE a bogus tool name (it decodes permissively, so absence here would
   * mean the walk is blind), and the known-name set must ACCEPT a real name while REJECTING the
   * bogus one. The assertions key off the names, not the wording, so they survive rewording.
   */
  @Test
  fun `tool-name gate surfaces an unknown name and clears a known one`() {
    val bogus = "assertVisibleBySelectorNoSuchToolProbe"
    val probe = """
      config:
        id: probe/tool-name-gate
        title: probe
        target: square
      trail:
        - step: s
          recording:
            ios:
              - assertVisibleBySelector:
                  reason: r
                  nodeSelector:
                    iosMaestro:
                      textRegex: More
              - $bogus:
                  reason: r
    """.trimIndent()

    val names = ToolNameBackings.toolNamesReferencedBy(probe, createTrailblazeYaml(strict = true))
    assertTrue(
      names.containsAll(listOf("assertVisibleBySelector", bogus)),
      "The decoder must surface both tool names for the gate to see them, but got: $names",
    )

    val gitRoot = File(
      GitUtils.getGitRootViaCommand() ?: error("Failed to determine git repository root"),
    )
    val known = ToolNameBackings.scan(gitRoot).known
    assertTrue("assertVisibleBySelector" in known, "A registered tool must read as known")
    assertFalse(bogus in known, "An undeclared tool name must NOT read as known")
  }

  /**
   * `variable` is the load-bearing key — a capture that writes nothing leaves every downstream
   * `{{token}}` literal, which reads as a trail bug far from its cause. It is declared required
   * rather than defaulted precisely so a misspelling is rejected at parse time by BOTH parsers,
   * not silently dropped by the lenient one. Also pins that the authoring shape parses strict.
   */
  @Test
  fun `both parsers reject a rememberTextBySelector with a misspelled variable`() {
    val documented = """
      config:
        id: probe/remember-text-by-selector
        title: probe
        target: square
      trail:
        - step: s
          recording:
            ios:
              - rememberTextBySelector:
                  reason: Capture the currently selected check-reporting option.
                  variable: currentOption
                  nodeSelector:
                    iosAxe:
                      labelRegex: Print when the order is ready
    """.trimIndent()

    createTrailblazeYaml(strict = true).decodeTrailDocument(documented)

    val misspelled = documented.replace("variable: currentOption", "varaible: currentOption")
    assertFailsWith<Exception> {
      createTrailblazeYaml(strict = true).decodeTrailDocument(misspelled)
    }
    assertFailsWith<Exception> {
      createTrailblazeYaml().decodeTrailDocument(misspelled)
    }
  }
}

/**
 * Every place a tool NAME can be declared in this repo, and how the gate above decides a name is
 * real. Split into two groups:
 *
 * **Registry (runtime).** [TrailblazeSerializationInitializer.buildAllTools] (class-backed),
 * [TrailblazeSerializationInitializer.buildYamlDefinedTools] (`tools:`-mode YAML) and
 * [ScriptedToolNameDiscoverer.discoverAllNames] (statically-named scripted descriptors), reached
 * through [ToolNameResolver.isKnown]. These see only this test's CLASSPATH, which is a small slice
 * of the repo: `:trailblaze-common:jvmTest` carries its own bundled trailmaps and nothing else — no
 * `:trailblaze-playwright` / `:trailblaze-compose` tools, no `uitests-*` tools, and no workspace
 * trailmap under `trails/config/trailmaps/` (`platformConfigResourceSource()` reads the classpath,
 * and no workspace resolver is installed in a test JVM).
 *
 * **Worktree (filesystem).** The declarations the classpath can't reach, read from the git tree so
 * the gate covers the whole repo rather than one module's slice: `id:` in
 * `*.tool.yaml` / `*.shortcut.yaml` / `*.trailhead.yaml`, `name:` in scripted-tool descriptors and
 * in a target's inline `tools:` entries, `registerTool("…")` in an MCP subprocess server,
 * `@TrailblazeToolClass("…")` in Kotlin sources, and — for scripted TypeScript tools, whose names
 * are only fully knowable by running the analyzer — a deliberate SUPERSET built from two
 * independent extractors: every candidate `.ts` basename, and every
 * `export const X = trailblaze.tool` declaration.
 *
 * The superset bias is the point: its only failure mode is MISSING a typo, never inventing one. A
 * false failure here would get the gate disabled, which is strictly worse than a measured blind
 * spot. Two blind spots follow from that and are reported on every run rather than implied:
 *  1. a typo that collides with a `.ts` basename or an exported symbol reads as known;
 *  2. tool calls nested inside another tool's arguments (a `block_runIf`-style wrapper, whose
 *     recorded body decodes to an opaque raw-args map) are not walked, so their names are not
 *     adjudicated at all.
 *
 * Never replace any of this with a hand-written list of tool names: a literal list rots into a
 * second source of truth that says a real tool is a typo (or the reverse) with no signal.
 */
private object ToolNameBackings {

  const val REGISTRY_ENTRY_POINTS =
    "TrailblazeSerializationInitializer.buildAllTools/buildYamlDefinedTools, " +
      "ScriptedToolNameDiscoverer.discoverAllNames"

  /**
   * Scope of the worktree scan for tool declarations: trailmap trees (`trailmaps/<id>/…`) and
   * target configs (`targets/<id>.yaml`, whose `target.tools:` entries name inline script tools).
   * Anything outside `trails/config/` cannot declare a tool name, except Kotlin annotations, which
   * are scanned repo-wide.
   */
  private const val CONFIG_MARKER = "trails/config/"
  private val WORD = "[A-Za-z0-9_${'$'}]"
  private val ANNOTATED_NAME = Regex("""@TrailblazeToolClass\(\s*(?:name\s*=\s*)?"([^"]+)"""")
  private val YAML_TOOL_ID = Regex("""^id:\s*(\S+)\s*$""", RegexOption.MULTILINE)
  private val DESCRIPTOR_NAME = Regex("""^\s*(?:-\s+)?name:\s*"?($WORD+)"?\s*$""", RegexOption.MULTILINE)
  private val EXPORTED_TOOL = Regex("""export\s+const\s+($WORD+)\s*=\s*trailblaze\s*\.\s*tool""")

  /**
   * A tool advertised by an MCP server the host spawns as a subprocess at session start (the
   * `sampleapp` trailmap's `tools/mcp/tools.ts`). Its name is a string literal in the
   * `registerTool` / `tool` call, so it reads exactly rather than by heuristic — but it is invisible
   * to every other extractor here (it is not a `trailblaze.tool`, has no descriptor YAML, and is
   * deliberately excluded from the generated typed surface).
   */
  private val MCP_REGISTERED_TOOL = Regex("""\.(?:registerTool|tool)\(\s*"([^"]+)"""")

  /** One partition of the known-name set: where the names came from, and whether it is exact. */
  class Partition(val label: String, val names: Set<String>, val exact: Boolean = true)

  class Backings(val partitions: List<Partition>) {
    val known: Set<String> = partitions.flatMapTo(mutableSetOf()) { it.names }

    /** Names only a superset partition claims — matched, but not proven to exist. */
    private val supersetOnly: Set<String> =
      partitions.filterNot { it.exact }.flatMapTo(mutableSetOf()) { it.names } -
        partitions.filter { it.exact }.flatMapTo(mutableSetOf()) { it.names }

    fun census(
      adjudicated: Set<String>,
      trailFiles: Int,
      callSites: Int,
      undecodable: Int,
      unknown: List<String>,
    ): String = buildString {
      appendLine("\n=== Trail tool-name resolution ===")
      appendLine("Known-name set, by declaring backing:")
      partitions.forEach { p ->
        appendLine("  ${p.names.size.toString().padStart(4)}  ${p.label}${if (p.exact) "" else "  [SUPERSET — contributes names, never adjudicated or failed on]"}")
      }
      appendLine("  ${known.size.toString().padStart(4)}  union")
      appendLine("Corpus: $trailFiles trail file(s), $callSites recorded tool call(s)")
      appendLine("  adjudicated: ${adjudicated.size} distinct tool name(s)")
      appendLine("  skipped:     $undecodable file(s) that did not decode (reported by the parse gate, not here)")
      val bySuperset = adjudicated.count { it in supersetOnly }
      appendLine(
        "  of the adjudicated, $bySuperset matched ONLY via the scripted-TypeScript superset — " +
          "known-not-a-typo, not known-to-exist",
      )
      appendLine(
        "  blind spot: tool calls nested inside another tool's args (e.g. a `block_runIf` body) " +
          "decode to opaque raw args and are NOT walked",
      )
      appendLine(if (unknown.isEmpty()) "  unknown: 0" else "  unknown: ${unknown.size} -> ${unknown.joinToString(", ")}")
    }
  }

  /** Tool names this trail YAML references, taken from the real decoder rather than a regex. */
  fun toolNamesReferencedBy(yaml: String, parser: TrailblazeYaml): List<String> =
    when (val doc = parser.decodeTrailDocument(yaml)) {
      is TrailDocument.Unified ->
        (listOfNotNull(doc.trail.trailhead) + doc.trail.trail)
          .flatMap { step -> step.recordings.values.flatten() }
          .map { it.name }
    }

  fun scan(gitRoot: File): Backings {
    val resolver = ToolNameResolver.fromBuiltInAndCustomTools()
    val yamlToolIds = mutableSetOf<String>()
    val descriptorNames = mutableSetOf<String>()
    val scriptBasenames = mutableSetOf<String>()
    val exportedToolNames = mutableSetOf<String>()
    val mcpAdvertisedNames = mutableSetOf<String>()
    val annotatedNames = mutableSetOf<String>()

    val root = gitRoot.toPath()
    Files.walkFileTree(
      root,
      object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
          val name = dir.fileName?.toString()
          return if (dir != root && name in TrailDiscovery.DEFAULT_EXCLUDED_DIRS) {
            FileVisitResult.SKIP_SUBTREE
          } else {
            FileVisitResult.CONTINUE
          }
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
          val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
          val inConfig = file.toString().replace(File.separatorChar, '/').contains(CONFIG_MARKER)
          when {
            name.endsWith(".kt") -> read(file)?.let { text ->
              if ("@TrailblazeToolClass" in text) {
                ANNOTATED_NAME.findAll(text).forEach { annotatedNames.add(it.groupValues[1]) }
              }
            }

            !inConfig -> Unit

            name.endsWith(".tool.yaml") || name.endsWith(".shortcut.yaml") ||
              name.endsWith(".trailhead.yaml") -> read(file)?.let { text ->
              YAML_TOOL_ID.findAll(text).forEach { yamlToolIds.add(it.groupValues[1]) }
            }

            name.endsWith(".yaml") -> read(file)?.let { text ->
              DESCRIPTOR_NAME.findAll(text).forEach { descriptorNames.add(it.groupValues[1]) }
            }

            name.endsWith(".d.ts") || name.endsWith(".test.ts") -> Unit

            name.endsWith(".ts") -> {
              scriptBasenames.add(name.removeSuffix(".ts"))
              read(file)?.let { text ->
                EXPORTED_TOOL.findAll(text).forEach { exportedToolNames.add(it.groupValues[1]) }
                MCP_REGISTERED_TOOL.findAll(text).forEach { mcpAdvertisedNames.add(it.groupValues[1]) }
              }
            }
          }
          return FileVisitResult.CONTINUE
        }

        // A file we can't read contributes no names; it must not abort the scan (which would
        // shrink the known set and turn real tools into reported typos).
        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
          FileVisitResult.CONTINUE
      },
    )

    return Backings(
      listOf(
        Partition(
          "classpath registry (class-backed / YAML-defined / scripted descriptors)",
          registryNames().filter { resolver.isKnown(it) }.toSet(),
        ),
        Partition("worktree *.tool|shortcut|trailhead.yaml `id:`", yamlToolIds),
        Partition("worktree `name:` in scripted descriptors / target `tools:` entries", descriptorNames),
        Partition("worktree Kotlin @TrailblazeToolClass(\"…\")", annotatedNames),
        Partition("worktree MCP subprocess registerTool(\"…\")", mcpAdvertisedNames),
        Partition("worktree scripted .ts basenames", scriptBasenames, exact = false),
        Partition("worktree scripted .ts `export const … = trailblaze.tool`", exportedToolNames, exact = false),
      ),
    )
  }

  /**
   * The registry's own names, so the census can report the classpath partition's size. Filtered
   * through [ToolNameResolver.isKnown] at the call site above so resolution — not this enumeration
   * — remains the thing that decides a name is known.
   */
  private fun registryNames(): Set<String> = buildSet {
    TrailblazeSerializationInitializer.buildAllTools().keys.forEach { add(it.toolName) }
    TrailblazeSerializationInitializer.buildYamlDefinedTools().keys.forEach { add(it.toolName) }
    ScriptedToolNameDiscoverer.discoverAllNames().forEach { add(it.toolName) }
  }

  private fun read(file: Path): String? = try {
    file.toFile().readText()
  } catch (_: Exception) {
    null
  }
}
