@file:OptIn(ExperimentalEncodingApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import kotlin.io.encoding.ExperimentalEncodingApi

plugins {
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.jetbrains.compose.multiplatform)
  alias(libs.plugins.compose.hot.reload)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName.set("composeApp")
    browser {
      val rootDirPath = project.rootDir.path
      val projectDirPath = project.projectDir.path
      commonWebpackConfig {
        outputFileName = "composeApp.js"
        // Optimize for embedding - ensure single file output
        // Configure output for better embedding - use mode to optimize for single file output
        mode =
          if (project.hasProperty("production")) KotlinWebpackConfig.Mode.PRODUCTION else KotlinWebpackConfig.Mode.DEVELOPMENT
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

      implementation(libs.multiplatform.markdown.renderer)
      implementation(libs.multiplatform.markdown.renderer.m3)
      implementation(compose.components.resources)
      implementation(compose.components.uiToolingPreview)
      implementation(libs.material.icons.extended)
      implementation(libs.kotlinx.serialization.core)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.datetime)

      // Navigation
      implementation(libs.compose.navigation)

      implementation(libs.koog.prompt.model)
      implementation(libs.koog.agents.tools)
      // Image loading
      implementation(libs.coil3.compose)
      implementation(libs.coil3.network.ktor)

      // HTTP client
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.serialization.kotlinx.json)

    }
    wasmJsMain.dependencies {
      implementation(libs.ktor.client.js)
    }
    jvmMain.dependencies {
      implementation(project(":trailblaze-models"))
      implementation(project(":trailblaze-common"))
      implementation(project(":trailblaze-server"))
      implementation(project(":trailblaze-report"))
      implementation(libs.ktor.server.core)
      implementation(compose.uiTooling)
      implementation(compose.preview)
    }
  }
}
