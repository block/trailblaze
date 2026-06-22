import java.util.concurrent.TimeUnit
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.dagp)
}

android {
  namespace = "xyz.block.trailblaze.common"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  // KMP commonMain/resources/ are not automatically included as Java resources on Android.
  // Explicitly add them so trails/config/tools/*.yaml files are bundled into the AAR/APK
  // and discoverable as Android assets from on-device instrumentation tests.
  sourceSets.getByName("main") {
    resources.srcDirs("src/commonMain/resources")
    assets.srcDirs("src/commonMain/resources")
  }
}

kotlin {
  compilerOptions {
    // Suppress Beta warning for expect/actual classes
    freeCompilerArgs.add("-Xexpect-actual-classes")
  }

  androidTarget {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_17
    }
  }

  jvm {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_17
    }
  }

  // Apply the default hierarchy template explicitly
  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies {
      api(libs.kotlinx.datetime)
      api(libs.kotlinx.serialization.json)
      api(libs.coroutines)
      api(libs.junit)
      api(libs.kaml)
      api(libs.okio)
      api(libs.koog.agents.tools)
      api(libs.ktor.client.core)

      api(project(":trailblaze-models"))
      implementation(project(":trailblaze-tracing"))

      implementation(libs.exp4j)
      implementation(libs.gson)
      implementation(libs.koog.prompt.model)
      implementation(libs.kotlinx.serialization.core)
      implementation(libs.ktor.client.logging)
      implementation(libs.ktor.client.okhttp)
      implementation(libs.ktor.http)
      implementation(libs.ktor.utils)
      implementation(libs.kotlin.reflect)

      runtimeOnly(libs.jackson.dataformat.yaml)
      runtimeOnly(libs.jackson.module.kotlin)
    }


    // Shared source set for JVM and Android (Maestro-dependent code)
    val jvmAndAndroid by creating {
      dependsOn(commonMain.get())
      dependencies {
        // Koog reasoning-loop core (AIAgent + custom strategy graph) for the KOOG_STRATEGY_GRAPH
        // agent. Lives here (not jvmMain) so the on-device Android runtime can run the same agent.
        // JVM-only transitive modules with no Android variant are excluded at the configuration
        // level below.
        implementation(libs.koog.agents)
        implementation(libs.koog.prompt.executor.clients)
      }
    }

    val jvmAndAndroidTest by creating {
      dependsOn(commonTest.get())
      dependencies {
        implementation(libs.kotlin.test.junit4)
        implementation(libs.assertk)
      }
    }

    jvmMain {
      dependsOn(jvmAndAndroid)
      dependencies {
        // dadb: speaks the ADB wire protocol directly so AndroidHostAdbUtils can avoid
        // shelling out to the `adb` binary for shell/install/push/pull/forward operations.
        // Scoped to JVM-only — `AndroidHostAdbUtils` and the rest of the host-only ADB code
        // live under src/jvmMain, so on-device Android consumers do not need (and must not
        // pay for) this dep.
        implementation(libs.dadb)
        // ktor-server (JVM only): RpcRouteExt's `registerRpcHandler` is a Ktor server route
        // extension shared by the host-side modules that run the daemon's embedded server. Scoped
        // to jvmMain so the on-device Android build does not inherit a server framework.
        implementation(libs.ktor.server.core)
      }
    }

    jvmTest {
      dependsOn(jvmAndAndroidTest)
      dependencies {
        implementation(libs.kotlin.test.junit4)
        implementation(libs.assertk)
      }
    }

    androidUnitTest {
      dependsOn(jvmAndAndroidTest)
    }

    androidMain {
      dependsOn(jvmAndAndroid)
      dependencies {
        // AndroidX Test and UiAutomator for on-device testing
        api(libs.androidx.test.monitor)
        api(libs.androidx.uiautomator)
      }
    }
  }
}

dependencies {
  val maestroVersion = libs.versions.maestro.get()

  // Maestro dependencies with non-transitive configuration for jvmAndAndroid
  // These are shared between JVM (host) and Android (on-device) builds
  add("jvmAndAndroidApi", "dev.mobile:maestro-orchestra-models:$maestroVersion") { isTransitive = false }
  add("jvmAndAndroidApi", "dev.mobile:maestro-client:$maestroVersion") { isTransitive = false }
  add("jvmAndAndroidApi", "dev.mobile:maestro-utils:$maestroVersion") { isTransitive = false }
  add("jvmAndAndroidImplementation", "dev.mobile:maestro-orchestra:$maestroVersion") { isTransitive = false }
}

// Koog pulls a fan of LLM-provider clients + a redis cache transitively. We only use the
// Anthropic/Google/OpenAI families (declared in the host/server runtime modules); the rest, plus
// the redis cache (Lettuce/Netty), are JVM-only and have no Android variant. Exclude them from the
// shared jvmAndAndroid config so the Android target can resolve koog-agents. Mirrors the excludes
// already applied in trailblaze-server / trailblaze-host.
configurations.named("jvmAndAndroidImplementation") {
  exclude(group = "ai.koog", module = "prompt-executor-bedrock-client")
  exclude(group = "ai.koog", module = "prompt-executor-dashscope-client")
  exclude(group = "ai.koog", module = "prompt-executor-deepseek-client")
  exclude(group = "ai.koog", module = "prompt-executor-mistralai-client")
  exclude(group = "ai.koog", module = "prompt-cache-redis")
}

dependencyGuard {
  configuration("jvmRuntimeClasspath") {
    modules = true
  }
}

// --- Framework scripted-tool QuickJS bundles ---
// Pre-compiles every @trailblaze/scripting-based `.ts` tool in the framework `trailblaze` trailmap
// into a self-contained `.bundle.js` that QuickJsToolHost evaluates directly (host daemon +
// on-device). The committed `.bundle.js` is what the toolset-delivery path loads from the
// classpath / APK assets — see TrailblazeHostYamlRunner.registerToolsetScriptedToolBundles and
// AndroidTrailblazeRule.launchToolsetScriptedToolBundles. Generalized over the directory so new
// framework scripted tools are picked up without editing this build script.
//
// This is an IN-PROCESS bundler: it aliases `@trailblaze/scripting` to the SLIM in-process SDK
// profile (`sdks/typescript/src/in-process.ts` — no MCP, no ajv, no zod) so each per-tool bundle
// stays KB-scale, and synthesizes a registration wrapper (typed-tool exports don't self-register).
//
// The wrapper JS is no longer hand-built here: it renders from the ONE committed template,
// `sdks/typescript/tools/in-process-wrapper-template.mjs`, read off disk via `frameworkRoot`. The
// build-time `BundleAuthorToolsTask` (build-logic) reads the same file; the daemon-time
// `DaemonScriptedToolBundler` (:trailblaze-host) reads it from the classpath. That single template
// retired the old three-way SISTER-IMPL-TAG duplication — there's nothing left to keep in lockstep.
val frameworkToolsDir =
  file("src/commonMain/resources/trails/config/trailmaps/trailblaze/tools")
val frameworkRoot: File? = run {
  val marker = "sdks/typescript/package.json"
  var dir: File? = rootProject.projectDir
  while (dir != null) {
    if (File(dir, marker).isFile) return@run dir
    dir.listFiles()?.forEach { child ->
      if (child.isDirectory && File(child, marker).isFile) return@run child
    }
    dir = dir.parentFile
  }
  null
}
val esbuildBinary = frameworkRoot?.let { File(it, "sdks/typescript/node_modules/.bin/esbuild") }
val scriptingSdkSrc = frameworkRoot?.let { File(it, "sdks/typescript/src/in-process.ts") }
val scriptingWrapperTemplate =
  frameworkRoot?.let { File(it, "sdks/typescript/tools/in-process-wrapper-template.mjs") }

fun frameworkScriptedToolSources(): List<File> =
  frameworkToolsDir.listFiles()
    ?.filter {
      it.isFile && it.name.endsWith(".ts") &&
        !it.name.endsWith(".d.ts") && !it.name.endsWith(".test.ts") &&
        // Skip a synthesized registration wrapper left behind by a crashed bundle run — it's a
        // build artifact, never a tool source (normal runs delete it in `finally`).
        !it.name.startsWith(".trailblaze-wrapper-")
    }
    ?.sortedBy { it.name }
    ?: emptyList()

// Renders the in-process registration wrapper for a typed scripted-tool source file
// (`export const <toolName> = trailblaze.tool<I>(...)`) from the ONE committed template,
// `sdks/typescript/tools/in-process-wrapper-template.mjs`. Typed-tool exports do NOT self-register,
// so the wrapper imports the file as a namespace, enumerates its function-valued exports, and
// registers each on `globalThis.__trailblazeTools[<exportName>]`. The output is byte-for-byte what
// the build-logic `BundleAuthorToolsTask` and the daemon-time `DaemonScriptedToolBundler` produce
// for the same source — they all render the same template.
//
// Token substitution is duplicated here (a few `.replace` calls) only because this build script
// can't import the build-logic plugin's renderer; the load-bearing JS lives in the template file,
// not in this build script.
fun synthesizeInProcessScriptedToolWrapper(userScriptFileName: String): String {
  val templateFile = requireNotNull(scriptingWrapperTemplate) {
    "Scripted-tool wrapper template not found — expected sdks/typescript/tools/in-process-wrapper-template.mjs."
  }
  require(templateFile.isFile) { "Wrapper template not found at ${templateFile.absolutePath}" }
  val header = buildString {
    appendLine("// Synthetic entry generated by :trailblaze-common's framework scripted-tool bundler.")
    appendLine("// Imports every typed-tool export from `$userScriptFileName`, builds a `client` shim")
    appendLine("// over the host's `__trailblazeCall` binding, and registers each export on")
    appendLine("// `globalThis.__trailblazeTools[<exportName>]` so QuickJsToolHost.callTool can dispatch it.")
  }
  val registration = buildString {
    appendLine("for (const __exportName of Object.keys(__userModule)) {")
    appendLine("  const __def = __userModule[__exportName];")
    appendLine("  if (typeof __def !== 'function') continue;")
    appendLine("  globalThis.__trailblazeTools[__exportName] = {")
    appendLine("    handler: async (args, ctx) => {")
    appendLine("      const result = await __def(args, ctx, __client);")
    appendLine("      return __normalizeResult(result);")
    appendLine("    },")
    appendLine("  };")
    appendLine("}")
  }
  return templateFile.readText()
    .replace("// __TRAILBLAZE_HEADER__\n", header)
    .replace("__TRAILBLAZE_IMPORT_SOURCE__", "./$userScriptFileName")
    // Multi-export: no single-export prelude — the empty replacement removes the token line.
    .replace("// __TRAILBLAZE_PRELUDE__\n", "")
    .replace("// __TRAILBLAZE_REGISTRATION__\n", registration)
}

fun esbuildScriptedTool(source: File, outFile: File) {
  requireNotNull(esbuildBinary) {
    "esbuild not found — run `bun install` in sdks/typescript/."
  }
  requireNotNull(scriptingSdkSrc) {
    "@trailblaze/scripting slim SDK entry not found at sdks/typescript/src/in-process.ts."
  }
  require(esbuildBinary.isFile) { "esbuild not found at ${esbuildBinary.absolutePath}" }
  require(scriptingSdkSrc.isFile) { "SDK source not found at ${scriptingSdkSrc.absolutePath}" }
  val logFile =
    layout.buildDirectory.file("tmp/bundle-${source.nameWithoutExtension}.log").get().asFile
  logFile.parentFile.mkdirs()
  // Synthesize a registration wrapper co-located with the source so esbuild resolves the relative
  // `./<source>` import; bundle the wrapper, not the raw source (typed-tool exports don't
  // self-register). Throw-only stdio stub mirrors the daemon-time bundler (defensive — the slim
  // SDK never imports MCP). Both temp files are cleaned up in `finally`.
  //
  // The wrapper filename must be DETERMINISTIC (not `createTempFile`): esbuild emits the entry
  // filename as a `// <name>` module comment in the output. The `.bundle.js` is a build
  // artifact regenerated each build, so a deterministic name keeps it byte-reproducible across
  // machines (the same property the committed `bun.lock` guarantees for the inputs). One source
  // bundles at a time (sequential `forEach`), so a per-source name can't collide.
  val wrapperFile = File(frameworkToolsDir, ".trailblaze-wrapper-${source.nameWithoutExtension}.ts")
  wrapperFile.writeText(synthesizeInProcessScriptedToolWrapper(source.name))
  val stdioStubFile =
    layout.buildDirectory.file("tmp/_ondevice-stdio-stub.ts").get().asFile.apply {
      parentFile.mkdirs()
      writeText(
        "/* GENERATED by :trailblaze-common bundler — esbuild --alias target for the " +
          "on-device QuickJS path. */\n" +
          "export class StdioServerTransport { " +
          "constructor() { throw new Error(\"StdioServerTransport unavailable on-device\"); } }\n",
      )
    }
  val argv = listOf(
    esbuildBinary.absolutePath,
    wrapperFile.absolutePath,
    "--bundle",
    "--platform=neutral",
    "--format=iife",
    "--target=es2020",
    "--main-fields=module,main",
    "--external:node:process",
    "--alias:@trailblaze/scripting=${scriptingSdkSrc.absolutePath}",
    "--alias:@modelcontextprotocol/sdk/server/stdio.js=${stdioStubFile.absolutePath}",
    "--outfile=${outFile.absolutePath}",
  )
  try {
    val proc = ProcessBuilder(argv)
      .directory(frameworkToolsDir)
      .redirectErrorStream(true)
      // Overwrite (not append) so the log reflects only the latest esbuild run for this tool.
      // Appending across Gradle runs grows the file unbounded and mixes stale output into triage.
      .redirectOutput(ProcessBuilder.Redirect.to(logFile))
      .start()
    if (!proc.waitFor(2, TimeUnit.MINUTES)) {
      proc.destroyForcibly()
      throw GradleException("esbuild timed out for ${source.name}. See ${logFile.absolutePath}.")
    }
    if (proc.exitValue() != 0) {
      throw GradleException(
        "esbuild failed for ${source.name} (exit ${proc.exitValue()}). See ${logFile.absolutePath}.",
      )
    }
  } finally {
    wrapperFile.delete()
    stdioStubFile.delete()
  }
}

tasks.register("bundleFrameworkScriptedTools") {
  group = "build"
  description = "Regenerates the QuickJS .bundle.js for every framework scripted tool (build artifact)."
  // Declare inputs + outputs so Gradle UP-TO-DATE-skips this when nothing changed — parity with
  // bundleTrailblazeSdk(Dts). Without these the task is wired into resource packaging (preBuild /
  // *ProcessResources below) but has no staleness info, so esbuild would re-run on every build.
  // Inputs: the tool `.ts` sources, the slim SDK source tree they bundle against, and the config
  // that controls the bundle (package.json / bun.lock / the wrapper template). Output: the
  // generated `.bundle.js` files.
  inputs.files(project.provider { frameworkScriptedToolSources() }).withPropertyName("frameworkToolSources")
  val sdkDir = frameworkRoot?.let { File(it, "sdks/typescript") }
  if (sdkDir != null) {
    inputs.files(project.provider { project.fileTree(File(sdkDir, "src")) }).withPropertyName("slimSdkSources")
    inputs.files(
      project.provider {
        listOf(
          File(sdkDir, "package.json"),
          File(sdkDir, "bun.lock"),
          File(sdkDir, "tools/in-process-wrapper-template.mjs"),
        ).filter { it.exists() }
      },
    ).withPropertyName("sdkBundleConfig")
  }
  outputs.files(
    project.provider {
      frameworkScriptedToolSources().map { File(frameworkToolsDir, "${it.nameWithoutExtension}.bundle.js") }
    },
  ).withPropertyName("frameworkToolBundles")
  doLast {
    val sources = frameworkScriptedToolSources()
    if (sources.isEmpty()) {
      logger.lifecycle("No framework scripted tools to bundle.")
      return@doLast
    }
    // Remove orphaned bundles first: a `.bundle.js` whose source `.ts` was renamed or deleted
    // would otherwise linger in this gitignored dir (there's no verify gate now) and still get
    // packaged. Only bundles with a current source survive (and are overwritten below).
    val expectedBundles = sources.map { "${it.nameWithoutExtension}.bundle.js" }.toSet()
    frameworkToolsDir.listFiles { f -> f.isFile && f.name.endsWith(".bundle.js") && f.name !in expectedBundles }
      ?.forEach { stale ->
        logger.lifecycle("Removing stale framework tool bundle ${stale.name} (no matching source)")
        stale.delete()
      }
    // The `.bundle.js` files are build artifacts, not committed source, so install the slim
    // SDK's esbuild from the committed bun.lock. Skip is keyed on the lockfile (not just binary
    // presence) so a `bun.lock` bump forces a fresh install rather than bundling against stale
    // deps. Mirrors `ensureSdkNodeModules` in TrailblazeSdkDtsBundlePlugin (which a build script
    // can't call directly).
    val sdkDir = frameworkRoot?.let { File(it, "sdks/typescript") }
    if (sdkDir != null) {
      val esbuildOk = File(sdkDir, "node_modules/.bin/esbuild").isFile
      val lockText = File(sdkDir, "bun.lock").let { if (it.exists()) it.readText() else "" }
      val installStamp = File(sdkDir, "node_modules/.trailblaze-install-lock")
      val upToDate = esbuildOk && installStamp.exists() && installStamp.readText() == lockText
      if (!upToDate) {
        logger.lifecycle("Installing @trailblaze/scripting devDependencies (bun install --frozen-lockfile)")
        val installProc = try {
          ProcessBuilder("bun", "install", "--frozen-lockfile").directory(sdkDir).inheritIO().start()
        } catch (e: java.io.IOException) {
          throw GradleException(
            "Could not launch `bun` to install @trailblaze/scripting devDependencies in $sdkDir. " +
              "Trailblaze is bun-only and `bun` is a hard build prerequisite — put it on PATH via " +
              "`source bin/activate-hermit` or install from https://bun.sh/. Cause: ${e.message}",
            e,
          )
        }
        if (!installProc.waitFor(15, TimeUnit.MINUTES)) {
          installProc.destroyForcibly()
          throw GradleException("`bun install --frozen-lockfile` timed out in $sdkDir.")
        }
        require(installProc.exitValue() == 0) {
          "`bun install --frozen-lockfile` failed (exit ${installProc.exitValue()}) in $sdkDir — " +
            "required to bundle framework scripted tools. Ensure `bun` is on PATH " +
            "(`source bin/activate-hermit`)."
        }
        runCatching { installStamp.writeText(lockText) }
      }
    }
    sources.forEach { src ->
      val out = File(frameworkToolsDir, "${src.nameWithoutExtension}.bundle.js")
      logger.lifecycle("Bundling ${src.name} → ${out.name}")
      esbuildScriptedTool(src, out)
    }
  }
}

// The framework scripted-tool `.bundle.js` files (under src/commonMain/resources/.../tools,
// gitignored) are build artifacts, not committed source. Wire `bundleFrameworkScriptedTools`
// ahead of every task that packages this module's commonMain resources so the bundles are
// present in the JVM jar and the Android APK/AAR (both Java resources AND assets — the
// on-device QuickJS launcher reads them via the AssetManager). Ordering-only dependsOn, and
// `matching {}.configureEach {}` tolerates a variant that lacks one of these task names.
val bundleFrameworkToolsTask = tasks.named("bundleFrameworkScriptedTools")
// Every task that consumes this module's commonMain resources must depend on the generator:
// the generated `.bundle.js` lives under `src/commonMain/resources` AND is a declared task
// output, so Gradle 8 hard-fails any consumer that reads it without a dependency edge
// ("uses this output of task … without declaring … a dependency"). That consumer set spans
// the KMP metadata/jvm/jvmTest `*ProcessResources` tasks, the Android resource/asset/JavaResource
// merges, the publish `*SourcesJar`s, and `preBuild` (root of every Android variant graph).
// Matched by name so new KMP/AGP variants are covered without re-enumerating; dependsOn is
// ordering-only so the broad reach is safe.
tasks.matching { t ->
  t.name.endsWith("ProcessResources") ||
    t.name == "preBuild" ||
    t.name.endsWith("SourcesJar") ||
    (t.name.startsWith("merge") &&
      (t.name.endsWith("Resources") || t.name.endsWith("Assets") || t.name.endsWith("JavaResource")))
}.configureEach { dependsOn(bundleFrameworkToolsTask) }

