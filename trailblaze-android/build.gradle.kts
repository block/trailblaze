plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.spotless)
  alias(libs.plugins.dagp)
}

android {
  namespace = "xyz.block.trailblaze.android"
  compileSdk = 35
  defaultConfig {
    minSdk = 24
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
  lint {
    abortOnError = false
  }

  @Suppress("UnstableApiUsage")
  testOptions {
    unitTests.all {
      it.useJUnitPlatform()
    }
  }
}

dependencies {
  api(project(":trailblaze-common"))

  api(libs.androidx.uiautomator)
  api(libs.ktor.client.okhttp)
  api(libs.kotlinx.serialization.core)
  api(libs.junit)
  api(libs.maestro.orchestra.models) { isTransitive = false }
  api(libs.maestro.utils) { isTransitive = false }
  api(libs.maestro.client) { isTransitive = false }
  api(libs.koog.agents.tools)
  api(libs.koog.prompt.llm)

  implementation(project(":trailblaze-agent"))
  implementation(libs.ktor.client.core.jvm)
  implementation(libs.maestro.orchestra) { isTransitive = false }
  implementation(libs.androidx.test.monitor)
  implementation(libs.okhttp)
  implementation(libs.okio)
  implementation(libs.slf4j.api)
  implementation(libs.koog.prompt.executor.clients)
  implementation(libs.kotlinx.datetime)

  implementation(libs.ktor.http)
  implementation(libs.coroutines)
  implementation(libs.kotlinx.serialization.json)

  runtimeOnly(libs.coroutines.android)
}

dependencyGuard {
  configuration("debugRuntimeClasspath")
}
