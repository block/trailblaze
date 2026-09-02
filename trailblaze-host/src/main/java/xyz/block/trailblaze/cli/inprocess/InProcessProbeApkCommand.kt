package xyz.block.trailblaze.cli.inprocess

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.cli.TrailblazeExitCode
import xyz.block.trailblaze.inprocess.apk.ApkReadException
import xyz.block.trailblaze.inprocess.apk.ProbeApkOptions
import xyz.block.trailblaze.inprocess.apk.ProbeApkRunner
import xyz.block.trailblaze.inprocess.apk.ProbeReasonCode
import java.io.File
import java.util.concurrent.Callable

@Command(
  name = "probe-apk",
  mixinStandardHelpOptions = true,
  description = [
    "Fingerprint an app APK and say whether the in-process driver can attach to it.",
    "",
    "Reads the APK only — no device, no Android SDK, no signing key. Emits a few KB of YAML: the " +
      "package, whether a launcher activity exists, every declared ContentProvider, the era of " +
      "each library that would collide in the shared classloader, the signing certificate digest, " +
      "and android:debuggable.",
    "",
    "Pass --shell to compare against the exact test APK the app will be paired with. Without it " +
      "the dex intersection cannot run, and the verdict is capped at INCOMPLETE — never GO.",
  ],
)
class InProcessProbeApkCommand : Callable<Int> {

  @Parameters(
    index = "0",
    paramLabel = "<app apk>",
    description = ["The app APK to fingerprint."],
  )
  lateinit var appApk: File

  @Option(
    names = ["--shell"],
    paramLabel = "<apk>",
    description = [
      "The processed shell (test) APK this app will be paired with. Adds the dex intersection — " +
        "classes the shell contributes that the app also ships, which in one classloader is the " +
        "duplicated-class crash. Required to reach a GO verdict, and it must be the exact APK " +
        "about to be installed, not a same-shaped build.",
    ],
  )
  var shellApk: File? = null

  @Option(
    names = ["--declared-deps"],
    paramLabel = "<yaml>",
    description = [
      "Library versions the app's team states, for an APK that packages no META-INF version " +
        "files and whose era dex markers cannot pin. Format: `libraries:` with `library:` / " +
        "`version:` entries.",
    ],
  )
  var declaredDepsFile: File? = null

  @Option(
    names = ["--shell-floor"],
    paramLabel = "<yaml>",
    description = [
      "Replaces the built-in floor for libraries the shell does not package (so the shell's own " +
        "bytes cannot state one). Format: `libraries:` with `library:` / `minVersion:` / `why:` " +
        "entries.",
    ],
  )
  var shellFloorFile: File? = null

  @Option(
    names = ["--fail-on"],
    paramLabel = "<CODE,CODE>",
    description = [
      "Exit non-zero only for these disqualifiers, instead of for any of them. The fingerprint and " +
        "the printed verdict are unchanged — every reason is still named, and one that fired " +
        "without being enforced is marked as such. Codes: \${COMPLETION-CANDIDATES}",
    ],
    split = ",",
  )
  var failOn: MutableSet<ProbeReasonCode>? = null

  @Option(
    names = ["--out"],
    paramLabel = "<yaml>",
    description = ["Write the fingerprint here instead of stdout."],
  )
  var outFile: File? = null

  override fun call(): Int {
    val outcome = try {
      ProbeApkRunner.run(
        ProbeApkOptions(
          appApk = appApk,
          shellApk = shellApk,
          declaredDepsFile = declaredDepsFile,
          shellFloorFile = shellFloorFile,
          outFile = outFile,
          failOn = failOn,
        ),
      )
    } catch (e: ApkReadException) {
      System.err.println(e.message)
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    if (outFile == null) {
      println(outcome.yaml)
    } else {
      println("Fingerprint written to ${outFile?.path}")
    }
    println(ProbeApkRunner.renderVerdict(outcome.fingerprint, failOn))
    // A verdict is a check that ran and answered, so a non-GO verdict is ASSERTION_FAILED rather
    // than INFRA_FAILED. `ProbeApkMain` — the lean entry point the farm's pre-flight calls — maps
    // the same outcomes to the same numbers, so a shell script does not care which surface it got.
    return if (outcome.exitCode == 0) {
      TrailblazeExitCode.SUCCESS.code
    } else {
      TrailblazeExitCode.ASSERTION_FAILED.code
    }
  }
}
