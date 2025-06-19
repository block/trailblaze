plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.spotless)
  alias(libs.plugins.dagp)
}

dependencies {
  api(libs.kotlinx.datetime)
  api(libs.kotlinx.serialization.json)
  api(libs.coroutines)
  api(libs.junit)
  api(libs.okio)
  api(libs.koog.agents.tools)
  api(libs.ktor.client.core)
  api(libs.maestro.orchestra.models) { isTransitive = false }
  api(libs.maestro.orchestra) { isTransitive = false }
  api(libs.maestro.client) { isTransitive = false }

  implementation(libs.gson)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.http)
  implementation(libs.ktor.utils)
  implementation(libs.kotlin.reflect)
  implementation(libs.snakeyaml)
  implementation(libs.koog.prompt.executor.clients)

  runtimeOnly(libs.jackson.dataformat.yaml)
  runtimeOnly(libs.jackson.module.kotlin)

  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.maestro.orchestra) { isTransitive = false }
  testImplementation(libs.assertk)
}

dependencyGuard {
  configuration("runtimeClasspath")
}

tasks.test {
  useJUnit()
}
