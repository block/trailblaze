# trailblaze-playwright

Playwright-native web testing module for Trailblaze. Provides browser automation tools that
operate directly against Playwright without any Maestro dependency.

Tools are named `web_*` — the trailmap id is `web` (see
`src/main/resources/trails/config/trailmaps/web/trailmap.yaml`), because the module name
reflects the implementation while the tool prefix reflects the user-visible capability.
Drivers: `playwright-native` (headless Chromium) and `playwright-electron`.

## Tool Surface

Three toolsets ship under `src/main/resources/trails/config/trailmaps/web/toolsets/`:

| Toolset | LLM-facing? | Tools |
|---------|-------------|-------|
| `web_core` | Yes | `web_click`, `web_type`, `web_navigate`, `web_scroll`, `web_hover`, `web_pressKey`, `web_selectOption`, `web_wait`, `web_snapshot`, `web_requestDetails`, `web_resize`, `web_currentUrl`, `web_waitForUrl`, `web_applyCookies`, `web_getStorageState`, `web_fillSecret` |
| `web_verification` | Yes | `web_verifyTextVisible`, `web_verifyElementVisible`, `web_verifyValue`, `web_verifyListVisible`, `web_assertNetworkEvent`, `assertWaypoint` |
| `web_framework` | **No** (`always_enabled`) | `web_evaluate` |

`web_framework` is the sibling of `android_framework` — arbitrary-string-execution
primitives that scripted tools reach via `client.tools.<name>(...)` but that must never
land in the LLM's catalog. Its members carry `surfaceToLlm = false` and
`isRecordable = false` at the class level.

`playwright_desktop_launchGoose` also lives in this module. It is an Electron-desktop launch
helper, not a generic web primitive, and is not a member of the three toolsets above.

## Playwright MCP Coverage

Our tools are modeled after the [Playwright MCP Server](https://github.com/microsoft/playwright-mcp)
but designed to be **LLM-first and recordable** for the Trailblaze agent loop.

### Core Automation

| Playwright MCP Tool        | Trailblaze Tool                        | Status |
|----------------------------|----------------------------------------|--------|
| `browser_navigate`         | `web_navigate` (action=GOTO)           | Done   |
| `browser_navigate_back`    | `web_navigate` (action=BACK)           | Done   |
| _(no MCP equivalent)_      | `web_navigate` (action=FORWARD)        | Done   |
| `browser_click`            | `web_click`                            | Done   |
| `browser_hover`            | `web_hover`                            | Done   |
| `browser_type`             | `web_type`                             | Done   |
| `browser_press_key`        | `web_pressKey`                         | Done   |
| `browser_select_option`    | `web_selectOption`                     | Done   |
| `browser_snapshot`         | `web_snapshot`                         | Done   |
| `browser_wait_for`         | `web_wait` (seconds), `web_waitForUrl` (URL regex) | Partial |
| `browser_mouse_wheel`      | `web_scroll`                           | Done   |
| `browser_drag`             | --                                     | TODO   |
| `browser_file_upload`      | --                                     | TODO   |
| `browser_fill_form`        | --                                     | TODO   |
| `browser_handle_dialog`    | --                                     | TODO   |
| `browser_close`            | --                                     | TODO   |

`browser_wait_for` is marked Partial because MCP's version also waits for text to appear or
disappear. On our side, waiting for text is covered by the `web_verify*` tools, which
auto-wait via `PlaywrightAssertions.assertThat`, and interaction tools auto-wait on element
resolution — so a blind sleep is rarely the right answer.

`web_snapshot` is not a return-the-tree tool: the ARIA element list reaches the LLM as
screen state on every turn regardless. It names and persists a screenshot + accessibility
tree into the session log for the report.

### Tab Management

| Playwright MCP Tool        | Trailblaze Tool | Status |
|----------------------------|-----------------|--------|
| `browser_tabs`             | --              | TODO   |

Popups are auto-adopted by `PlaywrightBrowserManager` and the screen state tells the LLM
which tab it is viewing ("tab 2 of 3"), but there is no tool to list, switch, or close tabs.

### Observation / DevTools

| Playwright MCP Tool         | Trailblaze Tool                             | Status |
|-----------------------------|---------------------------------------------|--------|
| `browser_console_messages`  | Framework capture (`console/WebConsoleCapture.kt`) | Done, not an LLM tool |
| `browser_network_requests`  | Framework capture (`network/WebNetworkCapture.kt`); `web_assertNetworkEvent` for assertions | Done, capture is not an LLM tool |
| `browser_evaluate`          | `web_evaluate`                              | Done, framework-only |
| `browser_run_code`          | --                                          | Not planned |

Console and network capture ship, but deliberately **not** as LLM tools — dumping raw
traffic into the agent loop burns tokens and confuses the model. Instead:

- `WebConsoleCapture` attaches at the `BrowserContext` level and appends every
  `console.log/warn/error/info/debug` to `<session-dir>/device.log`, which the report's
  "Device Logs" panel renders.
- `WebNetworkCapture` writes request/response events to `<session-dir>/network.ndjson`
  (with large or binary bodies spilled to `<session-dir>/bodies/`), which the report's
  network panel renders. `web_assertNetworkEvent` is the LLM- and trail-facing surface:
  a deterministic assertion that a named event fired, not a data dump.
- `web_evaluate` exists for host-side composition from scripted tools only. It is
  `surfaceToLlm = false` and `isRecordable = false`, so the LLM never sees it and it never
  becomes a recorded step.

### Vision (Coordinate-Based)

| Playwright MCP Tool          | Trailblaze Tool | Status       |
|------------------------------|-----------------|--------------|
| `browser_mouse_click_xy`     | --              | Out of scope |
| `browser_mouse_move_xy`      | --              | Out of scope |
| `browser_mouse_drag_xy`      | --              | Out of scope |
| `browser_mouse_down`         | --              | Out of scope |
| `browser_mouse_up`           | --              | Out of scope |

We use ARIA snapshots (accessibility tree), not pixel coordinates. Vision-based tools are
intentionally excluded.

### Other

| Playwright MCP Tool          | Trailblaze Tool                     | Status     |
|------------------------------|-------------------------------------|------------|
| `browser_resize`             | `web_resize`                        | Done       |
| `browser_take_screenshot`    | Handled by framework                | N/A        |
| `browser_install`            | Handled by `PlaywrightDriverManager` | N/A       |
| `browser_pdf_save`           | --                                  | Not needed |

`web_resize` changes the **viewport box only**. It does not change `User-Agent`,
`deviceScaleFactor`, `isMobile`, or `hasTouch` — those are fixed on the `BrowserContext` at
construction. For full mobile emulation, provision the device up front
(`trailblaze device create web --emulate "iPhone 15"`).

### Test Assertions (Playwright MCP `testing` capability)

| Playwright MCP Tool              | Trailblaze Tool             | Status     |
|----------------------------------|-----------------------------|------------|
| `browser_verify_text_visible`    | `web_verifyTextVisible`     | Done       |
| `browser_verify_element_visible` | `web_verifyElementVisible`  | Done       |
| `browser_verify_value`           | `web_verifyValue`           | Done       |
| `browser_verify_list_visible`    | `web_verifyListVisible`     | Done       |
| `browser_generate_locator`       | --                          | Not needed |

These are deterministic Playwright assertions (via `PlaywrightAssertions.assertThat`)
rather than LLM-based verification. They provide exact pass/fail results without burning
LLM tokens on interpretation.

### Beyond the MCP

Tools with no Playwright MCP counterpart:

| Trailblaze Tool      | Purpose |
|----------------------|---------|
| `web_currentUrl`     | Return the current URL as a string (e.g. detect a bounce to `/login`) |
| `web_waitForUrl`     | Wait until the URL matches a regex (post-login redirects) |
| `web_getStorageState` | Capture cookies + per-origin localStorage in Playwright's storage-state format |
| `web_applyCookies`   | Replay saved cookies so a trail can skip a UI login |
| `web_fillSecret`     | Fill a field with a value that must not be logged |
| `web_requestDetails` | Ask for a richer view hierarchy on the next turn (bounds, CSS selectors, offscreen, occluded) |
| `web_assertNetworkEvent` | Assert a named event appeared in captured network traffic |

## Not Implemented

Tracked gaps, so nobody has to read the tool list to find them:

- **File upload** — no wrapper for `locator.setInputFiles(...)`.
- **Drag and drop** — no drag tool of any kind.
- **Native dialog handling** — no `onDialog` listener is registered, so Playwright
  **auto-dismisses** native `alert()` / `confirm()` / `prompt()`. Nothing hangs, but a flow
  that needs "OK" is not testable and `beforeunload` prompts cannot be accepted. (In-page
  modals — `<dialog>` and `role="dialog"` — are unaffected: they appear in the screen state
  and are clickable like any other element.)
- **Tab management** — see above.
- **Page close** — no tool closes a page or tab.
- **Batch form fill** — no `browser_fill_form` equivalent; fill fields one at a time.
- **iframes** — the ARIA snapshot does not descend into frames and the bounds script walks
  `el.children` only, so frame content is neither seen nor clickable.

## Design Strategy

### Thin Shim on Playwright

Trailblaze tools are a thin shim over Playwright's own primitives — `locator.click()`,
`Locator.or()`, `waitFor`, `PlaywrightAssertions` — rather than hand-rolled retry, fallback,
or wait loops. Conditional runtime logic belongs in TS scripted tools, not in new Kotlin
branching. Safety-bypass knobs (e.g. `force: true`) are deliberately not exposed on the NL
surface.

### LLM-First Tool Design

Our tools differ from the Playwright MCP in several ways:

- **Focused descriptions**: Each tool's `@LLMDescription` is concise and action-oriented,
  telling the LLM exactly when and how to use it. The MCP descriptions are more generic.
- **Structured feedback**: Every tool returns a `Success(message=...)` with a human-readable
  result (e.g., "Clicked on 'Submit'. Page navigated to /dashboard"). The MCP returns raw
  accessibility snapshots after every action. Our approach gives the LLM actionable context
  without flooding it with the full page tree.
- **Fewer tools, better focus**: We combine related actions (e.g., navigate/back/forward in
  one tool) and keep raw-data utilities out of the LLM catalog entirely — console and
  network capture happen at the framework level, and `web_evaluate` is framework-only.
  Every tool the LLM *can* see should be something it can meaningfully choose between.

### Recordability

Tools are designed to be **recordable** — a recorded `trail.yaml` file can be played back
with no LLM. This means:

- Tool parameters are concrete and deterministic (ARIA refs, URLs, text values)
- Tool names map directly to `@TrailblazeToolClass` annotations used in YAML serialization
- The `PlaywrightExecutableTool` interface provides a `Page`-based execution path that
  works identically whether driven by an LLM or replayed from a recording
- Tools that cannot produce a deterministic replay step are marked `isRecordable = false`
  (`web_evaluate`, `web_requestDetails`)

### ARIA Snapshot Approach

Like the Playwright MCP, we use the accessibility tree (ARIA snapshots) rather than
screenshots for element identification. Elements are referenced by role+name descriptors
(e.g., `link "Home"`, `button "Submit"`) which are resolved via `PlaywrightAriaSnapshot.resolveRef()`
using Playwright's `getByRole` API.

Elements the user cannot act on are filtered out of the compact list by default: offscreen
elements, and elements a click could not reach. Occlusion is decided by a 1:1 port of
Playwright's `expectHitTarget` — the same actionability rule behind
`<el> intercepts pointer events` — so an element is hidden from the LLM exactly when
Playwright would refuse to click it. The LLM can ask for either category back via
`web_requestDetails` (`OFFSCREEN_ELEMENTS`, `OCCLUDED_ELEMENTS`).

## Tests

This module's `build.gradle.kts` removes the test tasks from `check`
deliberately: these tests launch real Chromium, and `check` must not require a browser
download. Run them explicitly:

```bash
./gradlew :trailblaze-playwright:test
```
