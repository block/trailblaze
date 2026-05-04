import java.util.concurrent.TimeUnit
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "xyz.block.trailblaze.scripting.bundle"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  // Wire the KMP `jvmAndAndroid` resources into the Android AAR's Java-style resources
  // so `BundleRuntimePrelude` can load `trailblaze-bundle-prelude.js` via
  // `ClassLoader.getResourceAsStream` on both the JVM jar and the Android AAR. Without
  // this, the KMP plugin only packages the prelude into the JVM jar and the on-device
  // launcher would fail at first `McpBundleSession.connect` with "missing prelude
  // resource." Same pattern `:trailblaze-common`/`:trailblaze-models` use for
  // `commonMain/resources` → Android assets + resources.
  //
  // Note: the SDK bundle (`trailblaze-sdk-bundle.js`) lives under
  // `src/jvmAndAndroid/resources/` as a committed generated artifact — same source-set
  // entry as the prelude above. See the `bundleTrailblazeSdk` task comments below for the
  // developer workflow (regenerate-and-commit when the TS SDK source changes). It isn't
  // generated at build time so CI doesn't need esbuild on PATH — the committed bundle is
  // authoritative.
  sourceSets.getByName("main") {
    resources.srcDirs("src/jvmAndAndroid/resources")
  }
}

kotlin {
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

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies {
      api(project(":trailblaze-common"))
      api(project(":trailblaze-models"))
      api(project(":trailblaze-scripting-mcp-common"))
      api(libs.mcp.sdk)
      implementation(libs.quickjs.kt)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.coroutines)
    }

    // The bundle runtime is almost entirely platform-neutral — QuickJS bindings, MCP types,
    // coroutines — so code that isn't load-path-specific lives in this shared source set.
    // `expect`/`actual` bridges for bundle loading (File on JVM, AssetManager on Android) land
    // in the target-specific source sets below.
    val jvmAndAndroid by creating {
      dependsOn(commonMain.get())
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
    }

    jvmTest {
      dependsOn(jvmAndAndroidTest)
    }

    androidUnitTest {
      dependsOn(jvmAndAndroidTest)
    }

    androidMain {
      dependsOn(jvmAndAndroid)
      dependencies {
        // `AndroidAssetBundleJsSource` pulls the instrumentation context's AssetManager via
        // `InstrumentationRegistry`, same pattern `:trailblaze-models/src/androidMain`'s
        // asset-backed config loader uses. The dep is `api` so consumers that call into the
        // bundle module's Android entry points don't need a separate declaration.
        api(libs.androidx.test.monitor)
      }
    }
  }
}

// Run the jvmTest `test` task as part of the default `check` (matches how
// `trailblaze-scripting-subprocess` wires its single Java-source test suite). Without this
// hook, `./gradlew :trailblaze-scripting-bundle:check` would run Android lint only and the
// in-process-transport round-trip wouldn't execute — defeats the whole point of the
// jvmTest existing.
tasks.named("check") {
  dependsOn("jvmTest")
}

// ---------------------------------------------------------------------------------------------
// bundleTrailblazeSdk
//
// Runs esbuild over the TypeScript SDK sources and writes a single-file IIFE bundle that
// installs `globalThis.trailblaze` / `globalThis.fromMeta` when evaluated, committed in-tree
// as `src/jvmAndAndroid/resources/trailblaze/scripting/bundle/trailblaze-sdk-bundle.js`.
//
// ### Developer workflow
//
// This is a manual-invocation task, NOT part of the default build graph. Developers run it
// after editing `sdks/typescript/src/*.ts`:
//
//   ./gradlew :trailblaze-scripting-bundle:bundleTrailblazeSdk
//   git add trailblaze-scripting-bundle/src/jvmAndAndroid/resources/trailblaze/scripting/bundle/trailblaze-sdk-bundle.js
//   git commit
//
// PRs that change the SDK source MUST include the regenerated bundle. Reviewers can eyeball
// the bundle diff alongside the source diff to confirm the two are consistent (the bundle
// is ~1.2MB minified-free, so diffs are necessarily coarse, but a regeneration without a
// source edit is a clear red flag).
//
// ### Why not build-time-generated
//
// An earlier revision wired this into the default build graph with a cross-project
// dependency on `installTrailblazeScriptingSdk`, so CI regenerated the bundle from source
// on every build. That required `esbuild` on the CI path. Some downstream npm registry
// mirrors don't proxy `esbuild` reliably (or at all — failure mode can differ from
// `@modelcontextprotocol/sdk` / `zod`, which tend to install fine). Depending on a
// CI-unavailable package for every build is strictly worse than committing the artifact;
// the artifact is a pure function of source, fits in the repo, and PR review catches
// staleness.
//
// ### Why esbuild flags are what they are
//
// MCP SDK pulls in `node:process` through `stdio.js` and `ajv`'s `main`-only resolution;
// `--platform=neutral --main-fields=module,main --external:node:process` handles both
// cleanly. The author-side `await import(...stdio.js)` in `run.ts` means the
// `node:process` path only runs on the host, so QuickJS never evaluates it on-device
// despite the bundle containing it.
// ---------------------------------------------------------------------------------------------

val trailblazeSdkDir = layout.projectDirectory.dir("../sdks/typescript")
val sdkBundleOutputFile = layout.projectDirectory
  .file("src/jvmAndAndroid/resources/trailblaze/scripting/bundle/trailblaze-sdk-bundle.js")

val bundleTrailblazeSdk by tasks.registering {
  group = "build"
  description = "Regenerates the committed @trailblaze/scripting SDK bundle. Manual-invocation: " +
    "run after editing sdks/typescript/src/*.ts and commit the regenerated bundle alongside " +
    "the source change."

  // Declared inputs/outputs exist so Gradle up-to-date logic works if this task IS wired
  // into a graph (e.g., a future `--check` mode that re-bundles into a temp location and
  // diffs against the committed file). The committed bundle path is the output so running
  // the task twice without source changes is a no-op.
  inputs.dir(trailblazeSdkDir.dir("src")).withPropertyName("sdkSources")
  inputs.file(trailblazeSdkDir.file("package.json")).withPropertyName("sdkPackageJson")
  outputs.file(sdkBundleOutputFile).withPropertyName("sdkBundle")

  doLast {
    val esbuildBin = trailblazeSdkDir.file("node_modules/.bin/esbuild").asFile
    if (!esbuildBin.exists()) {
      throw GradleException(
        "esbuild not found at ${esbuildBin.absolutePath}. Run `(cd ${trailblazeSdkDir.asFile} && " +
          "bun install)` (or `npm install`) to pull the TS SDK's devDependencies before " +
          "regenerating the bundle.",
      )
    }
    val entry = trailblazeSdkDir.file("src/index.ts").asFile
    val outputFile = sdkBundleOutputFile.asFile
    outputFile.parentFile.mkdirs()

    val argv = listOf(
      esbuildBin.absolutePath,
      entry.absolutePath,
      "--bundle",
      "--platform=neutral",
      "--format=iife",
      "--global-name=trailblazeSdk",
      "--target=es2020",
      "--main-fields=module,main",
      "--external:node:process",
      "--footer:js=globalThis.trailblaze = trailblazeSdk.trailblaze; globalThis.fromMeta = trailblazeSdk.fromMeta; globalThis.z = trailblazeSdk.z; void 0;",
      "--outfile=${outputFile.absolutePath}",
    )

    logger.lifecycle("Regenerating SDK bundle → ${outputFile.relativeTo(projectDir)}")
    val logFile = layout.buildDirectory.file("tmp/bundle-trailblaze-sdk.log").get().asFile
    logFile.parentFile.mkdirs()
    logFile.writeText("")

    val proc = ProcessBuilder(argv)
      .directory(trailblazeSdkDir.asFile)
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
      .start()
    if (!proc.waitFor(2, TimeUnit.MINUTES)) {
      proc.destroyForcibly()
      throw GradleException(
        "esbuild did not finish within 2 minutes — stuck or deadlocked. See ${logFile.absolutePath}.",
      )
    }
    if (proc.exitValue() != 0) {
      throw GradleException(
        "esbuild failed (exit ${proc.exitValue()}). See ${logFile.absolutePath}.",
      )
    }
    if (!outputFile.exists() || outputFile.length() == 0L) {
      throw GradleException(
        "esbuild reported success but ${outputFile.absolutePath} is missing or empty. " +
          "See ${logFile.absolutePath}.",
      )
    }
    logger.lifecycle(
      "Regenerated ${outputFile.relativeTo(projectDir)} (${outputFile.length() / 1024} KiB). " +
        "Remember to `git add` and commit it alongside the SDK source change.",
    )
  }
}
