## Tool `web_verify_list_visible`

## Description
Verify that a list or group of elements contains the expected items.
Checks that each expected item text is visible within the container element.

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeVerifyListVisibleTool`

### Registered `PlaywrightNativeVerifyListVisibleTool` in `ToolRegistry`
### Required Parameters
- `ref`: `String`
  Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'list'), or CSS selector with css= prefix (e.g., 'css=#my-list').
- `items`: `{
  "itemsType": {
    "name": "STRING"
  },
  "name": "ARRAY"
}`

### Optional Parameters
- `element`: `String`
  Human-readable description of the list being verified, for logging.
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION