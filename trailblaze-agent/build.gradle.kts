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

  implementation(platform(libs.openai.client.bom))
  implementation(libs.openai.client.core)
  implementation(libs.openai.client)
  implementation(libs.exp4j)
  implementation(libs.coroutines)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.kotlinx.serialization.json)

  runtimeOnly(libs.kotlin.reflect)
}

tasks.test {
  useJUnit() // Configure Gradle to use JUnit 4
}

dependencyGuard {
  configuration("runtimeClasspath")
}
