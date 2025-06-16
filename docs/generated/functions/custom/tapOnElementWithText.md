## Tool `tapOnElementWithText`

## Description
Invoking this function will trigger a tap on the provided text. Ensure that you provide the
entire string to this function to streamline finding the corresponding view.

The text argument is required. Only provide additional fields if the text provided exactly
matches elsewhere on the screen. In this case the additional fields will be used to identify
the specific view to tap on.

NOTE:
- This will only work if the item is actually visible in the screenshot, even if the item is in the view hierarchy.
- You may need to scroll down the page or close the keyboard if it is not visible in the screenshot.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.TapOnElementWithTextTrailblazeTool`

### Registered `TapOnElementWithTextTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `text`: `String`
  The text to match on. This is required.
NOTE:
- The text can be a regular expression.
- If more than one view matches the text, other optional properties are required to disambiguate.

### Optional Parameters
- `index`: `Integer`
  0-based index of the view to select among those that match all other criteria.
- `id`: `String`
  Regex for selecting the view by id.  This is helpful to disambiguate when multiple views have the same text.
- `enabled`: `Boolean`
- `selected`: `Boolean`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION