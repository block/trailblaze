<!--firebender-plan
name: Trail Prerequisites Feature
overview: Ensure prerequisites are visible in config UI and actually execute from both MCP and Desktop UI run paths.
todos:
  - id: confirm-server-path
    content: "Keep MCP `trail(action=RUN)` prerequisite auto-execution behavior in TrailMcpTool.handleRun()"
    status: completed
  - id: desktop-root-cause
    content: "Document Desktop UI run-path gap: DesktopYamlRunner/TrailblazeHostYamlRunner bypass TrailMcpTool prerequisite executor"
    status: completed
  - id: flatten-helper
    content: "Add and validate TrailFileManager.flattenPrerequisites(yamlContent, variantFilePath) with cycle detection"
    status: completed
  - id: wire-trails-tab
    content: "Call flattenPrerequisites before requestFactory.create(...) in TrailsBrowserTabComposable"
    status: completed
  - id: wire-yaml-tab
    content: "Call flattenPrerequisites before run in YamlTabComposables"
    status: completed
  - id: add-session-logs
    content: "Optionally emit prerequisite start/complete logs in Desktop path so session timeline matches MCP visibility"
  - id: test-matrix
    content: "Verify runs from MCP, Trails tab, and Session/YAML tab with direct + nested prerequisites"
-->

# Trail Prerequisites Feature - Handoff Notes

## Current State (What is already true)
- `TrailConfig.prerequisites` is already implemented and editable in the visual config editor.
- MCP run path (`TrailMcpTool.handleRun`) already executes prerequisites before the main trail.
- Prerequisite log types exist (`PrerequisiteStartLog`, `PrerequisiteCompleteLog`) and are handled by timeline/event summary UI.
- `TrailDetailsView` now surfaces prerequisites in the details panel.

## Root Cause of "it doesn't run from UI"
The user-reported mismatch is real:
- **Works** when running via MCP `trail(action=RUN)`.
- **Does not auto-run prerequisites** when running from Desktop UI tabs.

Why:
- Desktop UI uses `DesktopYamlRunner` -> `TrailblazeHostYamlRunner` with raw `runYamlRequest.yaml`.
- That path bypasses `TrailMcpTool.handleRun()` where prerequisite orchestration lives.

## Implementation Direction Chosen
To make Desktop UI run behavior match MCP behavior, we are flattening prerequisites into the YAML before dispatching host/on-device execution.

### Added utility (in progress)
- `TrailFileManager.flattenPrerequisites(yamlContent: String, variantFilePath: String? = null): String`
- Intended behavior:
  - Parse YAML.
  - Resolve effective config (including NL definition config via `variantFilePath`).
  - Resolve prerequisite IDs using `findTrailByName`.
  - Collect prerequisite prompt steps (including nested prerequisites) and prepend to main steps.
  - Return re-encoded YAML for normal runner pipeline.

## Required Next Edits

### 1) Wire into Trails Browser run path
File: `TrailsBrowserTabComposable.kt`
- Before `requestFactory.create(...)`, transform YAML:
  - Input: `yamlContentToRun!!`
  - Output: `flattenedYaml`
- Use `flattenedYaml` in request creation.
- Pass selected variant absolute path as `variantFilePath` when available for proper `blaze.yaml` prerequisite resolution.

### 2) Wire into Session/YAML tab run path
File: `YamlTabComposables.kt`
- Apply same flattening before invoking runner.
- Keep behavior consistent with Trails Browser tab.

### 3) Validate/fix flattening utility details
File: `TrailFileManager.kt`
- Ensure recursion handles cycles safely (visited set), avoiding infinite recursion.
- Ensure deterministic ordering of prerequisites.
- Ensure config output does not retain unresolved `prerequisites` after flattening.
- Preserve non-prompt items correctly.

## Testing Matrix (must pass)
1. Run trail with no prerequisites (no behavior change).
2. Run trail with one prerequisite from Trails tab.
3. Run trail with one prerequisite from Session/YAML tab.
4. Run trail with nested prerequisites (`A -> B -> C`).
5. Run trail with missing prerequisite ID (clear error/warning behavior).
6. Run trail with circular prerequisite graph (must fail safely, no hang).
7. Confirm MCP run path still works unchanged.

## Optional Follow-up (if UX parity desired)
Desktop flattened execution may not emit explicit prerequisite start/complete logs like MCP path. If parity is desired in session timeline, add explicit logging in host run path around flattened prerequisite boundaries.

## Key Files for Next Agent
- `trailblaze-server/.../TrailMcpTool.kt` (MCP behavior reference)
- `trailblaze-server/.../TrailFileManager.kt` (flatten helper + prerequisite resolution)
- `trailblaze-host/.../ui/tabs/trails/TrailsBrowserTabComposable.kt` (Desktop Trails tab run entry)
- `trailblaze-host/.../ui/tabs/sessions/YamlTabComposables.kt` (Desktop YAML/session run entry)
- `trailblaze-host/.../host/yaml/DesktopYamlRunner.kt` (Desktop execution pipeline)
- `trailblaze-host/.../host/TrailblazeHostYamlRunner.kt` (host execution dispatch)
