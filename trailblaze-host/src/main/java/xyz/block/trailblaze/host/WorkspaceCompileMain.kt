package xyz.block.trailblaze.host

import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigBootstrap
import xyz.block.trailblaze.util.Console
import kotlin.system.exitProcess

/**
 * JVM `main` wrapper around [WorkspaceCompileBootstrap.bootstrap] for invocation from a
 * Gradle `JavaExec` task in the [TrailblazeBundlePlugin] convention plugin.
 *
 * The bootstrap runs the same compile chain the CLI's `trailblaze compile` and the daemon
 * startup path run: workspace SDK extraction (`<workspace>/.trailblaze/sdk/` plus
 * `tsconfig.base.json`), per-trailmap `client.d.ts` codegen, per-trailmap `tsconfig.json` /
 * `.gitignore` emission, and `TrailblazeCompiler.compile`. Wiring it into `:check` (via the
 * convention plugin's `tasks.matching { it.name == "check" }` configureEach) means a fresh
 * clone's first `./gradlew build` materializes the typed bindings that authors otherwise
 * have to run `./trailblaze compile` manually to get — eliminating the "open editor, get
 * red squigglies on every `@trailblaze/scripting` import" onboarding cliff (#3210).
 *
 * **Why a dedicated main and not `TrailblazeCli.compile`.** The CLI's main entry constructs
 * a `TrailblazeDesktopApp` provider (compose-desktop, Skiko native bundling, daemon HTTP
 * server) before dispatching to the `compile` subcommand — overhead that's irrelevant when
 * the only goal is materializing typed bindings. This main calls
 * [WorkspaceCompileBootstrap.bootstrap] directly, so a JavaExec task pays for just the
 * compile path's classpath warm-up, not the desktop runtime's.
 *
 * **Workspace discovery.** `WorkspaceCompileBootstrap.bootstrap()` resolves the workspace
 * via `TrailblazeWorkspaceConfigResolver.resolve(Paths.get(""))`, which walks up from the
 * JVM's current working directory looking for `trails/config/trailblaze.yaml`. The Gradle
 * task sets `workingDir` to the workspace root so this walk-up succeeds without any extra
 * argv plumbing.
 */
object WorkspaceCompileMain {
  @JvmStatic
  fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    // Resolve the working directory once up-front so every log line below can attribute
    // its output to a specific workspace. Without this prefix, a `./gradlew build` that
    // runs the JavaExec across four example trailmaps back-to-back produces four
    // indistinguishable `Recompiled(emitted=1)` lines — useless when CI logs need
    // correlation. `Paths.get("").toAbsolutePath()` is the same resolution
    // `WorkspaceCompileBootstrap` does internally for workspace discovery.
    val workspacePath = java.nio.file.Paths.get("").toAbsolutePath().normalize()
    // Install the workspace-config-dir resolver so the default `platformConfigResourceSource()`
    // layers workspace-on-disk over the classpath. The bootstrap path below triggers discovery,
    // so this has to run before bootstrap().
    TrailblazeWorkspaceConfigBootstrap.ensureInstalled()
    try {
      val result = WorkspaceCompileBootstrap.bootstrap()
      Console.log("trailblaze workspace compile ($workspacePath): $result")
    } catch (e: WorkspaceCompileBootstrap.WorkspaceCompileException) {
      // Typed compile failure — message is already directed (lists each resolver error).
      Console.error("trailblaze workspace compile ($workspacePath) failed:")
      Console.error(e.message ?: "Workspace trailmap compilation failed.")
      exitProcess(1)
    } catch (e: Throwable) {
      // Anything else — `IOException` from SDK extraction, `RuntimeException` from a
      // future emitter helper that downgrades errors via `Console.error` in bootstrap but
      // throws here, an `OutOfMemoryError`, etc. Without this branch the JVM would emit a
      // raw stack trace, which buries the workspace context the CI log reader actually
      // needs to triage. Route the trace through `Console.error` (same writer as the
      // header line) rather than `e.printStackTrace()` so the two writes stay contiguous
      // in stderr — `printStackTrace` uses its own PrintWriter and can interleave with
      // other stderr output in CI logs, fragmenting the failure context.
      Console.error(
        "trailblaze workspace compile ($workspacePath) failed unexpectedly " +
          "(${e.javaClass.simpleName}): ${e.message ?: "no message"}\n" +
          e.stackTraceToString(),
      )
      exitProcess(1)
    }
  }
}
