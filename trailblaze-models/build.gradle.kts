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
  // Registers `bundleTrailblazeSdkDts` (manual regenerate) and
  // `verifyTrailblazeSdkDtsBundle` (CI freshness gate) for the declaration-bundle
  // artifact this module ships in JAR resources. See `TrailblazeSdkDtsBundlePlugin`.
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
    layout.projectDirectory.file("../sdks/typescript/src/generated/host-rpc-dtos.ts"),
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
      implementation(libs.kotlin.reflect)
      implementation(libs.kotlinx.serialization.core)
    }

    // Shared source set for JVM and Android (reflection-based code not available on wasmJs)
    val jvmAndAndroid by creating {
      dependsOn(commonMain.get())
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

// Ship the TypeScript scripted-tools SDK as a single committed declaration bundle at the
// JAR resource path `trails/config/sdk/typescript/dist/index.d.ts`.
// `WorkspaceTypeScriptSetup` extracts that single file into each workspace's
// `<workspace>/.trailblaze/sdk/dist/index.d.ts` at compile / daemon-bootstrap time, and
// per-trailmap tsconfigs point their `paths` mapping at it directly — no `node_modules/`,
// no per-trailmap `package.json`, no workspace `tsconfig.base.json` extends chain.
//
// **Why a single committed bundle and not the SDK source tree.** Per-trailmap `tsc --noEmit`
// against extracted SDK source surfaced ~20 ambient-globals / unresolvable-imports errors
// (DOM `URL`, Node `process`, `zod`, `@modelcontextprotocol/sdk` not on the trailmap's
// classpath). A rolled-up declaration bundle has none of these: it's a pure type surface
// with zod's exported types inlined, and the SDK implementation bodies (which reference
// runtime globals) don't ship at all. See `TrailblazeSdkDtsBundlePlugin` for the
// regenerate-and-commit workflow + CI byte-diff gate.
//
// Output goes to `build/generated-resources/sdk/...`, registered as an additional
// commonMain resources srcDir so it ships in the JAR. Authors edit `sdks/typescript/src/`
// and run `./gradlew :trailblaze-models:bundleTrailblazeSdkDts` to refresh
// `dist/index.d.ts`; the same task is invoked manually after a `bun install` in the SDK
// dir picks up a new zod / typescript version.
val copyTypescriptSdkResources by tasks.registering(Copy::class) {
  group = "trailblaze"
  description = "Stages the TypeScript SDK declaration bundle into build/ for inclusion in this module's JAR resources."
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

// `verifyTrailblazeSdkDtsBundle` is NOT wired into `check` — it requires
// `node_modules/.bin/dts-bundle-generator`, which means CI agents have to run
// `(cd sdks/typescript && bun install)` before invoking it. That installation step
// happens in the same CI static-checks step that runs `verifyTrailblazeSdkBundle`, so
// the verify task is gated through the CI pipeline rather than every developer's
// `./gradlew check`. Local devs who edit SDK source run
// `./gradlew :trailblaze-models:bundleTrailblazeSdkDts` manually; the CI gate catches
// the case where someone forgets.

// NOT also wired into the Android source set's `assets.srcDir`: the SDK is only consumed
// by the host-side `WorkspaceCompileBootstrap` (JVM-only), never by on-device test
// runners. Adding it to the Android assets pipeline triggered AGP's strict
// implicit-dependency validation against `mergeDebugAssets` — and there's no consumer
// that needs it on-device anyway.
