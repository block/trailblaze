## Tool `inputText`

## Description
Type characters into the currently focused text field.
- NOTE: If the text field is not focused, tap on it first.
- NOTE: If the field already contains text you want to replace, use eraseText first.
- NOTE: After typing, consider closing the soft keyboard to avoid issues with the app.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool`

### Registered `InputTextTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `text`: `String`
  The text to match on. This is required.
NOTE:
- The text can be a regular expression.
- If more than one view matches the text, other optional properties are required to disambiguate.

### Optional Parameters
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION