# Function `launchApp`

## Description
Use this to open an app on the device as if a user tapped on the app icon in the launcher.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool`

## Registered Tool Call to Open AI
```json
{
    "name": "launchApp",
    "parameters": {
        "type": "object",
        "properties": {
            "appId": {
                "type": "string",
                "description": "The package name of the app to launch. Example: 'com.example.app'"
            },
            "launchMode": {
                "type": "string",
                "description": "Available App Launch Modes:\n- \"REINSTALL\" (Default if unspecified) will launch the app as if it was just installed and never run on the device before.\n- \"RESUME\" will launch the app like you would from the apps launcher.  If the app was in memory, it'll pick up where it left off.\n- \"FORCE_RESTART\" will force stop the application and then launch the app like you would from the app launcher."
            }
        },
        "required": [
            "appId"
        ]
    },
    "description": "Use this to open an app on the device as if a user tapped on the app icon in the launcher."
}
```

<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION