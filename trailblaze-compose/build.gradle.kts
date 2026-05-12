plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.jetbrains.compose.multiplatform)
  alias(libs.plugins.dependency.guard)
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-opt-in=androidx.compose.ui.test.ExperimentalTestApi",
    )
  }
}

dependencies {
  api(project(":trailblaze-common"))
  api(project(":trailblaze-agent"))
  api(project(":trailblaze-compose-target"))

  api(compose.desktop.currentOs)

  // --------------------------------------------------------------------------
  // Cross-host Compose Desktop bundling
  // --------------------------------------------------------------------------
  // `compose.desktop.currentOs` above resolves to whatever the *build* host is
  // (`desktop-jvm-macos-arm64` on a Mac Runway agent, `desktop-jvm-linux-x64`
  // on a Linux CI worker, etc.). That's fine for compiling Compose code, but
  // it means the uber JAR built on host A is only runnable on host A —
  // which broke real downstream consumers when the macOS-built JAR was run on
  // Linux CI (see #2844 and the matching `skiko-awt-runtime` block in
  // `trailblaze-host`'s build.gradle.kts, which fixes the same category of
  // bug for the Skiko native binding shipped alongside this).
  //
  // To make the uber JAR OS-portable, we declare every supported variant as
  // `runtimeOnly` here. They propagate transitively through every downstream
  // consumer's runtime classpath, so the published JAR (whichever host built
  // it) contains all 3 platforms' Compose Desktop bindings — and the
  // dependency-guard baseline shows the explicit platform set as plain
  // documentation, with no host-specific normalization needed.
  //
  // The host's variant dedups with `compose.desktop.currentOs`'s api() entry
  // above, so this is +2 entries per host on the runtime classpath, not +3.
  // Intel macOS / Windows are intentionally omitted to match
  // `TrailblazeDesktopUtil.assertSupportedPlatform()`.
  listOf(
    "linux-x64",
    "linux-arm64",
    "macos-arm64",
  ).forEach { os ->
    runtimeOnly("org.jetbrains.compose.desktop:desktop-jvm-$os:${libs.versions.jetbrains.compose.multiplatform.get()}")
  }

  api(libs.compose.ui.test.junit4)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.datetime)
  implementation(libs.koog.agents.tools)

  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.content.negotiation)

  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
}

tasks.test { useJUnit() }

dependencyGuard {
  configuration("runtimeClasspath") {
    modules = true
  }
}
