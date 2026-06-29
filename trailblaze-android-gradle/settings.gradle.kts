// Repository URLs are resolved by name rather than written as literals here, so this file pins
// no specific registry host. The public Maven URLs in this build's `gradle.properties`
// (`mavenRepo*`) are the defaults; a build may redirect resolution to a private mirror by
// exporting the matching `TRAILBLAZE_GRADLE_REPO_*` environment variable, without editing this
// file. This plugin is an included build (mirrors `build-logic`), so it reads its OWN
// `gradle.properties` (next to this file) for the defaults — duplicated from the root
// `opensource/gradle.properties` on purpose.
//
// `mavenRepo` prefers the env var, then the Gradle property; a blank value from either source is
// treated as unset (an exported-but-empty env var would otherwise become an invalid `uri("")`),
// and a clear error is raised if neither source provides a URL. It is a block-local lambda
// because Gradle evaluates `pluginManagement {}` in isolation and can't see top-level helpers.
pluginManagement {
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
    maven { url = uri(mavenRepo("TRAILBLAZE_GRADLE_REPO_PLUGINS", "mavenRepoPlugins")) }
  }
  // Pull in the same version catalog the rest of the opensource/ tree uses, so the Kotlin
  // version + vanniktech-maven-publish version this plugin compiles against stay in lockstep
  // with the modules that consume it.
  versionCatalogs {
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
    }
  }
}

rootProject.name = "trailblaze-android-gradle"
