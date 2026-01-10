plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.jetbrains.compose.multiplatform)
}

tasks.withType<Tar> {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip> {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
  api(project(":trailblaze-agent"))

  api(libs.maestro.orchestra)
  api(libs.maestro.client)
  api(libs.maestro.ios)
  api(libs.maestro.web)
  api(libs.maestro.ios.driver)
  api(libs.dadb)
  api(libs.okhttp)
  api(libs.jansi)
  api(libs.picocli)
  api(libs.ktor.client.okhttp)
  api(libs.slf4j.api)

  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-report"))
  implementation(project(":trailblaze-server"))
  implementation(project(":trailblaze-ui"))

  // Compose dependencies for JVM UI code moved from trailblaze-ui
  implementation(compose.desktop.currentOs)
  implementation(compose.ui)
  implementation(compose.runtime)
  implementation(compose.foundation)
  implementation(compose.material3)
  implementation(compose.uiTooling)
  implementation(compose.preview)
  implementation(compose.components.resources)
  implementation(libs.material.icons.extended)
  implementation(libs.compose.navigation)

  implementation(libs.ktor.client.logging)
  implementation(libs.koog.prompt.executor.anthropic)
  implementation(libs.koog.prompt.executor.google)
  implementation(libs.koog.prompt.executor.ollama)
  implementation(libs.koog.prompt.executor.openai)
  implementation(libs.koog.prompt.executor.openrouter)
  implementation(libs.koog.agents.tools)
  implementation(libs.mcp.sdk)

  // We're not actually leveraging playwright now, so let's keep it out of the app
  implementation(libs.playwright)

  testImplementation(project(":trailblaze-tracing"))
  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
}

tasks.test {
  useJUnit()
}

dependencyGuard {
  configuration("runtimeClasspath") {
    baselineMap = rootProject.extra["trailblazePlatformBaselineMap"] as (String) -> String
  }
}
