## Tool `assertVisibleWithResourceId`

## Description
Asserts that an element with the provided resource ID is visible on the screen. The resourceId argument is required. Only provide additional fields if the resource ID provided exactly matches elsewhere on the screen. In this case, the additional fields will be used to identify the specific view to assert visibility for.

NOTE:
- This will wait for the item to appear if it is not visible yet.
- You may need to scroll down the page or close the keyboard if it is not visible in the screenshot.
- Use this tool whenever an objective begins with the word expect, verify, confirm, or assert (case-insensitive).

### Command Class
`xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithResourceIdTrailblazeTool`

### Registered `AssertVisibleWithResourceIdTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `resourceId`: `String`
  Resource ID of the view to assert visibility for. This should be the full resource ID string.

### Optional Parameters
- `accessibilityText`: `String`
  Regex for selecting the view by accessibility text. This is helpful to disambiguate when multiple views have the same resource ID.
- `index`: `Integer`
  0-based index of the view to select among those that match all other criteria.
- `enabled`: `Boolean`
- `selected`: `Boolean`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION