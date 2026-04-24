## Tool `compose_type`

## Description
Type text into a UI input element.
Identify the element using its element ID from the view hierarchy (e.g., 'e3'),
or by existing text content.
By default this clears the field first. Set clearFirst to false to append text instead.

### Command Class
`xyz.block.trailblaze.compose.driver.tools.ComposeTypeTool`

### Registered `ComposeTypeTool` in `ToolRegistry`
### Required Parameters
- `text`: `String`
  The text to type into the element.

### Optional Parameters
- `elementId`: `String`
  Element ID from the view hierarchy, e.g., 'e3'. Preferred method.
- `testTag`: `String`
  Accessibility identifier of the input element.
- `existingText`: `String`
  The existing text content of the input element.
- `element`: `String`
  Human-readable description of the element being typed into, for logging.
- `clearFirst`: `Boolean`
  If true (default), clear the field before typing. If false, append to existing text.



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION