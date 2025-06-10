plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.spotless)
  alias(libs.plugins.dagp)
}

dependencies {
  api(platform(libs.openai.client.bom))
  api(libs.kotlinx.serialization.json)
  api(libs.coroutines)
  api(libs.junit)
  api(libs.openai.client.core)
  api(libs.okio)
  api(libs.ktor.client.core)
  api(libs.maestro.orchestra.models) { isTransitive = false }
  api(libs.maestro.client) { isTransitive = false }

  implementation(libs.kotlinx.serialization.core)
  implementation(libs.gson)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.http)
  implementation(libs.ktor.utils)
  implementation(libs.kotlin.reflect)
  implementation(libs.snakeyaml)

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
