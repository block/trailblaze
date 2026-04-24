## Tool `web_scroll`

## Description
Scroll the page or a specific container in the specified direction.
When ref is provided, scrolls within that container (e.g., a sidebar or panel) by moving the
mouse to its center first. When ref is omitted, scrolls the full page.

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeScrollTool`

### Registered `PlaywrightNativeScrollTool` in `ToolRegistry`
### Optional Parameters
- `direction`: `[
  "UP",
  "DOWN",
  "LEFT",
  "RIGHT"
]`
- `amount`: `Integer`
  Number of pixels to scroll. Defaults to 500.
- `ref`: `String`
  Element reference for the container to scroll within: ARIA descriptor (e.g., 'navigation "Sidebar"'), element ID (e.g., 'e5'), or CSS selector with css= prefix (e.g., 'css=#scrollable-panel'). When omitted, scrolls the full page.
- `element`: `String`
  Human-readable description of the container being scrolled, for logging.
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION