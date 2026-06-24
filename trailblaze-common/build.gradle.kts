import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
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
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

abstract class BundleFrameworkScriptedToolsTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:IgnoreEmptyDirectories
  val inputSources: ConfigurableFileCollection = objects.fileCollection()

  @get:OutputFiles
  val outputBundles: ConfigurableFileCollection = objects.fileCollection()

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:IgnoreEmptyDirectories
  val sdkSources: ConfigurableFileCollection = objects.fileCollection()

  @get:Internal
  abstract val sdkDir: DirectoryProperty

  @get:Optional
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val scriptingSdkSrc: RegularFileProperty

  @get:Optional
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val scriptingWrapperTemplate: RegularFileProperty

  @get:Internal
  abstract val outputDir: DirectoryProperty

  @get:Internal
  abstract val temporaryWorkDir: DirectoryProperty

  @TaskAction
  fun bundle() {
    val toolsDir = outputDir.get().asFile
    val sources = inputSources.files.sortedBy { it.name }
    if (sources.isEmpty()) {
      logger.lifecycle("No framework scripted tools to bundle.")
      return
    }

    val expectedBundles = sources.map { "${it.nameWithoutExtension}.bundle.js" }.toSet()
    toolsDir.listFiles { f -> f.isFile && f.name.endsWith(".bundle.js") && f.name !in expectedBundles }
      ?.forEach { stale ->
        logger.lifecycle("Removing stale framework tool bundle ${stale.name} (no matching source)")
        stale.delete()
      }

    if (sdkDir.isPresent) {
      ensureSdkNodeModules(
        sdk = sdkDir.get().asFile,
        logFile = temporaryWorkDir.get().asFile.resolve("install-sdk-node-modules.log"),
      )
    }

    sources.forEach { src ->
      val out = File(toolsDir, "${src.nameWithoutExtension}.bundle.js")
      logger.lifecycle("Bundling ${src.name} -> ${out.name}")
      esbuildScriptedTool(
        source = src,
        outFile = out,
        toolsDir = toolsDir,
        sdkSource = scriptingSdkSrc.orNull?.asFile,
        wrapperTemplate = scriptingWrapperTemplate.orNull?.asFile,
        tempDir = temporaryWorkDir.get().asFile,
      )
    }
  }

  private fun isSdkNodeModulesUpToDate(sdk: File): Boolean {
    val esbuildOk = File(sdk, "node_modules/.bin/esbuild").isFile
    val lockText = File(sdk, "bun.lock").let { if (it.exists()) it.readText() else "" }
    val installStamp = File(sdk, "node_modules/.trailblaze-install-lock")
    return esbuildOk && installStamp.exists() && installStamp.readText() == lockText
  }

  private fun ensureSdkNodeModules(sdk: File, logFile: File) {
    if (isSdkNodeModulesUpToDate(sdk)) return
    val lockFile = File(sdk, "node_modules/.trailblaze-install.lock")
    lockFile.parentFile.mkdirs()
    RandomAccessFile(lockFile, "rw").channel.use { channel ->
      while (true) {
        val lock = try {
          channel.tryLock()
        } catch (_: OverlappingFileLockException) {
          null
        }
        if (lock != null) {
          lock.use {
            if (isSdkNodeModulesUpToDate(sdk)) return
            installSdkNodeModules(sdk, logFile)
            return
          }
        }
        Thread.sleep(250)
      }
    }
  }

  private fun installSdkNodeModules(sdk: File, logFile: File) {
    val lockText = File(sdk, "bun.lock").let { if (it.exists()) it.readText() else "" }
    val installStamp = File(sdk, "node_modules/.trailblaze-install-lock")
    logFile.parentFile.mkdirs()
    logFile.writeText("")
    logger.lifecycle("Installing @trailblaze/scripting devDependencies (bun install --frozen-lockfile)")
    val installProc = try {
      ProcessBuilder("bun", "install", "--frozen-lockfile")
        .directory(sdk)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        .start()
    } catch (e: java.io.IOException) {
      throw GradleException(
        "Could not launch `bun` to install @trailblaze/scripting devDependencies in $sdk. " +
          "Trailblaze is bun-only and `bun` is a hard build prerequisite — put it on PATH via " +
          "`source bin/activate-hermit` or install from https://bun.sh/. Cause: ${e.message}",
        e,
      )
    }
    if (!installProc.waitFor(15, TimeUnit.MINUTES)) {
      installProc.destroyForcibly()
      throw GradleException("`bun install --frozen-lockfile` timed out in $sdk. See ${logFile.absolutePath}.")
    }
    require(installProc.exitValue() == 0) {
      "`bun install --frozen-lockfile` failed (exit ${installProc.exitValue()}) in $sdk — " +
        "required to bundle framework scripted tools. Ensure `bun` is on PATH " +
        "(`source bin/activate-hermit`). See ${logFile.absolutePath}."
    }
    runCatching { installStamp.writeText(lockText) }
  }

  private fun synthesizeInProcessScriptedToolWrapper(
    userScriptFileName: String,
    templateFile: File,
  ): String {
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
      .replace("// __TRAILBLAZE_PRELUDE__\n", "")
      .replace("// __TRAILBLAZE_REGISTRATION__\n", registration)
  }

  private fun esbuildScriptedTool(
    source: File,
    outFile: File,
    toolsDir: File,
    sdkSource: File?,
    wrapperTemplate: File?,
    tempDir: File,
  ) {
    requireNotNull(sdkSource) {
      "@trailblaze/scripting slim SDK entry not found at sdks/typescript/src/in-process.ts."
    }
    requireNotNull(wrapperTemplate) {
      "Scripted-tool wrapper template not found — expected sdks/typescript/tools/in-process-wrapper-template.mjs."
    }
    val esbuildBinary = sdkSource.parentFile.parentFile.resolve("node_modules/.bin/esbuild")
    require(esbuildBinary.isFile) { "esbuild not found at ${esbuildBinary.absolutePath}" }
    require(sdkSource.isFile) { "SDK source not found at ${sdkSource.absolutePath}" }
    val logFile = tempDir.resolve("bundle-${source.nameWithoutExtension}.log")
    logFile.parentFile.mkdirs()

    val wrapperFile = File(toolsDir, ".trailblaze-wrapper-${source.nameWithoutExtension}.ts")
    wrapperFile.writeText(synthesizeInProcessScriptedToolWrapper(source.name, wrapperTemplate))
    val stdioStubFile = tempDir.resolve("_ondevice-stdio-stub.ts").apply {
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
      "--alias:@trailblaze/scripting=${sdkSource.absolutePath}",
      "--alias:@modelcontextprotocol/sdk/server/stdio.js=${stdioStubFile.absolutePath}",
      "--outfile=${outFile.absolutePath}",
    )
    try {
      val proc = ProcessBuilder(argv)
        .directory(toolsDir)
        .redirectErrorStream(true)
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
}

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

tasks.register<BundleFrameworkScriptedToolsTask>("bundleFrameworkScriptedTools") {
  group = "build"
  description = "Regenerates the QuickJS .bundle.js for every framework scripted tool (build artifact)."
  val frameworkToolsDirectory =
    layout.projectDirectory.dir("src/commonMain/resources/trails/config/trailmaps/trailblaze/tools")
  val frameworkToolSources =
    frameworkToolsDirectory.asFileTree.matching {
      include("*.ts")
      exclude("*.d.ts", "*.test.ts", ".trailblaze-wrapper-*")
    }
  inputSources.from(frameworkToolSources)
  outputBundles.from(
    provider {
      frameworkToolSources.files.map { source ->
        frameworkToolsDirectory.file("${source.nameWithoutExtension}.bundle.js").asFile
      }
    },
  )
  outputDir.set(frameworkToolsDirectory)
  temporaryWorkDir.set(layout.buildDirectory.dir("tmp/framework-scripted-tools"))
  frameworkRoot?.let { root ->
    val sdkDirectory = File(root, "sdks/typescript")
    sdkDir.set(layout.dir(provider { sdkDirectory }))
    sdkSources.from(
      fileTree(File(sdkDirectory, "src")) {
        include("**/*.ts")
        exclude("**/*.test.ts", "**/*.d.ts")
      },
    )
    scriptingSdkSrc.set(layout.file(provider { File(sdkDirectory, "src/in-process.ts") }))
    scriptingWrapperTemplate.set(
      layout.file(provider { File(sdkDirectory, "tools/in-process-wrapper-template.mjs") }),
    )
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
