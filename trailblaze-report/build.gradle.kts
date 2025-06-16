plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.spotless)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.dagp)
  application
}

application {
  mainClass.set("xyz.block.trailblaze.report.ReportMainKt")
}

dependencies {
  implementation(project(":trailblaze-common"))
  implementation(libs.freemarker)
  implementation(libs.maestro.orchestra.models) { isTransitive = false }
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.kotlinx.serialization.json)

  runtimeOnly(libs.slf4j.simple)
}

tasks.test {
  useJUnit()
}

dependencyGuard {
  configuration("runtimeClasspath")
}
