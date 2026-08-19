import java.io.File
// Imported rather than fully qualified at the use site: in a Kotlin DSL script `java` resolves
// to Gradle's `java` extension, so `java.util.zip.…` doesn't compile.
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  // Locks down the public Kotlin API surface this module ships. Baselines live under
  // `api/<target>.api` and are byte-diffed by the auto-wired `apiCheck` task on every
  // `:check`. When changing public Kotlin API here (incl. fields on `@Serializable`
  // data classes consumed by the TypeScript selector codegen), regenerate via
  // `./gradlew :trailblaze-models:apiDump` and commit the updated baseline alongside
  // the code change. See `CLAUDE.md`'s pre-push-checks section for the workflow.
  alias(libs.plugins.binary.compatibility.validator)
  alias(libs.plugins.vanniktech.maven.publish)
  id("trailblaze.bundled-config")
  // Registers `bundleTrailblazeSdkDts`, which generates the `@trailblaze/scripting` declaration
  // + runtime bundle (`dist/`) this module ships in JAR resources. The bundle is a build
  // artifact (gitignored, regenerated each build), not committed source. See
  // `TrailblazeSdkDtsBundlePlugin`.
  id("trailblaze.sdk-dts-bundle")
  // Registers `generateSelectorsTs` and `verifySelectorsTs` for the selector-grammar
  // Kotlin → TS codegen described in the 2026-05-22 "Kotlin canonical, TypeScript
  // derived" devlog. The Kotlin sealed-class hierarchy (TrailblazeNodeSelector +
  // DriverNodeMatch.* + MatchDescriptor + TrailblazeNode.Bounds) is the spec; the
  // generated TS file is the derived artifact consumed by `@trailblaze/scripting`.
  id("trailblaze.selector-ts-codegen")
  // Registers `generateDtoTs` / `verifyDtoTs` for the descriptor-walking DTO codegen. Here it
  // emits TypeScript for the daemon's /rpc/<Name> request/response types (see
  // xyz.block.trailblaze.codegen.HostRpcDtoTsBindings) so a TypeScript UI can call the same typed
  // RPC the Kotlin/Wasm UI uses. Unlike selector-ts-codegen (source-text), this runs the generator
  // via JavaExec because kotlinx.serialization descriptors need the compiled classes.
  id("trailblaze.dto-ts-codegen")
}

trailblazeDtoTsCodegen {
  mainClass.set("xyz.block.trailblaze.codegen.HostRpcDtoTsBindingsKt")
  // Deferred via providers: the `kotlin {}` block (which registers the `jvm` target) is evaluated
  // after this extension block, so resolve the compilation lazily at execution time.
  codegenClasspath.from(
    provider { kotlin.targets.getByName("jvm").compilations.getByName("main").output.allOutputs },
    provider { kotlin.targets.getByName("jvm").compilations.getByName("main").runtimeDependencyFiles },
  )
  generatedTsFile.set(
    layout.projectDirectory.file("../sdks/typescript/src/generated/host-rpc.ts"),
  )
}

trailblazeSelectorTsCodegen {
  // The selector grammar (`TrailblazeNodeSelector` + `DriverNodeMatch.*`),
  // `MatchDescriptor`, and `TrailblazeNode.Bounds` all live in this commonMain package.
  // The codegen reads the three .kt files by name from this directory.
  kotlinSourceDir.set(
    layout.projectDirectory.dir("src/commonMain/kotlin/xyz/block/trailblaze/api"),
  )
  // Generated file is committed and shipped to the SDK build via the bundleTrailblazeSdkDts
  // pipeline — `src/index.ts` re-exports from this path, so the generated types land in
  // `dist/index.d.ts` / `dist/index.js` alongside the hand-authored SDK surface.
  generatedTsFile.set(
    layout.projectDirectory.file("../sdks/typescript/src/generated/selectors.ts"),
  )
}

trailblazeSdkDtsBundle {
  trailblazeSdkDir.set(layout.projectDirectory.dir("../sdks/typescript"))
  sdkDtsBundleOutputFile.set(layout.projectDirectory.file("../sdks/typescript/dist/index.d.ts"))
  // Secondary bundle for `@trailblaze/scripting/testing` (mock client + mock context
  // helpers) so a trailmap author can `import { createMockClient, createMockContext } from
  // "@trailblaze/scripting/testing"` in a `*.test.ts` file with no per-trailmap tsconfig
  // changes — the per-trailmap tsconfig's `@trailblaze/scripting/*` glob already resolves
  // here via `dist/testing.d.ts`.
  sdkDtsTestingBundleOutputFile.set(layout.projectDirectory.file("../sdks/typescript/dist/testing.d.ts"))
  // Runtime ESM module that `bun test` loads at runtime when an author imports
  // `@trailblaze/scripting/testing` from a `*.test.ts` file. Pure esbuild transpile (not
  // bundle) — `src/testing.ts` has no runtime imports so the output is self-contained
  // and bun resolves it via the per-trailmap tsconfig `paths` mapping with no
  // node_modules step.
  sdkTestingRuntimeOutputFile.set(layout.projectDirectory.file("../sdks/typescript/dist/testing.js"))
  // Runtime ESM bundle paired with `dist/index.d.ts`. Bun resolves it when a scripted
  // tool authored as `import { trailblaze } from "@trailblaze/scripting"` runs, either
  // in `bun test` for a `*.test.ts` or in the host's per-tool subprocess. Without this
  // file the `paths` mapping resolves only to types and the value import fails at
  // load time — see PR #3338's `contacts_ios_searchContacts` doc-block for the
  // historical failure mode.
  sdkRuntimeBundleOutputFile.set(layout.projectDirectory.file("../sdks/typescript/dist/index.js"))
}

configurations.all {
  exclude(group = "ai.koog", module = "prompt-executor-bedrock-client")
  exclude(group = "ai.koog", module = "prompt-executor-dashscope-client")
  exclude(group = "ai.koog", module = "prompt-executor-deepseek-client")
  exclude(group = "ai.koog", module = "prompt-executor-mistralai-client")
}

android {
  namespace = "xyz.block.trailblaze.models"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  // KMP commonMain/resources/ are not automatically included as Java resources on Android.
  // Explicitly add them so trails/config/{providers,toolsets,targets}/*.yaml files are
  // bundled into the AAR/APK and also exposed as Android assets. Android's classloader cannot
  // enumerate resource directories, so the classpath fallback alone is insufficient in an
  // on-device instrumentation-test context — AssetManagerConfigResourceSource needs the
  // configs reachable via the app's assets.
  sourceSets.getByName("main") {
    resources.srcDirs("src/commonMain/resources")
    assets.srcDirs("src/commonMain/resources")
  }

}

kotlin {
  // Apply the default hierarchy template explicitly
  applyDefaultHierarchyTemplate()

  if (findProperty("trailblaze.wasm")?.toString()?.toBoolean() != false) {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
      browser()
      compilerOptions {
        // Enable qualified names in Kotlin/Wasm to support KClass.qualifiedName used in OtherTrailblazeToolSerializer
        // Required since Kotlin 2.2.20 where qualifiedName usage in Wasm became a compile error by default
        freeCompilerArgs.add("-Xwasm-kclass-fqn")
      }
    }
  }

  // Kotlin/Native (iOS simulator, arm64). Declared for its COMPILE SIGNAL, not to ship: an
  // on-device iOS agent needs this module's commonMain to build for Native, and the only way
  // to know it still does is to compile it every build. wasmJs already forces commonMain to
  // be platform-neutral, but it is single-threaded and reflection-free in ways Native is not,
  // so it cannot stand in for this target — the `concurrentMutableMap` seam is the concrete
  // example (see PlatformConcurrency.ios.kt).
  //
  // Pinned OFF in `gradle.properties`, exactly like `trailblaze.wasm`, so an ordinary
  // build pays nothing for this: the target isn't declared,
  // no klib compiles, and the multi-GB Kotlin/Native toolchain is never needed. The KMP
  // cleanliness gate passes `-Ptrailblaze.ios=true`, which is where the signal comes from.
  // Deleting the properties line turns it on by default — the predicate is opt-*out* in shape.
  // Apple targets only build on a macOS host; KGP disables their compile tasks elsewhere, so a
  // Linux build reports SKIPPED rather than failing.
  if (findProperty("trailblaze.ios")?.toString()?.toBoolean() != false) {
    iosSimulatorArm64()
  }

  androidTarget {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_17
    }
  }

  jvm {
    this.compilerOptions {
      jvmTarget = JvmTarget.JVM_17
    }
  }

  sourceSets {
    commonMain.dependencies {
      implementation(libs.coroutines)
      implementation(libs.kaml)
      implementation(libs.koog.agents.tools)
      implementation(libs.koog.prompt.model)
      implementation(libs.koog.prompt.executor.anthropic)
      implementation(libs.koog.prompt.executor.clients)
      implementation(libs.koog.prompt.executor.google)
      // Provides `MultiLLMPromptExecutor` / `PromptExecutor` / `RoutingLLMPromptExecutor`.
      // In Koog 0.8.x these were hauled in transitively via `prompt-executor-llms-all`; in 1.0
      // we need a direct dep because `llms-all` is now a thin convenience wrapper.
      implementation(libs.koog.prompt.executor.model)
      implementation(libs.koog.prompt.executor.openai)
      implementation(libs.koog.prompt.executor.openrouter)
      // The `TrailblazeDynamicLlmTokenProvider` interface signature still takes a Ktor
      // `HttpClient` (callers wrap it in a `KoogHttpClient.Factory`). Direct ktor-core dep
      // here so the type is on the compile classpath.
      implementation(libs.ktor.client.core)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlinx.serialization.core)
    }

    // Shared source set for JVM and Android (reflection-based code not available on wasmJs)
    val jvmAndAndroid by creating {
      dependsOn(commonMain.get())
      dependencies {
        // kotlin-reflect is JVM-only; commonMain uses only the stdlib KClass surface, and the
        // `kotlin.reflect.full` callers (Koog tool descriptors, annotation lookups) live here.
        implementation(libs.kotlin.reflect)
      }
    }

    // Mirror the main source set on the test side so reflection-based tests (e.g.
    // [TrailblazeKoogToolTest]) can target jvmAndAndroid types and actually run on the JVM.
    // Without this `dependsOn` chain the test files compiled in src/jvmAndAndroidTest were
    // silently excluded from `:trailblaze-models:jvmTest` — the directory existed but no test
    // task picked it up.
    val jvmAndAndroidTest by creating {
      dependsOn(commonTest.get())
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.assertk)
      }
    }

    jvmMain {
      dependsOn(jvmAndAndroid)
    }

    androidMain {
      dependsOn(jvmAndAndroid)
      dependencies {
        // AssetManager-backed ConfigResourceSource resolves the Android Context via
        // InstrumentationRegistry. Trailblaze only runs on Android under instrumentation.
        api(libs.androidx.test.monitor)
      }
    }

    // Only present when the (opt-out) iOS target above is declared.
    findByName("iosTest")?.dependencies {
      implementation(kotlin("test"))
    }

    jvmTest {
      dependsOn(jvmAndAndroidTest)
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.assertk)
      }
    }
  }
}

dependencyGuard {
  configuration("jvmMainRuntimeClasspath")
}

// The iosSimulatorArm64 target exists ONLY as a compile-time gate (see the `kotlin {}` block), so
// its publication is suppressed — nothing consumes a Native variant of this module yet, and the
// release pipeline would otherwise start shipping a klib as a silent side effect of a lint gate.
// The wasmJs variant IS published, because the report UI consumes it; that's the distinction.
// Same known residual as `:trailblaze-common`'s wasmJs suppression: the root Gradle Module
// Metadata still advertises `iosSimulatorArm64*-published` variants whose `available-at`
// coordinate never receives an upload. Harmless while no Native consumer exists (JVM and Android
// consumers never resolve those variants); revisit when the on-device iOS agent needs the artifact.
tasks.matching {
  it.name.startsWith("publish") && it.name.contains("IosSimulatorArm64")
}.configureEach {
  enabled = false
}

// Kotlin/Native unit tests execute in an iOS SIMULATOR, and `check` is the deterministic merge
// gate — a simulator boot is exactly the kind of dependency that belongs in a separate CI lane, so
// it stays out of here. The test binary is still COMPILED on every build
// (`compileTestKotlinIosSimulatorArm64`, wired into the KMP cleanliness gate, so the test source
// cannot rot) while the run is opt-in:
//
//   ./gradlew :trailblaze-models:iosSimulatorArm64Test \
//     -Ptrailblaze.ios=true -Ptrailblaze.ios.simulator.tests=true
//
// Both flags: the first declares the target at all (it's pinned off), the second enables the run.
// Flip the run on in a macOS-guaranteed lane when there's more Native code under test than
// PlatformConcurrencyIosTest.
if (findProperty("trailblaze.ios.simulator.tests")?.toString()?.toBoolean() != true) {
  tasks.matching { it.name == "iosSimulatorArm64Test" }.configureEach {
    enabled = false
  }
}

// MatcherParityFixturesTest reads the shared cross-language matcher fixture that lives in the
// TS SDK tree (single source of truth for matching semantics, also consumed by the TS suite's
// matcher-parity.test.ts). Declare it as a test input so editing the fixture re-runs jvmTest
// instead of hitting a stale UP-TO-DATE.
tasks.named<Test>("jvmTest") {
  inputs.file(layout.projectDirectory.file("../sdks/typescript/src/matcher/matcher-parity-fixtures.json"))
    .withPropertyName("matcherParityFixtures")
    .withPathSensitivity(PathSensitivity.NONE)
  // SelectorEngineParityGoldenTest reads the selector-engine parity fixtures (trees + the
  // committed golden the Kotlin/JS engine byte-compares against) from the engine module.
  inputs.dir(layout.projectDirectory.dir("../trailblaze-selector-engine-js/parity"))
    .withPropertyName("selectorEngineParityFixtures")
    .withPathSensitivity(PathSensitivity.NONE)
  // Regen flag for the committed golden: ./gradlew :trailblaze-models:jvmTest
  //   --tests "*SelectorEngineParityGoldenTest*" -Dtrailblaze.updateSelectorEngineGolden=true
  systemProperty(
    "trailblaze.updateSelectorEngineGolden",
    providers.systemProperty("trailblaze.updateSelectorEngineGolden").getOrElse("false"),
  )
}

// Compile bundled framework trailmaps (clock, contacts, wikipedia) into materialized flat
// `targets/<id>.yaml` files at build time. Library trailmaps (`trailblaze`, no `target:`)
// contribute defaults but produce no target output. The generated targets are checked in
// alongside the trailmap sources via a regenerate-and-commit workflow, so the JAR ships
// pre-resolved targets that the daemon's existing flat-target discovery reads directly
// without any trailmap-aware runtime path. The `verifyBundledTrailblazeConfig` task is wired
// into `:check` and fails CI if a trailmap edit landed without a corresponding regen.
bundledTrailblazeConfig {
  trailmapsDir.set(layout.projectDirectory.dir("src/commonMain/resources/trails/config/trailmaps"))
  targetsDir.set(layout.projectDirectory.dir("src/commonMain/resources/trails/config/targets"))
  regenerateCommand.set("./gradlew :trailblaze-models:generateBundledTrailblazeConfig")
}

// Ship the TypeScript scripted-tools SDK declaration + runtime bundle at the JAR resource
// path `trails/config/sdk/typescript/dist/`. The bundle is generated at build time by
// `bundleTrailblazeSdkDts` (esbuild + dts-bundle-generator) and is NOT committed to source —
// it's a derived artifact that rewrites in full on any SDK-source change, so committing it
// was pure diff noise. `copyTypescriptSdkResources` depends on the generate task below.
// `WorkspaceTypeScriptSetup` extracts that single file into each workspace's
// `<workspace>/.trailblaze/sdk/dist/index.d.ts` at compile / daemon-bootstrap time, and
// per-trailmap tsconfigs point their `paths` mapping at it directly — no `node_modules/`,
// no per-trailmap `package.json`, no workspace `tsconfig.base.json` extends chain.
//
// **Why a rolled-up bundle and not the SDK source tree.** Per-trailmap `tsc --noEmit`
// against extracted SDK source surfaced ~20 ambient-globals / unresolvable-imports errors
// (DOM `URL`, Node `process`, `zod`, `@modelcontextprotocol/sdk` not on the trailmap's
// classpath). A rolled-up declaration bundle has none of these: it's a pure type surface
// with zod's exported types inlined, and the SDK implementation bodies (which reference
// runtime globals) don't ship at all. See `TrailblazeSdkDtsBundlePlugin` for the generator.
//
// Output goes to `build/generated-resources/sdk/...`, registered as an additional
// commonMain resources srcDir so it ships in the JAR. The `dist/` bundle is regenerated on
// demand by `bundleTrailblazeSdkDts` (which `bun install`s the SDK devDependencies from the
// committed `bun.lock` first); authors just edit `sdks/typescript/src/` — no manual regen or
// commit step, the build refreshes the bundle whenever the source changes.
val copyTypescriptSdkResources by tasks.registering(Copy::class) {
  group = "trailblaze"
  description = "Stages the TypeScript SDK declaration bundle into build/ for inclusion in this module's JAR resources."
  // The `dist/` bundle is a build artifact (not committed), so regenerate it before staging
  // it into JAR resources. `bundleTrailblazeSdkDts` runs esbuild + dts-bundle-generator from
  // the committed `bun.lock`; it's UP-TO-DATE-skipped when the SDK source is unchanged.
  dependsOn(tasks.named("bundleTrailblazeSdkDts"))
  // Path relative to `:trailblaze-models` project dir, so `../sdks/typescript` resolves
  // to the SDK source tree co-located alongside this module.
  from(layout.projectDirectory.file("../sdks/typescript/dist/index.d.ts"))
  // Sibling testing-helper bundle exposed at `@trailblaze/scripting/testing` (mock client
  // + mock context for `*.test.ts` files next to scripted tools). Same JAR-resource
  // extraction path — `WorkspaceTypeScriptSetup.extractSdk` walks the prefix recursively,
  // so the additional file flows through with no further changes.
  from(layout.projectDirectory.file("../sdks/typescript/dist/testing.d.ts"))
  // Runtime `testing.js` — paired with `testing.d.ts` so a per-trailmap `*.test.ts`
  // resolves `@trailblaze/scripting/testing` to a real executable module under bun.
  from(layout.projectDirectory.file("../sdks/typescript/dist/testing.js"))
  // Runtime `index.js` — paired with `index.d.ts` so a scripted tool authored as
  // `import { trailblaze } from "@trailblaze/scripting"` resolves to a real
  // executable module at load time. `WorkspaceTypeScriptSetup.extractSdk` walks the
  // prefix recursively, so the file flows through with no further code changes.
  from(layout.projectDirectory.file("../sdks/typescript/dist/index.js"))
  into(layout.buildDirectory.dir("generated-resources/sdk/trails/config/sdk/typescript/dist"))
}

kotlin.sourceSets.commonMain.get().resources.srcDir(
  copyTypescriptSdkResources.map { layout.buildDirectory.dir("generated-resources/sdk").get() },
)

// Bundle the scripted-tool analyzer shim (`extract-tool-defs.mjs`) together with its npm
// deps (`ts-json-schema-generator` + `typescript`) into ONE self-contained file via
// `bun build`, shipped at JAR resource `trails/config/analyzer/extract-tool-defs.mjs`.
//
// **Why.** The analyzer extracts each typed tool's JSON Schema by running that shim under
// `bun`. In a source checkout the shim resolves its deps from `sdks/typescript/node_modules/`,
// but an INSTALLED CLI (Homebrew / source-install) has no SDK source tree on disk — so typed
// authoring failed out of the box with "scripted-tool analyzer unavailable". Shipping a
// self-contained bundle lets `ScriptedToolDefinitionAnalyzer.resolveSdkDir` extract it to
// `~/.trailblaze/analyzer/` and run it with only `bun` on PATH — no `node_modules`, no
// `bun install`. Mirrors the SDK `.d.ts` bundle above.
//
// **Built at build time, not committed** (the minified bundle is ~7 MB). Guarded by
// `onlyIf` so a fresh checkout that hasn't run `bun install` in `sdks/typescript/` (or a
// build host without `bun` on PATH) still builds — the resource is simply absent and the
// runtime surfaces the same clean "analyzer unavailable" message it already does. Same
// tolerance posture as `copyTypescriptCompilerResources` in `:trailblaze-host`.
//
// **The bundle is not actually self-contained, and can't be.** `typescript` is CJS, so
// `bun build` inlines its code but freezes `__dirname` / `__filename` to the absolute paths
// they had on the BUILD machine. TypeScript locates its own `lib.*.d.ts` files relative to
// those, and the lib files are data, not code — no bundler inlines them. So a shipped bundle
// looks for `lib.es5.d.ts` under a build-agent path that exists nowhere else, silently loads
// no standard library, and fails to resolve every lib-declared type: `Record`, `Partial`,
// `Pick`, `Omit`. Authors saw `Unhandled error while creating Base Type` on any tool whose
// I/O type used one, while a raw index signature (needing no lib type) worked — which is
// what made it read like a `Record<K,V>` bug. It shipped in 2026.08.18 and was invisible in
// CI and dev checkouts, where the baked path happens to exist.
//
// Fix: rewrite the baked SDK root to [ANALYZER_SDK_ROOT_PLACEHOLDER] and ship the lib files
// alongside, keyed by their SDK-relative paths. `ScriptedToolDefinitionAnalyzer` substitutes
// its extraction dir for the placeholder, so the frozen paths land on the shipped libs.
//
// SISTER-IMPL-TAG: analyzer-bundle-lib-payload. Both constants below are read back by
// `ScriptedToolDefinitionAnalyzer` at extraction time and must stay byte-identical to the
// copies declared there. A Gradle script can't reference the Kotlin source set it builds, so
// there is no compile-time check that they agree — `ScriptedToolAnalyzerBundledShimTest`
// asserts the shipped resource actually contains the placeholder to cover the gap.
val ANALYZER_SDK_ROOT_PLACEHOLDER = "__TRAILBLAZE_ANALYZER_SDK_ROOT__"
val ANALYZER_TS_LIB_ARCHIVE_NAME = "ts-lib.zip"

val bundleScriptedToolAnalyzerShim by tasks.registering(Exec::class) {
  group = "trailblaze"
  description = "Bundles the scripted-tool analyzer shim into a self-contained .mjs for the JAR."
  // The shim bundles `typescript` + `ts-json-schema-generator` from the SDK's node_modules.
  // `bundleTrailblazeSdkDts` owns the SDK devDependency install, so run after it instead of
  // racing a cold checkout where node_modules is not populated yet.
  dependsOn(tasks.named("bundleTrailblazeSdkDts"))
  val sdkDir = layout.projectDirectory.dir("../sdks/typescript")
  val shimSrc = sdkDir.file("tools/extract-tool-defs.mjs")
  val tsjsgDir = sdkDir.dir("node_modules/ts-json-schema-generator")
  val outFile = layout.buildDirectory.file(
    "generated-resources/analyzer/trails/config/analyzer/extract-tool-defs.mjs",
  )
  val libArchive = layout.buildDirectory.file(
    "generated-resources/analyzer/trails/config/analyzer/$ANALYZER_TS_LIB_ARCHIVE_NAME",
  )
  // Captured as task-local values so the `doLast` lambda closes over these and not over the
  // build script object, which the configuration cache can't serialize.
  val sdkRootPath = sdkDir.asFile.absolutePath
  val sdkRootDir = sdkDir.asFile
  val sdkRootPlaceholder = ANALYZER_SDK_ROOT_PLACEHOLDER
  inputs.file(shimSrc)
  inputs.dir(tsjsgDir).optional(true)
  // `typescript` is an input in its own right: it supplies both the inlined compiler and the
  // lib payload, so a version bump has to invalidate this task. Declaring only tsjsg meant a
  // TypeScript upgrade could leave a stale bundle in place.
  inputs.dir(sdkDir.dir("node_modules/typescript")).optional(true)
  outputs.file(outFile)
  outputs.file(libArchive)
  // Skip cleanly when the SDK deps aren't installed or `bun` isn't on PATH — never break
  // an unrelated host build over a payload the runtime degrades around.
  onlyIf {
    tsjsgDir.asFile.exists() &&
      (System.getenv("PATH")?.split(File.pathSeparator)
        ?.any { File(it, "bun").canExecute() } == true)
  }
  workingDir = sdkDir.asFile
  doFirst { outFile.get().asFile.parentFile.mkdirs() }
  commandLine(
    "bun", "build", "tools/extract-tool-defs.mjs",
    "--bundle", "--minify", "--target=bun",
    "--outfile", outFile.get().asFile.absolutePath,
  )
  doLast {
    val out = outFile.get().asFile
    // Fail loudly if `bun build` exits 0 but writes nothing/partial — better a broken build
    // than a JAR that silently ships an empty shim the runtime would choke on per-trailmap.
    require(out.isFile && out.length() > 0L) {
      "bundleScriptedToolAnalyzerShim: `bun build` produced no usable output at $out " +
        "(${if (out.exists()) "${out.length()} bytes" else "missing"})."
    }

    val bundled = out.readText()
    val bakedPathCount = Regex(Regex.escape(sdkRootPath)).findAll(bundled).count()
    // A bundle with no baked build path is either already portable or baked under a path we
    // don't recognize. Either way the assumption this rewrite rests on no longer holds, and
    // shipping it unchanged is how the original bug escaped — so stop the build instead.
    require(bakedPathCount > 0) {
      "bundleScriptedToolAnalyzerShim: expected `bun build` to bake the SDK path ($sdkRootPath) " +
        "into the bundle so it can be redirected at the shipped TypeScript libs, but found no " +
        "occurrence. Re-check how the bundler emits CJS `__dirname` for `typescript`; the " +
        "analyzer cannot resolve `lib.*.d.ts` without this rewrite."
    }

    // Ship the libs for exactly the TypeScript copies the bundle asks for, read off the baked
    // paths themselves rather than by scanning node_modules — several unrelated SDK dev deps
    // vendor their own `typescript`, and shipping those too would be dead weight. There is
    // normally more than one real copy: `ts-json-schema-generator` pins its own `typescript`
    // range, so the generator gets a nested copy while this shim imports the hoisted one, and
    // each needs the libs matching its own version.
    val bakedLibDirs = Regex(
      "${Regex.escape(sdkRootPath)}[^\"']*?/typescript/lib",
    ).findAll(bundled).map { it.value }.distinct().map { File(it) }.toList()
    require(bakedLibDirs.isNotEmpty()) {
      "bundleScriptedToolAnalyzerShim: the bundle bakes $bakedPathCount reference(s) to " +
        "$sdkRootPath but none resolve to a `typescript/lib` directory, so there is nothing to " +
        "ship as the standard library. Re-check how the bundler emits CJS `__dirname`."
    }
    out.writeText(bundled.replace(sdkRootPath, sdkRootPlaceholder))

    // Keyed by path relative to the SDK root — the same relative tails the baked paths carry,
    // so one placeholder substitution lands every file where its copy expects it.
    val libFiles = bakedLibDirs.flatMap { libDir ->
      libDir.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("lib") && it.name.endsWith(".d.ts") }
    }
    require(libFiles.isNotEmpty()) {
      "bundleScriptedToolAnalyzerShim: found no TypeScript `lib*.d.ts` files in " +
        "${bakedLibDirs.joinToString()}. The analyzer needs them to resolve `Record`, " +
        "`Partial`, and every other lib-declared type in a tool's I/O types."
    }
    val archive = libArchive.get().asFile
    archive.parentFile.mkdirs()
    ZipOutputStream(archive.outputStream().buffered()).use { zip ->
      libFiles.sortedBy { it.absolutePath }.forEach { libFile ->
        zip.putNextEntry(
          // Sorted entries + a fixed timestamp keep the archive byte-identical for identical
          // inputs; `ZipEntry` otherwise stamps "now" and the shipped resource would churn on
          // every build.
          ZipEntry(libFile.relativeTo(sdkRootDir).invariantSeparatorsPath).apply { time = 0L },
        )
        libFile.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
      }
    }
    println(
      "bundleScriptedToolAnalyzerShim: redirected $bakedPathCount baked path(s) and packaged " +
        "${libFiles.size} TypeScript lib file(s) (${archive.length() / 1024} KiB).",
    )
  }
}

kotlin.sourceSets.commonMain.get().resources.srcDir(
  bundleScriptedToolAnalyzerShim.map { layout.buildDirectory.dir("generated-resources/analyzer").get() },
)

// `bundleTrailblazeSdkDts` self-installs the SDK devDependencies (`bun install
// --frozen-lockfile`) before running dts-bundle-generator / esbuild, so it needs only `bun`
// on PATH (Hermit-pinned). `copyTypescriptSdkResources` depends on it, so the bundle is
// regenerated as part of any build that packages this module's JAR resources — there's no
// committed copy to drift and no separate verify gate.

// NOT also wired into the Android source set's `assets.srcDir`: the SDK is only consumed
// by the host-side `WorkspaceCompileBootstrap` (JVM-only), never by on-device test
// runners. Adding it to the Android assets pipeline triggered AGP's strict
// implicit-dependency validation against `mergeDebugAssets` — and there's no consumer
// that needs it on-device anyway.
