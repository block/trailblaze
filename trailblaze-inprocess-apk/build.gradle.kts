plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  // For the farm's install-time pre-flight only — see ProbeApkMain's KDoc for why that caller
  // cannot go through `:trailblaze-host`. Same pattern as the other farm-invoked tools
  // (`:test-farm-logs:run`, `:trailblaze-report-block:run`).
  application
}

application {
  mainClass.set("xyz.block.trailblaze.inprocess.apk.ProbeApkMain")
}

// Everything an in-process adopter's host has to know about an APK, with no Android SDK on the
// machine. That constraint is the module: `aapt2` and `apksigner` are per-OS native binaries and a
// team adopting the in-process driver is not asked to install an SDK, so the manifest reader, the
// dex reader and the fingerprint schema here are pure JVM.
//
// **Why this module is separate from `:trailblaze-host`.** The fingerprint is a contract between
// two callers that never share a process: the CLI (`trailblaze inprocess probe-apk`) writes it, and
// `make-test-apk`'s signing guards read it on the path where the app APK's bytes never travel — see
// docs/internal/inprocess-dogfooding-plan.md items 3 and 4. One schema, one decoder, no host
// dependency, so the reading side stays cheap enough for a key-custody team's CI to run.
//
// Deliberately lean, for the same reason `:trailblaze-trailmap-bundler` is: the only dependencies
// are kaml (the fingerprint's on-disk form) and apksig (certificate digests). Nothing here reaches
// `:trailblaze-models`.

dependencies {
  implementation(libs.kaml)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.apksig)

  testImplementation(kotlin("test"))
  testImplementation(kotlin("test-junit"))
}

tasks.named<Test>("test") {
  useJUnit()
  // The real-APK cross-checks (see RealApkProbeTest) take their inputs by system property because
  // the APKs they read are build outputs and mobile-releases downloads, not committed fixtures.
  // Forwarded rather than hardcoded so the same test class is a no-op on a machine that has neither.
  listOf(
    "trailblaze.probe.appApk",
    "trailblaze.probe.shellApk",
    "trailblaze.probe.declaredDeps",
  ).forEach { key ->
    System.getProperty(key)?.let { systemProperty(key, it) }
  }
}
