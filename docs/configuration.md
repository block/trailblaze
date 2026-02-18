---
title: Configuration
---

## On-Device Android Instrumentation Arguments
* `trailblaze.aiEnabled` (defaults to `true`) - This will have the Trailblaze SDK send all requests to the LLM.  When `false`, only recordings can be used.
* `trailblaze.reverseProxy` (defaults to `false`) - This will enable the reverse proxy for all Trailblaze traffic.
  * When `false`, logging traffic is sent to `https://10.0.2.2:<httpsPort>`, the default Android Emulator networking loopback address.
  * When `true`, the logs are sent through `https://localhost:<httpsPort>` and using `adb reverse tcp:<httpsPort> tcp:<httpsPort>` are forwarded to the host running the Trailblaze app.
    * This means all Trailblaze SDK Traffic is re-routed through `adb` and then the logs server reverse proxies the traffic to the final host.
    * This is important because it allows the Trailblaze Agent to run on-device, but not require a network connection.
    * It is also helpful/important because in the future it will allow you to not send your API Keys to the device itself, but add the `Authorization` information via the reverse proxy.
* `trailblaze.httpsPort` (defaults to `8443`) - The HTTPS port for the Trailblaze server. Override this when running multiple Trailblaze instances.
* `trailblaze.logsEndpoint` - Defaults to the same values as the `reverseProxy` uses.  You can use this value if you want to use a remote logs server.  NOTE: Logging timeouts are set to 5 seconds as they are expected to be fast.