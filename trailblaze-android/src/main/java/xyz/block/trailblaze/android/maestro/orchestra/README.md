The files in this package are copies of the Maestro Code, adapted for our on-device usage.

They must be kept in sync with Maestro's host implementation every time we upgrade Maestro, *and*
re-carry the Trailblaze-specific modifications listed below.

2026-06-15 - Latest code is from v2.6.1
https://github.com/mobile-dev-inc/Maestro/blob/cli-2.6.1/maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt

## How to perform the upgrade

Do **not** rewrite this file from the upstream copy. It carries local modifications (below), and at
least one of them is **not present in any upstream version** (the `waitForAppToSettle()` in
`hideKeyboardCommand`), so a clean rewrite silently drops it. Instead, apply the upstream *delta*:

1. Diff the two Maestro tags' `Orchestra.kt` to see exactly what changed upstream — e.g. curl both of
   `https://raw.githubusercontent.com/mobile-dev-inc/Maestro/cli-<OLD>/maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt`
   and `…/cli-<NEW>/…` and `diff` them.
2. Apply that delta to **this** file, leaving the modifications below intact. Don't worry about
   indentation (upstream is 4-space, we use 2-space) — `./gradlew spotlessApply` reformats it.
3. Update the version/date line above and the banner in `OrchestraExt.kt`.
4. Build, then run the validation tests in "Gotchas" below. Compilation passing is **not** enough —
   the load-bearing correctness signal is the matcher test suite.

## Modifications (re-apply these every upgrade)
- Removed AI commands (assertNoDefectsWithAI, assertWithAI, extractTextWithAI) and their supporting
  constructor parameters: `apiKey`, `AIPredictionEngine`, and `onCommandGeneratedOutput`.
- Removed assertScreenshot (needs Java AWT image comparison — `ImageComparison` in ≤2.3, `ScreenshotMatch`
  in 2.6 — which is unavailable on Android). The command branch throws instead of executing.
- Replaced GraalJS/Rhino with FakeJsEngine: `initJsEngine` always creates `FakeJsEngine`, and we omit
  the `jsEngineFactory` constructor parameter v2.6.1 introduced (it defaults to GraalJS and rejects
  `jsEngine: rhino`). See the FakeJsEngine section below.
- Skipped `jsEngine.onLogMessage` and `command.evaluateScripts(jsEngine)` in `executeCommands` /
  `executeSubflowCommands` — FakeJsEngine is a no-op and Maestro's `Env.evaluateScripts` uses a
  look-behind regex incompatible with Android's ICU regex engine.
- Copied `calculateElementRelativePoint()` into `OrchestraExt.kt` instead of depending on the internal
  util (upstream location as of 2.6.1: `maestro.orchestra.util.ElementCoordinateUtil`).
- Removed the `FlowController` / `DefaultFlowController` definitions; import them from `maestro.orchestra`.
- Changed package to `xyz.block.trailblaze.android.maestro.orchestra`.
- `setClipboardCommand` calls `DeviceClipboardUtil` to set the real device clipboard via ClipboardManager.
- **Local-only:** `hideKeyboardCommand` calls `maestro.waitForAppToSettle()` after `hideKeyboard()`.
  This is NOT in any upstream version — keep it through the diff.
- **Local-only:** in `runFlow`, the `catch (CancellationException)` routes the exception through the
  `exception` var (`exception = e`) instead of upstream's bare `throw e`. Upstream's `return` in the
  `finally` swallows a bare re-throw, dropping the cancellation; routing it through `exception` makes
  the finally's `exception?.let { throw it }` propagate it before that `return`. Deliberate divergence
  from upstream 2.6.1 — keep it through the diff.

(Historical note: older revisions also "removed an unused `CommandOutput` sealed class." Upstream
deleted that class in a later release, so as of 2.6.1 there is nothing to remove.)

## Gotchas observed during upgrades (most recently 2.3.0 → 2.6.1)

This vendored file is only one piece — a Maestro bump usually breaks dozens of call sites across the
repo. Beyond this file, expect:
- **`suspend` propagation.** In 2.6.1 nearly every high-level `Maestro` method became `suspend`, so most
  command handlers here became `suspend` too. The `Driver` SPI did NOT — driver implementations
  (`MaestroAndroidUiAutomatorDriver`, `LoggingDriver`, `ViewHierarchyOnlyDriver`) stay non-suspend.
- **Package moves / field-type changes.** e.g. `maestro.Platform` / `maestro.DeviceOrientation` →
  `maestro.device.*`; `WaitForAnimationToEndCommand.timeout` `Long?` → `String?`;
  `SetOrientationCommand.orientation` enum → `String` (use `resolvedOrientation()`).
- **Reflective coupling — the easy-to-miss one.** `ElementMatcherUsingMaestro` (in trailblaze-common)
  invokes this class's **private** `buildFilter` (arity 2) and `findElementViewHierarchy` (arity 3) by
  name + parameter count via kotlin-reflect. Renaming them or changing their arity breaks silently at
  runtime; making one `suspend` requires switching its reflective `.call(...)` to `.callSuspend(...)`.
- **`Filters` semantics can shift silently.** 2.6.1 changed `containsChild` to match *every* parent with
  a matching direct child (2.3.0 collapsed to the first), which changed the selectors `TapSelectorV2`
  generates. Compilation will not catch this. After any sync, run `./gradlew :trailblaze-common:jvmTest`;
  its viewmatcher / TapSelectorV2 suites exercise the reflective matcher path above and are the real
  validation. (Downstream forks that keep their own selector golden-suites should run those too.)

## FakeJsEngine and JavaScript Limitations

The on-device Orchestra uses FakeJsEngine (a no-op implementation of maestro.js.JsEngine)
because GraalJS/Rhino are JVM-only and unavailable on Android. This means:

**Not supported on-device (commands log a warning and return false):**
- `evalScript` (EvalScriptCommand) — JavaScript evaluation is a no-op
- `runScript` (RunScriptCommand) — JavaScript script execution is a no-op

**Functionally limited (no-op but benign):**
- `defineVariables` (DefineVariablesCommand) — `putEnv` is a no-op, so YAML-style variable
  interpolation (`${MY_VAR}`) will not work. This is acceptable because on-device flows
  construct commands programmatically rather than relying on YAML variable substitution.
- `evaluateScripts(jsEngine)` calls in executeCommands/executeSubflowCommands — returns
  commands mostly unchanged since FakeJsEngine doesn't evaluate expressions. Commands that
  contain `${...}` variable references will pass through as literal strings.

**Structural calls (no-op is correct behavior):**
- `onLogMessage`, `close`, `enterScope`, `leaveScope`, `enterEnvScope`, `leaveEnvScope` —
  scope management that does nothing in FakeJsEngine.
- `setCopiedText` — internal JS engine state; actual clipboard is set via DeviceClipboardUtil.

When upgrading to a new Maestro version, check if any new commands use the JS engine and
apply the same pattern: either substitute with on-device equivalents or log a warning that
the functionality is not supported.
