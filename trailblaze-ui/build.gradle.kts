import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.jetbrains.compose.multiplatform)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  // Enable WASM once Koog 0.3.0 is released with WASM support
  val useWasm = false
  if (useWasm) {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
      outputModuleName.set("composeApp")
      browser {
        val rootDirPath = project.rootDir.path
        val projectDirPath = project.projectDir.path
        commonWebpackConfig {
          outputFileName = "composeApp.js"
          devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
            static = (static ?: mutableListOf()).apply {
              // Serve sources to debug inside browser
              add(rootDirPath)
              add(projectDirPath)
            }
          }
        }
      }
      binaries.executable()
    }
  }
  jvm {
    this.compilerOptions {
      jvmTarget = JvmTarget.JVM_17
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":trailblaze-models"))
      api(compose.runtime)
      api(compose.foundation)
      api(compose.material3)
      api(compose.ui)

      implementation(compose.components.resources)
      implementation(compose.components.uiToolingPreview)
      implementation(libs.androidx.lifecycle.viewmodel)
      implementation(libs.androidx.lifecycle.runtimeCompose)
      implementation(libs.kotlinx.serialization.core)
      implementation(libs.kotlinx.serialization.json)
    }
    jvmMain.dependencies {
      implementation(project(":trailblaze-server"))
      implementation(libs.ktor.server.core)
    }
  }
}


