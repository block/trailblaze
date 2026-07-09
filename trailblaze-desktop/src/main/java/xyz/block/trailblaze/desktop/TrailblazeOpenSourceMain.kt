@file:JvmName("Trailblaze")

package xyz.block.trailblaze.desktop

import xyz.block.trailblaze.cli.TrailblazeCli
import xyz.block.trailblaze.host.networkcapture.AndroidNetworkCaptureRegistry
import xyz.block.trailblaze.host.networkcapture.CompositeAndroidNetworkCaptureActivator
import xyz.block.trailblaze.host.networkcapture.MitmproxyAndroidNetworkCaptureActivator

/**
 * Open source Trailblaze desktop application entry point.
 *
 * Uses the shared CLI infrastructure from trailblaze-host.
 */
fun main(args: Array<String>) {
  // Android network capture for OSS / external users: the mitmproxy MITM proxy path captures ANY
  // third-party app's traffic (no in-app capture plugin required). It's OFF by default and turned on
  // per-session via the TRAILBLAZE_ANDROID_PROXY_CAPTURE opt-in; there's no fallback capturer here,
  // so when the opt-in is off the Android capture branch is simply a no-op.
  AndroidNetworkCaptureRegistry.activator = CompositeAndroidNetworkCaptureActivator(
    proxy = MitmproxyAndroidNetworkCaptureActivator,
    fallback = null,
    useProxy = { CompositeAndroidNetworkCaptureActivator.proxyCaptureEnabledFromEnv() },
  )

  TrailblazeCli.run(
    args = args,
    appProvider = { OpenSourceTrailblazeDesktopApp() },
    configProvider = { OpenSourceTrailblazeDesktopAppConfig() },
  )
}
