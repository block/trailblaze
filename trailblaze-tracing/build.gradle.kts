import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.vanniktech.maven.publish)
}

android {
  namespace = "xyz.block.trailblaze.tracing"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
  }
  lint {
    abortOnError = false
  }
}

kotlin {
  if (findProperty("trailblaze.wasm")?.toString()?.toBoolean() != false) {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
      browser()
    }
  }

  androidTarget {
    publishLibraryVariants("release", "debug")
    this.compilerOptions {
      jvmTarget = JvmTarget.JVM_17
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

    jvmTest.dependencies {
      implementation(libs.kotlin.test.junit4)
    }
  }
}

dependencyGuard {
  configuration("jvmMainRuntimeClasspath")
}
