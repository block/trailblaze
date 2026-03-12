@file:Suppress("UnstableApiUsage")

pluginManagement {
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
  ":trailblaze-accessibility",
  ":trailblaze-accessibility-app",
  ":trailblaze-agent",
  ":trailblaze-android",
  ":trailblaze-android-ondevice-mcp",
  ":trailblaze-capture",
  ":trailblaze-common",
  ":trailblaze-desktop",
  ":trailblaze-host",
  ":trailblaze-models",
  ":trailblaze-compose",
  ":trailblaze-playwright",
  ":trailblaze-ui",
  ":trailblaze-report",
  ":trailblaze-server",
  ":trailblaze-tracing",
)
