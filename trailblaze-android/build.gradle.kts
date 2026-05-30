plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.dagp)
}

android {
  namespace = "xyz.block.trailblaze.android"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  lint {
    abortOnError = false
  }

  @Suppress("UnstableApiUsage")
  testOptions {
    unitTests.all {
      it.useJUnitPlatform()
    }
  }
}

dependencies {
  api(project(":trailblaze-common"))
  // On-device QuickJS tool runtime. `AndroidTrailblazeRule.quickjsToolBundles` takes
  // `McpServerConfig` and the launcher reads `script:` paths via the supplied resolver.
  // `api` exposes `BundleSource` / `AndroidAssetBundleSource` so consumers wiring custom
  // resolvers can reach those types without a separate declaration. See the
  // `:trailblaze-quickjs-tools` README for the runtime overview.
  api(project(":trailblaze-quickjs-tools"))

  api(libs.androidx.uiautomator)
  api(libs.ktor.client.okhttp)
  // Folded in from `:trailblaze-accessibility` (api there → preserves the same version
  // pinning for downstream consumers; ktor 3.3.3 vs the 3.2.2 they'd otherwise resolve to
  // when this is dropped). No accessibility code uses these directly — they're here to keep
  // the resolved-classpath stable across the merge.
  api(libs.ktor.client.content.negotiation.jvm)
  api(libs.ktor.utils.jvm)
  api(libs.ktor.events.jvm)
  api(libs.ktor.serialization.jvm)
  api(libs.kotlinx.serialization.core)
  api(libs.junit)
  api(libs.maestro.orchestra.models) { isTransitive = false }
  api(libs.maestro.utils) { isTransitive = false }
  api(libs.maestro.client) { isTransitive = false }
  api(libs.koog.agents.tools)
  api(libs.koog.prompt.llm)

  implementation(project(":trailblaze-agent"))
  implementation(project(":trailblaze-tracing"))
  implementation(libs.ktor.client.core.jvm)
  implementation(libs.maestro.orchestra) { isTransitive = false }
  implementation(libs.androidx.test.monitor)
  implementation(libs.okhttp)
  implementation(libs.okio)
  implementation(libs.slf4j.api)
  implementation(libs.koog.prompt.executor.clients)
  implementation(libs.koog.prompt.executor.openai)
  implementation(libs.koog.prompt.executor.openrouter)
  implementation(libs.koog.prompt.executor.ollama)
  // Koog 1.0.0 stopped leaking Ktor types through the LLM client modules. On-device code
  // wraps our customized Ktor `HttpClient` (TLS / reverse-proxy / token-capture interceptor)
  // in `KtorKoogHttpClient.Factory`, so we need an explicit dep here.
  implementation(libs.koog.http.client.ktor)
  implementation(libs.kotlinx.datetime)

  implementation(libs.ktor.http)
  implementation(libs.coroutines)
  implementation(libs.kotlinx.serialization.json)

  runtimeOnly(libs.coroutines.android)

  // Unit-test deps for the accessibility-side tests folded in from `:trailblaze-accessibility`
  // (`useJUnitPlatform` is already enabled above for this module).
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit5.jupiter.engine)
}

dependencyGuard {
  configuration("debugRuntimeClasspath") {
    modules = true
  }
}
