import java.net.Socket
import java.net.InetSocketAddress

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

android {
  namespace = "xyz.block.trailblaze.examples"
  compileSdk = 35
  defaultConfig {
    minSdk = 28
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    System.getenv("OPENAI_API_KEY")?.let { apiKey ->
      testInstrumentationRunnerArguments["OPENAI_API_KEY"] = apiKey
    }

    // Trailblaze Reverse Proxy to support Physical Devices and Ollama
    if (isHttpsServerRunning(8443)) {
      testInstrumentationRunnerArguments["trailblaze.reverseProxy"] = "true"
    }

    project.afterEvaluate {
      tasks.matching { task -> task.name == "connectedDebugAndroidTest" }.configureEach {
        doFirst {
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
          try {
            project.exec {
              // Trailblaze Reverse Proxy (Allows us to call Ollama, even though it's only http)
              commandLine(listOf("adb", "reverse", "tcp:8443", "tcp:8443"))
            }
          } catch (e: Exception) {
            println("Failed to enable adb reverse proxy: ${e.message}")
          }
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
