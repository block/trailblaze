# trailblaze-playwright

Playwright-native web testing module for Trailblaze. Provides browser automation tools that
operate directly against Playwright without any Maestro dependency.

## Playwright MCP Coverage

Our tools are modeled after the [Playwright MCP Server](https://github.com/microsoft/playwright-mcp)
but designed to be **LLM-first and recordable** for the Trailblaze agent loop.

### Coverage Checklist

#### Core Automation

| Playwright MCP Tool        | Trailblaze Tool                      | Status |
|----------------------------|--------------------------------------|--------|
| `browser_navigate`         | `playwright_navigate` (action=goto)  | Done   |
| `browser_navigate_back`    | `playwright_navigate` (action=back)  | Done   |
| _(no MCP equivalent)_      | `playwright_navigate` (action=forward) | Done |
| `browser_click`            | `playwright_click`                   | Done   |
| `browser_hover`            | `playwright_hover`                   | Done   |
| `browser_type`             | `playwright_type`                    | Done   |
| `browser_press_key`        | `playwright_press_key`               | Done   |
| `browser_select_option`    | `playwright_select_option`           | Done   |
| `browser_snapshot`         | `playwright_snapshot`                | Done   |
| `browser_wait_for`         | `playwright_wait`                    | Done   |
| `browser_mouse_wheel`      | `playwright_scroll`                  | Done   |
| `browser_drag`             | --                                   | TODO   |
| `browser_file_upload`      | --                                   | TODO   |
| `browser_fill_form`        | --                                   | TODO   |
| `browser_handle_dialog`    | --                                   | TODO   |
| `browser_close`            | --                                   | TODO   |

#### Tab Management

| Playwright MCP Tool        | Trailblaze Tool                      | Status   |
|----------------------------|--------------------------------------|----------|
| `browser_tabs`             | --                                   | TODO     |

#### Observation / DevTools

| Playwright MCP Tool         | Trailblaze Tool                     | Status        |
|-----------------------------|-------------------------------------|---------------|
| `browser_console_messages`  | --                                  | Out of scope  |
| `browser_network_requests`  | --                                  | Out of scope  |
| `browser_evaluate`          | --                                  | Out of scope  |
| `browser_run_code`          | --                                  | Out of scope  |

These dump raw data that burns tokens and confuses the LLM in an agent loop. If we need
network/console inspection, it's better handled at the framework level (Trailblaze logging)
rather than as an LLM tool.

#### Vision (Coordinate-Based)

| Playwright MCP Tool          | Trailblaze Tool                    | Status        |
|------------------------------|-------------------------------------|---------------|
| `browser_mouse_click_xy`     | --                                  | Out of scope  |
| `browser_mouse_move_xy`      | --                                  | Out of scope  |
| `browser_mouse_drag_xy`      | --                                  | Out of scope  |
| `browser_mouse_down`         | --                                  | Out of scope  |
| `browser_mouse_up`           | --                                  | Out of scope  |

We use ARIA snapshots (accessibility tree), not pixel coordinates. Vision-based tools are
intentionally excluded.

#### Other

| Playwright MCP Tool          | Trailblaze Tool                    | Status        |
|------------------------------|-------------------------------------|---------------|
| `browser_resize`             | --                                  | Not needed    |
| `browser_take_screenshot`    | Handled by framework                | N/A           |
| `browser_install`            | --                                  | Not needed    |
| `browser_pdf_save`           | --                                  | Not needed    |

#### Test Assertions (Playwright MCP `testing` capability)

| Playwright MCP Tool              | Trailblaze Tool                        | Status     |
|----------------------------------|----------------------------------------|------------|
| `browser_verify_text_visible`    | `playwright_verify_text_visible`       | Done       |
| `browser_verify_element_visible` | `playwright_verify_element_visible`    | Done       |
| `browser_verify_value`           | `playwright_verify_value`              | Done       |
| `browser_verify_list_visible`    | `playwright_verify_list_visible`       | Done       |
| `browser_generate_locator`       | --                                     | Not needed |

These are deterministic Playwright assertions (via `PlaywrightAssertions.assertThat`)
rather than LLM-based verification. They provide exact pass/fail results without burning
LLM tokens on interpretation.

## Design Strategy

### LLM-First Tool Design

Our tools differ from the Playwright MCP in several ways:

- **Focused descriptions**: Each tool's `@LLMDescription` is concise and action-oriented,
  telling the LLM exactly when and how to use it. The MCP descriptions are more generic.
- **Structured feedback**: Every tool returns a `Success(message=...)` with a human-readable
  result (e.g., "Clicked on 'Submit'. Page navigated to /dashboard"). The MCP returns raw
  accessibility snapshots after every action. Our approach gives the LLM actionable context
  without flooding it with the full page tree.
- **Fewer tools, better focus**: We combine related actions (e.g., navigate/back/forward in
  one tool) and exclude tools that would waste LLM iterations (console messages, network
  requests, JS evaluation). Every tool in the set should be something the LLM can
  meaningfully choose between.

### Recordability

Tools are designed to be **recordable** — a recorded `trail.yaml` file can be played back
with no LLM. This means:

- Tool parameters are concrete and deterministic (ARIA refs, URLs, text values)
- Tool names map directly to `@TrailblazeToolClass` annotations used in YAML serialization
- The `PlaywrightExecutableTool` interface provides a `Page`-based execution path that
  works identically whether driven by an LLM or replayed from a recording

### ARIA Snapshot Approach

Like the Playwright MCP, we use the accessibility tree (ARIA snapshots) rather than
screenshots for element identification. Elements are referenced by role+name descriptors
(e.g., `link "Home"`, `button "Submit"`) which are resolved via `PlaywrightAriaSnapshot.resolveRef()`
using Playwright's `getByRole` API.
