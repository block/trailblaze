# Locator Request

Please identify the best locator for an element that answers this questions or matches this request:

**Description**: ${identifier}

Here is the current view hierarchy:
```json
${view_hierarchy}
```

You MUST use the elementRetriever tool to return your response. Do not return raw JSON.

The tool requires these parameters:
- identifier: "${identifier}" (the user's description of the element)
- locatorType: One of "RESOURCE_ID", "ACCESSIBILITY_TEXT", or "TEXT"
- value: The exact string value of the locator from the view hierarchy
- index: (Optional, default 0) The 0-based index if multiple elements share the same locator
- success: true if you found a reliable locator, false otherwise
- reason: Brief explanation of your choice or why you couldn't find a locator

Example of using the tool:

elementRetriever(
  identifier = "the add to cart button",
  locatorType = RESOURCE_ID,
  value = "com.example.app:id/add_to_cart_button",
  index = 0,
  success = true,
  reason = "Found button with matching resource ID"
)

IMPORTANT:
1. The value MUST be an exact match from the view hierarchy, not a guess or approximation
2. If multiple elements have the same locator value, use the index parameter to specify which one
3. Check for exact IDs in the view hierarchy - don't make them up or guess
4. Analyze the view hierarchy thoroughly before responding

Remember to use the exact text from the view hierarchy for the value parameter. 