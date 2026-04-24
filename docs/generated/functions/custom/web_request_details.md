## Tool `web_request_details`

## Description
Request additional detail in the next view hierarchy snapshot.
Call this when you need more information than the default compact element list provides.
The next turn's view hierarchy will include the requested details for ALL elements,
then automatically revert to the compact format on subsequent turns.

Available detail types:
- BOUNDS: Include bounding box coordinates {x,y,w,h} for each element.
  Useful for spatial reasoning, determining element positions, checking viewport visibility,
  or disambiguating visually similar elements by location.
- CSS_SELECTORS: Include CSS selectors for elements and surface hidden elements.
  Adds [css=...] annotations to existing elements that have an HTML id or data-testid.
  Also discovers elements that are invisible in the default compact list (e.g., unnamed
  divs with id or data-testid attributes) and lists them with their CSS selectors.
  Use the css= prefix in ref fields to target these elements (e.g., ref: 'css=#my-panel').
- OFFSCREEN_ELEMENTS: Include all elements regardless of viewport position.
  By default, elements outside the viewport are filtered out to save tokens. Request this
  to see all elements with offscreen ones annotated as (offscreen). Useful when you need
  to find elements that require scrolling to reach.

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeRequestDetailsTool`

### Registered `PlaywrightNativeRequestDetailsTool` in `ToolRegistry`
### Required Parameters
- `include`: `{
  "itemsType": {
    "entries": [
      "BOUNDS",
      "CSS_SELECTORS",
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