import java.net.InetSocketAddress
import java.net.Socket

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.dagp)
}

fun isHttpsServerRunning(port: Int): Boolean {
  return try {
    Socket().use { socket ->
      socket.connect(InetSocketAddress("localhost", port), 100) // 100ms timeout
      true
    }
  } catch (e: Exception) {
    false
  }
}

// Check if we're running a test task to avoid validation errors during assemble/check
val isRunningTests = gradle.startParameter.taskNames.any { taskName ->
  taskName.contains("test", ignoreCase = true) || taskName.contains("connected", ignoreCase = true)
}

// HTTPS port defaults to HTTP port + 1 (mirrors TrailblazeDevicePort and the wrapper script).
val trailblazeDefaultHttpPort = 52525
val trailblazeHttpPort = System.getenv("TRAILBLAZE_PORT")?.toIntOrNull() ?: trailblazeDefaultHttpPort
val trailblazeHttpsPort = System.getenv("TRAILBLAZE_HTTPS_PORT")?.toIntOrNull() ?: (trailblazeHttpPort + 1)

android {
  namespace = "xyz.block.trailblaze.examples"
  compileSdk = 36
  defaultConfig {
    minSdk = 28
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Pass API keys from environment variables to authenticate with LLM providers
    val providerEnvVars = mapOf(
      "openai" to "OPENAI_API_KEY",
      "openrouter" to "OPENROUTER_API_KEY",
      "anthropic" to "ANTHROPIC_API_KEY",
      "google" to "GOOGLE_API_KEY",
    )
    for ((providerId, envVar) in providerEnvVars) {
      System.getenv(envVar)?.let { apiKey ->
        testInstrumentationRunnerArguments["trailblaze.llm.auth.token.$providerId"] = apiKey
      }
    }

    // Trailblaze Reverse Proxy to support Physical Devices and Ollama
    val isTrailblazeServerRunning = isHttpsServerRunning(trailblazeHttpsPort)
    if (isTrailblazeServerRunning) {
      if (isRunningTests) println("Server is running on port $trailblazeHttpsPort, enabling Trailblaze Reverse Proxy")
      testInstrumentationRunnerArguments["trailblaze.reverseProxy"] = "true"
      testInstrumentationRunnerArguments["trailblaze.httpsPort"] = trailblazeHttpsPort.toString()
    }
  }

  project.afterEvaluate {
    tasks.matching { task -> task.name == "connectedDebugAndroidTest" }.configureEach {
      doFirst {
        val isTrailblazeServerRunning = isHttpsServerRunning(trailblazeHttpsPort)
        if (isTrailblazeServerRunning) {
          try {
            project.exec {
              // Trailblaze Reverse Proxy
              commandLine(listOf("adb", "reverse", "tcp:$trailblazeHttpsPort", "tcp:$trailblazeHttpsPort"))
            }
          } catch (e: Exception) {
            println("Failed to enable adb reverse proxy: ${e.message}")
          }
        }

        // Kill any running instrumentation processes before running test
        try {
          // Ensure Any Maestro Test is Disconnected
          project.exec {
            commandLine("adb", "shell", "am force-stop dev.mobile.maestro.test")
          }
          // Ensure Any Trailblaze Test is Disconnected
          project.exec {
            commandLine("adb", "shell", "am force-stop xyz.block.trailblaze.runner")
          }
        } catch (e: Exception) {
          println("Failed to force-stop app (this is safe to ignore): ${e.message}")
        }
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  lint {
    abortOnError = false
  }

  packaging {
    exclude("META-INF/INDEX.LIST")
    exclude("META-INF/AL2.0")
    exclude("META-INF/LICENSE.md")
    exclude("META-INF/LICENSE-notice.md")
    exclude("META-INF/LGPL2.1")
    exclude("META-INF/io.netty.versions.properties")
  }

  sourceSets {
    getByName("androidTest") {
      assets.srcDirs("../trails")
    }
  }

  testOptions {
    animationsDisabled = true
  }
}

dependencies {
  androidTestImplementation(project(":trailblaze-common"))
  androidTestImplementation(project(":trailblaze-android"))

  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.koog.prompt.executor.ollama)
  androidTestImplementation(libs.koog.prompt.executor.openai)
  androidTestImplementation(libs.koog.prompt.executor.openrouter)
  androidTestImplementation(libs.koog.prompt.executor.clients)
  androidTestImplementation(libs.koog.prompt.llm)
  androidTestImplementation(libs.ktor.client.core)
  androidTestImplementation(libs.kotlinx.datetime)

  androidTestRuntimeOnly(libs.androidx.test.runner)
  androidTestRuntimeOnly(libs.coroutines.android)
  androidTestImplementation(libs.maestro.orchestra.models) { isTransitive = false }
}

dependencyGuard {
  configuration("debugAndroidTestRuntimeClasspath") {
    modules = true
  }
}
