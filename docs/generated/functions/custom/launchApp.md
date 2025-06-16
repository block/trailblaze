## Tool `launchApp`

## Description
Use this to open an app on the device as if a user tapped on the app icon in the launcher.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool`

### Registered `LaunchAppTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `appId`: `String`
  The package name of the app to launch. Example: 'com.example.app'

### Optional Parameters
- `launchMode`: `Enum(entries=[REINSTALL, RESUME, FORCE_RESTART])`
  Available App Launch Modes:
- "REINSTALL" (Default if unspecified) will launch the app as if it was just installed and never run on the device before.
- "RESUME" will launch the app like you would from the apps launcher.  If the app was in memory, it'll pick up where it left off.
- "FORCE_RESTART" will force stop the application and then launch the app like you would from the app launcher.



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION