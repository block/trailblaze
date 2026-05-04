@file:Suppress("UnstableApiUsage")

// `includeBuild("build-logic")` makes ALL plugins registered in `build-logic/` available
// to projects in this build. The plugins include:
//
//   - `trailblaze.bundle`         — per-pack TS bindings
//   - `trailblaze.bundled-config` — flat-target generation from packs
//   - `trailblaze.spotless`       — formatting
//   - `trailblaze.multi-simulator`— iOS sim provisioning
//
// No active module applies plugins beyond what it needs.
pluginManagement {
  // `build-logic/` lives next to this settings file (alongside `gradle/`,
  // `trailblaze-*/`, etc.) so it travels with the rest of this directory.
  // Downstream builds that wrap this tree pick the same plugins up via their own
  // `includeBuild` pointing at this `build-logic/`.
  includeBuild("build-logic")
  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    google()
  }
}

rootProject.name = "trailblaze"
include(
  ":docs:generator",
  ":examples",
  ":examples:android-sample-app",
  ":examples:compose-desktop",
  ":examples:playwright-native",
  ":trailblaze-accessibility-app",
  ":trailblaze-agent",
  ":trailblaze-android",
  ":trailblaze-android-ondevice-mcp",
  ":trailblaze-android-world-benchmarks",
  ":trailblaze-capture",
  ":trailblaze-common",
  ":trailblaze-compose-target",
  ":trailblaze-desktop",
  ":trailblaze-host",
  ":trailblaze-models",
  ":trailblaze-pack-bundler",
  ":trailblaze-compose",
  ":trailblaze-playwright",
  ":trailblaze-quickjs-tools",
  ":trailblaze-revyl",
  ":trailblaze-scripting",
  ":trailblaze-scripting-bundle",
  ":trailblaze-scripting-mcp-common",
  ":trailblaze-scripting-subprocess",
  ":trailblaze-ui",
  ":trailblaze-report",
  ":trailblaze-server",
  ":trailblaze-tracing",
)
