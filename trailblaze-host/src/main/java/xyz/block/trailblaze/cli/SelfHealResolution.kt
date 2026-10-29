package xyz.block.trailblaze.cli

internal const val SELF_HEAL_ENV_VAR = "TRAILBLAZE_SELF_HEAL_ENABLED"

/**
 * The last tiers of `trailblaze run`'s self-heal precedence, shared by in-process and
 * daemon-delegated runs: the caller's [override] (flag, then env — see
 * [TrailCommand.resolveSelfHealOverride]) wins, else the persisted `trailblaze config self-heal`,
 * else off.
 */
internal fun resolveSelfHeal(
  override: Boolean?,
  persistedConfigReader: () -> Boolean? = ::readPersistedSelfHeal,
): Boolean = override ?: persistedConfigReader() ?: false

internal fun readPersistedSelfHeal(): Boolean? = CliConfigHelper.readConfig()?.selfHealEnabled
