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
  ":trailblaze-agent",
  ":trailblaze-android",
  ":trailblaze-android-ondevice-mcp",
  ":trailblaze-common",
  ":trailblaze-desktop",
  ":trailblaze-host",
  ":trailblaze-models",
  ":trailblaze-ui",
  ":trailblaze-report",
  ":trailblaze-server",
  ":trailblaze-tracing",
  ":trailblaze-yaml",
)
