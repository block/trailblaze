# Function `swipe`

## Description
Swipes the screen in the specified direction. This is useful for navigating through long lists or pages.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool`

## Registered Tool Call to Open AI
```json
{
    "name": "swipe",
    "parameters": {
        "type": "object",
        "properties": {
            "direction": {
                "type": "string",
                "description": "Valid values: UP, DOWN, LEFT, RIGHT"
            },
            "swipeOnElementText": {
                "type": "string",
                "description": "The text value to swipe on. If not provided, the swipe will be performed on the center of the screen."
            }
        },
        "required": [
            "direction"
        ]
    },
    "description": "Swipes the screen in the specified direction. This is useful for navigating through long lists or pages."
}
```

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION