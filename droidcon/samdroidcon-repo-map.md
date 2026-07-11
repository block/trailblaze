# Trailblaze repo map

*A "where to find things" reference for the Block open-source Trailblaze repo (clone of github.com/block/trailblaze). All paths are repo-relative.*

Trailblaze is natural-language, replayable device control for coding agents across iOS, Android, and web. Every session becomes a portable `.trail.yaml` that CI can replay deterministically with no LLM at replay time. It is a Kotlin Multiplatform + Gradle monorepo (JVM host, Android on-device, WASM report viewer, Compose desktop UI) plus a TypeScript SDK for authoring typed scripted tools.

Root landmarks: `README.md` (the pitch), `settings.gradle.kts` (module list), `mkdocs.yaml` (docs site), `install.sh` (release installer), `trailblaze` (dev launcher script), `build-logic/` (custom Gradle convention plugins: `trailblaze.bundle`, `trailblaze.bundled-config`, `trailblaze.spotless`, `trailblaze.multi-simulator`).

---

## 1. Gradle modules

Paths under each are `src/<sourceSet>/{kotlin,java}/xyz/block/trailblaze/…`. Package root is `xyz.block.trailblaze` unless noted.

### Core model & config
- **`trailblaze-models`** — Pure KMP data models (commonMain, also wasmJs/android/jvm). The unified trail model (`yaml/unified/UnifiedTrail.kt`, `UnifiedTrailStep.kt`, `UnifiedTrailConfig.kt`, `UnifiedTrailAdapter.kt`, `UnifiedTrailEmitter.kt`), legacy trail YAML (`yaml/TrailYamlItem.kt`, `yaml/TrailblazeYaml.kt`), trailmap manifest (`config/project/TrailblazeTrailmapManifest.kt`, `TrailblazeProjectConfig.kt`), waypoint schema (`api/waypoint/WaypointDefinition.kt`, `WaypointCondition.kt`, `WaypointMatchResult.kt`, `ResolvedWaypoint.kt`, `WaypointVariant.kt`), device model (`devices/TrailblazeDriverType.kt`, `TrailblazeDevicePlatform.kt`, `TrailblazeClassifierLineage.kt`, `TrailblazeDeviceClassifier.kt`), screen-state / compact element APIs (`api/ScreenState.kt`, `api/*CompactElementList.kt`, `api/MatchDescriptor.kt`), agent status models (`agent/model/*`), and `logs/client/TrailblazeLog.kt`.
- **`trailblaze-common`** — Shared JVM+Android logic (jvmAndAndroid source set). The Maestro-backed agent base (`MaestroTrailblazeAgent.kt`, `BaseTrailblazeAgent.kt`, `api/TrailblazeAgent.kt`), the "LLM-as-compiler" (`compile/TrailblazeCompiler.kt`), project/workspace config loading (`config/project/TrailblazeProjectConfigLoader.kt`, `TrailblazeTrailmapManifestLoader.kt`, `TrailmapDependencyResolver.kt`, `TrailmapRuntimeRegistryResolver.kt`), the waypoint matcher + loader (`waypoint/WaypointMatcher.kt`, `WaypointLoader.kt`, `WaypointAssertion.kt`, `WaypointRegistryResolver.kt`), built-in tool call definitions (`toolcalls/commands/*`, incl. `RunTrailTool.kt`, `AssertWaypointTrailblazeTool.kt`), YAML tool definitions (`config/YamlDefinedTrailblazeTool.kt`), device driver registry (`devices/DeviceDriver.kt`, `DeviceDriverRegistry.kt`), and the view-matcher engine (`viewmatcher/`).

### Agent
- **`trailblaze-agent`** — The agent loop. `agent/MultiAgentV3Runner.kt` (Mobile-Agent-v3-style multi-agent runner), `TrailblazeRunner.kt`, tool-processing strategies (`SingleToolStrategy.kt`, `MultipleToolStrategy.kt`, `ToolProcessingStrategy.kt`), Koog LLM client glue (`TrailblazeKoogLlmClientHelper.kt`, `KoogLlmRequestData.kt`, `TracingLlmClient.kt`), deterministic replay (`agent/trail/DeterministicTrailExecutor.kt`, `TrailGoalPlanner.kt`), resilience (`agent/blaze/ProgressTracking.kt`, `StaleRefRecovery.kt`), `AgentUiActionExecutor.kt`, `TrailblazeElementComparator.kt`.

### Host (JVM) — the biggest module
- **`trailblaze-host`** — The CLI, daemon route handlers, desktop-app wiring, iOS/web host drivers. Contains the entire CLI command tree (`cli/*Command.kt`), the trail runner daemon routes (`trailrunner/*Routes.kt`, `TrailRunnerEndpoint.kt`, `TrailRunnerRpc.kt`), the waypoint graph builder/viewer (`graph/WaypointGraphBuilder.kt`, `WaypointGraphHtmlRenderer.kt`, `WaypointGraphEndpoint.kt`, `WaypointGraphData.kt`), TypeScript type-check validation (`host/TrailTscValidator.kt`, `WorkspaceTypeScriptSetup.kt`, `PerTrailmapClientDtsEmitter.kt`), iOS driving via AXe (`host/axe/*` — `AxeCli.kt`, `AxeTrailRunner.kt`, `IosAxeTrailblazeAgent.kt`), device services (`host/devices/*`), recording RPC handlers (`host/recording/rpc/*`), the Compose-desktop UI shell (`ui/MainTrailblazeApp.kt`, `ui/TrailblazeDesktopApp.kt`, `ui/tabs/*`), scripted-tool host launchers (`scripting/HostScriptedToolLauncher.kt`, `DaemonScriptedToolBundler.kt`), and trail migration (`migration/UnifiedTrailMigrator.kt`).
- **`trailblaze-server`** — MCP/HTTP server. `logs/server/TrailblazeMcpServer.kt` (Ktor `embeddedServer`; `startStreamableHttpMcpServer`, `startStdioMcpServer`, `startSseMcpServer`), server endpoints (`logs/server/endpoints/*` — CLI exec/run/status, report generation, screenshot/trace ingest), MCP session + device management (`mcp/MultiDeviceSessionManager.kt`, `DeviceClaimRegistry.kt`, `TrailblazeMcpSessionContext.kt`), Koog MCP agent (`mcp/agent/KoogMcpAgent.kt`, `KoogMcpFactory.kt`), and MCP tool sets (`mcp/newtools/*`).
- **`trailblaze-desktop`** — Thin app entry point. `desktop/TrailblazeOpenSourceMain.kt` (`@JvmName("Trailblaze")`, `fun main`) delegates to `TrailblazeCli.run(...)` with the OSS app/config providers; `OpenSourceTrailblazeDesktopApp.kt`, `OpenSourceTrailblazeDesktopAppConfig.kt`. This is the module the launcher runs (`TRAILBLAZE_MODULE=:trailblaze-desktop`).
- **`trailblaze-ui`** — Compose Multiplatform UI (commonMain + jvmMain + wasmJsMain). Trace/view-hierarchy inspector (`InspectViewHierarchyScreenComposable.kt`, `InspectTrailblazeNodeComposable.kt`, `InspectTrailblazeNodeSelectorHelper.kt`) and shared composables (`composables/*`, `desktoputil/*`). Reused by both desktop and the WASM report viewer.

### Platform drivers
- **`trailblaze-android`** — On-device Android instrumentation. Accessibility service driver (`android/accessibility/TrailblazeAccessibilityService.kt`, `AccessibilityTrailRunner.kt`, `AccessibilityTrailblazeAgent.kt`, `AccessibilityNode*.kt`, `TrailblazeNodeMapper.kt`), the JUnit test rule (`android/AndroidTrailblazeRule.kt`), Maestro/UiAutomator driver (`android/maestro/MaestroAndroidUiAutomatorDriver.kt`), on-device LLM client factory, on-device scripted-tool bundle launcher.
- **`trailblaze-compose`** — Compose (desktop/JVM) UI driver: `compose/driver/ComposeTrailblazeAgent.kt`, `ComposeScreenState.kt`, `ComposeSemanticTreeMapper.kt`, RPC transport (`compose/driver/rpc/*`), and Compose tools (`ComposeClickTool.kt`, `ComposeScrollTool.kt`, etc.).
- **`trailblaze-compose-target`** — Build-only support module (a `build.gradle.kts`, no Kotlin source) providing the Compose-target instrumentation surface.
- **`trailblaze-playwright`** — Playwright web driver (native + Electron). `playwright/PlaywrightTrailblazeAgent.kt`, `PlaywrightBrowserManager.kt`, `PlaywrightElectronBrowserManager.kt`, `PlaywrightScreenState.kt`, `PlaywrightAriaSnapshot.kt`, `PlaywrightTrailblazeNodeMapper.kt`, and a large `playwright/tools/PlaywrightNative*Tool.kt` set. README documents parity with the Playwright MCP server.
- **`trailblaze-revyl`** — Integration with the Revyl cloud device service: `revyl/RevylSession.kt`, `RevylCliClient.kt`, `RevylScreenState.kt`, and `revyl/tools/RevylNative*Tool.kt`.

### Scripted-tools stack
- **`trailblaze-quickjs-tools`** — The MCP-free **in-process QuickJS runtime**. `quickjs/tools/QuickJsToolHost.kt` (engine wrapper; `connect`, `callTool`), `QuickJsToolBundleLauncher.kt`, `QuickJsToolDescriptor.kt`, `BundleSource.kt`/`AndroidAssetBundleSource.kt`. This is what `AndroidTrailblazeRule` runs on-device.
- **`trailblaze-scripting`** — In-process scripted-tool launcher over QuickJS (`scripting/TrailblazeScriptEngine.kt`, `InProcessScriptedToolLauncher.kt`, `ScriptTrailblazeTool.kt`). Sibling of `-subprocess`.
- **`trailblaze-scripting-subprocess`** — Out-of-process **bun subprocess** runtime speaking real MCP JSON-RPC over stdio (`scripting/subprocess/McpSubprocessSpawner.kt`, `McpSubprocessSession.kt`, `InlineScriptToolServerSynthesizer.kt`, `BunRuntime.kt`).
- **`trailblaze-scripting-bundle`** — Ships the esbuild-bundled `@trailblaze/scripting` SDK JS (`SdkBundleResource.kt`); build artifact, not committed source.
- **`trailblaze-scripting-fetch`** — Adds a real `globalThis.fetch` (OkHttp-backed) to the QuickJS runtime as an opt-in engine extension (`scripting/fetch/OkHttpFetchExtension.kt`, `FetchHostAllowlist.kt`).
- **`trailblaze-scripting-mcp-common`** — Shared MCP scripting helpers (tool descriptors, envelope/result mapping): `scripting/mcp/McpToolDescriptors.kt`, `CallToolResultMapper.kt`, `TrailblazeContextEnvelope.kt`.
- **`trailblaze-trailmap-bundler`** — Turns a trailmap's TypeScript tools into QuickJS bundles + generated TS bindings: `bundle/TrailblazeTrailmapBundler.kt`, `WorkspaceClientDtsGenerator.kt`, `JsonSchemaToTsRich.kt`, `BundlerYamlSchema.kt`.

### Capture / reporting / tracing
- **`trailblaze-capture`** — Screen/video/log capture across platforms: `capture/CaptureSession.kt`, `video/AndroidVideoCapture.kt`, `IosVideoCapture.kt`, `PlaywrightVideoCapture.kt`, `MuxToMp4Consumer.kt`, `logcat/AndroidLogcatCapture.kt`, `IosLogCapture.kt`.
- **`trailblaze-report`** — Report/storyboard generation, incl. the WASM report viewer (`report/WasmReport.kt`), `ReportMain.kt`, `StoryboardHtmlBuilder.kt`, `RunReportGenerator.kt`, session models (`report/models/*`), `utils/LogsRepo.kt`, `snapshot/*`.
- **`trailblaze-tracing`** — Chrome-trace-format tracing primitives (KMP): `tracing/TrailblazeTracer.kt`, `TrailblazeTraceRecorder.kt`, `Tracing.kt`, serializers.

### Android peripheral modules
- **`trailblaze-android-gradle`** — Published OSS Gradle plugin (`xyz.block.trailblaze.android-gradle`; composite-included from `settings.gradle.kts`). JUnit-shell codegen from `.trail.yaml` files, plus a nested `trailmap { }` block for scripted-tool bundling into `androidTest` assets.
- **`trailblaze-android-ondevice-mcp`** — On-device HTTP/MCP server run as a blocking instrumentation test: `mcp/handlers/RunYamlRequestHandler.kt`, `GetScreenStateRequestHandler.kt`, `android/runner/rpc/OnDeviceRpcServer.kt`, `mcp/progress/ProgressSessionManager.kt`.
- **`trailblaze-android-world-benchmarks`** — Tools implementing the AndroidWorld benchmark setup/asserts (`android/tools/androidworldbenchmarks/AndroidWorldBenchmarks*TrailblazeTool.kt` — push assets, run adb shell, sqlite, add contact, send SMS, assert file exists, etc.).
- **`trailblaze-accessibility-app`** — Minimal shell APK that hosts `TrailblazeAccessibilityService` + its broadcast receiver so the service can be enabled and validated (`AndroidStandaloneServerTest.kt`).

---

## 2. `docs/` tree

Published via MkDocs Material (`mkdocs.yaml`, site name "Trailblaze"; `docs_hooks.py` fills missing per-trail gallery assets). Nav ordering is driven by `docs/.pages`.

Top-level docs (one line each):
- `index.md` — docs landing page.
- `getting_started.md` — install and first run.
- `your-first-trailmap.md` — tutorial for authoring a first trailmap.
- `architecture.md` — system architecture overview.
- `project_layout.md` — repo/workspace layout reference.
- `CLI.md` — CLI command reference.
- `configuration.md` / `llm_configuration.md` / `llms.md` — general and LLM-provider configuration.
- `trailmaps.md` — trailmap concept and manifest.
- `publishing-a-trailmap.md` — distributing trailmaps (npm).
- `scripted_tools.md` + `scripted-tools-typed-authoring.md`, `scripted-tools-project-layout.md`, `scripted-tools-network-requests.md`, `scripted-tools-snapshot-queries.md` — the typed scripted-tool authoring guide family.
- `tools.md` — built-in tool catalog.
- `maestro.md` — Maestro integration.
- `android_on_device.md`, `android_accessibility_tree_completeness.md` — Android on-device driving.
- `reports.md`, `logging.md`, `host_jvm_unit_tests.md` — reporting/logging/testing.
- `revyl-integration.md` — Revyl cloud devices.
- `support.md` — help/support.
- `showcase-trails.yml` — config (not a page; excluded from the site) selecting showcase trails for the gallery.

Subtrees:
- `docs/devlog/` — chronological, dated development notes. **Convention:** files named `YYYY-MM-DD-slug.md`; `docs/devlog/index.md` holds an auto-generated table (between `<!-- BEGIN/END GENERATED DEVLOG INDEX -->` markers) tagging each entry **Decision** or **Devlog**. ~90 entries spanning 2025-10 → 2026-07 (e.g. `2026-06-28-classifier-lineage-primitive.md`, `2026-07-01-trail-recording-type-validation.md`). This is the richest source of design rationale for a talk.
- `docs/generated/` — build-generated docs, not hand-edited: `LLM_MODELS.md`, `cli-scenarios.md`, `external-config.md`, `functions/` (generated per-function/tool reference). Regenerated by the `:docs:generator` module (`docs/generator/`).
- `docs/benchmarks/` — Playwright-native benchmark results (`playwright-native-benchmarks.md` + `.csv`).
- `docs/images/` — screenshots/GIFs used in docs and README.
- `docs/generator/` — the `:docs:generator` Gradle subproject that emits `docs/generated/`.

---

## 3. `trails/`, `examples/`, `sdks/`, `skills/`, `scripts/`, `install.sh`

### `trails/` — checked-in trail corpus
The framework's own trail suite (v2 YAML; a trail is a list of `config` + `prompts`/steps, e.g. `trails/wikipedia/test-search-python/blaze.yaml`). Suites: `wikipedia/` (~30 web trails), `ios-contacts/` (~18 iOS), `playwright-native/` and `playwright-electron/`, `clock/` and `contacts/` and `calendar` (Android), `compose-desktop/`, `goose-desktop/`, `evals/`, and `benchmarks/` (`benchmarks/android_world/*` app-by-app, `benchmarks/llm-model/*` micro-benchmarks).
- `trails/config/trailblaze.yaml` — the **workspace anchor**: marks the OSS repo as a Trailblaze workspace, lists opt-in `targets:` (clock, contacts, wikipedia, calendar), defaults `llm = none`.
- `trails/config/trailmaps/<id>/` — one directory per target trailmap (`clock`, `contacts`, `wikipedia`, `calendar`), each with `trailmap.yaml` (id, `dependencies: [trailblaze]`, per-platform `target.platforms.<platform>` app ids + tools), plus `tools/`, `waypoints/` (auto-discovered `**.waypoint.yaml`), and some `shortcuts/`.

### `examples/` — standalone runnable workspaces (`examples/README.md`)
Each subdir is a self-contained Trailblaze workspace (own `trails/config/`, tools, `build.gradle.kts`). Authoring references: **`ios-contacts/`** (canonical mobile — 9 typed tools, 18 trails, `*.test.ts` tool unit tests) and **`wikipedia/`** (canonical web — Playwright Native, 28 trails). Others: `playwright-native/` and `playwright-electron/` (with bundled `sample-app/`), `compose-desktop/`. Sample target apps (driven, not authoring demos): `android-sample-app/` + `android-sample-app-uitests/`, `ios-sample-app/` (SwiftUI, `IosSampleApp.xcodeproj`), `examples/dependencies/` (shared version deps). Gradle subprojects `:examples:android-sample-app`, `:examples:compose-desktop`, `:examples:ios-contacts`, `:examples:playwright-native`, `:examples:wikipedia`.

### `sdks/typescript/` — the `@trailblaze/scripting` TS SDK
`package.json` (bun-based, `bun.lock`), `runtime-globals.d.ts`, `tsconfig.json`. `src/`: authoring entry points `index.ts`, `tool.ts`/`tool-core.ts` (typed `trailblaze.tool<In,Out>()`), `client.ts`, `context.ts`, `memory.ts`, `built-in-tools.ts`, `conditional-action.ts`, `view-hierarchy.ts`, and the two runtime profiles `in-process.ts` (slim, QuickJS) and `sub-process.ts` (full MCP). `src/generated/` (Kotlin-derived bindings: `built-in-tool-results.ts`, `host-rpc.ts`, `selectors.ts`, `trailrunner-dtos.ts`), `src/matcher/` (TS port of the waypoint/selector matcher with parity fixtures + `resolver.ts`, `trailblaze-node.ts`), `src/rpc/`. `tools/`: `extract-tool-defs.mjs`, `in-process-wrapper-template.mjs`.

### `skills/` — agent skills (Claude-Code-style)
- `skills/trailblaze/` — the main skill: `SKILL.md` (triggers on Trailblaze / `.trail.yaml` / trailmaps / waypoints / driving-a-device), `SETUP.md`, and `references/` (`drive-device.md`, `save-and-replay.md`, `compose-agent-surface.md`, `session-logs-inspection.md`).
- `skills/trailblaze-validate-oob/` — an out-of-box validation skill (`SKILL.md`).

### `scripts/`
`trailblaze` (installed-CLI launcher), `dev-jar-cache.sh` (uber-JAR cache used by the root `./trailblaze` dev launcher), `install-trailblaze-from-artifact.sh`, `install-trailblaze-source.sh`, `install-git-hooks.sh` + `git-hooks/`, `generate-docs-and-diff.sh`, `scan_readme_cli_drift.sh`, `test_ipc_replay.sh`, `fossa_analyze.py`.

### `install.sh`
End-user installer: `curl … | bash`, downloads the latest release JAR + launcher from GitHub `block/trailblaze` into `~/.trailblaze` (`TRAILBLAZE_VERSION`, `TRAILBLAZE_DIR` overrides), auto-installs `bun` (the sole JS runtime), warns on optional `esbuild`/`ffmpeg`.

---

## 4. Key entry points

- **Dev launcher:** `./trailblaze` (root bash script) → runs `:trailblaze-desktop` from a cached uber JAR (or `--gradle`); auto-pins `TRAILBLAZE_SDK_DIR` to `sdks/typescript`.
- **CLI / app main:** `trailblaze-desktop/…/desktop/TrailblazeOpenSourceMain.kt` (`fun main`, JVM class name `Trailblaze`) → `TrailblazeCli.run(...)`.
- **CLI root command:** `trailblaze-host/…/cli/TrailblazeCli.kt` — `@Command(name = "trailblaze")` `TrailblazeCliCommand` (picocli). `--stop` stops the daemon; `--all` reveals hidden commands; a `GroupedCommandListRenderer` groups subcommands in `--help`.
  Top-level subcommands (from the `subcommands = [...]` block): **`step`, `ask`, `verify`, `snapshot`, `tool`, `toolbox`, `trail`, `session`, `report`, `waypoint`, `results`, `config`, `device`, `show`, `app`, `mcp`, `check`, `migrate-trails`**, and hidden `desktop`. `waypoint` has a deep subtree (`list`, `graph`, `locate`, `validate`, `suggest-selector`, `propose`, `tune`, `shortcut`, `migrate-trail`, `capture-example` — see `cli/Waypoint*Command.kt`, `cli/propose/`, `cli/tune/`, `cli/shortcut/`). There is intentionally no `test` subcommand (bun unit tests run inside `check`).
- **Daemon/server startup:** `trailblaze-server/…/logs/server/TrailblazeMcpServer.kt` — Ktor `embeddedServer`; `startStreamableHttpMcpServer()`, `startStdioMcpServer()`, `startSseMcpServer()`. Daemon HTTP routes live in `trailblaze-host/…/trailrunner/*Routes.kt` (mounted via `TrailRunnerEndpoint.kt` / `TrailRunnerExtension.kt`); the CLI talks to it through `cli/DaemonClient.kt`.
- **Desktop UI:** `trailblaze-host/…/ui/MainTrailblazeApp.kt` (`class MainTrailblazeApp`) + `ui/TrailblazeDesktopApp.kt`, tabs under `ui/tabs/` (home, devices, recording, sessions, trails, waypoints, mcp, settings, testresults). App instance provided by `trailblaze-desktop`'s `OpenSourceTrailblazeDesktopApp`.
- **On-device server:** `trailblaze-android-ondevice-mcp/…/android/runner/rpc/OnDeviceRpcServer.kt`, driven as a blocking instrumentation test.

---

## 5. Quick lookup: concept → file

| Concept | Path |
|---|---|
| Unified trail model | `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/yaml/unified/UnifiedTrail.kt` (+ `UnifiedTrailStep.kt`, `UnifiedTrailConfig.kt`, `UnifiedTrailAdapter.kt`, `UnifiedTrailEmitter.kt`, `TrailDocument.kt`) |
| Legacy trail YAML model | `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/yaml/TrailYamlItem.kt`, `.../yaml/TrailblazeYaml.kt`, `.../yaml/TrailConfig.kt` |
| Trailmap manifest model | `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/project/TrailblazeTrailmapManifest.kt` (loader: `trailblaze-common/.../config/project/TrailblazeTrailmapManifestLoader.kt`) |
| Workspace/project config | `trailblaze-models/.../config/project/TrailblazeProjectConfig.kt`; loader `trailblaze-common/.../config/project/TrailblazeProjectConfigLoader.kt` |
| Waypoint model | `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/api/waypoint/WaypointDefinition.kt` (+ `WaypointCondition.kt`, `WaypointMatchResult.kt`, `ResolvedWaypoint.kt`, `WaypointVariant.kt`) |
| Waypoint matcher | `trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/waypoint/WaypointMatcher.kt` (also `WaypointLoader.kt`, `WaypointAssertion.kt`, `WaypointRegistryResolver.kt`) |
| Classifier lineage | `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/devices/TrailblazeClassifierLineage.kt` |
| Driver types enum | `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/devices/TrailblazeDriverType.kt` (values: `ANDROID_ONDEVICE_ACCESSIBILITY`, `ANDROID_ONDEVICE_INSTRUMENTATION`, `IOS_HOST`, `IOS_AXE`, `PLAYWRIGHT_NATIVE`, `PLAYWRIGHT_ELECTRON`, `REVYL_ANDROID`, `REVYL_IOS`, `COMPOSE`) |
| Platform enum | `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/devices/TrailblazeDevicePlatform.kt` (`ANDROID`, `IOS`, `WEB`, `DESKTOP`) |
| Accessibility service | `trailblaze-android/src/main/java/xyz/block/trailblaze/android/accessibility/TrailblazeAccessibilityService.kt` |
| Trail runner (Android on-device) | `trailblaze-android/.../android/accessibility/AccessibilityTrailRunner.kt` |
| Trail runner (iOS) | `trailblaze-host/.../host/axe/AxeTrailRunner.kt` |
| Trail runner (host/desktop) | `trailblaze-host/.../host/TrailblazeHostYamlRunner.kt`, `.../host/MaestroHostRunnerImpl.kt`, `.../host/yaml/DesktopYamlRunner.kt` |
| Deterministic replay | `trailblaze-agent/.../agent/trail/DeterministicTrailExecutor.kt` |
| TrailTscValidator | `trailblaze-host/src/main/java/xyz/block/trailblaze/host/TrailTscValidator.kt` |
| QuickJsToolHost | `trailblaze-quickjs-tools/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/quickjs/tools/QuickJsToolHost.kt` |
| Trailmap bundler | `trailblaze-trailmap-bundler/.../bundle/TrailblazeTrailmapBundler.kt` |
| Graph builder | `trailblaze-host/src/main/java/xyz/block/trailblaze/graph/WaypointGraphBuilder.kt` |
| Graph viewer/renderer | `trailblaze-host/.../graph/WaypointGraphHtmlRenderer.kt`, `.../graph/WaypointGraphEndpoint.kt`, CLI `cli/WaypointGraphCommand.kt` |
| Agent loop / MultiAgentV3Runner | `trailblaze-agent/src/main/java/xyz/block/trailblaze/agent/MultiAgentV3Runner.kt` |
| LLM-as-compiler | `trailblaze-common/.../compile/TrailblazeCompiler.kt` (`CompileCommand.kt` in host CLI) |
| MCP server | `trailblaze-server/.../logs/server/TrailblazeMcpServer.kt` |
| Trail migration | `trailblaze-host/.../migration/UnifiedTrailMigrator.kt` (CLI `MigrateTrailsCommand.kt`) |

---

Notes for talk prep: the `docs/devlog/` entries are the best narrative source for "why" decisions (classifier lineage, unified trail YAML, bun-only runtime, scripted-tool consolidation, waypoints/graphs, LLM-as-compiler). The two-runtime scripted-tool split (in-process QuickJS vs. bun subprocess MCP) is documented crisply in the `trailblaze-scripting/README.md` and `trailblaze-scripting-subprocess/README.md` comparison tables. `examples/ios-contacts/` and `examples/wikipedia/` are the canonical live-demo workspaces.
