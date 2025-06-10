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
  implementation(platform(libs.openai.client.bom))
  implementation(libs.openai.client.core)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.kotlinx.serialization.json)
  runtimeOnly(libs.kotlin.reflect)
}
