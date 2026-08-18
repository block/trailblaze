import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.wire)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.vanniktech.maven.publish)
}

android {
  namespace = "xyz.block.trailblaze.ondevice.rpc.proto"
  compileSdk = 36
  defaultConfig { minSdk = 26 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  lint { abortOnError = false }
}

kotlin {
  // Same opt-in gate as trailblaze-models: the wasmJs target exists so commonMain is
  // compiler-enforced KMP-clean (metadata compile), off by default to keep local builds fast.
  if (findProperty("trailblaze.wasm")?.toString()?.toBoolean() != false) {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
      browser()
    }
  }

  androidTarget { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
  jvm { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies {
      api(project(":trailblaze-models"))
      api(libs.wire.runtime)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.serialization.json)
    }
  }
}

wire {
  sourcePath { srcDir("src/main/proto") }
  kotlin {}
}

dependencyGuard {
  configuration("jvmRuntimeClasspath")
}

// The wasmJs target above exists ONLY as a compile-time KMP-cleanliness gate (it makes
// `compileCommonMainKotlinMetadata` run), so its publication is suppressed: nothing consumes a
// wasmJs variant of this module, and the release pipeline publishes with
// `-Ptrailblaze.wasm=true`, which would otherwise start shipping a new artifact variant to the
// Maven repository as a silent side effect of the lint gate. `:trailblaze-models` DOES publish
// its wasmJs variant — the report UI consumes it — which is the distinction here.
//
// Known residual: this disables the publish TASKS, so no wasmJs artifact is ever uploaded, but
// the root Gradle Module Metadata still advertises `wasmJs*Elements-published` variants whose
// `available-at` coordinate never receives an upload. KMP offers no first-class "declare a
// target but don't publish it" switch, so the task disable is the standard workaround and this
// dangling variant entry is its known cost. Harmless while no wasmJs consumer of this module
// exists — JVM and Android consumers never resolve those variants — revisit if one appears.
tasks.matching { it.name.startsWith("publish") && it.name.contains("WasmJs") }.configureEach {
  enabled = false
}
