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
  // CliDocsGenerator + LlmConfigDocsGenerator reach into CLI command classes that live in
  // :trailblaze-host. The per-tool renderer (`ResolvedTargetToolDetailRenderer`) is in
  // :trailblaze-models and does NOT require this dep — once those CLI doc generators move
  // out (or the CLI command surface gets a lighter-weight reflection home), this dep can
  // be dropped.
  implementation(project(":trailblaze-host"))
  runtimeOnly(libs.kotlin.reflect)
  implementation(libs.koog.agents.tools)
  implementation(libs.gson)
  implementation(libs.picocli)
  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
}

tasks.test {
  useJUnit()
}
