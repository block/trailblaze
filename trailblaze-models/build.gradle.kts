import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.spotless)
  alias(libs.plugins.dependency.guard)
}

kotlin {
  // Enable WASM once Koog 0.3.0 is released with WASM support
  val useWasm = false
  if (useWasm) {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
      browser()
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
      implementation(libs.koog.agents.tools)
      implementation(libs.koog.prompt.model)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlinx.serialization.core)
    }
  }
}

dependencyGuard {
  configuration("jvmMainRuntimeClasspath")
}
