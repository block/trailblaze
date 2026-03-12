## Tool `requestViewHierarchyDetails`

## Description
Request additional detail in the next view hierarchy snapshot.
Call this when you need more information than the default compact element list provides.
The next turn's view hierarchy will include the requested details for ALL elements,
then automatically revert to the compact format on subsequent turns.

Available detail types:
- FULL_HIERARCHY: Include ALL nodes (even empty structural containers), bounding box
  coordinates (center: x,y), dimensions (size: WxH), and enabled/disabled state.
  Useful for spatial reasoning, determining element positions, understanding layout
  structure, or disambiguating visually similar elements by location.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.RequestViewHierarchyDetailsTrailblazeTool`

### Registered `RequestViewHierarchyDetailsTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `include`: `{
  "itemsType": {
    "entries": [
      "FULL_HIERARCHY"
    ],
    "name": "ENUM"
  },
  "name": "ARRAY"
}`

### Optional Parameters
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION