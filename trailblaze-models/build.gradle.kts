import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.vanniktech.maven.publish)
  id("trailblaze.bundled-config")
}

configurations.all {
  exclude(group = "ai.koog", module = "prompt-executor-bedrock-client")
  exclude(group = "ai.koog", module = "prompt-executor-dashscope-client")
  exclude(group = "ai.koog", module = "prompt-executor-deepseek-client")
  exclude(group = "ai.koog", module = "prompt-executor-mistralai-client")
}

android {
  namespace = "xyz.block.trailblaze.models"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  // KMP commonMain/resources/ are not automatically included as Java resources on Android.
  // Explicitly add them so trailblaze-config/{providers,toolsets,targets}/*.yaml files are
  // bundled into the AAR/APK and also exposed as Android assets. Android's classloader cannot
  // enumerate resource directories, so the classpath fallback alone is insufficient in an
  // on-device instrumentation-test context — AssetManagerConfigResourceSource needs the
  // configs reachable via the app's assets.
  sourceSets.getByName("main") {
    resources.srcDirs("src/commonMain/resources")
    assets.srcDirs("src/commonMain/resources")
  }

}

kotlin {
  // Apply the default hierarchy template explicitly
  applyDefaultHierarchyTemplate()

  if (findProperty("trailblaze.wasm")?.toString()?.toBoolean() != false) {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
      browser()
      compilerOptions {
        // Enable qualified names in Kotlin/Wasm to support KClass.qualifiedName used in OtherTrailblazeToolSerializer
        // Required since Kotlin 2.2.20 where qualifiedName usage in Wasm became a compile error by default
        freeCompilerArgs.add("-Xwasm-kclass-fqn")
      }
    }
  }

  androidTarget {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_17
    }
  }

  jvm {
    this.compilerOptions {
      jvmTarget = JvmTarget.JVM_17
    }
  }

  sourceSets {
    commonMain.dependencies {
      implementation(libs.coroutines)
      implementation(libs.kaml)
      implementation(libs.koog.agents.tools)
      implementation(libs.koog.prompt.model)
      implementation(libs.koog.prompt.executor.anthropic)
      implementation(libs.koog.prompt.executor.clients)
      implementation(libs.koog.prompt.executor.google)
      implementation(libs.koog.prompt.executor.llms.all)
      implementation(libs.koog.prompt.executor.openai)
      implementation(libs.koog.prompt.executor.openrouter)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlin.reflect)
      implementation(libs.kotlinx.serialization.core)
    }

    // Shared source set for JVM and Android (reflection-based code not available on wasmJs)
    val jvmAndAndroid by creating {
      dependsOn(commonMain.get())
    }

    jvmMain {
      dependsOn(jvmAndAndroid)
    }

    androidMain {
      dependsOn(jvmAndAndroid)
      dependencies {
        // AssetManager-backed ConfigResourceSource resolves the Android Context via
        // InstrumentationRegistry. Trailblaze only runs on Android under instrumentation.
        api(libs.androidx.test.monitor)
      }
    }

    jvmTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.serialization.json)
    }
  }
}

dependencyGuard {
  configuration("jvmMainRuntimeClasspath")
}

// Compile bundled framework packs (clock, contacts, wikipedia) into materialized flat
// `targets/<id>.yaml` files at build time. Library packs (`trailblaze`, no `target:`)
// contribute defaults but produce no target output. The generated targets are checked in
// alongside the pack sources via a regenerate-and-commit workflow, so the JAR ships
// pre-resolved targets that the daemon's existing flat-target discovery reads directly
// without any pack-aware runtime path. The `verifyBundledTrailblazeConfig` task is wired
// into `:check` and fails CI if a pack edit landed without a corresponding regen.
bundledTrailblazeConfig {
  packsDir.set(layout.projectDirectory.dir("src/commonMain/resources/trailblaze-config/packs"))
  targetsDir.set(layout.projectDirectory.dir("src/commonMain/resources/trailblaze-config/targets"))
  regenerateCommand.set("./gradlew :trailblaze-models:generateBundledTrailblazeConfig")
}
