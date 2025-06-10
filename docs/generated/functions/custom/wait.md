# Function `wait`

## Description
Use this when you think you see a loading screen.
This will force the app to wait for a specified amount of time.
Prefer using this over the back button.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool`

## Registered Tool Call to Open AI
```json
{
    "name": "wait",
    "parameters": {
        "type": "object",
        "properties": {
            "timeToWaitInSeconds": {
                "type": "integer",
                "description": "Unit: seconds. Default Value: 5 seconds."
            }
        },
        "required": []
    },
    "description": "Use this when you think you see a loading screen.\nThis will force the app to wait for a specified amount of time.\nPrefer using this over the back button."
}
```

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION