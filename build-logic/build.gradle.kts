plugins {
  `java-gradle-plugin`
  alias(libs.plugins.kotlin.jvm)
  // Required because the shared bundler sources (composed below via `srcDir`) include
  // `@Serializable` classes that are decoded by kaml at task-action time.
  alias(libs.plugins.kotlin.serialization)
}

gradlePlugin {
  plugins {
    create("bundled-config") {
      id = "trailblaze.bundled-config"
      implementationClass = "TrailblazeBundledConfigPlugin"
    }
    create("bundle") {
      id = "trailblaze.bundle"
      implementationClass = "TrailblazeBundlePlugin"
    }
    create("spotless") {
      id = "trailblaze.spotless"
      implementationClass = "TrailblazeSpotlessPlugin"
    }
    create("multi-simulator") {
      id = "trailblaze.multi-simulator"
      implementationClass = "TrailblazeMultiSimulatorPlugin"
    }
    create("author-tool-bundle") {
      id = "trailblaze.author-tool-bundle"
      implementationClass = "TrailblazeAuthorToolBundlePlugin"
    }
    // Stages QuickJS author-tool bundles into an asset tree consumers wire into AGP's
    // `assets.srcDirs(...)`. Centralizes the per-bundle Copy / staging-dir / dependsOn
    // boilerplate so consumers like `:examples:android-sample-app-uitests` and future
    // downstream test modules don't each re-implement the same scaffolding.
    create("quickjs-bundle-assets") {
      id = "trailblaze.quickjs-bundle-assets"
      implementationClass = "TrailblazeQuickjsBundleAssetsPlugin"
    }
    // Owns the `bundleTrailblazeSdk` / `verifyTrailblazeSdkBundle` task pair so the verify
    // logic can be exercised by GradleTestKit (see TrailblazeSdkBundlePluginFunctionalTest).
    // Inlining these tasks in `:trailblaze-scripting-bundle/build.gradle.kts` (where they
    // originally lived) made them unreachable from any fixture project without copy-pasting
    // the task body — and a forked copy would silently miss regressions in the production
    // path. The plugin centralizes the bytes and the test runs that exact plugin code.
    create("sdk-bundle") {
      id = "trailblaze.sdk-bundle"
      implementationClass = "TrailblazeSdkBundlePlugin"
    }
  }
}

// The bundler library lives in the main build at `:trailblaze-pack-bundler`. build-logic is
// an `includedBuild` (composite-build for plugin substitution), which can't take a normal
// `project(":trailblaze-pack-bundler")` dependency on a sibling main-build module. Compose
// the bundler's sources directly via `srcDir` so this build compiles the same Kotlin files
// the main-build module compiles — single source-of-truth, two consumers, no drift. The
// kaml + kotlinx-serialization runtime deps need to be replicated below since the source
// files are shared but the dependency declarations aren't.
sourceSets["main"].kotlin {
  srcDir(file("../trailblaze-pack-bundler/src/main/kotlin"))
  // Exclude the daemon-only `WorkspaceClientDtsGenerator` from build-logic's source set.
  // That class imports koog (`ToolDescriptor`) and trailblaze-models (`PackScriptedToolFile`)
  // — neither of which build-logic has on its lean Gradle-plugin classpath, and pulling them
  // in would inflate every Gradle build's configuration phase. The runtime consumer is
  // `:trailblaze-host`'s daemon startup; build-logic doesn't construct this generator. See
  // the matching `compileOnly` declaration in `:trailblaze-pack-bundler/build.gradle.kts`.
  exclude("**/WorkspaceClientDtsGenerator.kt")
}

dependencies {
  implementation(libs.plugins.spotless.get().let { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
  // YAML: kaml. Used directly by `TrailblazeBundledConfigPlugin` (parses pack manifests
  // via the kaml tree API) and by `TrailblazePackBundler` (the bundler library composed in
  // via shared srcDir above). SnakeYAML is gone — Trailblaze code does not import
  // `org.yaml.snakeyaml.*` directly anywhere; if Maestro pulls it transitively through
  // some other path that's fine, we just don't reach for it ourselves.
  implementation(libs.kaml)
  implementation(libs.kotlinx.serialization.core)

  testImplementation(kotlin("test"))
  testImplementation(kotlin("test-junit"))
  // Gradle TestKit — runs the plugin against an isolated fixture project and asserts on
  // task wiring + outputs. Used by `TrailblazeBundlePluginFunctionalTest` to guard the
  // plugin contract beyond what the unit tests on `TrailblazePackBundler` can cover (build
  // task dependency, extension-property validation, symlink-skipping discovery).
  testImplementation(gradleTestKit())
}

tasks.named<Test>("test") {
  useJUnit()
  // Expose the real TS SDK dir to the functional test JVM. The test reads this property
  // (via `System.getProperty`) to locate `node_modules/.bin/esbuild` and the SDK sources —
  // re-bundling against the real SDK is the only way the byte-comparison verify behavior
  // can be exercised without shipping a separate fixture esbuild + SDK in this repo.
  systemProperty("trailblaze.sdkDir", file("../sdks/typescript").absolutePath)
}
