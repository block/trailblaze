plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
}

android {
  namespace = "xyz.block.trailblaze.android.mcp.ondevice"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testApplicationId = "xyz.block.trailblaze.runner"
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

  @Suppress("UnstableApiUsage")
  testOptions {
    unitTests.all {
      it.useJUnitPlatform()
    }
  }
}

dependencies {
  // NO driver module here, deliberately. This module is the driver-agnostic on-device RPC
  // server: the driver-specific pieces (screen-state capture, the pre-tool settle gate) are
  // injected through [OnDeviceRpcServer]'s constructor seams, so the accessibility runner and
  // the in-process ANDROID_TEST driver can host the same server without this module dragging
  // either driver's dependency tree into the other's APK.
  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-models"))
  implementation(project(":trailblaze-ondevice-rpc-proto"))
  implementation(project(":trailblaze-tracing"))
  implementation(project(":trailblaze-agent"))

  implementation(libs.ktor.server.core.jvm)
  implementation(libs.coroutines)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.websockets)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.androidx.test.monitor)

  // The androidTest runner (AndroidStandaloneServerTest) is the ACCESSIBILITY-driver harness:
  // it wires the trailblaze-android captor/settle implementations into the server and builds
  // real LLM clients. Test-scoped so no consumer of this module inherits them.
  androidTestImplementation(project(":trailblaze-android"))
  androidTestImplementation(libs.koog.prompt.executor.anthropic)
  androidTestImplementation(libs.koog.prompt.executor.openai)
  androidTestImplementation(libs.koog.prompt.executor.ollama)
  // Koog 1.0.0: LLM clients no longer accept a raw Ktor `HttpClient`; the androidTest source
  // wraps the cached client in `KtorKoogHttpClient.Factory` so its config flows through.
  androidTestImplementation(libs.koog.http.client.ktor)
  androidTestImplementation(libs.androidx.test.runner)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit5.jupiter.engine)
  testImplementation(libs.kotlinx.coroutines.test)
}

dependencyGuard {
  configuration("debugRuntimeClasspath") {
    modules = true
  }
}
