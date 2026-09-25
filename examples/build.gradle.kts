import org.gradle.process.ExecOperations
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.dagp)
}

/**
 * Carries [ExecOperations] into a task action. `project.exec` is off limits there: anything a task
 * action reaches for on the build script — `project` included — drags a reference to the script
 * into the configuration cache, which cannot serialize one. Gradle serializes this managed object
 * by reference instead.
 */
interface TrailblazeExecOps {
  @get:Inject
  val execOperations: ExecOperations
}

/**
 * Device prep for `connectedDebugAndroidTest`. An object rather than script-level functions, for
 * the same reason: a task action that calls a script-level function captures the script.
 */
object TrailblazeDevicePrep {
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

  fun prepareDevice(execOperations: ExecOperations, httpsPort: Int) {
    if (isHttpsServerRunning(httpsPort)) {
      try {
        execOperations.exec {
          // Trailblaze Reverse Proxy
          commandLine(listOf("adb", "reverse", "tcp:$httpsPort", "tcp:$httpsPort"))
        }
      } catch (e: Exception) {
        println("Failed to enable adb reverse proxy: ${e.message}")
      }
    }

    // Kill any running instrumentation processes before running test
    try {
      // Ensure Any Maestro Test is Disconnected
      execOperations.exec {
        commandLine("adb", "shell", "am force-stop dev.mobile.maestro.test")
      }
      // Ensure Any Trailblaze Test is Disconnected
      execOperations.exec {
        commandLine("adb", "shell", "am force-stop xyz.block.trailblaze.runner")
      }
    } catch (e: Exception) {
      println("Failed to force-stop app (this is safe to ignore): ${e.message}")
    }
  }
}

/**
 * Whether the Trailblaze host daemon is up, as a configuration input.
 *
 * A bare socket probe is invisible to the configuration cache, so its answer would be frozen into
 * the cache entry: start the daemon after a build that ran without it and every later build would
 * reuse the entry and still tell the on-device runner there is no proxy. Gradle re-runs a
 * [ValueSource] at the start of each build and invalidates the entry when the answer changes.
 */
abstract class TrailblazeServerRunning : ValueSource<Boolean, TrailblazeServerRunning.Params> {
  interface Params : ValueSourceParameters {
    val httpsPort: Property<Int>
  }

  override fun obtain(): Boolean = TrailblazeDevicePrep.isHttpsServerRunning(parameters.httpsPort.get())
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
    val isTrailblazeServerRunning = providers.of(TrailblazeServerRunning::class) {
      parameters.httpsPort.set(trailblazeHttpsPort)
    }.get()
    if (isTrailblazeServerRunning) {
      if (isRunningTests) println("Server is running on port $trailblazeHttpsPort, enabling Trailblaze Reverse Proxy")
      testInstrumentationRunnerArguments["trailblaze.reverseProxy"] = "true"
      testInstrumentationRunnerArguments["trailblaze.httpsPort"] = trailblazeHttpsPort.toString()
    }
  }

  project.afterEvaluate {
    // Read into locals at configuration time. A task action that reads a script-level property
    // captures the script object, which the configuration cache cannot serialize; a local is
    // captured by value.
    val execOps = objects.newInstance<TrailblazeExecOps>()
    val httpsPort = trailblazeHttpsPort
    tasks.matching { task -> task.name == "connectedDebugAndroidTest" }.configureEach {
      doFirst {
        TrailblazeDevicePrep.prepareDevice(execOps.execOperations, httpsPort)
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
