package xyz.block.trailblaze.toolcalls.commands

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the single definition of what "offline" means on Android.
 *
 * Every Android driver stands in for airplane mode by switching the radios
 * [NetworkConnectionTrailblazeTool.ANDROID_RADIOS] names. That used to be written out once per
 * driver, kept together only by comments asking the next editor to keep them in sync — and a
 * comment has no compiler behind it. A radio added to one driver and missed in another changes
 * what a recorded `networkConnection` step MEANS depending on which driver replays the trail: same
 * YAML, two different device states, nothing red anywhere.
 *
 * Hoisting the list fixed the three copies that existed. It does not stop a fourth from being
 * written, which is the failure this test makes loud.
 *
 * Deliberately a proxy, and a narrow one:
 * - It matches the `svc` spelling only. A driver reaching the radios by some other mechanism is
 *   invisible here.
 * - It reads Kotlin only, so it cannot see the CI script that restores the radios between builds.
 *   That copy is called out in [NetworkConnectionTrailblazeTool.ANDROID_RADIOS]' documentation
 *   instead.
 * - The files it reads are not declared Gradle inputs, so an incremental local run can report a
 *   stale pass after a copy is added elsewhere. CI builds clean, which is where this has to hold;
 *   locally, reach for `--rerun-tasks` when the point is to prove the guard.
 */
class AndroidRadioShellCommandsStayInOneSourceTest {

  @Test
  fun `no kotlin source hardcodes a radio svc command outside the shared source`() {
    val root = sourceTreeRoot()
    val offenders = root.walkTopDown()
      .onEnter { it.name !in SKIPPED_DIRECTORIES }
      .filter { it.isFile && it.extension == "kt" && it.name !in ALLOWED_FILES }
      .flatMap { file ->
        file.readLines().withIndex()
          .filterNot { (_, line) -> isComment(line) }
          .filter { (_, line) -> RADIO_SVC_COMMANDS.any { it.containsMatchIn(line) } }
          .map { (index, line) -> "${file.toRelativeString(root)}:${index + 1}  ${line.trim()}" }
      }
      .toList()

    assertTrue(
      offenders.isEmpty(),
      "These hardcode a radio `svc` command or the airplane-mode command instead of reading it " +
        "from NetworkConnectionTrailblazeTool. A second copy silently changes what " +
        "`networkConnection` means on the driver that owns it. Call " +
        "NetworkConnectionTrailblazeTool.androidRadioShellCommand(radio, on), " +
        "androidAirplaneModeShellCommand(enabled), or " +
        "androidMaestroAirplaneModeRadioCommands(airplaneModeEnabled) from a Maestro " +
        "`setAirplaneMode`, instead:\n" +
        offenders.joinToString("\n") { "  - $it" },
    )
  }

  /**
   * Prose about `svc` is not a second definition of the radio set, and this area is heavily
   * commented precisely because the invariant is subtle. Skipping comment lines is what lets the
   * patterns below be wide enough to catch an interpolated command without going red on a sentence
   * describing one.
   */
  private fun isComment(line: String): Boolean = line.trimStart().let { trimmed ->
    trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
  }

  /**
   * The whole checkout, so a copy in a module outside this framework's own is caught too.
   *
   * Gradle runs a Test task with the project directory as its working directory. Walking up to the
   * checkout root rather than stopping at the sibling modules is what makes the scan match what
   * this guard claims: this tree can be a build's root or one directory inside a larger build, and
   * in the second case the drivers' siblings are not the only Kotlin around.
   */
  private fun sourceTreeRoot(): File {
    val root = generateSequence(File("..").canonicalFile) { it.parentFile }
      .firstOrNull { File(it, ".git").exists() }
      ?: fail("No checkout root above ${File("..").canonicalFile} — this guard can't scan.")
    if (root.walkTopDown().onEnter { it.name !in SKIPPED_DIRECTORIES }
        .none { it.isFile && it.name == SHARED_SOURCE_FILE }
    ) {
      fail("Did not find $SHARED_SOURCE_FILE under ${root.absolutePath} — this guard can't scan.")
    }
    return root
  }

  companion object {
    private const val SHARED_SOURCE_FILE = "NetworkConnectionTrailblazeTool.kt"

    /**
     * A radio `svc` command written out in code, in either of the two shapes a copy takes.
     *
     * The literal one is obvious, and its radio names come from [ANDROID_RADIOS] so that adding a
     * radio widens the guard instead of quietly narrowing it. The interpolated one is the shape the
     * shared source itself uses (`"svc $radio $enableOrDisable"` over a radio list), so a driver
     * keeping its own `listOf("wifi", "data", "bluetooth")` beside it would read identically and
     * name no radio for a literal pattern to find — which is exactly the copy this guard exists to
     * catch.
     */
    private val RADIO_SVC_COMMANDS = listOf(
      Regex(
        """svc\s+(""" +
          NetworkConnectionTrailblazeTool.ANDROID_RADIOS.joinToString("|") { Regex.escape(it) } +
          """)\b""",
      ),
      Regex("""svc\s+\$\{?\w"""),
      // Real airplane mode is the other half of what this tool sets, and it drifts the same way:
      // one CI script already writes this command out because it cannot read it from Kotlin, so a
      // second Kotlin copy is exactly the divergence this guard exists to catch.
      Regex("""cmd\s+connectivity\s+airplane-mode"""),
      // READING the flag drifts too, and the drivers do read it — the Boolean airplane-mode read
      // every Android driver answers is a `settings get global airplane_mode_on`. Scoped to the
      // airplane-mode flag on purpose: `settings get/put global` is a generic primitive that a
      // couple of dozen unrelated call sites legitimately use for their own settings, so a generic
      // pattern would flag all of them and this guard would be turned off rather than obeyed.
      // Callers name the flag through ANDROID_AIRPLANE_MODE_SETTING, which this does not match.
      Regex("""settings\s+(get|put|delete)\s+global\s+airplane_mode"""),
    )

    /** The one place the commands are built, and the test that pins their exact spelling. */
    private val ALLOWED_FILES = setOf(
      "NetworkConnectionTrailblazeTool.kt",
      "NetworkConnectionTrailblazeToolTest.kt",
    )

    private val SKIPPED_DIRECTORIES = setOf("build", "node_modules", ".gradle", ".git", "generated")
  }
}
