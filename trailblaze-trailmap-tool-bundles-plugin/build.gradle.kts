import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  `java-gradle-plugin`
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
}

gradlePlugin {
  plugins {
    // Pre-compiles a trailmap's in-process scripted tools (the `*.ts` under a trailmap's
    // `tools/`) into QuickJS `.bundle.js` files and stages them as test-APK assets, so a
    // target's `target.tools:` scripted tools are dispatchable on the on-device instrumentation
    // runner — the device has no bun/esbuild to bundle them live.
    create("trailmap-tool-bundles") {
      id = "xyz.block.trailblaze.trailmap-tool-bundles"
      implementationClass = "TrailblazeTrailmapToolBundlesPlugin"
    }
  }
}

dependencies {
  testImplementation(kotlin("test"))
  testImplementation(kotlin("test-junit"))
  // Gradle TestKit — runs the plugin against an isolated fixture project and asserts on
  // task wiring + outputs. Used by `TrailblazeTrailmapToolBundlesPluginFunctionalTest` to
  // guard the contract beyond what the pure unit tests on the bundler library cover.
  testImplementation(gradleTestKit())
}

tasks.named<Test>("test") {
  useJUnit()
  // Expose the real TS SDK dir to the functional test JVM. The test reads this property
  // (via `System.getProperty`) to locate the wrapper template and (when present) the
  // SDK's node_modules/.bin/esbuild — re-bundling against the real SDK is the only way the
  // end-to-end behavior can be exercised without shipping a separate fixture esbuild + SDK
  // in this repo.
  systemProperty("trailblaze.sdkDir", file("../sdks/typescript").absolutePath)
}

// The included build doesn't sit under the parent's `subprojects {}` so the parent's
// publish wiring at ../build.gradle.kts:60-130 doesn't apply here. Replicate the relevant
// `vanniktech.maven.publish` configuration inline so this module gets the same coordinates,
// POM metadata, and signing as the rest of the OSS surface.
mavenPublishing {
  publishToMavenCentral()
  signAllPublications()
  configure(
    KotlinJvm(
      sourcesJar = true,
      javadocJar = JavadocJar.None(),
    ),
  )
  pom {
    url.set("https://www.github.com/block/trailblaze")
    name.set("trailblaze")
    description.set("trailblaze")
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

// Match the parent build's bytecode level so consumers on JDK 17 can load the plugin jar.
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}
