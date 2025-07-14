package xyz.block.trailblaze.ui

class Greeting {
  fun greet(): String {
    val platformName = PlatformInfo().getPlatformName()
    return "Hello, $platformName!"
  }
}
