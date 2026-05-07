## Tool `web_verify_value`

## Description
Verify the value of an element on the page. Supports checking:
- TEXT: the text content of any element
- VALUE: the input value of a form field (input, textarea, select)
- ATTRIBUTE: the value of an HTML attribute

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeVerifyValueTool`

### Registered `PlaywrightNativeVerifyValueTool` in `ToolRegistry`
### Required Parameters
- `expected`: `String`
  The expected value to verify against.

### Optional Parameters
- `ref`: `String`
  Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'textbox "Email"'), or CSS selector with css= prefix (e.g., 'css=#email-input').
- `type`: `[
  "TEXT",
  "VALUE",
  "ATTRIBUTE"
]`
- `attribute`: `String`
  The attribute name to check (required when type is ATTRIBUTE).
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION