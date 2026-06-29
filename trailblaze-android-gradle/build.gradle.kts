import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar

plugins {
  `java-gradle-plugin`
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
}

gradlePlugin {
  // The public-facing OSS plugin id. External consumers reach this via:
  //   plugins { id("xyz.block.trailblaze.android-gradle") version "..." }
  // The `xyz.block.trailblaze.*` namespace mirrors the Maven group used for every published
  // OSS Trailblaze artifact, so the plugin marker resolution from Maven Central follows the
  // normal `<id>:<id>.gradle.plugin:<version>` shape with no extra repository configuration.
  plugins {
    create("android-gradle") {
      id = "xyz.block.trailblaze.android-gradle"
      implementationClass = "TrailblazeAndroidGradlePlugin"
      displayName = "Trailblaze Android Gradle plugin"
      description =
        "Generates Android JUnit @Test shells from .trail.yaml files under " +
          "src/androidTest/assets/trails/<ClassName>/, retiring the pure-boilerplate hand-written shells."
    }
  }
}

dependencies {
  // Plugin runtime: only Gradle APIs (provided by `java-gradle-plugin`) and the Kotlin stdlib
  // (provided by `kotlin.jvm`). The shell renderer is plain `buildString`-style text emission;
  // no KotlinPoet, no kaml, no serialization — the trail YAML files are scanned by filename
  // only, never opened.

  testImplementation(kotlin("test"))
  testImplementation(kotlin("test-junit"))
  // Gradle TestKit — runs the plugin against an isolated fixture project and asserts on its
  // task wiring + emitted source, complementing the pure renderer unit tests.
  testImplementation(gradleTestKit())
}

// Use the `vanniktech.maven.publish.base` flavour (matches the rest of `opensource/`) and
// configure for a Gradle plugin: this publishes the plugin's own `jar` artifact plus the
// `<plugin-id>.gradle.plugin` marker artifact that `pluginManagement` resolves from. Sources
// jar is on; javadoc jar is off because the only public API is the plugin's id + extension
// (documented in this module's README).
mavenPublishing {
  publishToMavenCentral()
  // Signing only kicks in when signing credentials are present (`signing.signingInMemoryKey`
  // etc.). `build` / `check` still work in CI without secrets — only the `publish` task
  // requires them. Matches the posture every other vanniktech-published module in this repo
  // takes via `opensource/build.gradle.kts`'s subprojects block.
  signAllPublications()

  configure(
    GradlePlugin(
      javadocJar = JavadocJar.None(),
      sourcesJar = true,
    ),
  )

  pom {
    url.set("https://www.github.com/block/trailblaze")
    name.set("trailblaze-android-gradle")
    description.set(
      "Trailblaze's Android Gradle plugin — auto-generates Android JUnit shells from Trailblaze .trail.yaml files for AndroidJUnitRunner discovery.",
    )
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
}

tasks.named<Test>("test") {
  useJUnit()
}
