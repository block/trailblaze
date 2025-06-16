## Tool `longPressOnElementWithText`

## Description
Invoking this function will trigger a long press on the provided text. Ensure that you 
provide the entire string to this function to streamline finding the corresponding view.

The text argument is required. Only provide additional fields if the text provided exactly
matches elsewhere on the screen. In this case the additional fields will be used to identify
the specific view to long press on.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.LongPressOnElementWithTextTrailblazeTool`

### Registered `LongPressOnElementWithTextTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `text`: `String`
  The text to match on. This is required.
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