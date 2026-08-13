import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  // Published alongside its KMP sibling `:trailblaze-quickjs-tools`: the published
  // `:trailblaze-host` / `:trailblaze-server` depend on this module, so their generated POMs
  // reference `trailblaze-scripting-fetch` — it must be a real Maven artifact, not a dangling
  // coordinate. Coordinates/version come from the repo's publish convention (no per-module config,
  // same as `:trailblaze-quickjs-tools`).
  alias(libs.plugins.vanniktech.maven.publish)
}

// Isolates the OkHttp dependency for the QuickJS `fetch` binding. The lean engine module
// (`:trailblaze-quickjs-tools`) stays OkHttp-free; a runtime installs `fetch` by passing
// `OkHttpFetchExtension` to `QuickJsToolHost.connect(engineExtension = …)`.
//
// jvmAndAndroid (mirroring `:trailblaze-quickjs-tools`) so BOTH runtimes get it: the host daemon
// and the on-device launchers both install `fetch` (OkHttp runs on ART). The isolation buys a lean
// engine module — not a fetch-free APK — so a runtime that wants no networking simply doesn't
// depend on this module.
android {
  namespace = "xyz.block.trailblaze.scripting.fetch"
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
    val jvmAndAndroid by creating {
      dependsOn(commonMain.get())
      dependencies {
        // The engine module that owns `QuickJsEngineExtension` + `QuickJsToolHost.connect`.
        // `implementation` (not `api`) — consumers wire the extension into `connect`, they
        // don't re-expose it.
        implementation(project(":trailblaze-quickjs-tools"))
        // The QuickJS engine type (`com.dokar.quickjs.QuickJs`) that the extension binds into,
        // and the `asyncFunction` / `evaluate` idioms. `:trailblaze-quickjs-tools` keeps this as
        // `implementation`, so declare it here too.
        implementation(libs.quickjs.kt)
        // The whole point of the module: a real HTTP client. OkHttp's default ProxySelector honors
        // the JVM proxy system properties — see OkHttpFetchExtension's proxy note.
        implementation(libs.okhttp)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.coroutines)
      }
    }

    jvmMain {
      dependsOn(jvmAndAndroid)
    }

    androidMain {
      dependsOn(jvmAndAndroid)
    }

    // The Android half of the `EMULATOR_HOST_ALIASES` expect/actual — the only behavior that
    // differs by target, and the one a JVM-only suite structurally cannot see.
    androidUnitTest {
      dependencies {
        implementation(libs.kotlin.test.junit4)
      }
    }

    jvmTest {
      dependencies {
        implementation(libs.kotlin.test.junit4)
        implementation(libs.coroutines)
        // The end-to-end test drives a tool's `fetch(…)` against a real loopback server — the
        // JDK's `com.sun.net.httpserver.HttpServer` (a genuine server, no extra dependency and no
        // MockWebServer-API churn across OkHttp's two mockwebserver packages).
        //
        // `okhttp-tls` supplies `HeldCertificate` so the TLS test can stand that server up with a
        // real self-signed certificate — the shape of every Trailblaze HTTPS endpoint a device
        // reaches — instead of asserting the scoping only as a predicate.
        implementation(libs.okhttp.tls)
      }
    }
  }
}

// The load-bearing fetch round-trip (QuickJS engine → OkHttp → MockWebServer) runs on the JVM
// target, so `:check` must run it (the Android `*UnitTest` task graph wouldn't exercise it).
tasks.named("check") {
  dependsOn("jvmTest")
}
