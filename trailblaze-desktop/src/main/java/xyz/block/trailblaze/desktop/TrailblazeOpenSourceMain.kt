@file:JvmName("Trailblaze")
@file:OptIn(ExperimentalCoroutinesApi::class)

package xyz.block.trailblaze.desktop

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main() {
  OpenSourceTrailblazeDesktopApp().startTrailblazeDesktopApp()
}
