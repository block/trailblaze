## Trailblaze YAML Syntax

Trailblaze uses yaml to define the tests, including the recorded steps for natural language prompts.

There are currently three top-level tags:

`- prompts:`

`- tools:`

`- maestro:`

### Writing prompts

When you want to use a natural language prompt use the `- prompts:`  tag. This allows you to define one or more individual prompt steps.

A prompt step **requires** the text of the prompt you want to send to the LLM for a particular test step. This test is the instruction you want the LLM to take in the app, such as “Click continue” or “enter [jack@block.xyz](mailto:jack@block.xyz) in the email field”.

There are two optional values:

`recordable` indicates if a prompt is allowed to be recorded, which defaults to true.

`recording` stores the existing recorded steps taken for the prompts. These are saved as a series of `tools`.

```yaml
- prompts:
  - text: "This is a recordable prompt with no recorded steps"
  - text: "This is a recordable prompt with recorded steps"
    recording:
      tools:
        - inputText:
          text: Hello World
        - pressBack: {} 
  - text: "This is a non-recordable prompt"
    recordable: false
```

The above example would parse into three prompt steps.

- The first would run with AI since there are no recorded steps, but these steps can be added to this step in the future.
- The second would run from the existing recording.
- The third would also run with AI, but since it is marked as not recordable there will never be a recording for this prompt step.

## Using tools

Tools are how Trailblaze defines what the LLM is allowed to do, and also a way to provide custom behavior in tests without hitting the LLM.

Many of the tools are meant to be used by the LLM when it responds to a prompt, although some can be used manually when writing the test.

### Basic Device Interaction

**Tap On Point**

Taps on a specific pixel on the screen. Try not to use this version if possible since it is not a good fit for recorded test steps. However it can be very useful when interactively using the agent to explore a new test scenario.

```yaml
- tools:
  - tapOnPoint:
      x: 100
      y: 200
```

**Input Text**

This will type characters into the currently focused text field.

```yaml
- tools:
  - inputText:
      text: Text to enter
```

**Erase Text**

Erases the specified number of characters from the currently selected text field.  If no number is provided, it will erase everything in the field.

```yaml
# Erase all the text in the input field
- tools:
  - eraseText: {}
    
# Erase 10 characters from the input field
- tools:
  - eraseText:
      charactersToErase: 10
```

**Hide Keyboard**

This hide the keyboard on the screen. This is useful to do after entering text into an input field so the next LLM request includes more screen data.

```yaml
- tools:
  - hideKeyboard: {}
```

**Press Back**

Presses the virtual back button on an Android device.

```yaml
- tools:
  - pressBack: {}
```

**Swipe**

Swipes the screen in the specified direction. This is useful for navigating through long lists or pages. These events are triggered from the center of the device screen.

```yaml
# Swipe up from the center of the screen
- tools:
  - swipe:
      direction: UP

# Swipe down from the center of the screen
- tools:
  - swipe:
      direction: DOWN

# Swipe left from the center of the screen
- tools:
  - swipe:
      direction: LEFT
  
# Swipe right from the center of the screen
- tools:
  - swipe:
      direction: RIGHT
```

The swipe gestures can also be performed on a specific piece of text. This can be useful if you want to perform a horizontal swipe on a list of items that are not on the center of the screen.

```yaml
# Swipe up from the center of the text
- tools:
  - swipe:
      direction: UP
      swipeOnElementText: Text

# Swipe down from the center of the text
- tools:
  - swipe:
      direction: DOWN
      swipeOnElementText: Text

# Swipe left from the center of the text
- tools:
  - swipe:
      direction: LEFT
      swipeOnElementText: Text

# Swipe right from the center of the text
- tools:
  - swipe:
      direction: RIGHT
      swipeOnElementText: Text
```

**Wait**

This will force the app to wait for a specified amount of time. This tool should be used only by the LLM when handling prompts.

```yaml
# Wait for the default time period (5 seconds)
- tools:
  - wait: {}

# Wait for a specified amount of time
- tools:
  - wait:
      timeToWaitInSeconds: 15
```

**Launch App**

Use this to open an app on the device as if a user tapped on the app icon in the launcher.

```yaml
# Default launch app command (will reinstall the app)
- tools:
  - launchApp:
      appId: com.squareup.cash.beta.debug

# Launch the app with the reinstall launch mode
- tools:
  - launchApp:
      appId: com.squareup.cash.beta.debug
      launchMode: REINSTALL

# Launch the app with the resume launch mode
- tools:
  - launchApp:
      appId: com.squareup.cash.beta.debug
      launchMode: RESUME

# Launch the app with the force restart launch mode
- tools:
  - launchApp:
      appId: com.squareup.cash.beta.debug
      launchMode: FORCE_RESTART
```

### Interact with elements by property

In addition to tapping on specific points on the screen, you can also specify what you want to interact with by text and accessibility text.

**Tap on element with text**

This tool will search the view hierarchy for an element with matching text. If only one element is found then a tap event will be triggered on it.

```yaml
- tools:
  - tapOnElementWithText:
      text: Sign Out
```

**Tap on element with accessibility text**

This tool will search the view hierarchy for an element with matching accessibility text. If only one element is found then a tap event will be triggered on it.

```yaml
- tools:
  - tapOnElementWithAccessibilityText:
      accessibilityText: Plus
```

**Long press on element with text**

This tool will search the view hierarchy for an element with matching text. If only one element is found then a long press event will be triggered on it.

```yaml
- tools:
  - longPressOnElementWithText:
      text: Sign Out
```

**Long press on element with accessibility text**

This tool will search the view hierarchy for an element with matching accessibility text. If only one element is found then a long press event will be triggered on it.

```yaml
- tools:
  - longPressOnElementWithAccessibilityText:
      accessibilityText: Add Account
```

### Assertions

**AssertVisibleWithTextTrailblazeTool**

This tool will verify that there is an element on the screen that contains the provided text.
This tool delegates to Maestro under the hood.

```yaml
- tools:
  - assertVisibleWithText:
      text: Sign Out
```

**AssertVisibleWithAccessibilityTextTrailblazeTool**

This tool will verify that there is an element on the screen that contains the provided accessibility text.
This tool delegates to Maestro under the hood.

```yaml
- tools:
  - assertVisibleWithAccessibilityText:
      text: Add Account
```

**AssertVisibleWithResourceIdTrailblazeTool**

```yaml
- tools:
  - assertVisibleWithResourceId:
      resourceId: "com.trailblaze.development:id/check"
```

This tool will verify that there is an element on the screen that contains the provided resource ID.
This tool delegates to Maestro under the hood.

### Memory tools

Trailblaze provides a set of tools that allow you to remember values from the screen state for later comparison in the test.

**Remember Number**

This tool allows you to identify a numerical value on the current screen with a natural language prompt. The LLM will find the value on the screen and send it back. This value will then be stored in the agent memory for the current test run by the variable name provided.

These remembered values can be used in the assertions below.

```yaml
- tools:
  - rememberNumber:
    prompt: here is a prompt
    variable: promptVar
```

**Remember Text**

This tool allows you to identify some text on the current screen with a natural language prompt. The LLM will find the text on the screen and send it back. This value will then be stored in the agent memory for the current test run by the variable name provided.

These remembered values can be used in the assertions below.

```yaml
- tools:
  - rememberNumber:
    prompt: here is a prompt
    variable: promptVar
```

**Remember with AI**

This is a generic remember tool that will find some value on the screen and save it to the agent memory under the provided variable name. These values are saved as a string.

These remembered values can be used in the assertions below.

```yaml
- tools:
  - rememberWithAi:
    prompt: here is a prompt
    variable: promptVar
```

**Assert Equals**

This tool allows you to verify that two values are equal, and it has the capability to access data from the agent memory.

In the following example it will pull the `someVariable` value from the agent memory and compare it to “some expected value”. If these values are equivalent then the tool will succeed.

```yaml
- tools:
  - assertEquals:
    actual: {{someVariable}}
    expected: "some expected value"
```

**Assert Not Equals**

This tool allows you to verify that two values are not equal, and it has the capability to access data from the agent memory.

In the following example it will pull the `someVariable` value from the agent memory and compare it to “some expected value”. If these values are not equal then the tool will succeed.

```yaml
- tools:
  - assertNotEquals:
    actual: {{someVariable}}
    expected: "some expected value"
```

**Assert Math**

This tool allows you to verify value changes during your test run. The expression is interpolated and it can access the remembered values stored in the agent memory.

The following example is from a test that verifies adding a water bottle to the stock count increments the number the user sees by 1.

Values in the square bracket syntax: `[[...]]`  are interpolated just in time from the current screen. The screen state is sent to the LLM with the prompt, in this case “number of water bottles available”, and the LLM will return that value back.

Values in the curly bracket syntax: `{{...}}`  are pulled from the agent memory. In this case the `stockCount` variable will be pulled from the agent memory and will be subtracted from the evaluated “number of water bottles available” value. If the difference of these two values is 1 then this assertion passes.

```yaml
- tools:
  - assertMath:
      expression: "[[number of water bottles available]] - {{stockCount}}"
      expected: 1
```

**Assert with AI**

This tool allows you to verify some screen state using the LLM. This is meant to handle true / false verifications. The prompt is sent to the LLM which returns a boolean value. If the response is true then the assertion passes, otherwise it will fail the test.

```yaml
- tools:
  - assertWithAi:
      prompt: There are more than one recipients available for the payment.
```

### Recording tools

These tools are not meant to be used within the test yaml. These are tools provided to the LLM when the Set of Mark prompting logic is enabled. This assists the LLM when selecting the view to interact with and is only valid for the single request where the node IDs are specified.

**AssertVisibleByNodeIdTrailblazeTool**

Assert that the element with the given nodeId is visible on the screen. This will delegate to the appropriate assert tool (by text, resource ID, or accessibility text) based on the node's properties.

**TapOnElementByNodeIdTrailblazeTool**

This will perform a tap event on the screen element with a particular node ID generated during the set of mark preprocessing step.

**ObjectiveStatusTrailblazeTool**

Use this tool to indicate the status of the current objective. Trailblaze uses this to determine when to move on to the next prompt or tool.

### Using Maestro

A subset of maestro commands are directly supported by Trailblaze. These need to be nested under the `- maestro:` tag in the test file. You can define any number of maestro commands to run under this tag.

```yaml
- maestro:
  - assertVisible: "Enter your email or phone number"
  - assertNotVisible: "Sign out"
```

Supported Maestro commands:

- [assertVisible](https://docs.maestro.dev/api-reference/commands/assertvisible)
- [assertNotVisible](https://docs.maestro.dev/api-reference/commands/assertnotvisible)
- [back](https://docs.maestro.dev/api-reference/commands/back)
- [clearState](https://docs.maestro.dev/api-reference/commands/clearstate)
- [eraseText](https://docs.maestro.dev/api-reference/commands/erasetext)
- [hideKeyboard](https://docs.maestro.dev/api-reference/commands/hidekeyboard)
- [inputText](https://docs.maestro.dev/api-reference/commands/inputtext)
- [killApp](https://docs.maestro.dev/api-reference/commands/killapp)
- [launchApp](https://docs.maestro.dev/api-reference/commands/launchapp)
- [pressKey](https://docs.maestro.dev/api-reference/commands/presskey)
- [scrollVertical](https://docs.maestro.dev/api-reference/commands/scroll)
- [stopApp](https://docs.maestro.dev/api-reference/commands/stopapp)
- [swipe](https://docs.maestro.dev/api-reference/commands/swipe)
- [takeScreenshot](https://docs.maestro.dev/api-reference/commands/takescreenshot)
- [tap](https://docs.maestro.dev/api-reference/commands/tapon)