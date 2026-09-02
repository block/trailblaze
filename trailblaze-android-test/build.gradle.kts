plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  // androidTest hosts a real Compose fixture Activity, so the test APK compiles Compose.
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
}

android {
  namespace = "xyz.block.trailblaze.android.test"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  lint { abortOnError = false }

  @Suppress("UnstableApiUsage")
  testOptions {
    unitTests.all { test ->
      test.useJUnitPlatform()
      // AndroidTestTrailAssetContractTest reads the instrumentation trail assets off disk rather
      // than off the classpath (they ship in the androidTest APK, not this source set). Undeclared,
      // Gradle would call the task up-to-date after an asset edit and report a stale pass.
      test.inputs.dir(layout.projectDirectory.dir("src/androidTest/assets"))
        .withPropertyName("androidTestTrailAssets")
        .withPathSensitivity(PathSensitivity.RELATIVE)
      // Same test also covers the sample app's ANDROID_TEST trails — the ones the in-process farm
      // lane stages — which live outside this module.
      test.inputs.dir(layout.projectDirectory.dir("../examples/android-sample-app/trails/android-test"))
        .withPropertyName("sampleAppAndroidTestTrails")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    }
  }

  // On device, YAML config discovery enumerates Android assets — Android cannot list an APK's
  // Java resources by directory the way `ClasspathResourceDiscovery` does on the JVM. Without
  // this, the trailmap/toolset/tool descriptors below resolve in JVM unit tests and are invisible
  // to the instrumentation test that actually replays a recorded `androidTest_*` step.
  // `trailblaze-common` and `trailblaze-models` do the same for their `commonMain/resources`.
  sourceSets.getByName("main") {
    assets.srcDirs("src/main/resources")
  }
}

configurations.configureEach {
  if (name.endsWith("CompileClasspath")) {
    resolutionStrategy.force(
      "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
      "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2",
      "org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3",
      "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3",
      "org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3",
      "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3",
    )
  }
}

// The coroutines version this driver is compiled and unit-tested against. Deliberately NOT
// `libs.coroutines` (1.11.0): see the compileOnly declaration below for why this module sits on
// the consumer floor instead. One constant so the compile classpath and the JVM unit tests cannot
// drift apart when that floor moves.
val consumerFloorCoroutines = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"

dependencies {
  api(project(":trailblaze-common"))

  // The scripted-tool runtime: `OnDeviceScriptedToolBundleLauncher` (quickjs-tools androidMain)
  // registers pre-compiled QuickJS bundles at session start, and the OkHttp-backed `fetch`
  // extension is what every production launcher installs into those engines. Without these a
  // recorded step naming a scripted tool fails as an unresolvable `OtherTrailblazeTool`.
  implementation(project(":trailblaze-quickjs-tools"))
  implementation(project(":trailblaze-scripting-fetch"))

  // Android-register and other large instrumentation harnesses currently standardize on
  // coroutines 1.10.x. Trailblaze's wider graph can resolve 1.11 through Ktor, whose renamed
  // runBlockingK symbol is not binary-compatible with 1.10 at runtime. Compile this thin driver
  // against the supported consumer floor; its public API exposes no coroutine types.
  compileOnly(consumerFloorCoroutines)
  compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
  compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  // These are intentionally compileOnly. The Android test driver runs inside an app's existing
  // instrumentation harness, which must own the Espresso and Compose versions used by that app.
  compileOnly("androidx.test.espresso:espresso-core:3.7.0")
  compileOnly("androidx.compose.ui:ui-test-junit4:1.9.0")

  implementation(libs.androidx.test.core)
  implementation(libs.androidx.test.monitor)
  implementation(libs.kotlinx.datetime)

  // Adds no runtime artifact: kaml ships transitively through `trailblaze-common` → models'
  // `implementation`, which is runtime-transitive but not compile-transitive. This declares the
  // compile visibility `AssetBackedHostAppTarget` needs for TrailblazeConfigYaml's `Yaml` type.
  compileOnly(libs.kaml)

  // Adds no runtime artifact: `trailblaze-common` is `api` above and already depends on
  // `trailblaze-tracing` as `implementation`, which is runtime-transitive but not
  // compile-transitive. This declares the compile visibility the phase spans need.
  implementation(project(":trailblaze-tracing"))

  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit5.jupiter.engine)
  // Coroutines are compileOnly above (the consumer harness owns the version); the JVM unit tests
  // for TestThreadWorkQueue need a real runtime, on the same consumer floor.
  testImplementation(consumerFloorCoroutines)
  // The Compose compiler plugin (enabled for the androidTest fixture below) runs on every
  // compilation in the module and requires the runtime on each compile classpath. The unit
  // tests contain no composables, so compileOnly satisfies the plugin without shipping anything.
  testCompileOnly("androidx.compose.runtime:runtime:1.9.0")
  compileOnly("androidx.compose.runtime:runtime:1.9.0")

  // The androidTest APK is a real consumer of this driver: it owns its own Espresso/Compose
  // versions (as an app harness would) and a mixed View + Compose fixture Activity.
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.kotlin.test)
  // Declared again for androidTest: an Android library's test variant does not inherit the main
  // variant's `implementation` dependencies, so the tracing assertions cannot see the tracer
  // without this even though production code in this module can.
  androidTestImplementation(project(":trailblaze-tracing"))
  androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
  androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.9.0")
  androidTestImplementation("androidx.compose.ui:ui-test-manifest:1.9.0")
  androidTestImplementation("androidx.compose.material3:material3:1.3.2")
  androidTestImplementation("androidx.activity:activity-compose:1.9.3")
}

dependencyGuard {
  configuration("debugRuntimeClasspath") { modules = true }
}
