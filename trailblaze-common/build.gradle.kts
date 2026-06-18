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
// Pre-compiles every @trailblaze/tools-based `.ts` tool in the framework `trailblaze` trailmap into
// a self-contained `.bundle.js` that QuickJsToolHost evaluates directly (host daemon + on-device).
// The committed `.bundle.js` is what the toolset-delivery path loads from the classpath / APK
// assets — see TrailblazeHostYamlRunner.registerToolsetScriptedToolBundles and
// AndroidTrailblazeRule.launchToolsetScriptedToolBundles. Generalized over the directory so new
// framework scripted tools are picked up without editing this build script.
val frameworkToolsDir =
  file("src/commonMain/resources/trails/config/trailmaps/trailblaze/tools")
val frameworkRoot: File? = run {
  val marker = "sdks/typescript-tools/package.json"
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
val toolsSdkSrc = frameworkRoot?.let { File(it, "sdks/typescript-tools/src/index.ts") }

fun frameworkScriptedToolSources(): List<File> =
  frameworkToolsDir.listFiles()
    ?.filter {
      it.isFile && it.name.endsWith(".ts") &&
        !it.name.endsWith(".d.ts") && !it.name.endsWith(".test.ts")
    }
    ?.sortedBy { it.name }
    ?: emptyList()

fun esbuildScriptedTool(source: File, outFile: File) {
  requireNotNull(esbuildBinary) {
    "esbuild not found — run `bun install` in sdks/typescript/."
  }
  requireNotNull(toolsSdkSrc) {
    "@trailblaze/tools SDK source not found at sdks/typescript-tools/src/index.ts."
  }
  require(esbuildBinary.isFile) { "esbuild not found at ${esbuildBinary.absolutePath}" }
  require(toolsSdkSrc.isFile) { "SDK source not found at ${toolsSdkSrc.absolutePath}" }
  val logFile =
    layout.buildDirectory.file("tmp/bundle-${source.nameWithoutExtension}.log").get().asFile
  logFile.parentFile.mkdirs()
  val argv = listOf(
    esbuildBinary.absolutePath,
    source.absolutePath,
    "--bundle",
    "--platform=neutral",
    "--format=iife",
    "--target=es2020",
    "--main-fields=module,main",
    "--alias:@trailblaze/tools=${toolsSdkSrc.absolutePath}",
    "--external:node:process",
    "--outfile=${outFile.absolutePath}",
  )
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
}

tasks.register("bundleFrameworkScriptedTools") {
  group = "build"
  description = "Regenerates the committed QuickJS .bundle.js for every framework scripted tool."
  doLast {
    val sources = frameworkScriptedToolSources()
    if (sources.isEmpty()) {
      logger.lifecycle("No framework scripted tools to bundle.")
      return@doLast
    }
    sources.forEach { src ->
      val out = File(frameworkToolsDir, "${src.nameWithoutExtension}.bundle.js")
      logger.lifecycle("Bundling ${src.name} → ${out.name}")
      esbuildScriptedTool(src, out)
    }
  }
}

// Intentionally NOT wired into `check`: esbuild requires `(cd sdks/typescript && bun install)`
// first, so the gate runs in the same CI static-checks step that runs `verifyTrailblazeSdkBundle`
// rather than burdening every local `./gradlew check`. Mirrors the `:trailblaze-models` SDK-bundle
// convention. Local devs who edit a framework scripted tool's `.ts` run
// `./gradlew :trailblaze-common:bundleFrameworkScriptedTools` and commit the regenerated bundle.
tasks.register("verifyFrameworkScriptedToolsBundle") {
  group = "verification"
  description = "Verifies every committed framework scripted-tool .bundle.js matches a fresh build."
  doLast {
    val sources = frameworkScriptedToolSources()
    val drift = mutableListOf<String>()
    sources.forEach { src ->
      val committed = File(frameworkToolsDir, "${src.nameWithoutExtension}.bundle.js")
      val temp =
        layout.buildDirectory.file("tmp/verify-${src.nameWithoutExtension}.bundle.js").get().asFile
      temp.parentFile.mkdirs()
      esbuildScriptedTool(src, temp)
      when {
        !committed.isFile -> drift += "${committed.name} (missing — never committed)"
        !committed.readBytes().contentEquals(temp.readBytes()) -> drift += committed.name
      }
    }
    if (drift.isNotEmpty()) {
      throw GradleException(
        "Framework scripted-tool bundle(s) out of date: ${drift.joinToString()}.\n" +
          "Regenerate and commit:\n" +
          "  ./gradlew :trailblaze-common:bundleFrameworkScriptedTools\n" +
          "  git add ${frameworkToolsDir.relativeTo(rootDir)}/*.bundle.js",
      )
    }
    logger.lifecycle("✓ All ${sources.size} framework scripted-tool bundle(s) are fresh.")
  }
}
