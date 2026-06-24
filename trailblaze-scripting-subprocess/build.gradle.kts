import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.TimeUnit
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":trailblaze-common"))
  api(project(":trailblaze-models"))
  // Shared MCP helpers (tool-meta filter, call-result mapper, context envelope, schema
  // projection) extracted so the on-device bundle runtime (`:trailblaze-scripting-bundle`,
  // PR A5) reads the same `_meta` keys and maps MCP shapes the same way. Kept as `api` so
  // consumers of this module can call the helpers directly without a transitive declare.
  api(project(":trailblaze-scripting-mcp-common"))

  implementation(libs.mcp.sdk)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.coroutines)

  // The synthesizer's generated wrapper does `pathToFileURL($sdkBundlePath)`, so it needs the
  // committed `trailblaze-sdk-bundle.js` materialized on disk. The bundle module owns the
  // classpath resource and the safe extract-to-temp-file logic
  // (`SdkBundleResource.extractToFile`); we depend on it for both the API call and so the
  // resource ends up on the host daemon's classpath / inside the installed uber jar (see
  // `scripts/install-trailblaze-from-source.sh`). Without this dep, any trail that triggers
  // an inline-script-tool runtime fails with "classpath resource ... not found".
  implementation(project(":trailblaze-scripting-bundle"))

  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
}

// Installs npm dependencies (via `bun install`) for the sample-app reference MCP tools so
// `SampleAppMcpToolsTest` can spawn `tools.ts` against the real `@modelcontextprotocol/sdk`.
// Best-effort: failures (bun missing, no network) don't fail the build — the test downgrades
// to an `assumeTrue(node_modules exists)` skip, matching the opt-in TS-fixture pattern. This
// keeps the "reference example is exercised in CI" property without requiring every build
// environment to have bun + registry access. bun is the sole supported JS runtime — there is
// no npm fallback (see root CLAUDE.md and PR #3503 for the bun-only contract).
//
// Anchored against `projectDir` rather than `rootProject.layout.projectDirectory` so the
// path stays stable regardless of which `settings.gradle.kts` is active when this module is
// consumed (e.g. when it's included as a sub-build from a parent project). `projectDir` is
// always the module's own directory; a rootProject-relative path would change meaning when
// the rootProject does.
val sampleAppMcpToolsDir = layout.projectDirectory
  .dir("../examples/android-sample-app/trails/config/trailmaps/sampleapp/tools/mcp")

// The `@trailblaze/scripting` SDK itself. Bun installs `file:` deps by symlinking each file in
// the linked package individually (not the package root), so a consumer's `import` from the
// SDK hits the SDK's REAL source path — which means transitive resolution (e.g. the SDK's
// `import "@modelcontextprotocol/sdk/server/mcp.js"`) walks up from `sdks/typescript/` and
// never reaches the consumer's `node_modules`. Giving the SDK its own `node_modules` here
// makes that walk-up find the deps on the first try on any machine, no workspace/bundling
// setup required.
//
// Downstream consumers (uitest JAR builds, the analyzer tests in `:trailblaze-host`, and any
// other module that evaluates the SDK source on disk) rely on this install completing first —
// without it, the first `@modelcontextprotocol/sdk` import fails on any machine that doesn't
// happen to have those deps cached or hoisted by a parent `node_modules`.
val trailblazeScriptingSdkDir = layout.projectDirectory
  .dir("../sdks/typescript")

/**
 * Install registration for the sample-app raw-MCP-SDK reference example, plus the
 * `@trailblaze/scripting` SDK's own node_modules. The trailblaze-SDK authoring surface is
 * demonstrated by the framework-loaded trailmap-scripted-tool pattern (wikipedia, ios-contacts,
 * `sampleapp_writeArtifact`, etc.) rather than a sister standalone-MCP-server demo, so there's no
 * longer a sibling `mcp-sdk/` install task. [label] appears in the Gradle warning + log
 * filename so CI failures attribute to the right source.
 */
abstract class InstallBunDepsTask : DefaultTask() {
  @get:Input
  abstract val label: Property<String>

  @get:Input
  abstract val testGateDocString: Property<String>

  @get:Input
  abstract val failOnInstallError: Property<Boolean>

  @get:Input
  abstract val installTimeoutMinutes: Property<Long>

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val packageJson: RegularFileProperty

  @get:Optional
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val lockFile: RegularFileProperty

  @get:OutputFile
  abstract val installSentinel: RegularFileProperty

  @get:Internal
  abstract val workingDir: DirectoryProperty

  @get:Internal
  abstract val installLog: RegularFileProperty

  @get:Internal
  abstract val rootDir: DirectoryProperty

  @TaskAction
  fun install() {
    val label = label.get()
    val testGateDocString = testGateDocString.get()
    val failOnInstallError = failOnInstallError.get()
    val installTimeoutMinutes = maxOf(1L, installTimeoutMinutes.get())
    val installSentinel = installSentinel.get().asFile
    val workingDir = workingDir.get().asFile
    val installLog = installLog.get().asFile
    val lockFile = lockFile.orNull?.asFile?.takeIf { it.isFile }
    val sharedInstallStamp = File(workingDir, "node_modules/.trailblaze-install-lock")

    // Surface the one-time install cost in the Gradle console so a developer watching a
    // cold `./gradlew test` understands what's happening instead of staring at a silent wait.
    // `lifecycle` is the visible-by-default log level; Gradle's up-to-date cache suppresses
    // this message (and the rest of the task action) on subsequent runs, so this only fires on
    // actual installs.
    logger.lifecycle(
      "Installing $label (one-time; Gradle's up-to-date check caches this on subsequent runs). " +
        "Log: ${installLog.absolutePath}",
    )

    installLog.parentFile.mkdirs()
    installLog.writeText("")
    // Clear any stale sentinel from a prior partial install before re-running. On a cold
    // run this is a no-op; on re-run after a failure it guarantees we don't mark the task
    // up-to-date if a subsequent install also fails silently.
    if (installSentinel.exists()) installSentinel.delete()

    fun <T> withInstallLock(action: () -> T): T {
      val installLockFile = File(workingDir, "node_modules/.trailblaze-install.lock")
      installLockFile.parentFile.mkdirs()
      RandomAccessFile(installLockFile, "rw").channel.use { channel ->
        while (true) {
          val lock = try {
            channel.tryLock()
          } catch (_: OverlappingFileLockException) {
            null
          }
          if (lock != null) {
            lock.use { return action() }
          }
          Thread.sleep(250)
        }
      }
    }

    fun tryInstall(command: List<String>): Int = try {
      installLog.appendText("\n\n==== ${command.joinToString(" ")} (cwd=$workingDir) ====\n")
      // Redirect child stdout+stderr to the install log file rather than capturing on
      // the Gradle thread. An earlier revision read stdout via `readText()` before
      // `waitFor(timeout)`, which defeated the timeout — `readText()` blocks on EOF, and a
      // hung install never closes its stdout. Redirecting to disk lets the kernel drain
      // output asynchronously, so `waitFor(timeout)` actually bounds the wait AND the
      // failure is diagnosable after the fact (vs `DISCARD` which loses everything).
      val proc = ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(installLog))
        .start()
      if (proc.waitFor(installTimeoutMinutes, TimeUnit.MINUTES)) {
        proc.exitValue()
      } else {
        logger.warn(
          "${command.joinToString(" ")} did not finish within ${installTimeoutMinutes}m — killing.",
        )
        proc.destroyForcibly()
        proc.waitFor(10, TimeUnit.SECONDS)
        -1
      }
    } catch (e: java.io.IOException) {
      // Most common cause: bun executable isn't on PATH. Surface at WARN so the developer
      // sees it without hunting the log file — the path-vs-install distinction matters for
      // triage now that there's no npm fallback to paper over a missing bun.
      installLog.appendText("[launch failed (likely bun not on PATH): ${e.message}]\n")
      logger.warn("${command.joinToString(" ")} failed to launch (likely bun not on PATH): ${e.message}")
      -1
    } catch (e: Exception) {
      installLog.appendText("[launch failed: ${e.message}]\n")
      logger.info("${command.joinToString(" ")} failed to launch: ${e.message}")
      -1
    }

    // bun is the sole supported JS runtime (see root CLAUDE.md / PR #3503). No npm fallback —
    // if bun isn't on PATH or `bun install` fails, surface that loudly rather than papering
    // over it with a parallel toolchain that would diverge from the lockfile bun resolves.
    val installCommand =
      if (lockFile != null) listOf("bun", "install", "--frozen-lockfile") else listOf("bun", "install")
    val installSucceeded = withInstallLock {
      tryInstall(installCommand) == 0
    }

    if (installSucceeded) {
      // Only touch the sentinel after a clean install. Gradle's up-to-date check then skips
      // this task on future builds as long as `package.json` is unchanged AND the sentinel
      // still exists.
      installSentinel.parentFile.mkdirs()
      installSentinel.writeText("ok\n")
      if (lockFile != null) {
        sharedInstallStamp.writeText(lockFile.readText())
      }
    } else {
      val rootDir = rootDir.get().asFile.absolutePath
      val installCommandString = installCommand.joinToString(" ")
      val message =
        "Failed to install $label deps via `$installCommandString`. $testGateDocString\n" +
          "  Install output:  ${installLog.absolutePath}\n" +
          "  Manual install:  (cd $workingDir && $installCommandString)\n" +
          "  Trailblaze requires bun; activate the repo's Hermit env " +
          "(from repo root: $rootDir, run `source bin/activate-hermit`) " +
          "or install bun from https://bun.sh/."
      if (failOnInstallError) {
        throw GradleException(message)
      } else {
        logger.warn(message)
      }
    }
  }
}

fun registerInstallTask(
  taskName: String,
  label: String,
  workingDirProvider: Directory,
  logName: String,
  testGateDocString: String,
  // When `true`, `bun install` failure throws GradleException and fails the build. Used for
  // installs whose output is a prerequisite of runtime-artifact-producing tasks — e.g.
  // `installTrailblazeScriptingSdk`, which `:trailblaze-bundled-config:compileTrailblazeWorkspace`
  // and `:trailblaze-quickjs-tools` both `dependsOn`. Without this, a missing bun would
  // silently produce a broken daemon JAR that fails at first SDK import. When `false`, the
  // task only warns — appropriate for the sample-app MCP install, which is gated by the
  // test's own `assumeTrue(node_modules/.install-ok)` skip.
  failOnInstallError: Boolean,
) = tasks.register<InstallBunDepsTask>(taskName) {
  group = "verification"
  val tone = if (failOnInstallError) "required" else "best-effort"
  description = "Installs npm deps for $label via `bun install` ($tone)."

  this.label.set(label)
  this.testGateDocString.set(testGateDocString)
  this.failOnInstallError.set(failOnInstallError)
  // 15 minutes is the default ceiling. It covers corporate proxies that aggressively
  // rate-limit or drop connections (e.g. an internal npm registry mirror, where a fresh
  // `bun install` can sit on triple-ECONNRESET retries before a 200). On CI and fast
  // public-registry environments the install completes in well under a minute, and
  // Gradle's input/output up-to-date check skips the task entirely on subsequent builds,
  // so the headroom is paid at most once per developer-machine. Override via Gradle
  // property for edge cases: `./gradlew test -PtrailblazeInstallTimeoutMinutes=30`.
  installTimeoutMinutes.set(
    providers.gradleProperty("trailblazeInstallTimeoutMinutes")
      .map { it.toLongOrNull() ?: 15L }
      .orElse(15L),
  )
  packageJson.set(workingDirProvider.file("package.json"))
  val candidateLockFile = workingDirProvider.file("bun.lock")
  if (candidateLockFile.asFile.isFile) {
    lockFile.set(candidateLockFile)
  }
  // Sentinel file touched only after a successful install. Using it as the declared task
  // output (rather than the `node_modules` dir itself) fixes the Gradle up-to-date check on
  // partial/truncated installs: if a developer Ctrl-C's during the first install, or
  // `installTimeoutMinutes` fires mid-install, `node_modules/` exists but is incomplete.
  // Declaring `.install-ok` as the output means the task is only "up to date" when install
  // cleanly finished — subsequent `./gradlew test` runs re-install instead of starting the
  // subprocess against a broken node_modules and failing with an opaque import error.
  installSentinel.set(workingDirProvider.file("node_modules/.install-ok"))
  workingDir.set(workingDirProvider)
  installLog.set(layout.buildDirectory.file("tmp/$logName"))
  rootDir.set(rootProject.layout.projectDirectory)
}

val installSampleAppMcpTools = registerInstallTask(
  taskName = "installSampleAppMcpTools",
  label = "the sample-app reference MCP tools",
  workingDirProvider = sampleAppMcpToolsDir,
  logName = "install-sample-app-mcp.log",
  testGateDocString =
    "SampleAppMcpToolsTest will skip (or fail loud in CI — see TRAILBLAZE_SAMPLE_APP_MCP_TEST).",
  // Best-effort. The test is the only consumer and self-gates on the sentinel.
  failOnInstallError = false,
)

// See [trailblazeScriptingSdkDir] for *why* the SDK gets its own node_modules (bun's
// file-level symlink strategy for `file:` deps makes the consumer's node_modules invisible
// to transitive resolution inside the SDK). Consumers across the build wire this task via
// `tasks.named("installTrailblazeScriptingSdk")`.
val installTrailblazeScriptingSdk = registerInstallTask(
  taskName = "installTrailblazeScriptingSdk",
  label = "the @trailblaze/scripting SDK itself",
  workingDirProvider = trailblazeScriptingSdkDir,
  logName = "install-trailblaze-scripting-sdk.log",
  testGateDocString =
    "Analyzer tests + downstream JAR builds that read the SDK source rely on this install. " +
      "If it fails the subprocess will error out on the first `@modelcontextprotocol/sdk` " +
      "import, surfacing as a hard test failure rather than an `assumeTrue` skip. Investigate " +
      "the install log (path printed above) before chasing the test symptom.",
  // Required. This task is a transitive `dependsOn` of every `compileTrailblazeWorkspace`
  // run (wired via `TrailblazeBundlePlugin`) and of `:trailblaze-quickjs-tools`. A silent
  // warn-only failure would let a broken daemon JAR ship — fail loudly instead so the issue
  // surfaces at build time, not at first SDK import.
  failOnInstallError = true,
)

tasks.test {
  useJUnit()
  dependsOn(installSampleAppMcpTools)
  dependsOn(installTrailblazeScriptingSdk)
  // Pass the absolute path of the reference tools.ts file into the test JVM so the test
  // doesn't rely on JVM cwd. Gradle sets cwd to `projectDir` for `./gradlew test`, but IDE
  // test runners (IntelliJ in particular) often set cwd to the repo root or the
  // run-configuration's dir — a `File("..")`-relative path silently breaks in that case.
  // Resolving via Gradle at configuration time gives the path the same absolute form.
  systemProperty(
    "trailblaze.sampleApp.mcp.toolsTs",
    sampleAppMcpToolsDir.file("tools.ts").asFile.absolutePath,
  )
}
