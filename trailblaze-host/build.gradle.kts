import java.util.concurrent.TimeUnit
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class BundleTrailRunnerDaemonTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val daemonEntry: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val webTsconfig: RegularFileProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:IgnoreEmptyDirectories
  val sdkSources: ConfigurableFileCollection = objects.fileCollection()

  @get:OutputFile
  abstract val outputFile: RegularFileProperty

  @get:Internal
  abstract val webDir: DirectoryProperty

  @get:Internal
  abstract val logFile: RegularFileProperty

  @TaskAction
  fun bundle() {
    val out = outputFile.get().asFile
    out.parentFile.mkdirs()
    val log = logFile.get().asFile
    log.parentFile.mkdirs()
    log.writeText("")
    val proc = try {
      ProcessBuilder("bun", "build", "app/rpc/daemon.ts", "--format=iife", "--outfile", out.absolutePath)
        .directory(webDir.get().asFile)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        .start()
    } catch (e: java.io.IOException) {
      throw GradleException(
        "Could not launch `bun` to bundle the Trail Runner RPC client in " +
          "${webDir.get().asFile}. Trailblaze is bun-only and `bun` is a hard build " +
          "prerequisite — put it on PATH via `source bin/activate-hermit` or install from " +
          "https://bun.sh/. Cause: ${e.message}",
        e,
      )
    }
    try {
      if (!proc.waitFor(2, TimeUnit.MINUTES)) {
        throw GradleException("`bun build` of daemon.ts did not finish within 2 minutes. See ${log.absolutePath}.")
      }
      if (proc.exitValue() != 0) {
        throw GradleException("`bun build` of daemon.ts failed (exit ${proc.exitValue()}). See ${log.absolutePath}.")
      }
    } finally {
      if (proc.isAlive) proc.destroyForcibly()
    }
    require(out.isFile && out.length() > 0L) {
      "`bun build` reported success but $out is missing or empty. See ${log.absolutePath}."
    }
  }
}

// Type-checks the Trail Runner web UI's TypeScript source (app/**/*.ts + the migrated app/**/*.tsx)
// with `tsc --noEmit`. The UI ships no npm deps at runtime (React/CodeMirror/… load as CDN UMD
// globals, Babel-standalone transpiles in-browser), so this is the ONLY thing that actually catches
// type errors — `bun build` of daemon.ts merely transpiles. Runs `bun install --frozen-lockfile`
// (resolving @types via whatever npm registry the environment configures — the committed bun.lock
// pins versions and integrity hashes) then `bun run typecheck` (tsconfig.check.json). bun is
// already a hard build prerequisite for this module.
abstract class CheckTrailRunnerTypesTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:IgnoreEmptyDirectories
  val sources: ConfigurableFileCollection = objects.fileCollection()

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val packageJson: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val lockfile: RegularFileProperty

  @get:OutputFile
  abstract val marker: RegularFileProperty

  @get:Internal
  abstract val webDir: DirectoryProperty

  @get:Internal
  abstract val logFile: RegularFileProperty

  @get:Internal
  abstract val bunCacheDir: DirectoryProperty

  private fun run(log: java.io.File, vararg cmd: String) {
    val proc = try {
      ProcessBuilder(*cmd)
        .directory(webDir.get().asFile)
        .apply {
          // bun resolves its install cache as $BUN_INSTALL/install/cache; a set-but-EMPTY
          // BUN_INSTALL (seen in some app-spawned environments) turns that into a CWD-relative
          // `install/cache` — i.e. a cache directory dropped INSIDE src/main/resources, whose
          // entry names embed the resolving registry host. Pin the cache into the build dir so
          // the location never depends on the inherited environment; an explicitly set
          // BUN_INSTALL_CACHE_DIR is a deliberate operator choice and is respected.
          if (environment()["BUN_INSTALL_CACHE_DIR"].isNullOrBlank()) {
            environment()["BUN_INSTALL_CACHE_DIR"] = bunCacheDir.get().asFile.absolutePath
          }
        }
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        .start()
    } catch (e: java.io.IOException) {
      throw GradleException(
        "Could not launch `${cmd.first()}` to type-check the Trail Runner web UI in " +
          "${webDir.get().asFile}. Trailblaze is bun-only and `bun` is a hard build prerequisite — " +
          "put it on PATH via `source bin/activate-hermit` or install from https://bun.sh/. " +
          "Cause: ${e.message}",
        e,
      )
    }
    try {
      if (!proc.waitFor(5, TimeUnit.MINUTES)) {
        throw GradleException(
          "`${cmd.joinToString(" ")}` did not finish within 5 minutes. See ${log.absolutePath}.\n" +
            log.readText().lines().takeLast(40).joinToString("\n"),
        )
      }
      if (proc.exitValue() != 0) {
        throw GradleException(
          "Trail Runner web UI type-check failed (`${cmd.joinToString(" ")}` exit ${proc.exitValue()}). " +
            "Full log: ${log.absolutePath}\n" +
            log.readText().lines().takeLast(40).joinToString("\n"),
        )
      }
    } finally {
      if (proc.isAlive) proc.destroyForcibly()
    }
  }

  @TaskAction
  fun check() {
    val log = logFile.get().asFile
    log.parentFile.mkdirs()
    log.writeText("")
    run(log, "bun", "install", "--frozen-lockfile")
    run(log, "bun", "run", "typecheck")
    val out = marker.get().asFile
    out.parentFile.mkdirs()
    out.writeText("ok")
  }
}

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.jetbrains.compose.multiplatform)
  // Registers generateDtoTs / verifyDtoTs (verify wired into check) for the Trail Runner DTO
  // → TypeScript codegen. See the trailblazeDtoTsCodegen {} block below.
  id("trailblaze.dto-ts-codegen")
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-opt-in=androidx.compose.ui.test.ExperimentalTestApi",
    )
  }
}

tasks.withType<Tar> {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip> {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

configurations.all {
  // Selenium comes transitively from Maestro and we do not use it.
  exclude(group = "org.seleniumhq.selenium")

  // Playwright driver-bundle (~194 MB) contains pre-packaged Node.js + driver binaries for all
  // platforms. Excluded to reduce uber JAR size; PlaywrightDriverManager downloads the driver
  // for the current platform on first use and caches it at ~/.cache/trailblaze/playwright-driver/.
  exclude(group = "com.microsoft.playwright", module = "driver-bundle")

  // Note: GraalVM JS/Rhino are NOT excluded — Maestro's Orchestra.initJsEngine() eagerly
  // initializes GraalJsEngine which requires org.graalvm.polyglot at runtime.

  // Unused LLM provider clients (~6 MB total including AWS SDK tree) pulled transitively by
  // ai.koog:agents-mcp → prompt-executor-llms-all. Trailblaze only uses Anthropic, Google,
  // OpenAI, OpenRouter, and Ollama — these are declared explicitly in the modules that need them.
  exclude(group = "ai.koog", module = "prompt-executor-bedrock-client")
  exclude(group = "ai.koog", module = "prompt-executor-dashscope-client")
  exclude(group = "ai.koog", module = "prompt-executor-deepseek-client")
  exclude(group = "ai.koog", module = "prompt-executor-mistralai-client")

  // AWS SDK for Bedrock (~5.7 MB) — only transitive dep of the excluded bedrock-client above.
  // No direct AWS SDK usage anywhere in the codebase.
  exclude(group = "aws.sdk.kotlin")
  exclude(group = "aws.smithy.kotlin")

  // Redis/Lettuce (~2.3 MB + deps) — transitive from ai.koog:prompt-cache-redis.
  // Trailblaze does not use Redis-based prompt caching.
  exclude(group = "io.lettuce")
  exclude(group = "redis.clients.authentication")
  exclude(group = "ai.koog", module = "prompt-cache-redis")

  // Reactor (~1.5 MB) — transitive from Lettuce Redis client. No direct usage in codebase.
  // Note: io.micrometer is NOT excluded — maestro-utils MetricsProvider depends on it.
  exclude(group = "io.projectreactor")

  // Apache HTTP Client 5 (~2 MB) — transitive from Koog's ktor-client-apache5.
  // Trailblaze uses ktor-client-okhttp, not Apache.
  exclude(group = "org.apache.httpcomponents.client5")
  exclude(group = "org.apache.httpcomponents.core5")
  exclude(group = "io.ktor", module = "ktor-client-apache5")

  // Note: io.grpc is NOT excluded — Maestro uses gRPC to communicate with its instrumentation
  // APK on Android and the XCTest runner on iOS (via maestro-client).
  // Maestro bundles both grpc-netty and grpc-okhttp. When both are on the classpath,
  // Netty's NameResolverProvider wins ServiceLoader priority and resolves localhost to a
  // DomainSocketAddress, which OkHttp's gRPC transport can't handle (ClassCastException).
  // Maestro's AndroidDriver uses OkHttp transport, so grpc-netty is not needed.
  exclude(group = "io.grpc", module = "grpc-netty")
}

dependencies {
  api(project(":trailblaze-agent"))
  implementation(project(":trailblaze-capture"))

  api(libs.maestro.orchestra)
  api(libs.maestro.client)
  api(libs.maestro.ios)
  api(libs.maestro.web)
  api(libs.maestro.ios.driver)
  api(libs.dadb)
  api(libs.okhttp)
  api(libs.jansi)
  api(libs.picocli)
  api(libs.ktor.client.okhttp)
  api(libs.slf4j.api)

  implementation(project(":trailblaze-common"))
  // RecordingTabComposable reads TrailblazeToolMeta (trailhead-picker scoping) directly. This
  // resolved transitively via :trailblaze-scripting-subprocess's `api` dependency before, which
  // would silently break if that module ever narrowed its own dependency scope — declare it
  // explicitly since this module has its own real usage of the type.
  implementation(project(":trailblaze-scripting-mcp-common"))
  // Goose-config writer in TrailblazeDesktopUtil reuses the bundler module's shared
  // YAML emit/quote utilities (`xyz.block.trailblaze.bundle.yaml.YamlEmitter`). The
  // bundler is otherwise build-time-only — this is the only runtime consumer today;
  // future `trailblaze bundle` CLI will be the second.
  implementation(project(":trailblaze-trailmap-bundler"))
  implementation(project(":trailblaze-revyl"))
  implementation(project(":trailblaze-compose"))
  implementation(project(":trailblaze-scripting-subprocess"))
  // Inline scripted tools (under trailmap manifest `target.tools:`) now run their handlers
  // in-process inside QuickJsToolHost rather than via an MCP subprocess detour — see
  // `LazyYamlScriptedToolRegistration` and #2749 for the swap. The dep is added here
  // (not in :trailblaze-scripting-subprocess) because the wire-in lives in
  // TrailblazeHostYamlRunner.launchSubprocessMcpServersIfAny.
  implementation(project(":trailblaze-quickjs-tools"))
  // OkHttp-backed `fetch` for in-process scripted tools (host daemon opts in; localhost-only by
  // default). Isolated in its own module so the lean engine module stays OkHttp-free — see
  // `:trailblaze-scripting-fetch`.
  implementation(project(":trailblaze-scripting-fetch"))
  implementation(libs.compose.ui.test.junit4)
  implementation(project(":trailblaze-playwright"))
  implementation(project(":trailblaze-report"))
  implementation(project(":trailblaze-server"))
  implementation(project(":trailblaze-ui"))
  implementation(libs.jna)

  // Compose dependencies for JVM UI code moved from trailblaze-ui
  implementation(compose.desktop.currentOs)

  // --------------------------------------------------------------------------
  // Cross-host Skiko native bundling
  // --------------------------------------------------------------------------
  // Skiko is the JNI binding behind `org.jetbrains.skia.*`, used for WebP
  // screenshot encoding (and pulled in by Compose Desktop). It ships as one
  // JAR per host OS+arch, each containing a single `lib*.so` / `.dylib`.
  //
  // Without the explicit declarations below, the only contributor would be
  // `compose.desktop.currentOs` (transitively), which resolves to *just* the
  // build host's variant. That's how the uber JAR built on macOS-arm64
  // Runway agents historically shipped only `libskiko-macos-arm64.dylib` —
  // running it on Linux CI failed with:
  //   `LibraryLoadException: Cannot find libskiko-linux-x64.so.sha256`
  // the first time anything touched WebP encoding (the default screenshot
  // format). See #2844 for the breaking build, and the sibling
  // `runtimeOnly("org.jetbrains.compose.desktop:desktop-jvm-<os>:…")` block
  // in `trailblaze-compose`'s build.gradle.kts that handles the same
  // category of bug for the Compose Desktop aggregator artifact.
  //
  // Declaring all 3 supported variants as `runtimeOnly` makes the uber JAR
  // OS-portable regardless of build host, and lets the dependency-guard
  // baseline read as plain documentation of which platforms we ship.
  //
  // Intel macOS and Windows are intentionally omitted — see
  // `TrailblazeDesktopUtil.assertSupportedPlatform()`, which rejects those
  // hosts at startup with a clear message rather than crashing later inside
  // Skiko's JNI loader.
  listOf(
    "linux-x64",
    "linux-arm64",
    "macos-arm64",
  ).forEach { target ->
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-$target:${libs.versions.skiko.get()}")
  }

  implementation(libs.compose.ui)
  implementation(libs.compose.runtime)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.components.resources)
  implementation(libs.material.icons.extended)
  implementation(libs.multiplatform.markdown.renderer.m3)
  implementation(libs.compose.navigation)

  implementation(libs.ktor.client.logging)
  // Trail Runner's server-sent-events session stream (sessionStreamRoutes) and TypeScript
  // language-server bridge (lspRoutes' webSocket{} route) register on the daemon's embeddedServer,
  // which installs both plugins; the route DSL symbols live in these artifacts.
  implementation(libs.ktor.server.sse)
  implementation(libs.ktor.server.websockets)
  implementation(libs.koog.prompt.executor.anthropic)
  implementation(libs.koog.prompt.executor.google)
  implementation(libs.koog.prompt.executor.ollama)
  implementation(libs.koog.prompt.executor.openai)
  implementation(libs.koog.prompt.executor.openrouter)
  implementation(libs.koog.agents.tools)
  // Koog 1.0.0 stopped leaking Ktor types transitively through the LLM client modules; we
  // construct `KtorKoogHttpClient.Factory` directly from our customized Ktor client so this
  // module needs a direct dep on http-client-ktor.
  implementation(libs.koog.http.client.ktor)
  implementation(libs.mcp.sdk)

  // We're not actually leveraging playwright now, so let's keep it out of the app
  implementation(libs.playwright)

  implementation(libs.differ.jvm)
  implementation(project(":trailblaze-tracing"))
  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
  testImplementation(libs.ktor.server.cio)
  testImplementation(libs.ktor.server.content.negotiation)
  testImplementation(libs.ktor.serialization.kotlinx.json)
  // Trail Runner endpoint tests: spin up an in-memory Ktor app via testApplication.
  testImplementation(libs.ktor.server.test.host)
}

tasks.test {
  useJUnit()
}

tasks.register<Test>("updateSystemPromptBaselines") {
  description = "Regenerate system prompt baseline files. Commit the updated files after running."
  group = "verification"
  testClassesDirs = tasks.test.get().testClassesDirs
  classpath = tasks.test.get().classpath
  useJUnit()
  environment("UPDATE_BASELINES", "true")
  filter {
    includeTestsMatching("*.ComposedSystemPromptBaselineTest")
  }
  // Always rerun: this task writes to source-controlled `.txt` baselines, and the
  // source prompt `.md` is not a declared input — Gradle could otherwise mark the
  // task UP-TO-DATE after a prior run and silently skip the regen, leaving stale
  // baselines on disk.
  outputs.upToDateWhen { false }
}

// Generate version.properties file with git version info
val generateVersionProperties by tasks.registering {
  val outputDir = layout.buildDirectory.dir("generated/resources/version")
  outputs.dir(outputDir)

  // Declare every input the doLast block reads as a task input so Gradle's up-to-date
  // check re-runs the task when any of these change. Without these, passing a different
  // `-Ptrailblaze.variant=...` on the command line silently reuses the previously
  // generated `version.properties` because the task's outputs haven't changed shape —
  // which surfaced as `install-trailblaze-source.sh` baking the wrong variant
  // into a fresh JAR.
  val gitTagVersion = rootProject.extra["gitTagVersion"] as String
  val gitVersionFull = rootProject.extra["gitVersionFull"] as String
  val cliVersionInput = project.version.toString()
  val hasExplicitVersionInput = gradle.startParameter.projectProperties.containsKey("version")
  val variantInput = rootProject.findProperty("trailblaze.variant")?.toString() ?: ""
  inputs.property("gitTagVersion", gitTagVersion)
  inputs.property("gitVersionFull", gitVersionFull)
  inputs.property("cliVersion", cliVersionInput)
  inputs.property("hasExplicitVersion", hasExplicitVersionInput)
  inputs.property("variant", variantInput)

  doLast {
    val dir = outputDir.get().asFile
    dir.mkdirs()
    val propsFile = File(dir, "version.properties")
    // Prefer: 1) explicit -Pversion from CLI, 2) semver from git tag, 3) git timestamp
    val version = when {
      hasExplicitVersionInput -> cliVersionInput
      gitTagVersion.isNotEmpty() -> gitTagVersion
      else -> gitVersionFull
    }
    val content = buildString {
      appendLine("version=$version")
      if (variantInput.isNotEmpty()) appendLine("variant=$variantInput")
    }
    propsFile.writeText(content)
  }
}

// Stage the TypeScript compiler (`_tsc.js` + standard-library `lib.*.d.ts` files) as JAR
// resources at `trails/config/typecheck/typescript/lib/...`, so `trailblaze typecheck`
// can extract the bundled tsc into each workspace's `<workspace>/.trailblaze/typecheck/`
// at compile time without going through `bun install` per trailmap — which was the forcing
// function (per-trailmap transitive npm closure failed to resolve through corporate npm
// mirrors in some environments).
//
// Source: `sdks/typescript/node_modules/typescript/lib/`, populated by `bun install` in
// the SDK package (CI's static-checks pipeline runs this before any Gradle build does;
// developers do the same once locally). Pinned to typescript@6.0.3 via the SDK package's
// devDependency — matches the version `bun tsc --noEmit` would have used in the per-trailmap
// era, so authors see the same diagnostics they'd seen before this command existed.
//
// **Trimmed payload** — locale dirs (`cs/`, `de/`, `es/`, ..., `zh-tw/`), the `_tsserver.js`
// language-server entry point, and `_typingsInstaller.js` are excluded. `trailblaze
// typecheck` only needs `_tsc.js` + the standard-library `lib.*.d.ts` files; the rest is
// dead weight that would bloat the JAR by ~6 MB. The English diagnostic strings live inline
// in `_tsc.js`, so dropping the locale dirs only loses translated messages (no fallback
// path; tsc just emits in English).
//
// **Not in node_modules?** The task copies whatever exists at the source path. If
// `bun install` hasn't populated `node_modules/typescript/`, the resource bundle stays
// empty and `WorkspaceTypeScriptSetup.extractTypecheck` surfaces a clean error at
// runtime asking the developer to run `bun install` in `sdks/typescript/` once. Hard-
// failing here would block every other host build on a fresh checkout that hasn't run
// `bun install` yet, which the existing `copyTypescriptSdkResources` (in
// `:trailblaze-models`) also avoids.
val copyTypescriptCompilerResources by tasks.registering(Copy::class) {
  group = "trailblaze"
  description = "Stages typescript@6.0.3's tsc + lib.*.d.ts files into build/ for inclusion in this module's JAR resources."
  // Path relative to `:trailblaze-host` project dir → `../sdks/typescript/...`
  from(layout.projectDirectory.dir("../sdks/typescript/node_modules/typescript")) {
    // Top-level package metadata + license — tsc loads `package.json` for its own version
    // string. The two .txt files are tiny and let consumers see the upstream license.
    include("package.json")
    include("LICENSE.txt")
    include("ThirdPartyNoticeText.txt")
    // Compiler core + standard-library type declarations. `_tsc.js` is the entry point.
    include("lib/_tsc.js")
    include("lib/lib.*.d.ts")
    include("lib/diagnosticMessages.generated.json")
  }
  into(layout.buildDirectory.dir("generated-resources/typecheck/trails/config/typecheck/typescript"))
}

// Stage the single shared scripted-tool registration-wrapper template as a JAR resource so
// `DaemonScriptedToolBundler.synthesizeWrapper` can read it from the classpath at daemon time —
// always present regardless of how esbuild was resolved (the daemon may pick esbuild up from PATH
// rather than the SDK's node_modules, in which case the SDK directory can't be located by walk-up).
//
// Source of truth: `sdks/typescript/tools/in-process-wrapper-template.mjs`. The two Gradle bundlers
// (`build-logic`'s BundleAuthorToolsTask and `:trailblaze-common`'s framework bundler) read the same
// committed file directly off disk; this Copy stages it onto the classpath so the runtime module
// reaches the identical template without a filesystem walk. Keeping ONE committed template is what
// retires the old SISTER-IMPL-TAG triplication of the wrapper JS.
val copyScriptedToolWrapperTemplate by tasks.registering(Copy::class) {
  group = "trailblaze"
  description = "Stages the shared scripted-tool registration-wrapper template into this module's JAR resources."
  // Path relative to `:trailblaze-host` project dir → `../sdks/typescript/tools/...`
  from(layout.projectDirectory.file("../sdks/typescript/tools/in-process-wrapper-template.mjs"))
  into(layout.buildDirectory.dir("generated-resources/scripted-tool-wrapper/xyz/block/trailblaze/scripting"))
}

// Stage the agent skill (`SKILL.md` + `SETUP.md` + `references/` + any `scripts/`/assets) as JAR
// resources so `trailblaze skill` can print/install it from any installed CLI (Homebrew,
// install.sh) without a repo checkout. `Sync` (not `Copy`) so a file deleted from the skill can't
// linger as a stale resource. The generated `manifest.txt` (one relative path per line, sorted) is
// how the runtime enumerates the files — JAR classpath "directories" can't be listed. Consumed by
// `BundledAgentSkill`.
//
// Which skill ships is variant-gated: the Internal build bundles the *superset* skill maintained at
// the repo root (extra internal-only references + scripts, with the shared references symlinked back
// to the OSS copy — `Sync` dereferences those symlinks so their content lands in the jar). The OSS
// build bundles the public skill that lives alongside this module. The Internal path resolves
// against `rootDir`, which is the internal repo root only in the internal Gradle build; in the OSS
// mirror `trailblaze.variant` is unset, so that branch is never taken.
val agentSkillSourceDir: File =
  if (providers.gradleProperty("trailblaze.variant").orNull == "Internal") {
    rootDir.resolve(".claude/skills/trailblaze")
  } else {
    layout.projectDirectory.dir("../skills/trailblaze").asFile
  }
val copyAgentSkillResources by tasks.registering(Sync::class) {
  group = "trailblaze"
  description = "Stages the trailblaze agent skill into this module's JAR resources."
  from(agentSkillSourceDir) {
    // Ship every file in the curated skill dir (markdown, scripts, assets) — not just markdown, so a
    // reference that points at a helper script installs a working copy. `.DS_Store` and editor swap
    // files are the only things excluded.
    exclude("**/.DS_Store", "**/*.swp")
  }
  into(layout.buildDirectory.dir("generated-resources/agent-skill/xyz/block/trailblaze/skill"))
  doLast {
    val root = destinationDir
    val entries = root.walkTopDown()
      .filter { it.isFile }
      .map { it.relativeTo(root).invariantSeparatorsPath }
      .sorted()
      .toList()
    root.resolve("manifest.txt").writeText(entries.joinToString("\n") + "\n")
  }
}

// Package the bundled trailblaze authoring skill (skills/trailblaze) as a single zip JAR
// resource so AgentSkillMaterializer can unpack it into a workspace's .claude/skills/ when
// an external agent session starts and no trailblaze skill is otherwise discoverable there.
// Zipped (rather than staged file-by-file) because classpath resources can't be listed as a
// directory at runtime; one known-name zip entry sidesteps that.
val zipTrailblazeSkill by tasks.registering(Zip::class) {
  group = "trailblaze"
  description = "Packages the bundled trailblaze authoring skill for workspace materialization."
  from(layout.projectDirectory.dir("../skills/trailblaze"))
  destinationDirectory = layout.buildDirectory.dir("generated-resources/agent-skills/xyz/block/trailblaze/trailrunner")
  archiveFileName = "trailblaze-skill.zip"
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}

// The demonstrate-first Create generation agent leans on this second skill (skills/trailblaze-author):
// how to turn a captured human demonstration bundle into a durable, verified trail. Packaged and
// materialized identically to the trailblaze skill above (see AgentSkillMaterializer).
val zipTrailblazeAuthorSkill by tasks.registering(Zip::class) {
  group = "trailblaze"
  description = "Packages the bundled trailblaze-author skill for workspace materialization."
  from(layout.projectDirectory.dir("../skills/trailblaze-author"))
  destinationDirectory = layout.buildDirectory.dir("generated-resources/agent-skills/xyz/block/trailblaze/trailrunner")
  archiveFileName = "trailblaze-author-skill.zip"
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}

// Add generated resources to source sets
sourceSets {
  main {
    resources.srcDir(generateVersionProperties.map { it.outputs.files.singleFile })
    resources.srcDir(
      copyTypescriptCompilerResources.map { layout.buildDirectory.dir("generated-resources/typecheck").get() },
    )
    resources.srcDir(
      copyScriptedToolWrapperTemplate.map { layout.buildDirectory.dir("generated-resources/scripted-tool-wrapper").get() },
    )
    resources.srcDir(
      copyAgentSkillResources.map { layout.buildDirectory.dir("generated-resources/agent-skill").get() },
    )
    // Both skill zips write into this one dir; a single srcDir picks up both, so it must not be
    // registered twice or the entries collide. builtBy carries BOTH producers to every consumer
    // of the source set (processResources, sourcesJar, ...) - a provider mapped from just one
    // task would leave the other an undeclared dependency, which Gradle fails validation on.
    resources.srcDir(
      files(layout.buildDirectory.dir("generated-resources/agent-skills"))
        .builtBy(zipTrailblazeSkill, zipTrailblazeAuthorSkill),
    )
  }
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
  dependsOn(generateVersionProperties)
  dependsOn(copyTypescriptCompilerResources)
  dependsOn(copyScriptedToolWrapperTemplate)
  dependsOn(copyAgentSkillResources)
  dependsOn(zipTrailblazeSkill)
  dependsOn(zipTrailblazeAuthorSkill)
}

dependencyGuard {
  configuration("runtimeClasspath")
}

// ─── Trail Runner TypeScript DTO bindings codegen ────────────────────────────
// Generates trailrunner-dtos.ts from the Kotlin @Serializable DTOs the Trail Runner HTTP API
// exchanges as JSON, so a TypeScript UI can consume them type-safely (Kotlin canonical, TS derived).
// The reusable walker lives in :trailblaze-models; the roots + main are in
// xyz.block.trailblaze.trailrunner.codegen.TrailRunnerDtoTsBindings; the generate/verify task
// wiring (verify into check) comes from the trailblaze.dto-ts-codegen build-logic plugin.
trailblazeDtoTsCodegen {
  mainClass.set("xyz.block.trailblaze.trailrunner.codegen.TrailRunnerDtoTsBindingsKt")
  val mainCompilation = kotlin.target.compilations.getByName("main")
  codegenClasspath.from(
    mainCompilation.output.allOutputs,
    mainCompilation.runtimeDependencyFiles,
  )
  // Lands in the OSS scripting SDK's generated/ dir alongside selectors.ts so it's OSS-available
  // and importable by a TypeScript UI. Resolved relative to this module dir (`../sdks/...`) so the
  // same path works from either build root that includes this module.
  generatedTsFile.set(
    layout.projectDirectory.file("../sdks/typescript/src/generated/trailrunner-dtos.ts"),
  )
}

// ─── Trail Runner web RPC client bundle ──────────────────────────────────────
// `app/rpc/daemon.bundle.js` is the IIFE the desktop app serves to the Trail Runner web UI (it
// publishes `window.TbRpc`). It's produced from `app/rpc/daemon.ts` — which inlines the generated
// host-rpc.ts / trailrunner-dtos.ts DTOs and the rpc client — via `bun build`, and is a BUILD
// ARTIFACT, not committed source (gitignored). Mirrors the treatment of the
// @trailblaze/scripting bundles. `bun build` is self-contained (no node_modules), so unlike the
// SDK bundles there's no install preflight — `bun` (Hermit-pinned) is already a hard build
// prerequisite for this module.
val trailRunnerWebDir =
  layout.projectDirectory.dir("src/main/resources/xyz/block/trailblaze/trailrunner/web")
val bundleTrailRunnerDaemon by tasks.registering(BundleTrailRunnerDaemonTask::class) {
  group = "trailblaze"
  description =
    "Bundles the Trail Runner web RPC client (app/rpc/daemon.ts -> daemon.bundle.js) via bun. " +
      "Build artifact, gitignored; wired ahead of resource packaging below."
  val outFile = trailRunnerWebDir.file("app/rpc/daemon.bundle.js")
  // daemon.ts inlines the generated DTOs + rpc client from the SDK source tree; treat the whole
  // SDK `src/` (minus tests) as an input so any change to a transitively-bundled source re-bundles,
  // and so Gradle can UP-TO-DATE-skip when nothing changed. `bun build` output is byte-deterministic.
  daemonEntry.set(trailRunnerWebDir.file("app/rpc/daemon.ts"))
  // `bun build` reads web/tsconfig.json (compilerOptions — moduleResolution, target, paths — affect
  // the emitted bundle), so declare it an input. Otherwise editing it would leave daemon.bundle.js
  // stale while Gradle considers this task UP-TO-DATE.
  webTsconfig.set(trailRunnerWebDir.file("tsconfig.json"))
  sdkSources.from(
    project.fileTree(layout.projectDirectory.dir("../sdks/typescript/src")) {
      exclude("**/*.test.ts")
    },
  )
  outputFile.set(outFile)
  webDir.set(trailRunnerWebDir)
  logFile.set(layout.buildDirectory.file("tmp/bundle-trailrunner-daemon.log"))
}

// daemon.bundle.js lives under src/main/resources AND is a declared task output, so Gradle 8
// hard-fails any consumer of that resource dir that lacks a dependency edge ("uses this output of
// task ... without declaring ... a dependency"). The consumers here are `processResources` and the
// publish `sourcesJar`. Matched by name (ordering-only dependsOn) so new variants are covered
// without re-enumerating. (Same fix as the scripting-bundle resource tasks.)
val bundleDaemonTask = tasks.named("bundleTrailRunnerDaemon")
tasks.matching { t ->
  t.name == "processResources" || t.name == "sourcesJar" ||
    t.name.endsWith("ProcessResources") || t.name.endsWith("SourcesJar")
}.configureEach {
  dependsOn(bundleDaemonTask)
  // The web dir is a classpath resource tree served by the daemon, but it also carries a dev-only
  // type-check toolchain (node_modules with @types, package.json, lockfile, tsconfigs, .d.ts, tests,
  // playwright e2e). None of that is served or needed at runtime, and node_modules would bloat the
  // jar by 100s of MB — exclude it from both the runtime resources and the sources jar. The served
  // UI itself (index.html, *.jsx/*.tsx, *.css, *.js, daemon.bundle.js) is untouched.
  if (this is AbstractCopyTask) {
    exclude(
      "**/trailrunner/web/node_modules/**",
      // bun's local install-cache fallback (a broken environment can drop `install/cache` in the
      // web dir — see CheckTrailRunnerTypesTask); its entry names embed the resolving registry
      // host, so it must never be packaged.
      "**/trailrunner/web/install/**",
      "**/trailrunner/web/package.json",
      "**/trailrunner/web/bun.lock",
      "**/trailrunner/web/tsconfig*.json",
      "**/trailrunner/web/types/**",
      "**/trailrunner/web/**/*.test.ts",
      "**/trailrunner/web/e2e/**",
    )
  }
}

// Type-check gate for the Trail Runner web UI (`tsc --noEmit`). Wired into `check` so CI fails on a
// type error in the migrated .tsx source — the in-browser Babel path only strips types, so this is
// where errors are actually caught.
val checkTrailRunnerTypes by tasks.registering(CheckTrailRunnerTypesTask::class) {
  group = "verification"
  description = "Type-checks the Trail Runner web UI (.ts/.tsx) with tsc --noEmit via bun."
  dependsOn(bundleDaemonTask)
  sources.from(
    project.fileTree(trailRunnerWebDir) {
      include(
        "app/**/*.ts",
        "app/**/*.tsx",
        "types/**/*.d.ts",
        "tsconfig.json",
        "tsconfig.check.json",
      )
      exclude("**/*.test.ts")
    },
  )
  // daemon.ts type-checks against the generated SDK bindings it imports by relative path
  // (../sdks/typescript/src/generated/host-rpc + trailrunner-dtos + the rpc client). Those
  // files live outside the web dir, so `dependsOn(bundleDaemonTask)` only ORDERS the bundle — it does
  // NOT make them inputs. Declare the SDK source tree as inputs (mirroring bundleTrailRunnerDaemon) so
  // a DTO/RPC regeneration that makes daemon.ts type-invalid invalidates this task instead of leaving
  // it UP-TO-DATE and silently skipping tsc.
  sources.from(
    project.fileTree(layout.projectDirectory.dir("../sdks/typescript/src")) {
      exclude("**/*.test.ts")
    },
  )
  packageJson.set(trailRunnerWebDir.file("package.json"))
  lockfile.set(trailRunnerWebDir.file("bun.lock"))
  webDir.set(trailRunnerWebDir)
  marker.set(layout.buildDirectory.file("tmp/check-trailrunner-types.marker"))
  logFile.set(layout.buildDirectory.file("tmp/check-trailrunner-types.log"))
  bunCacheDir.set(layout.buildDirectory.dir("tmp/bun-install-cache"))
}

tasks.named("check") { dependsOn(checkTrailRunnerTypes) }
