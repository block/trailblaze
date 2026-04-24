## Tool `web_click`

## Description
Click on a web element identified by its element ID, ARIA descriptor, or CSS selector.
Use the element ID from the page elements list (e.g., 'e5'), an ARIA descriptor (e.g., 'button "Submit"'),
or a CSS selector prefixed with 'css=' (e.g., 'css=#my-button', 'css=[data-testid="submit"]').

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeClickTool`

### Registered `PlaywrightNativeClickTool` in `ToolRegistry`
### Required Parameters
- `ref`: `String`
  Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'button "Submit"'), or CSS selector with css= prefix (e.g., 'css=#my-id', 'css=[data-testid="btn"]').

### Optional Parameters
- `element`: `String`
  Human-readable description of the element being clicked, for logging.
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION