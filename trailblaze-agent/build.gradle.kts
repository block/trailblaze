plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.spotless)
  alias(libs.plugins.dagp)
}

dependencies {
  api(project(":trailblaze-common"))
  api(libs.maestro.orchestra.models) { isTransitive = false }

  implementation(libs.exp4j)
  implementation(libs.coroutines)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.koog.prompt.executor.clients)
  runtimeOnly(libs.kotlin.reflect)
}

tasks.test {
  useJUnit() // Configure Gradle to use JUnit 4
}

dependencyGuard {
  configuration("runtimeClasspath")
}
