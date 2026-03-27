plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":trailblaze-common"))
  api(project(":trailblaze-agent"))
  implementation(project(":trailblaze-tracing"))
  api(libs.playwright)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.datetime)
  implementation(libs.koog.agents.tools)
  implementation(libs.skiko)

  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
}

tasks.test { useJUnit() }

// Don't run tests as part of "check" — only when explicitly requested via "test"
project.tasks.named("check") { dependsOn.removeIf { it.toString().contains("test") } }