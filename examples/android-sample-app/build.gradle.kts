plugins {
  id("com.android.application")
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.compose.compiler)
  id("trailblaze.bundle")
}

// Materialize per-trailmap `client.d.ts` + workspace `.trailblaze/sdk/` for IDE autocomplete
// on every `./gradlew build` (#3210). `bundleEnabled = false` because this trailmap still
// ships a legacy `sampleapp_writeArtifact.js` host-tool — see `TrailblazeBundleExtension`
// kdoc for the full rationale.
trailblazeBundle {
  trailmapsDir.set(layout.projectDirectory.dir("trails/config/trailmaps"))
  bundleEnabled.set(false)
}

android {
  namespace = "xyz.block.trailblaze.examples.sampleapp"
  compileSdk = 36
  defaultConfig {
    applicationId = "xyz.block.trailblaze.examples.sampleapp"
    minSdk = 28
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin {
    compilerOptions {
      jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
  }
  buildFeatures { compose = true }
  lint { abortOnError = false }
}

dependencies {
  // Maps to Compose 1.9.0, level with the `ui-test` that `default-android-inprocess` ships. In-process
  // tests load Compose from THIS app, so a version behind the harness fails on device with a
  // NoClassDefFoundError raised inside Compose's own test rule.
  val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
  implementation(composeBom)
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.foundation:foundation")
  // Pinned off the BOM: it maps this to 1.7.8, which the internal artifact mirror does not carry.
  implementation("androidx.compose.material:material-icons-extended:1.7.6")
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.navigation:navigation-compose:2.9.7")
  implementation("androidx.viewpager2:viewpager2:1.1.0")
  implementation("androidx.recyclerview:recyclerview:1.3.2")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.fragment:fragment-ktx:1.8.5")

  // This app declares androidx.startup initializers (see SampleAppInitializers) so the in-process
  // lane has something to prove: that an instrumented process still installs the app under test's
  // ContentProviders and runs its startup init. Declared HERE rather than in the test module on
  // purpose — `default-android-inprocess` names this project as its `targetProjectPath`, so AGP
  // dedupes startup-runtime out of the test APK and the app's single copy is the one that loads.
  implementation("androidx.startup:startup-runtime:1.2.0")

  // Declared, not left to transitive resolution. In-process tests run inside THIS app's process,
  // and because `default-android-inprocess` names this project as its `targetProjectPath`, AGP
  // dedupes anything this app declares out of the test APK — so `kotlinx.coroutines` loads from
  // this APK and the test module's copy never ships. (Dedup is the mechanism, NOT classloader
  // precedence: measured 2026-08-31, the instrumented process's DexPathList lists the test APK
  // BEFORE the app APK, so an undeduped duplicate would resolve to the test copy. See
  // docs/internal/devlog/2026-08-31-inprocess-startup-init-providers-verify.md.) Compose's test rule
  // drives its clock through `kotlinx-coroutines-test`, which only calls into a runtime of its own
  // era — against the 1.7.3 that Compose alone left here, it raised a NoSuchMethodError on device
  // with nothing naming a version conflict. The catalog version is what the test APK resolves.
  implementation(libs.coroutines.android)

  // No `ui-tooling`: it is Layout Inspector support for a fixture app nobody inspects, and it drags
  // in `ui-tooling-data`, which the internal artifact mirror does not carry at this version.
}
