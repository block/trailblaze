package xyz.block.trailblaze.llm.config

actual fun readBuiltInProviderYamlResources(): Map<String, String> =
  readBuiltInProviderYamlResourcesFromClasspath()

actual fun readBuiltInProviderYaml(providerId: String): String? =
  readBuiltInProviderYamlFromClasspath(providerId)
