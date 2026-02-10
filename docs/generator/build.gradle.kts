import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.dagp)
  alias(libs.plugins.kotlin.serialization)
  application
}

application {
  mainClass.set("xyz.block.trailblaze.docs.GenerateDocsMainKt")
}

// Multiple dependencies transitively include the same JARs (e.g. library-desktop),
// so we need to allow duplicates in the distribution archives.
tasks.withType<Tar> {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.withType<Zip> {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}


kotlin {
  this.compilerOptions {
    jvmTarget = JvmTarget.JVM_17
  }
}

dependencies {
  implementation(project(":trailblaze-models"))
  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-host"))
  runtimeOnly(libs.kotlin.reflect)
  implementation(libs.koog.agents.tools)
  implementation(libs.gson)
  implementation(libs.picocli)
}
