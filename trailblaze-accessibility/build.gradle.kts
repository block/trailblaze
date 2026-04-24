plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
}

android {
  namespace = "xyz.block.trailblaze.android.accessibility.app"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  lint { abortOnError = false }
  @Suppress("UnstableApiUsage") testOptions { unitTests.all { it.useJUnitPlatform() } }
}

dependencies {
  api(libs.androidx.uiautomator)
  api(project(":trailblaze-android"))
  api(project(":trailblaze-common"))
  implementation(project(":trailblaze-tracing"))
  api(project(":trailblaze-agent"))
  api(libs.maestro.orchestra) { isTransitive = false }
  api(libs.maestro.orchestra.models) { isTransitive = false }
  api(libs.maestro.utils) { isTransitive = false }

  // Required since we excluded transitives above
  api(libs.ktor.client.core.jvm)
  api(libs.ktor.utils.jvm)
  api(libs.ktor.events.jvm)
  api(libs.ktor.serialization.jvm)
  api(libs.ktor.client.content.negotiation.jvm)
  api(libs.kotlinx.serialization.core)

  implementation(libs.androidx.uiautomator)
  implementation(libs.androidx.test.monitor)
  implementation(libs.okhttp)
  implementation(libs.ktor.client.logging)
  implementation(libs.okio)
  implementation(libs.slf4j.api)
  implementation(libs.koog.prompt.executor.openai)

  androidTestImplementation(libs.junit)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit5.jupiter.engine)
}

dependencyGuard { configuration("debugRuntimeClasspath") }
