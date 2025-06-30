@file:Suppress("UnstableApiUsage")

pluginManagement {
  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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
  ":trailblaze-models",
  ":trailblaze-ui",
  ":trailblaze-report",
  ":trailblaze-server",
  ":trailblaze-yaml",
)
