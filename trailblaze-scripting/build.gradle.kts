plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":trailblaze-common"))
  api(project(":trailblaze-models"))

  implementation(libs.quickjs.kt)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
}

tasks.test { useJUnit() }
