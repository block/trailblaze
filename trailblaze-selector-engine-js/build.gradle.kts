// Compiles the daemon's selector engine — TrailblazeNodeSelectorGenerator +
// TrailblazeNodeSelectorResolver and their closure — to browser JavaScript, so the
// interactive HTML report's UI Inspector can compute selector suggestions with the exact
// Kotlin logic the daemon records with. Reuse, not a TypeScript re-implementation.
//
// This module owns NO selector logic. Its commonMain source set points directly at
// `:trailblaze-models`' commonMain source directory, filtered down to the self-contained
// selector-engine closure (generator family + resolver + minimizer + quality + node model +
// analyzer + escape utils — pure Kotlin, kotlinx-serialization only). The single source of
// truth stays in `:trailblaze-models`; this module is a second compilation of those files,
// to JS. jsMain adds only the `@JsExport` boundary and the platform `actual` for
// `selectorPatternRegexMatches` (ECMAScript regex translation).
//
// Why not add a js(IR) target to `:trailblaze-models` itself: that module's commonMain also
// carries Koog / Ktor / kaml / coroutines (all of which do publish js variants — verified),
// so a js target there compiles the full LLM/tool/yaml model surface to JS on every
// contributor build (~75s vs ~3s for this module) and changes the published target set of a
// Maven Central artifact. The include-list below is the cheaper liability: if the selector
// closure grows a new file, this module fails to compile loudly and the fix is a one-line
// addition here.
//
// Deliberately NO `browser()` environment and NO webpack: the Kotlin/JS webpack pipeline
// drags in the npm/Node toolchain and (under Kotlin 2.4's npm default) rewrites the committed
// `kotlin-js-store` lockfile. Bundling is done by `bun` (Hermit-pinned, the repo's only JS
// toolchain) over the compiler's DCE'd production ESM output — see `bundleSelectorEngine`.
import java.io.File

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
}

kotlin {
  js {
    // Deliberately NO execution environment (`browser()` / `nodejs()`): either one wires the
    // Kotlin npm/Node toolchain (kotlinNpmInstall + kotlinStorePackageLock + a Node download)
    // into this module's `check` for test tasks that have no sources. The compiled output is
    // environment-agnostic (no DOM, no Node APIs); JS-side tests run under bun via the
    // verifySelectorEngineParity task, and the browser consumes the bun bundle.
    // Explicit output name: the default is `<rootProject.name>-<project.name>`, which would
    // couple the compiled filename (consumed by bundleSelectorEngine below) to whichever
    // root build includes this module.
    outputModuleName.set("trailblaze-selector-engine")
    // ES-module output so `bun build` can bundle + minify the production compile directly.
    useEsModules()
    binaries.executable()
  }

  sourceSets {
    commonMain {
      // Compile the selector engine's source files straight out of :trailblaze-models.
      kotlin.srcDir("../trailblaze-models/src/commonMain/kotlin")
      // Explicit allow-list: the transitive closure of TrailblazeNodeSelectorGenerator +
      // TrailblazeNodeSelectorResolver + TrailblazeSelectorAnalyzer. Everything here imports
      // only kotlinx.serialization and xyz.block.trailblaze.util.SelectorEscape.
      kotlin.include(
        "xyz/block/trailblaze/api/DriverNodeDetail.kt",
        "xyz/block/trailblaze/api/IosIdentityCoalescing.kt",
        "xyz/block/trailblaze/api/MatchDescriptor.kt",
        "xyz/block/trailblaze/api/MatchDescriptorBuilder.kt",
        "xyz/block/trailblaze/api/SelectorPatternRegex.kt",
        "xyz/block/trailblaze/api/SelectorTemplating.kt",
        "xyz/block/trailblaze/api/TrailblazeElementSelector.kt",
        "xyz/block/trailblaze/api/TrailblazeElementSelectorElementTrait.kt",
        "xyz/block/trailblaze/api/TrailblazeElementSelectorSizeSelector.kt",
        "xyz/block/trailblaze/api/TrailblazeNode.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelector.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelectorGenerator.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelectorGeneratorAndroidAccessibility.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelectorGeneratorAndroidMaestro.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelectorGeneratorCompose.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelectorGeneratorEnumeration.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelectorGeneratorHelpers.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelectorGeneratorIosAxe.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelectorGeneratorIosMaestro.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelectorGeneratorWeb.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelectorMinimizer.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelectorQuality.kt",
        "xyz/block/trailblaze/api/TrailblazeNodeSelectorResolver.kt",
        "xyz/block/trailblaze/api/TrailblazeSelectorAnalysis.kt",
        "xyz/block/trailblaze/api/TrailblazeSelectorAnalyzer.kt",
        "xyz/block/trailblaze/devices/TrailblazeDeviceClassifier.kt",
        "xyz/block/trailblaze/devices/TrailblazeDevicePlatform.kt",
        "xyz/block/trailblaze/util/SelectorEscape.kt",
      )
      dependencies {
        implementation(libs.kotlinx.serialization.json)
      }
    }
  }
}

dependencyGuard {
  configuration("jsRuntimeClasspath")
}

// Bundle + minify the DCE'd production ESM output into a single IIFE that installs the
// exported entry points at `globalThis.TrailblazeSelectorEngine`. The production-compile
// task graph contains no npm/node/webpack tasks, so this whole chain needs only `bun` on
// PATH — same posture as `bundleScriptedToolAnalyzerShim` in :trailblaze-models. The report
// packaging consumes this file (gz+base64 side-channel, evaluated on first inspector open).
val bundleSelectorEngine by tasks.registering(Exec::class) {
  group = "trailblaze"
  description = "Bundles the Kotlin/JS selector engine into a single minified IIFE via bun."
  dependsOn(tasks.named("compileProductionExecutableKotlinJs"))
  val compiledDir = layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin")
  val entryFile = layout.buildDirectory.file("selector-engine-entry/entry.mjs")
  val outFile = layout.buildDirectory.file("dist/trailblaze-selector-engine.min.js")
  inputs.dir(compiledDir)
  outputs.file(outFile)
  // Skip cleanly when `bun` isn't on PATH — never break an unrelated build over a payload
  // downstream consumers degrade around (mirrors bundleScriptedToolAnalyzerShim). Inlined
  // rather than a shared script helper: the configuration cache can't serialize references
  // to Gradle script objects captured by task closures.
  onlyIf {
    System.getenv("PATH")?.split(File.pathSeparator)
      ?.any { File(it, "bun").canExecute() } == true
  }
  doFirst {
    val entry = entryFile.get().asFile
    entry.parentFile.mkdirs()
    // Matches the target's explicit `outputModuleName` above (decoupled from rootProject.name).
    val mainModule = compiledDir.get().asFile.resolve("trailblaze-selector-engine.mjs")
    entry.writeText(
      """
      import * as engine from ${'"'}${mainModule.absolutePath}${'"'};
      globalThis.TrailblazeSelectorEngine = engine;
      """.trimIndent(),
    )
    outFile.get().asFile.parentFile.mkdirs()
  }
  commandLine(
    "bun", "build", entryFile.get().asFile.absolutePath,
    "--bundle", "--minify", "--format=iife",
    "--outfile", outFile.get().asFile.absolutePath,
  )
  doLast {
    val out = outFile.get().asFile
    require(out.isFile && out.length() > 0L) {
      "bundleSelectorEngine: `bun build` produced no usable output at $out."
    }
  }
}

// Cross-platform parity gate, wired into `check`: runs the compiled bundle under bun against
// (a) the shared matcher-parity fixture — the same behavioral contract MatcherParityFixturesTest
// (Kotlin/JVM) and matcher-parity.test.ts (TS matcher) consume — and (b) the committed golden
// corpus in `parity/expected-analysis.txt`, which the REAL daemon classes produce via
// SelectorEngineParityGoldenTest in :trailblaze-models jvmTest. Green here + green there proves
// the JS compile behaves byte-identically to the daemon's JVM path.
val verifySelectorEngineParity by tasks.registering(Exec::class) {
  group = "verification"
  description = "Runs the Kotlin/JS selector engine's parity tests under bun."
  dependsOn(bundleSelectorEngine)
  workingDir = layout.projectDirectory.asFile
  inputs.dir(layout.projectDirectory.dir("parity"))
  inputs.dir(layout.projectDirectory.dir("src/typescript"))
  inputs.files(layout.buildDirectory.file("dist/trailblaze-selector-engine.min.js"))
  inputs.file(layout.projectDirectory.file("../sdks/typescript/src/matcher/matcher-parity-fixtures.json"))
  // bun test writes no artifact; declare a marker so Gradle can track up-to-dateness.
  val marker = layout.buildDirectory.file("parity/verified.marker")
  outputs.file(marker)
  onlyIf {
    System.getenv("PATH")?.split(File.pathSeparator)
      ?.any { File(it, "bun").canExecute() } == true
  }
  commandLine("bun", "test", "parity/engine-parity.test.ts")
  doLast {
    marker.get().asFile.parentFile.mkdirs()
    marker.get().asFile.writeText("ok")
  }
}

tasks.named("check") {
  dependsOn(verifySelectorEngineParity)
}
