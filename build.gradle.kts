import com.diffplug.gradle.spotless.SpotlessExtension
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.vanniktech.maven.publish) apply false
  alias(libs.plugins.dokka) apply false
  alias(libs.plugins.spotless) apply false
  alias(libs.plugins.dagp) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.jetbrains.compose.multiplatform) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
}

subprojects {
  apply(plugin = "org.jetbrains.dokka")
}

subprojects
  .forEach {
    it.plugins.withId("com.android.library") {
      it.extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
        defaultConfig {
          System.getenv("OPENAI_API_KEY")?.let { apiKey ->
            testInstrumentationRunnerArguments["OPENAI_API_KEY"] = apiKey
          }
          testInstrumentationRunnerArguments["trailblaze.logs.endpoint"] =
            rootProject.property("trailblaze.logs.endpoint")
              ?.toString() ?: "https://10.0.2.2:8443"
          testInstrumentationRunnerArguments["trailblaze.ai.enabled"] =
            rootProject.findProperty("trailblaze.ai.enabled")?.toString() ?: "true"
        }
      }
    }

    it.afterEvaluate {
      if (plugins.hasPlugin("com.diffplug.spotless")) {
        it.extensions.getByType(SpotlessExtension::class.java).apply {
          kotlin {
            target("**/*.kt", "**/*.kts")
            targetExclude("**/dependencies/*.txt")
            ktlint("1.5.0").editorConfigOverride(
              mapOf(
                "indent_style" to "space", // match IntelliJ indent
                "indent_size" to "2", // match IntelliJ indent
                "ktlint_standard_indent" to "2", // match IntelliJ indent
                "ij_kotlin_imports_layout" to "*,java.**,javax.**,kotlin.**,^", // match IntelliJ import order
              ),
            )
          }
        }
      }

      val hasPublishPlugin = it.plugins.hasPlugin("com.vanniktech.maven.publish.base")
      if (hasPublishPlugin) {
        it.extensions.getByType(MavenPublishBaseExtension::class.java).also { publishing ->
          publishing.pom {
            url.set("https://www.github.com/block/trailblaze")
            name = "trailblaze"
            description = "trailblaze"
            licenses {
              license {
                name.set("The Apache Software License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
              }
            }
            scm {
              url.set("https://www.github.com/block/trailblaze")
              connection.set("scm:git:git://github.com/block/trailblaze.git")
              developerConnection.set("scm:git:ssh://git@github.com/block/trailblaze.git")
            }
            developers {
              developer {
                name.set("Block, Inc.")
                url.set("https://github.com/block")
              }
            }
          }

          if (plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
            publishing.configure(
              KotlinJvm(
                sourcesJar = true,
                javadocJar = JavadocJar.None(),
              )
            )
          }
          if (plugins.hasPlugin("com.android.library")) {
            publishing.configure(
              AndroidSingleVariantLibrary(
                sourcesJar = true,
                publishJavadocJar = false,
              )
            )
          }
          if (plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
            publishing.configure(
              KotlinMultiplatform(
                sourcesJar = true,
                javadocJar = JavadocJar.None(),
              )
            )
          }
        }
      }
    }
  }
