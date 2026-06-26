@file:Suppress("UnstableApiUsage")

// `includeBuild("build-logic")` makes ALL plugins registered in `build-logic/` available
// to projects in this build. The plugins include:
//
//   - `trailblaze.bundle`         — per-trailmap TS bindings
//   - `trailblaze.bundled-config` — flat-target generation from trailmaps
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
  // Repository URLs are resolved by name rather than written as literals here, so this file
  // pins no specific registry host. The public Maven URLs in `gradle.properties` (`mavenRepo*`)
  // are the defaults; a build may redirect resolution to a private mirror by exporting the
  // matching `TRAILBLAZE_GRADLE_REPO_*` environment variable, without editing this file and
  // without that host ever appearing in this tree. `mavenRepo` prefers the env var, then the
  // Gradle property; a blank value from either is treated as unset (an exported-but-empty env var
  // would otherwise become an invalid `uri("")`), and a clear error is raised if neither provides
  // one. It is a block-local lambda because Gradle evaluates `pluginManagement {}` in isolation
  // and can't see top-level helpers.
  val mavenRepo = { envVar: String, propKey: String ->
    (providers.environmentVariable(envVar).orNull?.takeIf(String::isNotBlank)
      ?: providers.gradleProperty(propKey).orNull?.takeIf(String::isNotBlank))
      ?: error("Set the $envVar environment variable or the $propKey Gradle property to a Maven repository URL")
  }
  repositories {
    maven { url = uri(mavenRepo("TRAILBLAZE_GRADLE_REPO_ANDROID", "mavenRepoAndroid")) }
    maven { url = uri(mavenRepo("TRAILBLAZE_GRADLE_REPO_GOOGLE", "mavenRepoGoogle")) }
    maven { url = uri(mavenRepo("TRAILBLAZE_GRADLE_REPO_PLUGINS", "mavenRepoPlugins")) }
  }
}

dependencyResolutionManagement {
  val mavenRepo = { envVar: String, propKey: String ->
    (providers.environmentVariable(envVar).orNull?.takeIf(String::isNotBlank)
      ?: providers.gradleProperty(propKey).orNull?.takeIf(String::isNotBlank))
      ?: error("Set the $envVar environment variable or the $propKey Gradle property to a Maven repository URL")
  }
  repositories {
    maven { url = uri(mavenRepo("TRAILBLAZE_GRADLE_REPO_ANDROID", "mavenRepoAndroid")) }
    maven { url = uri(mavenRepo("TRAILBLAZE_GRADLE_REPO_GOOGLE", "mavenRepoGoogle")) }
  }
}

rootProject.name = "trailblaze"
include(
  ":docs:generator",
  ":examples",
  ":examples:android-sample-app",
  ":examples:compose-desktop",
  ":examples:ios-contacts",
  ":examples:playwright-native",
  ":examples:wikipedia",
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
  ":trailblaze-trailmap-bundler",
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
