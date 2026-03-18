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

  api(compose.desktop.currentOs)
  api(libs.compose.ui.test.junit4)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.datetime)
  implementation(libs.koog.agents.tools)

  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.content.negotiation)
}

dependencyGuard {
  configuration("runtimeClasspath") {
    modules = true

    @Suppress("UNCHECKED_CAST")
    val map = rootProject.extra["trailblazePlatformBaselineMap"] as (String) -> String
    baselineMap = map
  }
}
