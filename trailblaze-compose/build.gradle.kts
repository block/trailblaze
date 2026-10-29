plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.jetbrains.compose.multiplatform)
  alias(libs.plugins.dependency.guard)
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-opt-in=androidx.compose.ui.test.ExperimentalTestApi",
    )
  }
}

dependencies {
  api(project(":trailblaze-common"))
  api(project(":trailblaze-agent"))
  api(project(":trailblaze-compose-target"))

  // Compose UI itself arrives through :trailblaze-compose-target's `api(compose.ui)`. The Skiko
  // native libraries it loads are not declared here: the CLI JAR gets all three supported hosts'
  // from :trailblaze-host's `skiko-awt-runtime-*` block, so a JAR built on one host runs on the
  // others. This module's own tests only need the build host's, below.
  testRuntimeOnly(compose.desktop.currentOs)

  api(libs.compose.ui.test.junit4)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.datetime)
  implementation(libs.koog.agents.tools)

  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.websockets)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.content.negotiation)

  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
}

tasks.test { useJUnit() }

dependencyGuard {
  configuration("runtimeClasspath") {
    modules = true
  }
}
