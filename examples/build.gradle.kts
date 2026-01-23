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

// Helper function to mask sensitive values
fun maskValue(value: String): String {
  return if (value.length > 4) {
    "*".repeat(value.length - 4) + value.takeLast(4)
  } else {
    "****"
  }
}

// Check if we're running a test task to avoid validation errors during assemble/check
val isRunningTests = gradle.startParameter.taskNames.any { taskName ->
  taskName.contains("test", ignoreCase = true) || taskName.contains("connected", ignoreCase = true)
}

android {
  namespace = "xyz.block.trailblaze.examples"
  compileSdk = 35
  defaultConfig {
    minSdk = 28
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Trailblaze Reverse Proxy to support Physical Devices and Ollama
    val isGitHubActions = (System.getenv("GITHUB_ACTIONS") == "true")
    val isTrailblazeServerRunning = isHttpsServerRunning(8443)
    val isOpenRouterApiKeyEnvVarSet = (System.getenv("OPENROUTER_API_KEY") != null)

    if (isGitHubActions && isRunningTests) {
      if (!isTrailblazeServerRunning) {
        throw GradleException("Trailblaze Reverse Proxy is required when running in GitHub Actions. Please ensure the server is running on port 8443.")
      }
      if (isOpenRouterApiKeyEnvVarSet) {
        // Setting a dummy value so this LLM client is used, but it doesn't get in the logs as it's replaced by the reverse proxy
        val dummyValue = "OPENROUTER_API_KEY_GOES_HERE"
        testInstrumentationRunnerArguments["OPENROUTER_API_KEY"] = dummyValue
      } else {
        // This key will be replaced by the reverse proxy, but is required as a system environmenet variable
        throw GradleException("OPENROUTER_API_KEY is not set. Please set it as a secret in GitHub Actions.")
      }
    } else {
      // Local Development
      System.getenv("OPENROUTER_API_KEY")?.let { apiKey ->
        testInstrumentationRunnerArguments["OPENROUTER_API_KEY"] = apiKey
      }

      System.getenv("OPENAI_API_KEY")?.let { apiKey ->
        testInstrumentationRunnerArguments["OPENAI_API_KEY"] = apiKey
      }
    }

    if (isTrailblazeServerRunning) {
      if (isRunningTests) println("Server is running on port 8443, enabling Trailblaze Reverse Proxy")
      testInstrumentationRunnerArguments["trailblaze.reverseProxy"] = "true"
    }
  }

  project.afterEvaluate {
    tasks.matching { task -> task.name == "connectedDebugAndroidTest" }.configureEach {
      doFirst {
        val isTrailblazeServerRunning = isHttpsServerRunning(8443)
        if (isTrailblazeServerRunning) {
          try {
            project.exec {
              // Trailblaze Reverse Proxy
              commandLine(listOf("adb", "reverse", "tcp:8443", "tcp:8443"))
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
  kotlinOptions {
    jvmTarget = "17"
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
