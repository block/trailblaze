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
- OFFSCREEN_ELEMENTS: Include all elements regardless of screen position.
  By default, elements outside the screen are filtered out to save tokens. Request this
  to see all elements with offscreen ones annotated as (offscreen). Useful when you need
  to find elements that require scrolling to reach.

### Command Class
`xyz.block.trailblaze.toolcalls.commands.RequestViewHierarchyDetailsTrailblazeTool`

### Registered `RequestViewHierarchyDetailsTrailblazeTool` in `ToolRegistry`
### Required Parameters
- `include`: `{
  "itemsType": {
    "entries": [
      "FULL_HIERARCHY",
      "OFFSCREEN_ELEMENTS"
    ],
    "name": "ENUM"
  },
  "name": "ARRAY"
}`

### Optional Parameters
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION