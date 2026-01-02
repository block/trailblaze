@file:JvmName("Trailblaze")
@file:OptIn(ExperimentalCoroutinesApi::class)

package xyz.block.trailblaze.desktop

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
  val headless = args.contains("--headless") || args.contains("-h")
  OpenSourceTrailblazeDesktopApp().startTrailblazeDesktopApp(headless = headless)
}
