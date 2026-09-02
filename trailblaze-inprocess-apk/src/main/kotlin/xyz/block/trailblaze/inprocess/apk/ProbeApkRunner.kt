package xyz.block.trailblaze.inprocess.apk

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import java.io.File

/** Everything `probe-apk` takes, however it was invoked. */
data class ProbeApkOptions(
  val appApk: File,
  /**
   * The exact processed shell APK this app will be paired with.
   *
   * Null is allowed and answers a real question (what does this app look like?), but it caps the
   * verdict at [ProbeStatus.INCOMPLETE] — no dex intersection, and half the era floor unread.
   */
  val shellApk: File? = null,
  val declaredDepsFile: File? = null,
  /** Replaces [DEFAULT_DECLARED_SHELL_FLOOR] — the floor for libraries the shell does not package. */
  val shellFloorFile: File? = null,
  /** Where to write the fingerprint. Null writes it to stdout. */
  val outFile: File? = null,

  /**
   * Which disqualifiers make this run a failure. Null means all of them.
   *
   * The verdict is unaffected — the fingerprint always names every reason, and the rendered verdict
   * always prints every reason. This narrows only the *exit code*, so a caller can gate on the
   * subset it is willing to stand behind while still surfacing the rest at full strength.
   *
   * The farm's pre-flight is the caller that needs it: some disqualifiers rest on a premise that is
   * not yet verified, or compare against reference data a known-open finding says is wrong, and a
   * gate that fails a working lane over either is not a gate, it is an outage. Keeping the policy in
   * the caller rather than the probe matters because the caller is what knows which lane it is.
   */
  val failOn: Set<ProbeReasonCode>? = null,
)

/** The fingerprint plus the exit code a caller should use. */
data class ProbeApkOutcome(
  val fingerprint: AppFingerprint,
  val yaml: String,
  private val failOn: Set<ProbeReasonCode>? = null,
) {
  /** The reasons that, per [ProbeApkOptions.failOn], make this run a failure. */
  val failingReasons: List<ProbeReason>
    get() = fingerprint.verdict.reasons.filter { failOn == null || it.code in failOn }

  /**
   * `0` when nothing in [failingReasons] fired, `1` otherwise.
   *
   * With no [ProbeApkOptions.failOn] narrowing, that is `0` for GO and `1` for anything else —
   * [ProbeStatus.INCOMPLETE] included, on purpose: a caller must not read "we could not check" as
   * "we checked and it is fine".
   *
   * `1` deliberately matches the Trailblaze CLI's `ASSERTION_FAILED` — a verdict is a check that
   * ran and said no. An unreadable APK exits `2` and a bad flag exits `3`, also matching, so a shell
   * reading either surface reads one policy. See [ProbeApkMain].
   */
  val exitCode: Int get() = if (failingReasons.isEmpty()) 0 else 1
}

/**
 * The one implementation of `probe-apk`.
 *
 * Two callers reach it — the `trailblaze inprocess probe-apk` CLI command, and the lean [main] this
 * module ships for the farm's install-time pre-flight — and they must produce byte-identical
 * fingerprints, because a committed fingerprint written by one is checked against a live probe run
 * by the other.
 */
object ProbeApkRunner {

  private val floorYaml = Yaml(configuration = YamlConfiguration(strictMode = true))

  fun run(options: ProbeApkOptions): ProbeApkOutcome =
    run(options, ApkSigningCertificate::sha256OfSoleSigner)

  /**
   * The same run with the signing-certificate reader supplied — [ApkProbe]'s own seam, one level up.
   *
   * Needed here because everything this function wires (the `--shell-floor` file replacing the
   * built-in floor, the declared-dependency list, and the fingerprint `--out` writes) is reachable
   * only through a probe that SUCCEEDS, and a probe reads the app's signature before anything else.
   * Signing an APK in a unit test needs a keystore and a certificate generator this module
   * deliberately does not depend on.
   */
  internal fun run(options: ProbeApkOptions, certificateDigest: (File) -> String): ProbeApkOutcome {
    requireReadable(options.appApk, "app APK")
    options.shellApk?.let { requireReadable(it, "shell APK") }

    val declared = options.declaredDepsFile?.let { file ->
      requireReadable(file, "declared-dependency list")
      DeclaredDependencies.parse(file.readText(), file.path)
    } ?: DeclaredDependencies()

    val declaredFloor = options.shellFloorFile?.let { file ->
      requireReadable(file, "shell-floor file")
      try {
        floorYaml.decodeFromString(ShellFloor.serializer(), file.readText())
      } catch (e: YamlException) {
        throw ApkReadException(
          "${file.path} is not a readable shell-floor file: ${e.message}. Expected `libraries:` " +
            "with `library:` / `minVersion:` / `why:` entries.",
          e,
        )
      }
    } ?: DEFAULT_DECLARED_SHELL_FLOOR

    val fingerprint = ApkProbe(declaredFloor = declaredFloor, certificateDigest = certificateDigest)
      .probe(options.appApk, options.shellApk, declared)
    val yaml = AppFingerprintCodec.encode(fingerprint)
    options.outFile?.let { out ->
      out.absoluteFile.parentFile?.mkdirs()
      out.writeText(yaml)
    }
    return ProbeApkOutcome(fingerprint, yaml, options.failOn)
  }

  /**
   * The verdict, rendered for a human reading a terminal or a CI log.
   *
   * The farm's pre-flight prints exactly this rather than restating the outcome, so a local probe
   * and a failed build say the same words about the same app.
   *
   * Every reason is printed regardless of [ProbeApkOptions.failOn]; a reason the caller chose not to
   * fail on is marked, so a reader can tell "this did not fire" from "this fired and was not fatal".
   */
  fun renderVerdict(fingerprint: AppFingerprint, failOn: Set<ProbeReasonCode>? = null): String = buildString {
    appendLine("${fingerprint.verdict.status}: ${fingerprint.targetPackage}")
    fingerprint.verdict.reasons.forEach { reason ->
      val fatal = failOn == null || reason.code in failOn
      appendLine("  - ${reason.code}${if (fatal) "" else " (reported, not enforced)"}: ${reason.message}")
    }
  }.trimEnd()

  private fun requireReadable(file: File, what: String) {
    if (!file.isFile) {
      throw ApkReadException("No $what at ${file.path}.")
    }
    // Existence is not readability. A file the process cannot open — wrong owner, a restrictive
    // umask on a CI agent, a sandbox — would otherwise fail later inside a reader, as an IOException
    // with no explanation and the wrong exit code.
    if (!file.canRead()) {
      throw ApkReadException(
        "The $what at ${file.path} exists but is not readable by this process. Check its permissions.",
      )
    }
  }
}

/**
 * Command-line entry point for callers that must not pay for the whole Trailblaze CLI.
 *
 * The farm's install-time pre-flight is the caller this exists for: it runs on a CI agent that has
 * already spent a Gradle build on the test APK, and asking it to build `:trailblaze-host`
 * as well — to run a check that reads two files — would be the expensive half of a cheap gate. The
 * flags are the same names the CLI command uses, and
 * `ProbeApkSurfaceParityTest` pins that the two surfaces produce the same fingerprint.
 *
 * Usage: `<app apk> [--shell <apk>] [--declared-deps <yaml>] [--shell-floor <yaml>]
 * `[--fail-on <CODE,CODE>] [--out <yaml>]`
 *
 * Exit codes match the CLI's policy so a shell script does not have to know which surface it called:
 * `0` GO, `1` a verdict of NO_GO or INCOMPLETE, `2` an input that could not be read, `3` a bad flag.
 */
object ProbeApkMain {
  /** A file named on the command line could not be read. Matches the CLI's `INFRA_FAILED`. */
  private const val EXIT_UNREADABLE_INPUT = 2

  /** The command line itself was wrong. Matches the CLI's `MISUSE`. */
  private const val EXIT_BAD_FLAGS = 3

  @JvmStatic
  fun main(args: Array<String>) {
    val options = try {
      parse(args)
    } catch (e: ApkReadException) {
      System.err.println(e.message)
      kotlin.system.exitProcess(EXIT_BAD_FLAGS)
    }
    val outcome = try {
      ProbeApkRunner.run(options)
    } catch (e: ApkReadException) {
      System.err.println(e.message)
      kotlin.system.exitProcess(EXIT_UNREADABLE_INPUT)
    }
    if (options.outFile == null) {
      println(outcome.yaml)
    } else {
      println("Fingerprint written to ${options.outFile.path}")
    }
    println(ProbeApkRunner.renderVerdict(outcome.fingerprint, options.failOn))
    kotlin.system.exitProcess(outcome.exitCode)
  }

  /**
   * Public so the CLI's own tests can hold the two surfaces to the same command line. The CLI does
   * not call it — picocli parses that surface — which is exactly why the parity has to be asserted.
   */
  fun parse(args: Array<String>): ProbeApkOptions {
    var appApk: File? = null
    var shell: File? = null
    var declaredDeps: File? = null
    var shellFloor: File? = null
    var out: File? = null
    var failOn: Set<ProbeReasonCode>? = null
    var i = 0
    while (i < args.size) {
      val arg = args[i]
      fun value(): String {
        val next = args.getOrNull(i + 1)
          ?: throw ApkReadException("$arg needs a value after it.")
        i++
        return next
      }
      when (arg) {
        "--shell" -> shell = File(value())
        "--declared-deps" -> declaredDeps = File(value())
        "--shell-floor" -> shellFloor = File(value())
        "--out" -> out = File(value())
        "--fail-on" -> failOn = parseFailOn(value())
        else -> {
          if (arg.startsWith("--")) throw ApkReadException("Unknown option $arg.")
          if (appApk != null) throw ApkReadException("Expected one app APK, got a second: $arg.")
          appApk = File(arg)
        }
      }
      i++
    }
    return ProbeApkOptions(
      appApk = appApk ?: throw ApkReadException(
        "Usage: <app apk> [--shell <apk>] [--declared-deps <yaml>] [--shell-floor <yaml>] " +
          "[--fail-on <CODE,CODE>] [--out <yaml>]",
      ),
      shellApk = shell,
      declaredDepsFile = declaredDeps,
      shellFloorFile = shellFloor,
      outFile = out,
      failOn = failOn,
    )
  }

  /**
   * Parses `--fail-on`. An unknown code is a hard error rather than a silent drop — a typo that
   * quietly removed a disqualifier from a gate's enforcing set would read as a passing build.
   */
  fun parseFailOn(raw: String): Set<ProbeReasonCode> {
    val names = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) {
      throw ApkReadException("--fail-on needs at least one reason code. Known codes: ${knownCodes()}.")
    }
    return names.map { name ->
      ProbeReasonCode.entries.firstOrNull { it.name == name }
        ?: throw ApkReadException("--fail-on does not know the reason code '$name'. Known codes: ${knownCodes()}.")
    }.toSet()
  }

  private fun knownCodes() = ProbeReasonCode.entries.joinToString(", ") { it.name }
}
