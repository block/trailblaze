# Function `tapOnPoint`

## Description
Taps on the UI at the provided coordinates.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool`

## Registered Tool Call to Open AI
```json
{
    "name": "tapOnPoint",
    "parameters": {
        "type": "object",
        "properties": {
            "x": {
                "type": "integer",
                "description": "The center X coordinate for the clickable element"
            },
            "y": {
                "type": "integer",
                "description": "The center Y coordinate for the clickable element"
            }
        },
        "required": [
            "x",
            "y"
        ]
    },
    "description": "Taps on the UI at the provided coordinates."
}
```

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION