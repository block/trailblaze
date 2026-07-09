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
  alias(libs.plugins.dagp) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.jetbrains.compose.multiplatform) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
}

subprojects {
  apply(plugin = "org.jetbrains.dokka")

  // Unit tests must never touch the developer's real Trailblaze state or a live daemon:
  // without this, a test that reaches the CLI's connect-or-start path resolves the installed
  // `trailblaze` from PATH and spawns detached daemon JVMs on the default port that outlive
  // the test run, and a test that merely *connects* could drive (or shut down) a real daemon
  // on 52525. A test task that deliberately drives a live daemon opts out by overriding these
  // (see :trailblaze-server:integrationTest).
  // NOTE: deliberately NOT setting TRAILBLAZE_HOME here — it outranks the per-test
  // `user.home` redirect (UserHomeRule) that state-dir tests rely on, so a shared
  // build-dir home would leak pin/config state across test methods.
  tasks.withType<Test>().configureEach {
    // Hard-refuse daemon auto-start (honored by cliTryStartDaemon / McpProxy.startDaemon).
    environment("TRAILBLAZE_DISABLE_DAEMON_AUTOSTART", "1")
    // Steer default-port resolution to a port no real daemon uses, so an accidental
    // connect fails fast instead of reaching a developer's live daemon.
    environment("TRAILBLAZE_PORT", "52995")
    // Point `user.home` at the build dir so tests read a fresh ~/.trailblaze instead of the
    // developer's real one. Without this, a persisted non-default `serverPort` in the real
    // config outranks the TRAILBLAZE_PORT env above (port precedence: persisted → env) and
    // tests could still reach a live daemon on a custom port. Also keeps test writes (config,
    // shell pins) out of the real home. UserHomeRule still overrides per-method on top.
    systemProperty("user.home", layout.buildDirectory.dir("test-user-home").get().asFile.absolutePath)
    // Test workers are plain JVMs: any test that touches AWT (image decode, report rendering)
    // would otherwise initialize a regular macOS GUI app — which macOS activates, stealing
    // keyboard focus from whatever the developer is typing, once per worker. Tests never need
    // a real display (the `trailblaze.test.hasDisplay` seam exists to pin display-dependent
    // logic), so force headless AWT in every worker.
    systemProperty("java.awt.headless", "true")
  }

  // Target Java 17 bytecode but use whatever JDK is installed
  plugins.withId("org.jetbrains.kotlin.jvm") {
    tasks.withType<JavaCompile>().configureEach {
      options.release = 17
    }
  }
  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
      jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
  }

  // For Android projects, configure compile and target compatibility
  plugins.withId("com.android.library") {
    configure<com.android.build.gradle.LibraryExtension> {
      compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
      }
    }
  }

  plugins.withId("com.android.application") {
    configure<com.android.build.gradle.AppExtension> {
      compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
      }
    }
  }
}

// The OSS release workflow on `block/trailblaze` runs
// `./gradlew publishAllPublicationsToMavenCentralRepository` from this root. That command
// finds matching tasks across all subprojects in this build, but composite-included builds
// (registered via `settings.gradle.kts`'s `pluginManagement.includeBuild(...)`) are isolated
// — their publish tasks must be delegated explicitly or external consumers never receive the
// published artifact + plugin marker. Register a root-level aggregator per included plugin
// build so the existing release command transitively picks them up.
val trailblazeAndroidGradlePluginPublishMavenCentral =
  tasks.register("trailblazeAndroidGradlePluginPublishMavenCentral") {
    group = "publishing"
    description =
      "Publishes the included trailblaze-android-gradle build to Maven Central."
    dependsOn(
      gradle.includedBuild("trailblaze-android-gradle")
        .task(":publishAllPublicationsToMavenCentralRepository"),
    )
  }
tasks.matching { it.name == "publishAllPublicationsToMavenCentralRepository" }.configureEach {
  dependsOn(trailblazeAndroidGradlePluginPublishMavenCentral)
}

// Apply shared dependency-resolution forces (version pins) so every configuration resolves the
// same versions and dependency-guard baselines stay consistent.
apply(from = "gradle/dependency-resolution.gradle.kts")

// Apply shared git version computation
apply(from = "gradle/git-version.gradle.kts")

subprojects
  .forEach {
    it.plugins.withId("com.android.library") {
      it.extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
        defaultConfig {
          rootProject.findProperty("trailblaze.reverseProxy")?.let { reverseProxy ->
            testInstrumentationRunnerArguments["trailblaze.reverseProxy"] = reverseProxy.toString()
          }
          rootProject.findProperty("trailblaze.aiEnabled")?.let { aiEnabled ->
            testInstrumentationRunnerArguments["trailblaze.aiEnabled"] = aiEnabled.toString()
          }
        }
      }
    }

    it.afterEvaluate {
      if (it.plugins.hasPlugin("com.vanniktech.maven.publish.base")) {
        it.extensions.getByType(MavenPublishBaseExtension::class.java).also { publishing ->
          publishing.publishToMavenCentral()
          publishing.signAllPublications()
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

          if (plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
            publishing.configure(
              KotlinMultiplatform(
                sourcesJar = true,
                javadocJar = JavadocJar.None(),
              )
            )
          } else if (plugins.hasPlugin("com.android.library")) {
            publishing.configure(
              AndroidSingleVariantLibrary(
                sourcesJar = true,
                publishJavadocJar = false,
              )
            )
          }
        }
      }
    }
  }

