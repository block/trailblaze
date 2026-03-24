plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":trailblaze-common"))
  api(project(":trailblaze-agent"))
  implementation(project(":trailblaze-host"))
  implementation(project(":trailblaze-tracing"))

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.koog.agents.tools)

  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
}

tasks.test { useJUnit() }

project.tasks.named("check") { dependsOn.removeIf { it.toString().contains("test") } }
