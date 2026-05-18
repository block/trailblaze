## Tool `pressKey`

## Description
Press a special key that isn't used for regular text input. Examples:
- BACK: navigate to the previous page or state (Android only).
- ENTER: submit the current form or text input.
- HOME: go to the device's home screen / send the current app to the background.
- BACKSPACE: delete the character before the caret in the currently focused field.
- TAB: move focus to the next field.
- ESCAPE: dismiss the keyboard or current modal.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool`

### Registered `PressKeyTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `keyCode`: `[
  "BACK",
  "ENTER",
  "HOME",
  "BACKSPACE",
  "TAB",
  "ESCAPE"
]`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION