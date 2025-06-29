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
  ":trailblaze-android",
  ":trailblaze-android-ondevice-mcp",
  ":trailblaze-common",
  ":trailblaze-agent",
  ":trailblaze-report",
  ":trailblaze-server",
  ":trailblaze-yaml",
)
