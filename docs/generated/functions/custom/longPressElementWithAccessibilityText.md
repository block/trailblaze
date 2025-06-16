## Tool `longPressElementWithAccessibilityText`

## Description
Invoking this function will trigger a long press on a view with the provided accessibility
text. This will commonly be used when long pressing on visual elements on the screen that do
not have text to identify it. This includes images or avatars with text, prefer to use the
accessibility text for these views because the text for an image or avatar will not resolve.
Ensure that you provide the entire accessibilityText string to this function to streamline 
finding the corresponding view.

The text argument is required. Only provide additional fields if the accessibility text
provided exactly matches elsewhere on the screen. In this case the additional fields will be
used to identify the specific view to long press on.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.LongPressElementWithAccessibilityTextTrailblazeTool`

### Registered `LongPressElementWithAccessibilityTextTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `accessibilityText`: `String`
  The accessibilityText to match on. This is required.
NOTE:
- The text can be a regular expression.
- If more than one view matches the text, other optional properties are required to disambiguate.

### Optional Parameters
- `id`: `String`
- `index`: `String`
- `enabled`: `Boolean`
- `selected`: `Boolean`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION