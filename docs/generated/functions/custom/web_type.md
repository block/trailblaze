## Tool `web_type`

## Description
Type text into a web input element identified by its element ID, ARIA descriptor, or CSS selector.
By default this clears the field first and fills in the new text.
Set clearFirst to false to append text instead.

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeTypeTool`

### Registered `PlaywrightNativeTypeTool` in `ToolRegistry`
### Required Parameters
- `text`: `String`
  The text to type into the element.
- `ref`: `String`
  Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'textbox "Email"'), or CSS selector with css= prefix (e.g., 'css=#email-input').

### Optional Parameters
- `element`: `String`
  Human-readable description of the element being typed into, for logging.
- `clearFirst`: `Boolean`
  If true (default), clear the field before typing. If false, append to existing text.
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION