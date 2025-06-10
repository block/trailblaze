# Function `tapOnElementByNodeId`

## Description
Provide the nodeId of the element you want to tap on in the nodeId parameter.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.TapOnElementByNodeIdTrailblazeTool`

## Registered Tool Call to Open AI
```json
{
    "name": "tapOnElementByNodeId",
    "parameters": {
        "type": "object",
        "properties": {
            "reason": {
                "type": "string",
                "description": "Reasoning on why this element was chosen. Do NOT restate the nodeId."
            },
            "nodeId": {
                "type": "object",
                "description": "The nodeId of the element in the view hierarchy that will be tapped on. Do NOT use the nodeId 0."
            },
            "longPress": {
                "type": "boolean",
                "description": "A standard tap is default, but return 'true' to perform a long press instead."
            }
        },
        "required": [
            "nodeId"
        ]
    },
    "description": "Provide the nodeId of the element you want to tap on in the nodeId parameter."
}
```

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION