import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.dagp)
  application
}

application {
  mainClass.set("xyz.block.trailblaze.docs.GenerateDocsMainKt")
}


kotlin {
  this.compilerOptions {
    jvmTarget = JvmTarget.JVM_17
  }
}

dependencies {
  implementation(project(":trailblaze-models"))
  implementation(project(":trailblaze-common"))
  runtimeOnly(libs.kotlin.reflect)
  implementation(libs.koog.agents.tools)
}
