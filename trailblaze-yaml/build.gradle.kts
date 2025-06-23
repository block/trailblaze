plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.spotless)
}

dependencies {
  api(project(":trailblaze-common"))
  implementation(libs.kaml)

  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
}

tasks.test {
  useJUnit()
}

dependencyGuard {
  configuration("runtimeClasspath")
}
