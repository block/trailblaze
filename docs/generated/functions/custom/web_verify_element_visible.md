## Tool `web_verify_element_visible`

## Description
Verify that an element identified by its element ID, ARIA descriptor, or CSS selector is visible on the page.
This is a test assertion — it will fail the test if the element is not visible.

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeVerifyElementVisibleTool`

### Registered `PlaywrightNativeVerifyElementVisibleTool` in `ToolRegistry`
### Required Parameters
- `ref`: `String`
  Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'button "Submit"'), or CSS selector with css= prefix (e.g., 'css=#my-element').

### Optional Parameters
- `element`: `String`
  Human-readable description of the element being verified, for logging.
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION