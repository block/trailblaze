## Tool `eraseText`

## Description
Erases characters from the currently focused text field.
- Pass the number of characters you want to erase based on the text currently visible
  in the field (count from the view hierarchy). Pass a large number (e.g. 500) to erase
  all text when you cannot determine the exact count.
- Omit charactersToErase entirely to erase all text in the field.
- Use this BEFORE inputText when you need to replace existing text in a field
  (e.g. a search bar or form field that already has content).

### Source
YAML-defined tool (no Kotlin class). Expanded from `trailblaze-config/tools/eraseText.yaml`.
### Optional Parameters
- `charactersToErase`: `Integer`
  Number of characters to erase from the end. Estimate from the field's current text in the view hierarchy. Omit to erase all text in the field.



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION