## Tool `web_assert_network_event`

## Description
Assert that a specific event name appeared in the network traffic captured during this session.

Use this to verify that instrumentation signals, analytics events, or user-journey tracking
calls actually fired when a flow was completed. The check scans request URLs and request bodies
(both inlined text and on-disk blobs) for the given event name string.

Only outgoing requests (REQUEST_START phase) are checked so each network exchange is counted
once regardless of how many response events accompany it.

The tool polls up to the configured timeout so it is safe to call immediately after a UI action
without waiting for the browser to flush the analytics beacon. At least one scan always runs
regardless of timeoutMs so passing timeoutMs=0 performs a single immediate check.

This is a test assertion — it will fail the trail if the event is not found in the network log.

Example uses:
- Verify a user-journey signal fired: eventName="adjust-stock"
- Verify an analytics call was made: eventName="checkout-started"

### Command Class
`xyz.block.trailblaze.playwright.tools.PlaywrightNativeAssertNetworkEventTool`

### Registered `PlaywrightNativeAssertNetworkEventTool` in `ToolRegistry`
### Required Parameters
- `eventName`: `String`
  The event name to search for in captured network traffic. Matched against request URLs and request body text (case-insensitive). Must not be blank.

### Optional Parameters
- `timeoutMs`: `Integer`
  Maximum milliseconds to wait for the event to appear in captured traffic. Defaults to 5000. Increase for slow analytics pipelines. One complete scan always runs before the timeout is enforced.
- `reasoning`: `String`



<hr/>

**NOTE**: THIS IS GENERATED DOCUMENTATION