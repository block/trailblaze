## Tool `inputText`

## Description
This will type characters into the currently focused text field. This is useful for entering text.
- NOTE: If the text input field is not currently focused, please tap on the text field to focus it before using this command.
- NOTE: After typing text, considering closing the soft keyboard to avoid any issues with the app.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool`

### Registered `InputTextTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `text`: `String`
  The text to match on. This is required.
NOTE:
- The text can be a regular expression.
- If more than one view matches the text, other optional properties are required to disambiguate.



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION