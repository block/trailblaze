import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.spotless)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    compilerOptions {
      // Enable qualified names in Kotlin/Wasm to support KClass.qualifiedName used in OtherTrailblazeToolSerializer
      // Required since Kotlin 2.2.20 where qualifiedName usage in Wasm became a compile error by default
      freeCompilerArgs.add("-Xwasm-kclass-fqn")
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
      implementation(libs.kaml)
      implementation(libs.koog.agents.tools)
      implementation(libs.koog.prompt.model)
      implementation(libs.koog.prompt.executor.clients)
      implementation(libs.koog.prompt.executor.llms)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlin.reflect)
      implementation(libs.kotlinx.serialization.core)
    }
  }
}

dependencyGuard {
  configuration("jvmMainRuntimeClasspath")
}
