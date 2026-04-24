## Tool `web_select_option`

## Description
Select one or more options from a <select> dropdown element identified by its element ID, ARIA descriptor, or CSS selector.
Provide the option values or labels to select.

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeSelectOptionTool`

### Registered `PlaywrightNativeSelectOptionTool` in `ToolRegistry`
### Required Parameters
- `ref`: `String`
  Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'combobox "Category"'), or CSS selector with css= prefix (e.g., 'css=#my-select').
- `values`: `{
  "itemsType": {
    "name": "STRING"
  },
  "name": "ARRAY"
}`

### Optional Parameters
- `element`: `String`
  Human-readable description of the select element, for logging.
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION