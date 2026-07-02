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
          "src/androidTest/assets/trails/<ClassName>/, retiring the pure-boilerplate hand-written " +
          "shells, and (via the optional trailmap { } block) pre-compiles a trailmap's TypeScript " +
          "scripted tools into QuickJS bundles staged as androidTest assets."
    }
  }
}

dependencies {
  // Plugin runtime: only Gradle APIs (provided by `java-gradle-plugin`) and the Kotlin stdlib
  // (provided by `kotlin.jvm`). The shell renderer is plain `buildString`-style text emission;
  // no KotlinPoet, no kaml, no serialization — the trail YAML files are scanned by filename
  // only, never opened.

  // No AGP dependency, not even `compileOnly` — this plugin's whole reason for existing is AGP
  // integration (auto-wiring the JUnit-shell codegen output and the trailmap scripted-tool
  // bundles into AGP's `androidTest` source set), but it reaches AGP's `sourceSets` by reflection
  // (see `wireAgpSourceSets` in `TrailblazeAndroidGradlePlugin.kt`) rather than a typed
  // `com.android.build.gradle.BaseExtension` reference — the same pattern `gradle/merged-trails
  // .gradle.kts` already uses for the identical reason: stay version-agnostic across whatever AGP
  // a consumer happens to be on, and side-step the Gradle TestKit classloader isolation a
  // `compileOnly` AGP dependency runs into when a fixture ALSO resolves a real AGP separately.

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
      "Trailblaze's Android Gradle plugin — auto-generates Android JUnit shells from Trailblaze " +
        ".trail.yaml files for AndroidJUnitRunner discovery, and (optionally) pre-compiles a " +
        "trailmap's TypeScript scripted tools into QuickJS bundles staged as androidTest assets.",
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
  // Expose the real TS SDK dir to the functional/unit test JVM — the trailmap-bundling tests
  // (ported from the retired `xyz.block.trailblaze.trailmap-tool-bundles` plugin) read this
  // property (via `System.getProperty`) to locate the wrapper template and (when present) the
  // SDK's node_modules/.bin/esbuild — re-bundling against the real SDK is the only way the
  // end-to-end behavior can be exercised without shipping a separate fixture esbuild + SDK in
  // this repo.
  systemProperty("trailblaze.sdkDir", file("../sdks/typescript").absolutePath)
}

// Match the parent build's bytecode level so consumers on JDK 17 can load the plugin jar. Carried
// over from the retired `trailblaze-trailmap-tool-bundles-plugin` module, whose scripted-tool
// bundling logic now lives here.
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
