## Tool `eraseText`

## Description
Erases characters from the currently focused text field.
- If charactersToErase is omitted or null, ALL text in the field is erased.
- If a number is provided, that many characters are erased from the end.
- Use this BEFORE inputText when you need to replace existing text in a field
  (e.g. a search bar or form field that already has content).

### Source
YAML-defined tool (no Kotlin class). Expanded from `trailblaze-config/tools/eraseText.yaml`.
### Optional Parameters
- `charactersToErase`: `Integer`
  Number of characters to erase from the end (null or omitted to erase all).



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION