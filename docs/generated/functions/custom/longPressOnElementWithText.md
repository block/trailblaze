# Function `longPressOnElementWithText`

## Description
Invoking this function will trigger a long press on the provided text. Ensure that you 
provide the entire string to this function to streamline finding the corresponding view.

The text argument is required. Only provide additional fields if the text provided exactly
matches elsewhere on the screen. In this case the additional fields will be used to identify
the specific view to long press on.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.LongPressOnElementWithTextTrailblazeTool`

## Registered Tool Call to Open AI
```json
{
    "name": "longPressOnElementWithText",
    "parameters": {
        "type": "object",
        "properties": {
            "text": {
                "type": "string",
                "description": "The text to match on. This is required.\nNOTE:\n- The text can be a regular expression.\n- If more than one view matches the text, other optional properties are required to disambiguate."
            },
            "id": {
                "type": "string"
            },
            "index": {
                "type": "string"
            },
            "enabled": {
                "type": "boolean"
            },
            "selected": {
                "type": "boolean"
            }
        },
        "required": [
            "text"
        ]
    },
    "description": "Invoking this function will trigger a long press on the provided text. Ensure that you \nprovide the entire string to this function to streamline finding the corresponding view.\n\nThe text argument is required. Only provide additional fields if the text provided exactly\nmatches elsewhere on the screen. In this case the additional fields will be used to identify\nthe specific view to long press on."
}
```

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION