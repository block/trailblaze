The files in this package are copies of the Maestro Code, Adapted for our on-device usage.

These files should ALWAYS be updated when we upgrade our version of Maestro so that we are matching their host implementation.
Also, please perform the modifications listed below in order to stay consistent with required changes.

2026-03-11 - Latest code is from v2.3.0
https://github.com/mobile-dev-inc/Maestro/blob/cli-2.3.0/maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt

Modifications:
- Removed Support for AI Commands (assertNoDefectsWithAI, assertWithAI, extractTextWithAI)
- Removed assertScreenshot command (requires Java AWT ImageComparison which is unavailable on Android)
- Removed API Key and AIPredictionEngine constructor parameters (Maestro Cloud API Key)
- Removed onCommandGeneratedOutput constructor parameter (used only by AI commands)
- Replaced GraalJS/Rhino Engine with Fake JS Engine (initJsEngine always creates FakeJsEngine)
- Removed the "CommandOutput" sealed class that is unused
- Copied over the internal calculateElementRelativePoint() method into OrchestraExt.kt (in v2.3.0 this was moved to maestro.orchestra.util but we keep a local copy)
- Remove "FlowController" and "DefaultFlowController" class definitions and use the OSS version (imported from maestro.orchestra)
- Changed package to xyz.block.trailblaze.android.maestro.orchestra
- setClipboardCommand uses DeviceClipboardUtil to set the actual device clipboard via ClipboardManager

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
