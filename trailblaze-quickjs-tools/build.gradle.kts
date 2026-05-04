import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  // Bundles author tool sources under `examples/android-sample-app/trails/config/quickjs-tools/`
  // into runnable JS via esbuild. The produced bundle is consumed by SampleAppToolsDemoTest as
  // the "real esbuild output" proof point — no inline esbuild invocation in the test.
  id("trailblaze.author-tool-bundle")
}

// Reuse `:trailblaze-scripting-subprocess`'s SDK install task so esbuild is on disk before
// the bundle task runs. Avoids a duplicate `installSampleAppMcpSdkTools`-shaped task pointed
// at the same `node_modules/`. Depending on the task path directly is lazy — Gradle resolves
// it after the sibling project evaluates without us needing `evaluationDependsOn` (which
// would have forced synchronous configuration of the sibling at every build invocation).
val installTrailblazeScriptingSdkTaskPath =
  ":trailblaze-scripting-subprocess:installTrailblazeScriptingSdk"

trailblazeAuthorToolBundles {
  register("sampleAppTyped") {
    // Path is relative to this module's projectDir — works regardless of repository layout.
    sourceDir.set(
      layout.projectDirectory.dir("../examples/android-sample-app/trails/config/quickjs-tools"),
    )
    entryPoint.set("typed.ts")
    autoInstall.set(false) // No package.json in that dir; the SDK's esbuild is what we need.
  }
}

tasks.named("bundleSampleAppTypedAuthorTool") { dependsOn(installTrailblazeScriptingSdkTaskPath) }

// Wire the produced bundle into jvmTest as a system property and a task dependency. The
// `SampleAppToolsDemoTest.on-device TS bundles via esbuild and runs in QuickJS` test reads
// the bundle at this path instead of invoking esbuild inline — the plugin is the single
// source of truth for how author bundles get produced.
val sampleAppTypedBundle = tasks.named("bundleSampleAppTypedAuthorTool").map { task ->
  (task as BundleAuthorToolsTask).outputFile.get().asFile.absolutePath
}
tasks.withType<Test>().configureEach {
  if (name == "jvmTest") {
    dependsOn("bundleSampleAppTypedAuthorTool")
    systemProperty("trailblaze.test.sampleAppTypedBundle", sampleAppTypedBundle.get())
  }
}

android {
  namespace = "xyz.block.trailblaze.quickjs.tools"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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
      // Tiny dependency surface — that's the whole point of dropping MCP. No `libs.mcp.sdk`,
      // no `:trailblaze-scripting-mcp-common`, no transport shims. Just QuickJS, JSON, and
      // coroutines for the suspend dispatch surface.
      implementation(libs.quickjs.kt)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.coroutines)
    }

    val jvmAndAndroid by creating {
      dependsOn(commonMain.get())
      // Tool-repo / dynamic-registration deps live here rather than in commonMain so the
      // QuickJS engine surface in commonMain stays pure (no Trailblaze-specific types). The
      // launcher + registration pieces only need to compile against JVM/Android targets,
      // which is where consumers (AndroidTrailblazeRule, host CLIs) actually integrate.
      dependencies {
        api(project(":trailblaze-common"))
        api(project(":trailblaze-models"))
      }
    }

    val jvmAndAndroidTest by creating {
      dependsOn(commonTest.get())
      dependencies {
        implementation(libs.kotlin.test.junit4)
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
        // `AndroidAssetBundleSource` pulls the instrumentation context's AssetManager via
        // `InstrumentationRegistry`. Same dep `:trailblaze-scripting-bundle/androidMain` uses
        // for its asset-loader. `api` so consumers wiring `bundleSourceResolver` don't need
        // a separate declaration.
        api(libs.androidx.test.monitor)
      }
    }
  }
}

// jvmTest covers the QuickJS round-trip on the JVM — `:check` should run it (the Android
// `*UnitTest` task graph runs lint and unit tests, but the load-bearing QuickJS evaluation
// happens on the JVM target).
tasks.named("check") {
  dependsOn("jvmTest")
}
