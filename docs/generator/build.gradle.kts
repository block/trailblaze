plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.dagp)
  application
}

application {
  mainClass.set("xyz.block.trailblaze.docs.GenerateDocsMainKt")
}

dependencies {
  implementation(project(":trailblaze-common"))
  runtimeOnly(libs.kotlin.reflect)
  implementation(libs.koog.agents.tools)
}
