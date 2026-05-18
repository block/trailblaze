@file:OptIn(ExperimentalEncodingApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import kotlin.io.encoding.ExperimentalEncodingApi

plugins {
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.jetbrains.compose.multiplatform)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {

  if (findProperty("trailblaze.wasm")?.toString()?.toBoolean() != false) {
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
  }
  jvm {
    this.compilerOptions {
      jvmTarget = JvmTarget.JVM_17
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":trailblaze-models"))
      api(libs.compose.runtime)
      api(libs.compose.foundation)
      api(libs.compose.material3)
      api(libs.compose.ui)

      implementation(libs.multiplatform.markdown.renderer)
      implementation(libs.multiplatform.markdown.renderer.m3)
      implementation(libs.compose.components.resources)
      implementation(libs.compose.components.ui.tooling.preview)
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
      // WebSocket client (shared core; engine-specific WS support comes from the
      // ktor-client-js wasmJs target below — the browser's native WebSocket is used).
      implementation(libs.ktor.client.websockets)

    }
    if (findProperty("trailblaze.wasm")?.toString()?.toBoolean() != false) {
      wasmJsMain.dependencies {
        implementation(libs.ktor.client.js)
      }
    }
    jvmMain.dependencies {
      implementation(project(":trailblaze-models"))
      implementation(project(":trailblaze-common"))
      implementation(project(":trailblaze-capture"))
      implementation(project(":trailblaze-server"))
      implementation(project(":trailblaze-report"))
      implementation(libs.ktor.server.core)
      implementation(libs.compose.ui.tooling)
      implementation(libs.compose.ui.tooling.preview)
    }
    jvmTest.dependencies {
      implementation(kotlin("test"))
      // Compose UI test rig. Used by ActionYamlCardTest to assert which controls render
      // under different descriptorResolver inputs (the null path matters for wasm callers
      // that don't have JVM reflection — `RecordingScreenComposable` passes the real
      // resolver on desktop, `WebDevicesPage` would pass `{ _ -> null }` on web).
      //
      // `compose.desktop.currentOs` is required alongside the JUnit4 rig — it brings in the
      // platform-specific Skia native (libskiko-macos-arm64.dylib on Apple Silicon, …) that
      // backs the test renderer. Without it the test class fails to initialize with
      // `LibraryLoadException: Cannot find libskiko-…sha256`. Same pattern as
      // examples/compose-desktop's test deps.
      implementation(compose.desktop.currentOs)
      implementation(libs.compose.ui.test.junit4)
    }
  }
}
