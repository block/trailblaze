plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.spotless)
  alias(libs.plugins.dagp)
}

dependencies {
  api(libs.maestro.orchestra.models) { isTransitive = false }

  api(project(":trailblaze-common"))
  api(libs.kaml)

  testImplementation(libs.kotlin.test.junit4)
  implementation(libs.koog.agents.tools)
  testImplementation(libs.koog.agents.tools)
  testImplementation(libs.maestro.client)
  testImplementation(libs.junit)
  api(libs.kotlinx.serialization.core)
  testImplementation(libs.kotlinx.serialization.core)
}

tasks.test {
  useJUnit()
}

dependencyGuard {
  configuration("runtimeClasspath")
}
