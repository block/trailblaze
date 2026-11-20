package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.config.MemoryKeyDeclaration
import xyz.block.trailblaze.config.PlatformConfig
import xyz.block.trailblaze.config.project.ResolvedTrailmap
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import xyz.block.trailblaze.config.project.TrailmapSource
import xyz.block.trailblaze.config.project.TrailmapTargetConfig
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry

/**
 * The memory keys in a trailmap's reach: its own, its dependency closure's, and those of every
 * toolset the closure loads — so an author relying on a dependency's or a toolset's tools sees the
 * keys those tools read and write. Memory is one flat namespace, so a key declared twice is a conflict.
 */
class TrailmapMemoryKeysTest {

  private fun key(description: String) = MemoryKeyDeclaration(description = description)

  private fun trailmap(
    id: String,
    dependencies: List<String> = emptyList(),
    toolSets: List<String>? = null,
    defaultToolSets: List<String>? = null,
    memory: Map<String, MemoryKeyDeclaration> = emptyMap(),
  ) = ResolvedTrailmap(
    manifest = TrailblazeTrailmapManifest(
      id = id,
      target = TrailmapTargetConfig(
        displayName = id,
        platforms = toolSets?.let { mapOf("android" to PlatformConfig(toolSets = it)) },
      ),
      dependencies = dependencies,
      defaults = defaultToolSets?.let { mapOf("android" to PlatformConfig(toolSets = it)) },
      memory = memory,
    ),
    source = TrailmapSource.Filesystem(File("/nonexistent/$id")),
    target = null,
    toolsets = emptyList(),
    tools = emptyList(),
    waypoints = emptyList(),
  )

  private fun toolSet(
    id: String,
    memory: Map<String, MemoryKeyDeclaration>,
    alwaysEnabled: Boolean = false,
    trailmapId: String? = null,
  ) = ToolSetCatalogEntry(
    id = id,
    description = id,
    toolClasses = emptySet(),
    alwaysEnabled = alwaysEnabled,
    memoryKeys = memory,
    trailmapId = trailmapId,
  )

  @Test
  fun `keys come from the trailmap, its dependencies and every toolset the closure loads`() {
    val app = trailmap(
      id = "app",
      dependencies = listOf("shared"),
      toolSets = listOf("accounts"),
      memory = mapOf("theme" to key("The run's theme.")),
    )
    val shared = trailmap(
      id = "shared",
      dependencies = listOf("base"),
      defaultToolSets = listOf("payments"),
      memory = mapOf("session_id" to key("The session a setup tool opened.")),
    )
    val base = trailmap(id = "base", memory = mapOf("locale" to key("The device locale.")))
    val unrelated = trailmap(id = "unrelated", memory = mapOf("other" to key("Not in app's closure.")))
    val catalog = listOf(
      toolSet("accounts", mapOf("account_email" to key("The email of the account a setup tool created.")), trailmapId = "identity"),
      toolSet("payments", mapOf("payment_id" to key("The payment a setup tool took."))),
      toolSet("core", mapOf("last_screen" to key("Written by an always-enabled tool.")), alwaysEnabled = true),
      toolSet("unused", mapOf("unused_key" to key("Not reachable."))),
    )
    val byId = listOf(app, shared, base, unrelated).associateBy { it.manifest.id }

    val reach = TrailmapMemoryKeys.inReach(app, byId, catalog)

    assertEquals(
      mapOf(
        "account_email" to "toolset `accounts`",
        "last_screen" to "toolset `core`",
        "locale" to "trailmap `base`",
        "payment_id" to "toolset `payments`",
        "session_id" to "trailmap `shared`",
        "theme" to "trailmap `app`",
      ),
      reach.keys.associate { it.name to it.declaredBy },
    )
    assertEquals(emptyList(), reach.conflicts)
    // Grouped under the declaring trailmap, or the trailmap shipping the toolset (its id otherwise).
    assertEquals(
      mapOf(
        "account_email" to "identity",
        "last_screen" to "core",
        "locale" to "base",
        "payment_id" to "payments",
        "session_id" to "shared",
        "theme" to "app",
      ),
      reach.keys.associate { it.name to it.trailmap },
    )
    val theme = PerTrailmapClientDtsEmitter.memoryKeysFor(app, byId, catalog).single { it.name == "theme" }
    assertEquals("The run's theme.", theme.description)
    assertEquals("trailmap `app`", theme.declaredBy)
  }

  @Test
  fun `a key declared twice in reach is a conflict and the surface keeps the nearest declaration`() {
    val app = trailmap(
      id = "app",
      dependencies = listOf("shared"),
      toolSets = listOf("accounts"),
      memory = mapOf("account_email" to key("The app's view of the account email.")),
    )
    val shared = trailmap(id = "shared", memory = mapOf("account_email" to key("Shared's view.")))
    val catalog = listOf(toolSet("accounts", mapOf("account_email" to key("The toolset's view."))))

    val reach = TrailmapMemoryKeys.inReach(app, mapOf("app" to app, "shared" to shared), catalog)

    assertEquals("The app's view of the account email.", reach.keys.single().declaration.description)
    val conflict = reach.conflicts.single()
    assertEquals(listOf("trailmap `app`", "trailmap `shared`", "toolset `accounts`"), conflict.declaredBy)
    assertEquals(
      "memory key 'account_email' is declared by trailmap `app`, trailmap `shared` and toolset `accounts`, " +
        "and trailmap `app` reaches all of them. Memory keys are global, so each is declared once: delete " +
        "the copy, or move the declaration into a dependency they share.",
      conflict.message(),
    )
  }

  @Test
  fun `two dependencies declaring one key conflict once, naming every trailmap that reaches both`() {
    val left = trailmap(id = "left", memory = mapOf("token" to key("Left's token.")))
    val right = trailmap(id = "right", memory = mapOf("token" to key("Right's token.")))
    val app = trailmap(id = "app", dependencies = listOf("left", "right"))
    val other = trailmap(id = "other", dependencies = listOf("right", "left"))
    val byId = listOf(left, right, app, other).associateBy { it.manifest.id }

    val conflicts = TrailmapMemoryKeys.conflicts(listOf(app, other, left), byId, emptyList())

    assertEquals(listOf("app", "other"), conflicts.single().reachedBy)
  }

  @Test
  fun `a trailmap with nothing declared in reach gets no keys`() {
    val app = trailmap(id = "app")
    assertEquals(TrailmapMemoryKeys.InReach(emptyList(), emptyList()), TrailmapMemoryKeys.inReach(app, mapOf("app" to app), emptyList()))
  }
}
