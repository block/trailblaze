# Function `inputText`

## Description
This will type characters into the currently focused text field. This is useful for entering text.
- NOTE: If the text input field is not currently focused, please tap on the text field to focus it before using this command.
- NOTE: After typing text, considering closing the soft keyboard to avoid any issues with the app.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool`

## Registered Tool Call to Open AI
```json
{
    "name": "inputText",
    "parameters": {
        "type": "object",
        "properties": {
            "text": {
                "type": "string",
                "description": "The text to match on. This is required.\nNOTE:\n- The text can be a regular expression.\n- If more than one view matches the text, other optional properties are required to disambiguate."
            }
        },
        "required": [
            "text"
        ]
    },
    "description": "This will type characters into the currently focused text field. This is useful for entering text.\n- NOTE: If the text input field is not currently focused, please tap on the text field to focus it before using this command.\n- NOTE: After typing text, considering closing the soft keyboard to avoid any issues with the app."
}
```

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION