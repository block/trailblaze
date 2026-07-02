plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.jetbrains.compose.multiplatform)
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
  }
}

tasks.named("processResources") {
  dependsOn(generateVersionProperties)
  dependsOn(copyTypescriptCompilerResources)
  dependsOn(copyScriptedToolWrapperTemplate)
}

dependencyGuard {
  configuration("runtimeClasspath")
}
