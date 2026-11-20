package xyz.block.trailblaze.ios.tools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * What one `ios_simctl` argv may run, checked before anything runs. Plain `simctl` arguments, but
 * only an allowlist of subcommands, each with a fixed shape:
 *
 * - `terminate`, `launch` (app arguments allowed, no environment), `openurl`, `keychain <device>
 *   reset`, `location`, `privacy`, `get_app_container`, `ui`, `status_bar`, `push` and `pbcopy`
 *   (input from stdin only), `pbpaste`, `listapps`, `appinfo`;
 * - `spawn <device>` runs only `defaults`, `notifyutil -p|-s` and `log show`. `log show` is
 *   deprecated: read app signals with `ctx.events`. It stays until its remaining callers move off
 *   it.
 *
 * The device is always the session's simulator: `booted`, its UDID or an omitted device all resolve
 * to it, and any other UDID is refused. Nothing here lets a caller choose a host path to read or
 * write: `launch --stdout/--stderr` are refused, `push` and `location start` take no file, and a
 * `defaults` path domain must resolve inside this simulator's app containers ([IosSimctl] checks
 * that, since it needs the filesystem).
 */
internal object IosSimctlArgv {

  /** A validated command, with the device already resolved to the session's simulator. */
  sealed interface Command {
    /** A simctl argv to run as is, with [stdin] written to it (`push`, `pbcopy`). */
    data class Plain(val args: List<String>, val stdin: String? = null) : Command {
      override fun toString() = "Plain(args=$args, stdin=${if (stdin == null) "null" else "<${stdin.length} chars>"})"
    }

    /** `terminate`: tolerates an app that isn't running, then waits for it to exit. */
    data class Terminate(val appId: String) : Command

    /** `spawn defaults <verb> <domain> [rest]`; the domain is resolved by [IosSimctl]. */
    data class Defaults(val verb: String, val domain: DefaultsDomain, val rest: List<String>) : Command

    /** `spawn log show`: [args] are the full simctl argv; its output is capped at [MAX_LOG_BYTES]. */
    data class LogShow(val args: List<String>) : Command
  }

  sealed interface DefaultsDomain {
    /** `-g` / `NSGlobalDomain`. */
    data object Global : DefaultsDomain

    /** A bundle id: rewritten to `<data container>/Library/Preferences/<appId>`, the only way to reach a sandboxed app. */
    data class App(val appId: String) : DefaultsDomain

    /** An absolute plist path; must resolve inside this simulator's app containers. */
    data class Path(val path: String) : DefaultsDomain

    /**
     * `com.apple.launchservices.schemeapproval`, a device domain passed by name: the record that a
     * URL scheme may open an app without the "Open in …?" prompt. Only one key shape and value type.
     */
    data object SchemeApproval : DefaultsDomain
  }

  const val ALLOWED =
    "terminate, launch, openurl, keychain <device> reset, location, privacy, get_app_container, " +
      "ui (appearance, content_size, increase_contrast), status_bar, push and pbcopy (input in stdin), pbpaste, " +
      "listapps, appinfo, and spawn <device> with defaults (read, read-type, write, delete), notifyutil -p/-s or log show"

  /**
   * Any launch environment is refused: a blocklist fails open on the first loader or debug variable
   * it misses (`DYLD_INSERT_LIBRARIES` loads code into the app), and no caller needs one yet.
   */
  const val LAUNCH_ENVIRONMENT_REFUSED =
    "launchEnvironment is not supported yet, so it must be empty: a launch environment can load code into " +
      "the app (DYLD_INSERT_LIBRARIES and the like). An allowlist of variable names will be added when a " +
      "real caller needs one; ask the Trailblaze maintainers."

  const val MAX_PREDICATE_LENGTH = 2_000
  const val MAX_LOG_SECONDS = 600

  /** `log show` is killed past this much output: narrow `--predicate` or `--last`. */
  const val MAX_LOG_BYTES = 4 * 1024 * 1024

  /** simctl's own limit for a push payload. */
  const val MAX_PUSH_BYTES = 4096
  const val MAX_PASTEBOARD_BYTES = 64 * 1024
  const val MAX_WAYPOINTS = 100

  fun parse(
    argv: List<String>,
    udid: String,
    launchEnvironment: Map<String, String> = emptyMap(),
    stdin: String? = null,
  ): Command {
    val tokens = stripSimctlPrefix(argv)
    require(tokens.isNotEmpty()) { "argv is empty. ios_simctl runs: $ALLOWED." }
    tokens.forEach { token -> require(token.none { it.isISOControl() }) { "argv must not contain control characters." } }
    val sub = tokens.first()
    val rest = tokens.drop(1)
    require(launchEnvironment.isEmpty()) { LAUNCH_ENVIRONMENT_REFUSED }
    require(stdin == null || sub in STDIN_COMMANDS) { "stdin is only for ${STDIN_COMMANDS.joinToString(" and ")}." }
    return when (sub) {
      "terminate" -> {
        val args = withDevice(rest, udid, sub)
        require(args.size == 2) { "Usage: terminate <device> <bundle id>." }
        Command.Terminate(requireAppId(args[1]))
      }
      "launch" -> parseLaunch(rest, udid)
      "openurl" -> {
        val args = withDevice(rest, udid, sub)
        require(args.size == 2) { "Usage: openurl <device> <url>." }
        val url = args[1]
        require(URL_SCHEME.containsMatchIn(url)) { "URL '$url' must start with a scheme, e.g. `myapp://`." }
        require(!url.startsWith("file:", ignoreCase = true)) { "openurl does not open file: URLs." }
        Command.Plain(listOf("openurl") + args)
      }
      "keychain" -> {
        val args = withDevice(rest, udid, sub)
        require(args.drop(1) == listOf("reset")) { "Only `keychain <device> reset` is allowed." }
        Command.Plain(listOf("keychain") + args)
      }
      "location" -> parseLocation(withDevice(rest, udid, sub))
      "privacy" -> {
        val args = withDevice(rest, udid, sub)
        val action = args.getOrNull(1)
        require(action in PRIVACY_ACTIONS && args.size in 3..4) {
          "Usage: privacy <device> grant|revoke|reset <service> [bundle id]."
        }
        require(PRIVACY_SERVICE.matches(args[2])) { "Privacy service '${args[2]}' must match ${PRIVACY_SERVICE.pattern}." }
        if (action != "reset") require(args.size == 4) { "privacy $action needs a bundle id." }
        args.getOrNull(3)?.let(::requireAppId)
        Command.Plain(listOf("privacy") + args)
      }
      "get_app_container" -> {
        val args = withDevice(rest, udid, sub)
        require(args.size in 2..3) { "Usage: get_app_container <device> <bundle id> [app|data|groups|<group id>]." }
        requireAppId(args[1])
        args.getOrNull(2)?.let { container ->
          require(container in CONTAINERS || APP_GROUP.matches(container)) {
            "Container '$container' must be app, data, groups or a group id (group.…)."
          }
        }
        Command.Plain(listOf("get_app_container") + args)
      }
      "ui" -> {
        val args = withDevice(rest, udid, sub)
        val values = UI_OPTIONS[args.getOrNull(1)]
        require(values != null && args.size in 2..3) {
          "Usage: ui <device> appearance|content_size|increase_contrast [value]."
        }
        args.getOrNull(2)?.let { value ->
          require(value in values) { "ui ${args[1]} takes one of ${values.joinToString()} (got '$value')." }
        }
        Command.Plain(listOf("ui") + args)
      }
      "status_bar" -> parseStatusBar(withDevice(rest, udid, sub))
      "push" -> {
        val args = withDevice(rest, udid, sub).let { if (it.lastOrNull() == "-") it.dropLast(1) else it }
        require(args.size == 2) { "Usage: push <device> <bundle id>, with the JSON payload in stdin. A payload file is not allowed." }
        requireAppId(args[1])
        val payload = requireNotNull(stdin) { "push needs its JSON payload in stdin; a payload file is not allowed." }
        require(payload.toByteArray().size <= MAX_PUSH_BYTES) { "push payload must be at most $MAX_PUSH_BYTES bytes." }
        val json = try {
          Json.parseToJsonElement(payload)
        } catch (e: SerializationException) {
          throw IllegalArgumentException("push payload is not valid JSON.")
        }
        require(json is JsonObject && "aps" in json) { "push payload must be a JSON object with an \"aps\" key." }
        Command.Plain(listOf("push") + args + "-", stdin = payload)
      }
      "pbcopy" -> {
        val args = withDevice(rest, udid, sub)
        require(args.size == 1) { "Usage: pbcopy <device>, with the text in stdin." }
        val text = requireNotNull(stdin) { "pbcopy needs the text in stdin." }
        require(text.toByteArray().size <= MAX_PASTEBOARD_BYTES) { "pbcopy text must be at most $MAX_PASTEBOARD_BYTES bytes." }
        Command.Plain(listOf("pbcopy") + args, stdin = text)
      }
      "pbpaste", "listapps" -> {
        val args = withDevice(rest, udid, sub)
        require(args.size == 1) { "Usage: $sub <device>." }
        Command.Plain(listOf(sub) + args)
      }
      "appinfo" -> {
        val args = withDevice(rest, udid, sub)
        require(args.size == 2) { "Usage: appinfo <device> <bundle id>." }
        requireAppId(args[1])
        Command.Plain(listOf("appinfo") + args)
      }
      "spawn" -> parseSpawn(rest, udid)
      else -> throw IllegalArgumentException("`$sub` is not allowed. ios_simctl runs: $ALLOWED.")
    }
  }

  private fun parseLaunch(rest: List<String>, udid: String): Command {
    val flags = rest.takeWhile { it.startsWith("-") && !UDID.matches(it) }
    flags.forEach { flag ->
      require(flag == TERMINATE_RUNNING) {
        when {
          flag.startsWith("--stdout") || flag.startsWith("--stderr") ->
            "launch $flag is refused: it writes to a host path. The only allowed flag is $TERMINATE_RUNNING."
          flag.startsWith("--console") -> "launch $flag is refused. The only allowed flag is $TERMINATE_RUNNING."
          else -> "launch flag $flag is not allowed. The only allowed flag is $TERMINATE_RUNNING."
        }
      }
    }
    val args = withDevice(rest.drop(flags.size), udid, "launch")
    require(args.size >= 2) { "Usage: launch [$TERMINATE_RUNNING] <device> <bundle id> [app arguments…]." }
    requireAppId(args[1])
    // simctl reads its options before the device, so these reach the app as plain arguments. Refused
    // anyway: an older or future simctl that parsed them would write host files or block.
    args.drop(2).firstOrNull { arg -> arg == "-w" || LAUNCH_ONLY_FLAGS.any { arg.startsWith(it) } }?.let { arg ->
      throw IllegalArgumentException("launch app argument $arg is refused: it reads like a simctl launch option.")
    }
    return Command.Plain(listOf("launch") + flags.distinct() + args)
  }

  private fun parseLocation(args: List<String>): Command {
    val usage = "Usage: location <device> list|clear|set <lat>,<lon>|run <scenario>|start [--speed=N] " +
      "[--distance=N|--interval=N] <lat>,<lon> <lat>,<lon>…"
    when (args.getOrNull(1)) {
      "list", "clear" -> require(args.size == 2) { usage }
      "set" -> {
        require(args.size == 3) { usage }
        requireCoordinate(args[2])
      }
      "run" -> {
        require(args.size == 3) { usage }
        require(SCENARIO.matches(args[2])) { "location scenario '${args[2]}' must match ${SCENARIO.pattern}." }
      }
      "start" -> {
        val options = args.drop(2).takeWhile { it.startsWith("--") }
        options.forEach { option ->
          val (name, value) = option.split("=", limit = 2).let { it[0] to it.getOrNull(1) }
          require(name in LOCATION_START_OPTIONS && value != null) {
            "location start option '$option' must be one of ${LOCATION_START_OPTIONS.joinToString { "$it=<number>" }}."
          }
          require(value.toDoubleOrNull()?.let { it > 0 && it.isFinite() } == true) {
            "location start $name needs a positive number (got '$value')."
          }
        }
        val waypoints = args.drop(2 + options.size)
        require(waypoints.size in 2..MAX_WAYPOINTS) {
          "location start needs 2..$MAX_WAYPOINTS waypoints as <lat>,<lon> arguments; reading them from stdin or a file is not allowed."
        }
        waypoints.forEach(::requireCoordinate)
      }
      else -> throw IllegalArgumentException(usage)
    }
    return Command.Plain(listOf("location") + args)
  }

  private fun parseStatusBar(args: List<String>): Command {
    when (args.getOrNull(1)) {
      "list", "clear" -> require(args.size == 2) { "Usage: status_bar <device> list|clear|override <flags>." }
      "override" -> {
        val flags = args.drop(2)
        require(flags.isNotEmpty() && flags.size % 2 == 0) {
          "status_bar override needs one or more `--flag value` pairs: ${STATUS_BAR_FLAGS.keys.joinToString()}."
        }
        val seen = mutableSetOf<String>()
        flags.chunked(2).forEach { (flag, value) ->
          val valid = STATUS_BAR_FLAGS[flag]
            ?: throw IllegalArgumentException("status_bar flag $flag is not allowed. Allowed: ${STATUS_BAR_FLAGS.keys.joinToString()}.")
          require(seen.add(flag)) { "status_bar override names $flag twice." }
          require(valid(value)) { "status_bar $flag value '$value' is not allowed." }
        }
      }
      else -> throw IllegalArgumentException("Usage: status_bar <device> list|clear|override <flags>.")
    }
    return Command.Plain(listOf("status_bar") + args)
  }

  private fun parseSpawn(rest: List<String>, udid: String): Command {
    rest.firstOrNull()?.takeIf { it.startsWith("-") && !UDID.matches(it) }?.let {
      throw IllegalArgumentException("spawn flag $it is not allowed.")
    }
    val args = withDevice(rest, udid, "spawn")
    val program = args.getOrNull(1)
    val programArgs = args.drop(2)
    return when (program) {
      "defaults" -> parseDefaults(programArgs)
      "notifyutil" -> {
        val ok = when (programArgs.firstOrNull()) {
          "-p" -> programArgs.size == 2
          "-s" -> programArgs.size == 3 && programArgs[2].toLongOrNull() != null
          else -> false
        }
        require(ok) { "Only `notifyutil -p <name>` and `notifyutil -s <name> <integer>` are allowed." }
        require(NOTIFICATION_NAME.matches(programArgs[1])) {
          "Notification name '${programArgs[1]}' must match ${NOTIFICATION_NAME.pattern}."
        }
        Command.Plain(listOf("spawn") + args)
      }
      "log" -> {
        require(programArgs.firstOrNull() == "show") { "Only `log show` is allowed." }
        validateLogShow(programArgs.drop(1))
        Command.LogShow(listOf("spawn") + args)
      }
      else -> throw IllegalArgumentException(
        "spawn runs only defaults, notifyutil and log show (got ${program ?: "nothing"}). ios_simctl runs: $ALLOWED.",
      )
    }
  }

  private fun parseDefaults(args: List<String>): Command {
    val verb = args.firstOrNull()
    require(verb in DEFAULTS_VERBS) { "defaults runs only ${DEFAULTS_VERBS.joinToString()} (got ${verb ?: "nothing"})." }
    val domainToken = args.getOrNull(1) ?: throw IllegalArgumentException("defaults $verb needs a domain: -g, a bundle id or a container path.")
    val domain = when {
      domainToken in GLOBAL_DOMAIN -> DefaultsDomain.Global
      domainToken == SCHEME_APPROVAL_DOMAIN -> DefaultsDomain.SchemeApproval
      domainToken.startsWith("/") -> DefaultsDomain.Path(domainToken)
      APP_ID.matches(domainToken) -> DefaultsDomain.App(domainToken)
      else -> throw IllegalArgumentException("defaults domain '$domainToken' must be -g, a bundle id or an absolute container path.")
    }
    val rest = args.drop(2)
    when (verb) {
      "read" -> require(rest.size <= 1) { "Usage: defaults read <domain> [key]." }
      // Without a key `defaults delete` removes the whole domain; with -g, every global preference.
      "delete" -> require(rest.size == 1) { "defaults delete needs a key: without one it deletes the whole domain." }
      "read-type" -> require(rest.size == 1) { "Usage: defaults read-type <domain> <key>." }
      "write" -> require(rest.size >= 2) { "Usage: defaults write <domain> <key> <value>." }
    }
    rest.firstOrNull()?.let { key -> require(!key.startsWith("-")) { "defaults key '$key' must not start with '-'." } }
    if (domain == DefaultsDomain.SchemeApproval) {
      val schemeUsage = "$SCHEME_APPROVAL_DOMAIN allows only `read <key>` and `write <key> -string <bundle id>`, " +
        "where the key is com.apple.CoreSimulator.CoreSimulatorBridge--><scheme>."
      val shapeOk = when (verb) {
        "read" -> rest.size == 1
        "write" -> rest.size == 3 && rest[1] == "-string" && APP_ID.matches(rest[2])
        else -> false
      }
      require(shapeOk && SCHEME_APPROVAL_KEY.matches(rest[0])) { schemeUsage }
    }
    return Command.Defaults(verb!!, domain, rest)
  }

  private fun validateLogShow(args: List<String>) {
    var predicate: String? = null
    var lastSeconds: Int? = null
    var i = 0
    while (i < args.size) {
      val flag = args[i]
      fun value(): String = args.getOrNull(++i) ?: throw IllegalArgumentException("log show $flag needs a value.")
      when (flag) {
        "--predicate" -> predicate = value()
        "--last" -> lastSeconds = parseLast(value())
        "--style" -> value().let { require(it in LOG_STYLES) { "log show --style must be one of ${LOG_STYLES.joinToString()}." } }
        "--info", "--debug", "--signpost", "--source" -> Unit
        else -> throw IllegalArgumentException(
          "log show $flag is not allowed. Allowed: --predicate, --last, --style, --info, --debug, --signpost, --source.",
        )
      }
      i++
    }
    val p = requireNotNull(predicate) { "log show needs --predicate: an unscoped log show reads every process." }
    require(p.isNotBlank() && p.length <= MAX_PREDICATE_LENGTH) {
      "log show --predicate must be 1..$MAX_PREDICATE_LENGTH characters (got ${p.length})."
    }
    // NSPredicate can call methods (FUNCTION), build classes (CAST), and walk objects (SUBQUERY,
    // SELF, $variables, @ operators). A log filter needs none of them.
    PREDICATE_REFUSED.find(p)?.let { match ->
      throw IllegalArgumentException(
        "log show --predicate must not use '${match.value}': FUNCTION, CAST, SUBQUERY, SELF, \$ and @ are refused.",
      )
    }
    requireNotNull(lastSeconds) { "log show needs --last (at most ${MAX_LOG_SECONDS}s, e.g. `--last 60s`)." }
  }

  private fun parseLast(value: String): Int {
    val match = LAST.matchEntire(value)
      ?: throw IllegalArgumentException("log show --last '$value' must be a number with s, m or h, e.g. `60s`.")
    val n = match.groupValues[1].toLongOrNull() ?: Long.MAX_VALUE
    val seconds = when (match.groupValues[2]) {
      "m" -> n * 60
      "h" -> n * 3600
      else -> n
    }
    require(seconds in 1..MAX_LOG_SECONDS) { "log show --last must be 1s..${MAX_LOG_SECONDS}s (got $value)." }
    return seconds.toInt()
  }

  /**
   * [args] with its first token, the device, resolved to [udid]: `booted` or the UDID itself are replaced, a
   * different UDID is refused, and anything else means the device was omitted, so [udid] is inserted.
   */
  private fun withDevice(args: List<String>, udid: String, sub: String): List<String> {
    val token = args.firstOrNull()
    return when {
      token == "booted" || token.equals(udid, ignoreCase = true) -> listOf(udid) + args.drop(1)
      token != null && UDID.matches(token) -> throw IllegalArgumentException(
        "$sub may only target this session's simulator ($udid), not $token.",
      )
      else -> listOf(udid) + args
    }
  }

  private fun stripSimctlPrefix(argv: List<String>): List<String> = when {
    argv.take(2) == listOf("xcrun", "simctl") -> argv.drop(2)
    argv.firstOrNull() == "simctl" -> argv.drop(1)
    else -> argv
  }

  private fun requireAppId(appId: String): String {
    require(APP_ID.matches(appId)) { "'$appId' is not a bundle id (${APP_ID.pattern})." }
    return appId
  }

  private fun requireCoordinate(value: String) {
    val parts = value.split(",")
    val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
    val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
    require(parts.size == 2 && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
      "location set needs <latitude>,<longitude> within -90..90 and -180..180 (got '$value')."
    }
  }

  private const val TERMINATE_RUNNING = "--terminate-running-process"
  private const val SCHEME_APPROVAL_DOMAIN = "com.apple.launchservices.schemeapproval"

  internal val UDID = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
  internal val APP_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
  private val APP_GROUP = Regex("^group\\.[A-Za-z0-9][A-Za-z0-9._-]*$")
  private val NOTIFICATION_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
  private val PRIVACY_SERVICE = Regex("^[a-z][a-z-]*$")
  private val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
  private val LAST = Regex("^(\\d{1,9})([smh]?)$")
  private val PRIVACY_ACTIONS = setOf("grant", "revoke", "reset")
  private val CONTAINERS = setOf("app", "data", "groups")
  private val DEFAULTS_VERBS = listOf("read", "read-type", "write", "delete")
  private val GLOBAL_DOMAIN = setOf("-g", "-globalDomain", "NSGlobalDomain")
  private val LOG_STYLES = setOf("default", "compact", "json", "ndjson", "syslog")
  private val STDIN_COMMANDS = listOf("push", "pbcopy")
  private val LAUNCH_ONLY_FLAGS = listOf("--stdout", "--stderr", "--console", "--wait-for-debugger")
  private val SCHEME_APPROVAL_KEY = Regex("^com\\.apple\\.CoreSimulator\\.CoreSimulatorBridge-->[A-Za-z][A-Za-z0-9+.-]*$")
  private val PREDICATE_REFUSED = Regex("\\b(FUNCTION|CAST|SUBQUERY|SELF)\\b|[$@\uFF20]", RegexOption.IGNORE_CASE)
  private val SCENARIO = Regex("^[A-Za-z0-9][A-Za-z0-9 ._()-]{0,63}$")
  private val LOCATION_START_OPTIONS = listOf("--speed", "--distance", "--interval")
  private val UI_OPTIONS = mapOf(
    "appearance" to listOf("light", "dark"),
    "increase_contrast" to listOf("enabled", "disabled"),
    "content_size" to listOf(
      "increment", "decrement", "extra-small", "small", "medium", "large", "extra-large", "extra-extra-large",
      "extra-extra-extra-large", "accessibility-medium", "accessibility-large", "accessibility-extra-large",
      "accessibility-extra-extra-large", "accessibility-extra-extra-extra-large",
    ),
  )
  private fun oneOf(vararg values: String): (String) -> Boolean = { it in values }
  private fun intIn(range: IntRange): (String) -> Boolean = { it.toIntOrNull()?.let { n -> n in range } == true }
  private val STATUS_BAR_TIME = Regex("^(\\d{1,2}:\\d{2}|\\d{4}-\\d{2}-\\d{2}(T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})?)?)$")
  private val OPERATOR_NAME = Regex("^[A-Za-z0-9 ._&'-]{0,32}$")
  private val STATUS_BAR_FLAGS: Map<String, (String) -> Boolean> = mapOf(
    "--time" to { STATUS_BAR_TIME.matches(it) },
    "--dataNetwork" to oneOf("hide", "wifi", "3g", "4g", "lte", "lte-a", "lte+", "5g", "5g+", "5g-uwb", "5g-uc"),
    "--wifiMode" to oneOf("searching", "failed", "active"),
    "--wifiBars" to intIn(0..3),
    "--cellularMode" to oneOf("notSupported", "searching", "failed", "active"),
    "--cellularBars" to intIn(0..4),
    "--operatorName" to { OPERATOR_NAME.matches(it) },
    "--batteryState" to oneOf("charging", "charged", "discharging"),
    "--batteryLevel" to intIn(0..100),
  )
}
