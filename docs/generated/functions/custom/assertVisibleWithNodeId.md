## Tool `assertVisibleWithNodeId`

## Description
Assert that the element with the given nodeId is visible on the screen. This will delegate to the appropriate assert tool (by text, resource ID, or accessibility text) based on the node's properties.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.AssertVisibleByNodeIdTrailblazeTool`

### Registered `AssertVisibleByNodeIdTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `nodeId`: `Integer`
  The nodeId of the element in the view hierarchy to assert visibility for. Do NOT use the nodeId 0.

### Optional Parameters
- `reason`: `String`
  Reasoning on why this element was chosen. Do NOT restate the nodeId.



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION