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
